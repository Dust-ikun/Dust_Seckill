package com.dustikun.seckill.Service;

import com.dustikun.seckill.Common.constant.CompensateType;
import com.dustikun.seckill.Common.result.RollbackResult;
import com.dustikun.seckill.Common.util.BackoffPolicy;
import com.dustikun.seckill.Common.util.Strings;
import com.dustikun.seckill.Mapper.CompensateTaskMapper;
import com.dustikun.seckill.Mapper.OrderMapper;
import com.dustikun.seckill.Metrics.SeckillMetrics;
import com.dustikun.seckill.entity.CompensateTask;
import com.dustikun.seckill.entity.Order;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 待补偿任务的登记与重试。
 * <p>
 * 【解决什么问题】「回补失败」不能只打一行 ERROR 日志就返回。日志不是状态：
 * 没人盯日志的时候，这部分库存永久消失（少卖），而且事后连丢了哪几笔都查不出来。
 * 把补偿动作落成一张表之后，它就有了独立于调用线程的生命周期——
 * 请求线程可以立刻返回，定时任务接着把这笔账追回来。
 * <p>
 * 【为什么不能靠「让调用方重试」】调用方是短命的：Redis 挂了它不可能在那里等，
 * 而库存丢失这件事既没有超时也不能重试。真正能兜住的是「持久化 + 独立调度」。
 */
@Slf4j
@Service
public class CompensateTaskService {

    /** 单条记录 reason / last_error 的截断长度，避免写入超长文本 */
    private static final int REASON_MAX = 255;
    private static final int ERROR_MAX = 500;

    private final CompensateTaskMapper compensateTaskMapper;
    private final StockCacheService stockCacheService;
    /**
     * 直接注入 Mapper 而不是 {@code SeckillPersistenceService}：后者依赖 {@code OutboxService}，
     * 而 {@code OutboxService} 又依赖 {@code PreDeductCompensator} → 本类，会形成构造器循环依赖。
     * 这里只需要「取消一条订单」这个数据库原语，用 Mapper 反而边界更清楚。
     */
    private final OrderMapper orderMapper;

    /** 首次重试延迟：给 Redis 一个短暂的恢复窗口，避免立刻撞在故障上 */
    private final long firstRetryDelaySeconds;
    /** 最大重试次数。超过则标记 FAILED，等人工介入，而不是无限重试 */
    private final int maxRetry;

    /**
     * 指标出口。计数不再由本类自己维护。
     * <p>
     * 补偿侧的计数尤其需要时间维度：{@code abandoned} 一旦增长就意味着有库存需要人工核账，
     * 告警规则应当是「最近 5 分钟新增 &gt; 0 就报警」——这是 JVM 累计值做不到的。
     */
    private final SeckillMetrics metrics;

    public CompensateTaskService(CompensateTaskMapper compensateTaskMapper,
                                 StockCacheService stockCacheService,
                                 OrderMapper orderMapper,
                                 @Value("${seckill.maintenance.compensate.first-retry-delay-seconds:10}")
                                 long firstRetryDelaySeconds,
                                 @Value("${seckill.maintenance.compensate.max-retry:10}")
                                 int maxRetry,
                                 SeckillMetrics metrics) {
        this.compensateTaskMapper = compensateTaskMapper;
        this.stockCacheService = stockCacheService;
        this.orderMapper = orderMapper;
        this.firstRetryDelaySeconds = firstRetryDelaySeconds;
        this.maxRetry = maxRetry;
        this.metrics = metrics;
    }

