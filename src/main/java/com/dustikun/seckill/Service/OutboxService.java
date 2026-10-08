package com.dustikun.seckill.Service;

import com.dustikun.seckill.Common.util.BackoffPolicy;
import com.dustikun.seckill.Common.util.Strings;
import com.dustikun.seckill.Config.OutboxProperties;
import com.dustikun.seckill.Mapper.OutboxMessageMapper;
import com.dustikun.seckill.Metrics.SeckillMetrics;
import com.dustikun.seckill.Mq.SeckillMessage;
import com.dustikun.seckill.Mq.SeckillMessageProducer;
import com.dustikun.seckill.entity.CompensateTask;
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
 *                      后台投递器：扫 outbox → 投 MQ → 标记 SENT → 失败退避重试
 *                                  → 耗尽则「同一事务里标记 FAILED + 登记归还待办」→ 立刻尝试归还
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
 *   <li><b>放弃投递不再是「一次内存里的调用」</b>：归还预扣这件事与 FAILED 同事务落库
 *       （见 {@link OutboxAbandonService}），因此进程在归还之前消失也追得回来。</li>
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

    /**
     * 「放弃投递」的落库点（FAILED + 归还待办同事务）。
     * <p>
     * 本类原先直接持有 {@code PreDeductCompensator}：放弃时先改状态、再在内存里调一次回补。
     * 那一步既不是原子的、也没有留下任何持久化痕迹，是「标记失败之后进程消失就永久少卖」的成因。
     * 现在改由本类<b>只负责登记</b>，执行交给待补偿任务链路 —— 它独立于调用线程的生命周期。
     */
    private final OutboxAbandonService abandonService;
    private final CompensateTaskService compensateTaskService;
    private final OutboxProperties properties;

    /**
     * 指标出口。计数不再由本类自己维护 —— 本类原先持有 5 个 {@code AtomicInteger}。
     * 多实例部署时它们各看各的，而 outbox 的欠账规模本来就是跨实例的事实，
     * 用 JVM 计数器描述它天生错位（真正准确的口径是 Gauge {@code seckill.outbox.pending}）。
     */
    private final SeckillMetrics metrics;

    public OutboxService(OutboxMessageMapper outboxMapper,
                         ObjectProvider<SeckillMessageProducer> producerProvider,
                         OutboxAbandonService abandonService,
                         CompensateTaskService compensateTaskService,
                         OutboxProperties properties,
                         SeckillMetrics metrics) {
        this.outboxMapper = outboxMapper;
        this.producerProvider = producerProvider;
        this.abandonService = abandonService;
        this.compensateTaskService = compensateTaskService;
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
            abandon(message, error, attempts + 1);
            return;
        }

        LocalDateTime next = LocalDateTime.now().plus(backoff(attempts));
        outboxMapper.reschedule(message.getId(), next, error);
        metrics.onOutboxRetried();
        log.warn("[Outbox·退避重试] 第 {} 次投递失败，将于 {} 重试。orderNo={}, error={}",
                attempts + 1, next, message.getOrderNo(), error);
    }

    /**
     * 重试耗尽：放弃这条记录，并让「归还这笔预扣」成为一条与 FAILED 同事务落库的待办。
     * <p>
     * 【为什么不再在这里直接回补 Redis】
     * 旧写法是「{@code markFailed}（自动提交）→ 调 {@code PreDeductCompensator} 回补」。
     * 顺序本身是对的（先放弃、后归还，反过来会让投递器把已归还的记录重新投出去），
     * 但它把<b>归还动作</b>放在了事务之外、进程之内：这两步之间进程消失，
     * 库里只留下一条 FAILED，Redis 里那件库存再没人归还，而且对账也看不出来 ——
     * 订单仍是 PENDING，会被算作「正常在途」，等式两边一起偏，结论是 CONSISTENT。
     * <p>
     * 现在拆成两步，且<b>顺序不可颠倒</b>：
     * <ol>
     *   <li>{@link OutboxAbandonService#abandon}：同一个事务里写 FAILED + 写归还待办。
     *       崩在任何一处，要么回到「还是 PENDING、继续重试」，要么两条都已落库；</li>
     *   <li>{@link CompensateTaskService#executeNow}：事务提交后立刻尝试执行一次。
     *       这只是<b>时延优化</b>，不是正确性的组成部分 —— 失败或崩溃都由 MaintenanceTask 接管。</li>
     * </ol>
     */
    private void abandon(OutboxMessage message, String error, int attempts) {
        CompensateTask task;
        try {
            task = abandonService.abandon(message.getId(), error, message.getStockId(),
                    message.getUserId(), message.getOrderNo(), message.getNum());
        } catch (Exception abandonFailure) {
            // 事务整体回滚了：记录退回 PENDING，下一轮投递会再试一次放弃。
            // 这里绝不能「接着把库存还了」—— 状态没改成功就意味着这条记录仍会被投递器捞起来重投，
            // 那时「库存已归还」与「订单随后落库」同时发生，就是超卖。
            log.error("[Outbox·放弃失败] FAILED 与归还待办未落库，记录仍在 PENDING，将重新尝试放弃。"
                            + "orderNo={}, stockId={}, userId={}, lastError={}",
                    message.getOrderNo(), message.getStockId(), message.getUserId(), error, abandonFailure);
            return;
        }

        if (task == null) {
            // 记录已被别的实例投出/放弃：本次不是「放弃」，因此不归还库存
            log.warn("[Outbox·放弃] 记录已由其它路径处理，本次不归还预扣。orderNo={}", message.getOrderNo());
            return;
        }

        metrics.onOutboxAbandoned();
        log.error("[Outbox·放弃 → 需人工介入] 重试 {} 次仍无法投递，已登记「取消订单 + 归还预扣」待办"
                        + "（id={}）。orderNo={}, stockId={}, userId={}, lastError={}",
                attempts, task.getId(), message.getOrderNo(), message.getStockId(),
                message.getUserId(), error);

        if (!compensateTaskService.executeNow(task)) {
            log.error("[Outbox·放弃] 本次归还未成功，已由待补偿任务按退避重试（请关注 compensatePending）。"
                    + "orderNo={}, taskId={}", message.getOrderNo(), task.getId());
        }
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
