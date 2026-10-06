package com.dustikun.seckill.Service;

import com.dustikun.seckill.Common.util.BackoffPolicy;
import com.dustikun.seckill.Common.util.Strings;
import com.dustikun.seckill.Config.OutboxProperties;
import com.dustikun.seckill.Mapper.OutboxMessageMapper;
import com.dustikun.seckill.Metrics.SeckillMetrics;
import com.dustikun.seckill.Mq.SeckillMessage;
import com.dustikun.seckill.Mq.SeckillMessageProducer;
import com.dustikun.seckill.entity.OutboxMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 本地消息表（Outbox）：把「这笔预扣需要投递一条消息」在返回用户之前持久化下来。
 *
 * <pre>
 * 同步投递（有窗口）    请求线程：Redis 预扣 → 【同步投递 MQ】→ 返回已受理
 *                                      ↑ 进程在这里消失，预扣就成了孤儿
 *
 * Outbox（窗口收窄）    请求线程：Redis 预扣 → 【写入 outbox】→ 返回已受理
 *                                      ↑ 写进库了，就不再依赖请求线程活着
 *                      后台投递器：扫 outbox → 投 MQ → 标记 SENT → 失败退避重试 → 耗尽则回补
 * </pre>
 *
 * 【它真正买到了什么，以及仍然买不到什么】
 * <p>
 * 买到了：
 * <ol>
 *   <li><b>MQ 不可用不再等于用户丢单</b>。同步投递方案里 Broker 抖动一下就得当场回补、让用户重抢；
 *       现在消息先落库，Broker 恢复后自动补投。</li>
 *   <li><b>窗口从「依赖外部中间件可达性」缩到「一次本地窄表 INSERT」</b>。
 *       请求线程要消失，必须恰好落在 Redis 预扣与这条 INSERT 之间。</li>
 *   <li><b>欠账可观测</b>：{@code PENDING} 的条数就是「已受理但还没投出去」的真实规模，
 *       可以查询、可以告警，不再依赖 JVM 内存计数器。</li>
 * </ol>
 * <p>
 * 仍然买不到（必须如实说明）：<b>这条 INSERT 之前的窗口依然存在</b>。
 * 若进程在「Redis 已扣、outbox 尚未写入」之间被杀，这笔预扣依旧没有任何持久化记录。
 * 想彻底消除它，必须让预扣本身也可恢复（把扣减一起落库，或依赖 Redis 的 AOF 持久化 +
 * 幂等回放），那是另一层取舍——那等于把热点写又搬回数据库，得不偿失。
 * 这里不假装它不存在，而是靠对账兜底。
 */
@Slf4j
@Service
public class OutboxService {

    /** reason / last_error 的截断长度 */
    private static final int ERROR_MAX = 500;

    private final OutboxMessageMapper outboxMapper;
    private final ObjectProvider<SeckillMessageProducer> producerProvider;
    private final PreDeductCompensator compensator;
    private final OutboxProperties properties;

    /**
     * 指标出口。计数不再由本类自己维护 —— 本类原先持有 5 个 {@code AtomicInteger}。
     * 多实例部署时它们各看各的，而 outbox 的欠账规模本来就是跨实例的事实，
     * 用 JVM 计数器描述它天生错位（真正准确的口径是 Gauge {@code seckill.outbox.pending}）。
     */
    private final SeckillMetrics metrics;

    public OutboxService(OutboxMessageMapper outboxMapper,
                         ObjectProvider<SeckillMessageProducer> producerProvider,
                         PreDeductCompensator compensator,
                         OutboxProperties properties,
                         SeckillMetrics metrics) {
        this.outboxMapper = outboxMapper;
        this.producerProvider = producerProvider;
        this.compensator = compensator;
        this.properties = properties;
        this.metrics = metrics;
    }

    // ==================================================================== 写入

