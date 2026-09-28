package com.dustikun.seckill.Task;

import com.dustikun.seckill.Common.result.ReconcileReport;
import com.dustikun.seckill.Mq.SeckillMessageProducer;
import com.dustikun.seckill.Mq.SeckillOrderConsumer;
import com.dustikun.seckill.Service.CompensateTaskService;
import com.dustikun.seckill.Service.OutboxService;
import com.dustikun.seckill.Service.StockReconcileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 后台维护任务（阶段 4 评审修复新增）。
 * <p>
 * 两个职责，对应评审里两条「补偿逻辑照不到、必须靠独立调度兜住」的路径：
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
    private final ObjectProvider<SeckillMessageProducer> producerProvider;
    private final ObjectProvider<SeckillOrderConsumer> consumerProvider;

    private final int compensateBatchSize;
    private final boolean autoRepair;
    private final List<Long> reconcileStockIds;

    public MaintenanceTask(CompensateTaskService compensateTaskService,
                           StockReconcileService stockReconcileService,
                           OutboxService outboxService,
                           ObjectProvider<SeckillMessageProducer> producerProvider,
                           ObjectProvider<SeckillOrderConsumer> consumerProvider,
                           @Value("${seckill.maintenance.compensate.batch-size:100}")
                           int compensateBatchSize,
                           @Value("${seckill.maintenance.reconcile.auto-repair:false}")
                           boolean autoRepair,
                           @Value("${seckill.maintenance.reconcile.stock-ids:}")
                           List<Long> reconcileStockIds) {
        this.compensateTaskService = compensateTaskService;
        this.stockReconcileService = stockReconcileService;
        this.outboxService = outboxService;
        this.producerProvider = producerProvider;
        this.consumerProvider = consumerProvider;
        this.compensateBatchSize = compensateBatchSize;
        this.autoRepair = autoRepair;
        this.reconcileStockIds = reconcileStockIds;
    }

    /**
     * 重试到期的待补偿任务。
     * <p>
     * 幂等由回补脚本本身保证（rollback 靠 SREM 返回值、restoreStockOnly 靠 SET NX），
     * 所以这里可以放心地「至少重试一次」，不会把库存补多。
     */
    @Scheduled(fixedDelayString = "${seckill.maintenance.compensate.interval-ms:30000}")
    public void retryCompensateTasks() {
        try {
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
     * 只在「整条链路已经排空」时才做判断：只要 MQ 里还有在途消息，
     * 「Redis 比数据库小」就是正常中间态，此时判断会把正常状态误报成泄漏。
     */
    @Scheduled(fixedDelayString = "${seckill.maintenance.reconcile.interval-ms:300000}")
    public void reconcileStocks() {
        try {
            if (reconcileStockIds == null || reconcileStockIds.isEmpty()) {
                return;
            }

            for (Long stockId : reconcileStockIds) {
                try {
                    // 排空判断放到每个活动内部：不同活动的在途情况互不相同，
                    // 一个活动还没排空不该拖累其它活动的对账
                    if (!pipelineDrained(stockId)) {
                        log.debug("[维护任务·对账] stockId={} 仍有在途，本次跳过（在途时 Redis 比 DB 小属正常）", stockId);
                        continue;
                    }
                    ReconcileReport report = stockReconcileService.reconcile(stockId, 0L, autoRepair);
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
     * 两个条件都必须满足，缺一不可：
     * <ol>
     *   <li><b>没有待投递记录</b>（阶段 5 新增）：还在 PENDING 的 outbox 记录意味着
     *       这笔预扣确定尚未落库，此时判断对账必然误报。这一条查的是数据库，多实例也准确。</li>
     *   <li><b>已投递的消息都已处理完</b>：这一步仍然只能靠单实例的 JVM 计数
     *       （MQ 的投递/消费进度无法从库里推出来）。要让它跨实例准确，
     *       需要消费端落库成功后回写 outbox 状态（计划在下一阶段做）。</li>
     * </ol>
     * MQ 未启用时（同步降级模式）视为已排空——那条链路上落库是同步完成的，不存在在途。
     */
    private boolean pipelineDrained(Long stockId) {
        if (outboxService.countPendingByStockId(stockId) > 0) {
            log.debug("[维护任务·对账] stockId={} 仍有待投递记录，本次跳过", stockId);
            return false;
        }
        SeckillMessageProducer producer = producerProvider.getIfAvailable();
        SeckillOrderConsumer consumer = consumerProvider.getIfAvailable();
        if (producer == null || consumer == null) {
            return true;
        }
        long sent = producer.getSentCount();
        long resolved = consumer.getResolvedCount();
        return sent <= resolved;
    }
}