    /**
     * 登记一条待补偿任务。
     * <p>
     * 本方法<b>不抛异常</b>：它总是在「业务已经失败」的 catch 块里被调用，
     * 此时再抛一个异常会把原始错误盖掉，反而更难排查。
     */
    public void enqueue(Long stockId, Long userId, String orderNo, long num,
                        CompensateType type, String reason) {
        try {
            CompensateTask task = new CompensateTask();
            task.setStockId(stockId);
            task.setUserId(userId);
            task.setOrderNo(orderNo);
            task.setNum((int) num);
            task.setType(type.name());
            task.setReason(Strings.truncate(reason, REASON_MAX));
            task.setNextRetryTime(LocalDateTime.now().plusSeconds(firstRetryDelaySeconds));
            compensateTaskMapper.insert(task);

            metrics.onCompensateEnqueued();
            log.warn("[待补偿·登记] id={}, type={}, stockId={}, userId={}, orderNo={}, reason={}",
                    task.getId(), type, stockId, userId, orderNo, reason);
        } catch (Exception e) {
            // 登记失败是本链路最坏的情况：库存既没归还，又没留下任何待处理记录，
            // 事后无从追溯。日志必须带全还原现场所需的所有字段。
            log.error("[待补偿·登记失败 → 库存将永久丢失，需人工介入] "
                            + "type={}, stockId={}, userId={}, orderNo={}, num={}, reason={}",
                    type, stockId, userId, orderNo, num, reason, e);
        }
    }

    /**
     * 重试一批到期的待补偿任务。
     *
     * @return 本次真正补偿成功（或确认无需补偿）的条数
     */
    public int retryDue(int batchSize) {
        List<CompensateTask> due = compensateTaskMapper.selectDue(LocalDateTime.now(), batchSize);
        int recovered = 0;
        for (CompensateTask task : due) {
            if (retryOne(task)) {
                recovered++;
            }
        }
        return recovered;
    }