    /**
     * 登记一条待投递消息。<b>失败时抛异常</b>（不吞），因为调用方必须据此回补 Redis 预扣——
     * 记录没写成功就意味着这笔预扣又回到了「无人认领」的状态，绝不能静默返回。
     * <p>
     * 【本方法自身不开事务】它只发一条 INSERT，会加入<b>调用方当前的事务</b>。
     * 调用方是 {@code SeckillPersistenceService#createPending}，
     * 因此这条记录与那条 PENDING 预订单共享同一个本地事务 ——
     * 「订单已受理」与「消息待投递」要么一起成功、要么一起失败。
     * 反过来说：<b>任何调用方都必须处在事务中</b>，否则这个保证不成立。
     */
    public void enqueue(String orderNo, Long userId, Long stockId, long num) {
        OutboxMessage message = new OutboxMessage();
        message.setOrderNo(orderNo);
        message.setUserId(userId);
        message.setStockId(stockId);
        message.setNum((int) num);
        // 首次投递时间设在「过去 1 秒」，让记录一落库就处于可投递状态。
        // 不写 LocalDateTime.now() 是有原因的：next_retry_time 是 DATETIME(0)，
        // MySQL 对小数秒向上取整，写 now() 反而可能被推到下一秒才到期。
        message.setNextRetryTime(LocalDateTime.now().minusSeconds(1));

        outboxMapper.insert(message);
        metrics.onOutboxEnqueued();
        log.debug("[Outbox·登记] id={}, orderNo={}, stockId={}, userId={}",
                message.getId(), orderNo, stockId, userId);
    }

    // ==================================================================== 投递

    /**
     * 投递一批到期的待投递记录。
     * <p>
     * 【为什么先批量、失败再退回逐条】批量发送把 N 次网络往返和 N 条 UPDATE 都压成 1 次，
     * 是投递吞吐的关键（见 {@link SeckillMessageProducer#sendOrderCreatedBatch}）。
     * 但批量路径的失败粒度是「一整批」，拿不到「第几条没进去」——
     * 而退避重试、重试次数耗尽后回补 Redis 这些语义都是<b>按单条</b>设计的。
     * 所以这里的分工是：批量负责吞吐，逐条负责失败处置。批量抛异常时一条都不标记 SENT，
     * 直接退回逐条投递；即使批量其实已部分到达，消费端幂等也保证重投不会产生第二张订单。
     *
     * @return 本轮成功投出的条数
     */
    public int dispatchDue(int batchSize) {
        SeckillMessageProducer producer = producerProvider.getIfAvailable();
        if (producer == null) {
            // MQ 未启用：这条链路本来就不写 outbox（请求线程会退化为同步落库），
            // 真出现残留也不该在这里盲目回补，等人工确认。
            return 0;
        }

        List<OutboxMessage> due = outboxMapper.selectPending(LocalDateTime.now(), batchSize);
        if (due.isEmpty()) {
            return 0;
        }

        try {
            List<Long> ids = new ArrayList<>(due.size());
            List<SeckillMessage> messages = new ArrayList<>(due.size());
            for (OutboxMessage message : due) {
                ids.add(message.getId());
                messages.add(SeckillMessage.of(
                        message.getOrderNo(), message.getUserId(), message.getStockId(), message.getNum()));
            }
            producer.sendOrderCreatedBatch(messages);
            outboxMapper.markSentBatch(ids);
            metrics.onOutboxSent(ids.size());
            log.debug("[Outbox·批量投出] {} 条", ids.size());
            return ids.size();
        } catch (Exception e) {
            log.warn("[Outbox·批量投递未成功，退回逐条] 本批 {} 条，原因={}", due.size(), e.getMessage());
        }

        int sent = 0;
        for (OutboxMessage message : due) {
            if (dispatchOne(message, producer)) {
                sent++;
            }
        }
        return sent;
    }

    /**
     * 投递单条记录。
     * <p>
     * 【为什么不做「先抢锁再投递」】{@code SELECT ... FOR UPDATE SKIP LOCKED} 能让多实例各投各的，
     * 但要么把行锁持有到网络往返结束（长事务），要么引入 claim 状态 + 僵尸回收（复杂度陡增）。
     * 而 Outbox 的重复投递是<b>安全</b>的：消费端已有幂等（{@code uk_order_no} 唯一索引），
     * 同一条消息被投两次不会产生第二张订单。所以这里选择「不抢锁、允许偶发重复投递」，
     * 用一点点冗余换掉一整块并发控制复杂度。多实例部署时唯一代价是可能重复投递。
     */
    private boolean dispatchOne(OutboxMessage message, SeckillMessageProducer producer) {
        try {
            producer.sendOrderCreated(SeckillMessage.of(
                    message.getOrderNo(), message.getUserId(), message.getStockId(), message.getNum()));
            outboxMapper.markSent(message.getId());
            metrics.onOutboxSent(1);
            log.debug("[Outbox·已投出] orderNo={}", message.getOrderNo());
            return true;
        } catch (Exception e) {
            handleFailure(message, e);
            return false;
        }
    }

