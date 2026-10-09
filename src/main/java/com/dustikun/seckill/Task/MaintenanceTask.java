package com.dustikun.seckill.Task;

import com.dustikun.seckill.Common.result.ReconcileReport;
import com.dustikun.seckill.Metrics.SeckillMetrics;
import com.dustikun.seckill.Service.CompensateTaskService;
import com.dustikun.seckill.Service.OutboxService;
import com.dustikun.seckill.Service.StockReconcileService;
import com.dustikun.seckill.monitor.core.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 后台维护任务。
 * <p>
 * 两个职责，对应两条「补偿逻辑照不到、必须靠独立调度兜住」的路径：
 * <ol>
 *   <li><b>待补偿重试</b>：回补失败时只打日志是没用的（日志不是状态）。任务表 + 定时重试才追得回来。</li>
 *   <li><b>库存对账</b>：Redis 故障降级、以及 Redis 响应超时造成的假阴性，都无法靠事后补偿修复，
 *       只能通过比对两侧真实状态发现。</li>
 * </ol>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "seckill.maintenance", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MaintenanceTask {

    private final CompensateTaskService compensateTaskService;
    private final StockReconcileService stockReconcileService;
    private final OutboxService outboxService;

    /**
     * 在途估算的唯一出口。
     * <p>
     * 原先这里注入 {@code ObjectProvider<SeckillMessageProducer/SeckillOrderConsumer>}，
     * 只为拿两个进程内计数相减；那两个 getter 已随计数器一起迁到 {@link SeckillMetrics}，
     * 这里也直接改成读它，避免“同一个量在两个地方各数一遍”。
     */
    private final SeckillMetrics metrics;

    private final int compensateBatchSize;
    private final boolean autoRepair;
    private final List<Long> reconcileStockIds;

    public MaintenanceTask(CompensateTaskService compensateTaskService,
                           StockReconcileService stockReconcileService,
                           OutboxService outboxService,
                           SeckillMetrics metrics,
                           @Value("${seckill.maintenance.compensate.batch-size:100}")
                           int compensateBatchSize,
                           @Value("${seckill.maintenance.reconcile.auto-repair:false}")
                           boolean autoRepair,
                           @Value("${seckill.maintenance.reconcile.stock-ids:}")
                           List<Long> reconcileStockIds) {
        this.compensateTaskService = compensateTaskService;
        this.stockReconcileService = stockReconcileService;
        this.outboxService = outboxService;
        this.metrics = metrics;
        this.compensateBatchSize = compensateBatchSize;
        this.autoRepair = autoRepair;
        this.reconcileStockIds = reconcileStockIds;
    }

    /**
     * 重试到期的待补偿任务。
     * <p>
     * 幂等由回补脚本本身保证（rollback 靠 SREM 返回值、restoreStockOnly 靠 SET NX），
     * 所以这里可以放心地「至少重试一次」，不会把库存补多。
     * <p>
     * 它还承担「放弃投递之后把预扣还回去」这件事：{@code OutboxAbandonService} 只在放弃的瞬间
     * 立刻尝试一次，成功与否都不影响正确性 —— 真正的保证在这里，独立于投递器线程的存活。
     */
    @Scheduled(fixedDelayString = "${seckill.maintenance.compensate.interval-ms:30000}")
    public void retryCompensateTasks() {
        // 定时任务的线程池会复用线程，MDC 不会自动清 —— 不开作用域时本轮日志
        // 会带上上一轮（甚至上一个任务）的 traceId，那比没有 traceId 更容易误导人。
        try (TraceContext.Scope ignored = TraceContext.open(null, "retryCompensate")) {
            int pending = compensateTaskService.retryDue(compensateBatchSize);
            if (pending > 0) {
                log.info("[维护任务·待补偿] 本轮结清 {} 条，仍待处理 {} 条",
                        pending, compensateTaskService.countPending());
            }
        } catch (Exception e) {
            // 定时任务里绝不能把异常抛出去：一次执行失败会连带影响后续调度
            log.error("[维护任务·待补偿] 本轮执行异常", e);
        }
    }

    /**
     * 库存对账。
     * <p>
     * 在途数走<b>自动模式</b>（按「数据库库存 − 可信在途」计算，可信在途 = PENDING 预订单
     * 减去「投递已放弃但仍停在 PENDING」的那些，多实例也准确），不再依赖排空后传 0：
     * 即使仍有在途，期望值也已把在途算进去，正常中间态不会被误报。
     * <p>
     * 排空门控仍然保留，但它防的对象变了——现在防的是<b>读偏斜</b>：
     * 数据库快照与 Redis 读数之间的窗口里若恰好有一笔请求「已扣 Redis、还没插入 PENDING」，
     * 会把正常中间态读成 REDIS_BEHIND(1)。排空后再对账，这个窗口自然关上。
     * <p>
     * 【注意门控放行 ≠ 一切正常】门控只数 outbox 的 PENDING，FAILED 是终态、不计数，
     * 所以「已放弃但订单还挂在 PENDING」的记录不会让门控关上。它由对账自身的
     * {@code ABANDONED_PENDING} 结论负责报出来（默认只告警，repair=true 时才补登记归还待办）。
     */
    @Scheduled(fixedDelayString = "${seckill.maintenance.reconcile.interval-ms:300000}")
    public void reconcileStocks() {
        // 与待补偿任务同理：一轮一个 traceId。对账是最需要「事后能把一轮的判定过程完整捞出来」
        // 的动作，因为它默认只告警不改数据，人工复核时要看的就是那一轮的全部推理痕迹。
        try (TraceContext.Scope ignored = TraceContext.open(null, "reconcile")) {
            if (reconcileStockIds == null || reconcileStockIds.isEmpty()) {
                return;
            }

            for (Long stockId : reconcileStockIds) {
                try {
                    // 排空判断放到每个活动内部：不同活动的在途情况互不相同，
                    // 一个活动还没排空不该拖累其它活动的对账
                    if (!pipelineDrained(stockId)) {
                        log.debug("[维护任务·对账] stockId={} 仍有在途，本次跳过（防快照与 Redis 读数之间的读偏斜）", stockId);
                        continue;
                    }
                    ReconcileReport report = stockReconcileService.reconcile(stockId, autoRepair);
                    if (!report.healthy() && report.status() != ReconcileReport.Status.NOT_PREHEATED) {
                        log.error("[维护任务·对账] stockId={} 判定为 {}：{}。修复动作={}",
                                stockId, report.status(), report.conclusion(), report.actions());
                    }
                } catch (Exception e) {
                    // 单个活动失败不能影响其它活动
                    log.error("[维护任务·对账] stockId={} 执行异常", stockId, e);
                }
            }
        } catch (Exception e) {
            log.error("[维护任务·对账] 本轮执行异常", e);
        }
    }

    /**
     * 管道是否已排空。
     * <p>
     * 在途数改为数据库自动计算后，排空不再是「对账结果准确性」的必要条件
     * （AUTO 模式的期望值天然把在途算进去），保留它是为了关上读偏斜窗口。
     * 两个条件仍然都查：
     * <ol>
     *   <li><b>没有待投递记录</b>：还在 PENDING 的 outbox 记录意味着
     *       这笔预扣确定尚未落库。这一条查的是数据库，多实例也准确。</li>
     *   <li><b>已投递的消息都已处理完</b>：这一步仍然只能靠<b>单实例</b>的进程内计数
     *       （MQ 的投递/消费进度无法从库里推出来；计数现在存在 MeterRegistry 里，
     *       但仍然是每个实例各数各的 —— 换成 Prometheus 并不会让它自动变跨实例）。
     *       要让它跨实例准确，需要消费端落库成功后回写 outbox 状态。</li>
     * </ol>
     * MQ 未启用时（同步降级模式）两侧计数恒为 0，下面的比较天然判定已排空 ——
     * 那条链路上落库是同步完成的，本来就不存在在途，因此不必再单独判一次 Bean 是否存在。
     * <p>
     * 【门控必须封顶，否则它会掩盖最该报出来的故障】上面两条判据问的都是「还在忙吗」，
     * 而消费者组一旦挂掉、消息在 Broker 侧丢失，这个问题的答案会<b>永远</b>是「是」：
     * 门控一直关着 → 对账连报都不报 → 「消息没人消费」这件事彻底静默。
     * 所以先看一条时间线：若该活动最老的 PENDING 预订单已经超过陈旧阈值
     * （{@link StockReconcileService#pipelineStuck}），那就不是「忙」，是「卡住」，
     * 直接放行、让对账把 STALE_PENDING 说出来。阈值与陈旧判定共用一份配置，
     * 避免「门控挡住自己本该报出的问题」。
     */
    private boolean pipelineDrained(Long stockId) {
        if (stockReconcileService.pipelineStuck(stockId)) {
            log.warn("[维护任务·对账] stockId={} 链路长时间未排空（最老预订单已超过陈旧阈值），"
                    + "本轮不再跳过 —— 卡住的链路正是最需要对账出声的时候", stockId);
            return true;
        }
        if (outboxService.countPendingByStockId(stockId) > 0) {
            log.debug("[维护任务·对账] stockId={} 仍有待投递记录，本次跳过", stockId);
            return false;
        }
        long sent = metrics.mqSentCount();
        long resolved = metrics.resolvedCount();
        return sent <= resolved;
    }
}