    private boolean retryOne(CompensateTask task) {
        CompensateType type = CompensateType.valueOf(task.getType());
        try {
            RollbackResult result = apply(task, type);

            if (result.rolledBack()) {
                metrics.onCompensateRecovered();
                compensateTaskMapper.markDone(task.getId());
                log.warn("[待补偿·完成] id={}, type={}, 剩余库存={}, orderNo={}",
                        task.getId(), type, result.remainStock(), task.getOrderNo());
                return true;
            }

            if (result.benign()) {
                // 幂等命中（此前已补过）或活动已结束，都属于「无需再做任何事」，直接结案。
                // 特别注意幂等命中：如果把它当成失败继续重试，每次都会再补一遍库存 → 超卖。
                compensateTaskMapper.markDone(task.getId());
                log.info("[待补偿·结案] id={}, type={}, 结局={}（无需处理）",
                        task.getId(), type, result.status());
                return true;
            }

            scheduleNextOrAbandon(task, result.status().name());
            return false;
        } catch (Exception e) {
            // Redis 仍未恢复、或任务数据不完整：退避后重试
            scheduleNextOrAbandon(task, e.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        }
    }

    private RollbackResult apply(CompensateTask task, CompensateType type) {
        // 前置校验：关键字段缺失时「拒绝执行」，绝不能拿 null 去操作库存。
        //
        // 这不是假想的防御。实测过一次真实后果：MyBatis 的下划线转驼峰配置因前缀写错而从未生效，
        // 任务读回来 stockId/userId 都是 null，回补脚本被以 seckill:stock:{null} 调用，
        // EXISTS 为 0 → 返回「活动已结束」→ 被下面的 benign 分支当成「无需处理」正常结案。
        // 结果是：库存静默丢失，而任务状态显示「已完成」，谁也不会去看。
        // 抛异常则会走 scheduleNextOrAbandon，重试耗尽后置为 FAILED，至少是响的。
        if (task.getStockId() == null || task.getUserId() == null || task.getNum() == null) {
            throw new IllegalStateException("待补偿任务关键字段缺失"
                    + "（stockId=" + task.getStockId() + ", userId=" + task.getUserId()
                    + ", num=" + task.getNum() + "），拒绝执行以免误操作库存。id=" + task.getId());
        }

        if (type == CompensateType.RESTORE_STOCK_ONLY) {
            if (task.getOrderNo() == null || task.getOrderNo().isBlank()) {
                // 没有单号就没有稳定的幂等标识，重试用不同的键会绕过去重保护、把库存补两次。
                // 宁可不动，也不能用不稳定的键去改库存。
                throw new IllegalStateException("RESTORE_STOCK_ONLY 任务缺少 order_no，无法安全重试");
            }
            return stockCacheService.restoreStockOnly(
                    task.getStockId(), task.getUserId(), task.getOrderNo(), task.getNum());
        }

        if (type == CompensateType.CANCEL_ORDER) {
            if (task.getOrderNo() == null || task.getOrderNo().isBlank()) {
                throw new IllegalStateException("CANCEL_ORDER 任务缺少 order_no，无法安全重试");
            }
            // 先取消（数据库，幂等），确认订单确实不成立之后才归还库存
            cancelOrderBeforeRestoring(task);
            return stockCacheService.restoreStockOnly(
                    task.getStockId(), task.getUserId(), task.getOrderNo(), task.getNum());
        }

        return stockCacheService.rollback(task.getStockId(), task.getUserId(), task.getNum());
    }

    /**
     * 归还库存的<b>前置条件</b>：订单必须已经不成立。
     * <p>
     * 【为什么不能省掉这一步】订单若仍是 PENDING，MQ 的重投随时可能拿到许可证并真实扣减库存。
     * 此时把库存还回去，就变成「订单成立 + 库存已归还」——超卖，且不可修复。
     * 所以「取消失败」绝不能退化成「照样归还」：这里选择抛异常，让任务退避后重试，
     * 重试耗尽则置 FAILED 等人介入（少卖可被对账发现，超卖不能）。
     * <p>
     * 【为什么「已取消」要放行】上一次执行可能正是在「取消成功、归还之前」崩掉的。
     * 继续归还才符合预期，而归还动作本身有一次性去重键，重复执行不会把库存补多。
     */
    private void cancelOrderBeforeRestoring(CompensateTask task) {
        if (orderMapper.cancelOrder(task.getOrderNo()) == 1) {
            return;
        }
        Order existing = orderMapper.selectByOrderNo(task.getOrderNo());
        if (existing != null && Order.STATUS_CANCELLED.equals(existing.getStatus())) {
            log.warn("[待补偿·取消订单] orderNo={} 此前已取消，继续完成归还", task.getOrderNo());
            return;
        }
        throw new IllegalStateException("订单不处于可取消状态（"
                + (existing == null ? "订单不存在" : existing.getStatus())
                + "），拒绝归还库存以免超卖。orderNo=" + task.getOrderNo());
    }

    private void scheduleNextOrAbandon(CompensateTask task, String error) {
        int attempts = task.getRetryCount() == null ? 0 : task.getRetryCount();

        if (attempts + 1 >= maxRetry) {
            metrics.onCompensateAbandoned();
            compensateTaskMapper.markFailed(task.getId(), Strings.truncate(error, ERROR_MAX));
            log.error("[待补偿·放弃 → 需人工介入] 已重试 {} 次仍失败。"
                            + "id={}, type={}, stockId={}, userId={}, orderNo={}, lastError={}",
                    attempts + 1, task.getId(), task.getType(),
                    task.getStockId(), task.getUserId(), task.getOrderNo(), error);
            return;
        }

        compensateTaskMapper.reschedule(
                task.getId(), LocalDateTime.now().plus(backoff(attempts)),
                Strings.truncate(error, ERROR_MAX));
    }

    /**
     * 指数退避：first, 2×first, 4×first …… 但不超过 5 分钟。
     * <p>
     * 算法本身在 {@link BackoffPolicy} 里 —— 与 Outbox 投递链路共用同一份实现，
     * 只是首次延迟来自不同的配置项（{@code seckill.maintenance.compensate.first-retry-delay-seconds}）。
     * 上限的作用是让「Redis 宕机 30 分钟」这类故障不必退避到几小时之后才被重试一次。
     */
    private Duration backoff(int attempts) {
        return BackoffPolicy.exponential(firstRetryDelaySeconds, attempts);
    }

    // 本类原先在这里暴露 getEnqueuedCount() / getRecoveredCount() / getAbandonedCount()
    // 读 JVM 计数器，现已删除：读数请走 SeckillMetrics。
    //
    // 另：私有的 truncate(text, max) 已抽到 Common.util.Strings（与 OutboxService 共用）。

    /** 当前仍待处理的条数（从库里查，跨实例可见） */
    public int countPending() {
        return compensateTaskMapper.countByStatus(CompensateTask.STATUS_PENDING);
    }
}