    private void handleFailure(OutboxMessage message, Exception e) {
        String error = Strings.truncate(e.getClass().getSimpleName() + ": " + e.getMessage(), ERROR_MAX);
        int attempts = message.getRetryCount() == null ? 0 : message.getRetryCount();

        if (attempts + 1 >= properties.getMaxRetry()) {
            // 先写「已放弃」，再回补库存 —— 顺序不能反。
            // 反过来的话，若在回补与改状态之间进程消失，这条记录仍会被投递器捞起来重投，
            // 于是「库存已归还」与「订单随后落库」同时发生，变成超卖。
            // 按现在的顺序，最坏情况是预扣泄漏（少卖），而对账能发现它；两者代价不对称，
            // 因此顺序选择应当明确偏向「宁可少卖，绝不超卖」。
            outboxMapper.markFailed(message.getId(), error);
            metrics.onOutboxAbandoned();
            log.error("[Outbox·放弃 → 需人工介入] 重试 {} 次仍无法投递，已回补 Redis 预扣。"
                            + "orderNo={}, stockId={}, userId={}, lastError={}",
                    attempts + 1, message.getOrderNo(), message.getStockId(), message.getUserId(), error);

            PreDeductCompensator.Outcome outcome = compensator.rollbackAll(
                    message.getStockId(), message.getUserId(), message.getNum(), "Outbox 投递重试耗尽");
            if (outcome == PreDeductCompensator.Outcome.FAILED) {
                log.error("[Outbox·放弃] 回补未成功，已登记待补偿任务，请关注 compensatePending。orderNo={}",
                        message.getOrderNo());
            }
            return;
        }

        LocalDateTime next = LocalDateTime.now().plus(backoff(attempts));
        outboxMapper.reschedule(message.getId(), next, error);
        metrics.onOutboxRetried();
        log.warn("[Outbox·退避重试] 第 {} 次投递失败，将于 {} 重试。orderNo={}, error={}",
                attempts + 1, next, message.getOrderNo(), error);
    }

    /**
     * 指数退避：first, 2×first, 4×first …… 但不超过 5 分钟。
     * <p>
     * 算法本身在 {@link BackoffPolicy} 里（与待补偿任务链路共用同一份），
     * 这里只负责把配置项喂进去。两条链路共用一份实现是刻意的：
     * 退避曲线决定「故障多久后重试」，不该存在两个可能悄悄跑偏的真相。
     */
    private Duration backoff(int attempts) {
        return BackoffPolicy.exponential(properties.getFirstRetryDelaySeconds(), attempts);
    }

    // ==================================================================== 查询

    /** 按单号查待投递记录（订单尚未落库时，用它判断这笔下单处在哪一步） */
    public OutboxMessage findByOrderNo(String orderNo) {
        return outboxMapper.selectByOrderNo(orderNo);
    }

    /** 待投递总数：已受理但消息还没进 Broker 的欠账规模 */
    public int countPending() {
        return outboxMapper.countPending();
    }

    /** 某活动待投递数：对账时作为「在途预扣下界」 */
    public int countPendingByStockId(Long stockId) {
        return outboxMapper.countPendingByStockId(stockId);
    }

    /** 已放弃总数：需要人工介入的规模 */
    public int countFailed() {
        return outboxMapper.countFailed();
    }

    // ==================================================================== 归档

    /**
     * 清理已投递且超过保留期的历史记录。
     * <p>
     * Outbox 表会持续增长，这是它固有的运维成本（同步投递方案没有这个问题）。
     * 不清会拖慢投递器的扫描；一次删干净又会造成长事务与主从延迟。
     * 因此按批删除、由定时任务反复执行。
     */
    public int purgeExpired() {
        LocalDateTime before = LocalDateTime.now().minusHours(properties.getRetentionHours());
        int removed = outboxMapper.purgeSentBefore(before, properties.getPurgeBatchSize());
        if (removed > 0) {
            metrics.onOutboxPurged(removed);
            log.info("[Outbox·归档] 已清理 {} 条早于 {} 的已投递记录", removed, before);
        }
        return removed;
    }

    // 本类原先在这里暴露 5 个 getXxxCount() 读 JVM 计数器，现已删除：
    // 读数请走 SeckillMetrics，两个出口读的是同一份。
    //
    // 另：私有的 truncate(text, max) 已抽到 Common.util.Strings —— 它与
    // CompensateTaskService 里的那份逐字相同，属于同一条「截断到列宽」的约定。
}
