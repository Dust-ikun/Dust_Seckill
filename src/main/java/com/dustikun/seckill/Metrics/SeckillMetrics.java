package com.dustikun.seckill.Metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 秒杀链路的<b>唯一指标出口</b>：一份真相，两个出口。
 *
 * <pre>
 *                       ┌──────────────────────────────┐
 *   业务代码 ──onXxx()──▶│        SeckillMetrics        │
 *                       │  Counter / Gauge（注册表内）  │
 *                       └───────────┬──────────────────┘
 *                                   │
 *              ┌────────────────────┴────────────────────┐
 *              ▼                                         ▼
 *   /actuator/prometheus                    GET /seckill/metrics
 *   （机器：告警规则 / Grafana）              （人：排障 / 交付验收）
 * </pre>
 *
 * <p><b>为什么要把原来各 Service 里的 {@code AtomicInteger} 全部删掉</b>
 * <p>
 * 不是「多了一个依赖」，而是原先那套计数有三个回避不掉的缺陷：
 * <ol>
 *   <li><b>重启即归零</b>：{@code AtomicInteger} 是纯进程内状态，重启后曲线断崖式回到 0，
 *       既没法算一段时间内的成功率，也没法用它写「最近 5 分钟失败率 &gt; 1%」这种告警规则。</li>
 *   <li><b>多实例各看各的</b>：部署两个实例时，{@code /seckill/metrics} 打到哪个实例就只看到哪个实例的数，
 *       要自己加；而 Prometheus 抓下来天然按实例聚合，也能 sum()。</li>
 *   <li><b>没有时间维度</b>：这是最要命的。{@code consumed} 是一个瞬时累计值，
 *       你无法从它判断「现在消费速度是快了还是慢了」——而 <b>rate() 才是排障的起点</b>。</li>
 * </ol>
 * <p>
 * 因此本类的定位是「把计数搬进 {@link MeterRegistry}」，而不是「再加一套计数」。
 * 业务类只允许调用本类的 {@code onXxx()}，不允许再自己维护任何计数器。
 *
 * <p><b>累计量一律用 {@link Counter}，不用 Gauge</b>
 * <p>
 * Counter 是单调递增的，Prometheus 能识别进程重启造成的「counter reset」并正确计算速率；
 * 若把累计值做成 Gauge，重启会在曲线上造出一个从 N 掉回 0 的假跌，rate() 会算出负数。
 *
 * <p><b>Gauge 一律走「缓存 + 定时刷新」，绝不直接查库</b>
 * <p>
 * 写成 {@code Gauge.builder("...", outboxService, OutboxService::countPending)} 看起来最省事，
 * 但那意味着<b>每次 scrape 都执行一次 {@code SELECT COUNT(*)}</b>：按 15 秒的抓取间隔，
 * 一天就是 5760 次全表聚合——等于让监控系统给数据库加负载，而这恰恰是引入监控要避免的事。
 * 所以这里的 Gauge 读的是本地 {@link AtomicLong} 缓存，由
 * {@link SeckillMetricsRefresher} 按固定间隔（默认 30s）刷新。
 * 代价是最多滞后一个刷新周期，对「欠账规模」这类慢变量完全够用。
 *
 * <p><b>为什么不把 {@code refreshXxx} 直接做成查库方法放在本类</b>
 * <p>
 * 本类被 {@code OutboxService} / {@code CompensateTaskService} 等业务类注入。
 * 若本类再去反向依赖它们，就构成构造器循环依赖。因此本类只负责「存值 + 暴露」，
 * 「取值」交给 {@link SeckillMetricsRefresher}——依赖方向保持单向。
 */
@Component
public class SeckillMetrics {

    // ================================================================ 请求侧

    private final Counter queued;
    private final Counter degraded;
    private final Counter syncSuccess;
    private final Counter rollbackAll;
    private final Counter restoreStockOnly;

    // ================================================================ 待投递侧（Outbox）

    private final Counter outboxEnqueued;
    private final Counter outboxSent;
    private final Counter outboxRetried;
    private final Counter outboxAbandoned;
    private final Counter outboxPurged;

    // ================================================================ MQ 投递侧

    private final Counter mqSent;
    private final Counter mqSendFailed;

    // ================================================================ 消费侧

    private final Counter consumeConfirmed;
    private final Counter consumeDuplicate;
    private final Counter consumeOrderMissing;
    private final Counter consumeCancelled;
    private final Counter consumeFailed;
    private final Counter consumeStockRestored;

    // ================================================================ 补偿侧

    private final Counter compensateEnqueued;
    private final Counter compensateRecovered;
    private final Counter compensateAbandoned;

    // ================================================================ 数据库事实（Gauge）
    //
    // 【为什么必须做成字段而不是局部变量】Micrometer 的 Gauge 只持有对象的<b>弱引用</b>
    // （避免注册表把业务对象钉在堆里不放）。若传一个内联 lambda 或临时对象，
    // 注册表还没来得及抓取，它就已经被 GC —— 表现是 Gauge 恒为 NaN，且完全不报错。
    // 这三个 AtomicLong 是本单例 Bean 的字段，生命周期与容器一致，因此是安全的。

    /** 已受理但消息还没进 Broker 的欠账规模（来自数据库，多实例视角下依然准确） */
    private final AtomicLong outboxPending = new AtomicLong();

    /** 投递重试耗尽、需人工介入的规模 */
    private final AtomicLong outboxFailed = new AtomicLong();

    /** 待补偿任务未结案数 */
    private final AtomicLong compensatePending = new AtomicLong();

    public SeckillMetrics(MeterRegistry registry) {
        // ---------------- 请求侧 ----------------
        this.queued = Counter.builder("seckill.request.queued")
                .description("已受理的下单数（Redis 预扣成功并已写入预订单与待投递凭据）")
                .register(registry);
        this.degraded = Counter.builder("seckill.request.degraded")
                .description("Redis 不可用触发降级、回落 MySQL 条件扣减的次数")
                .register(registry);
        this.syncSuccess = Counter.builder("seckill.request.sync.success")
                .description("降级路径下同步落库成功的订单数")
                .register(registry);
        this.rollbackAll = Counter.builder("seckill.rollback.all")
                .description("完整回滚成功次数（库存 + 已购标记一起还原）")
                .register(registry);
        this.restoreStockOnly = Counter.builder("seckill.rollback.stock.only")
                .description("仅回补库存成功次数（保留已购标记，用于「用户其实已买过」）")
                .register(registry);

        // ---------------- 待投递侧 ----------------
        this.outboxEnqueued = Counter.builder("seckill.outbox.enqueued")
                .description("写入 local message table 的待投递记录数")
                .register(registry);
        this.outboxSent = Counter.builder("seckill.outbox.sent")
                .description("outbox 记录成功投出到 Broker 的条数（批量投递按条计入）")
                .register(registry);
        this.outboxRetried = Counter.builder("seckill.outbox.retried")
                .description("outbox 投递失败进入退避重试的条数")
                .register(registry);
        this.outboxAbandoned = Counter.builder("seckill.outbox.abandoned")
                .description("outbox 重试耗尽、已放弃并登记归还待办的条数（需人工过问）")
                .register(registry);
        this.outboxPurged = Counter.builder("seckill.outbox.purged")
                .description("归档清理掉的已投递历史记录数")
                .register(registry);

        // ---------------- MQ 投递侧 ----------------
        this.mqSent = Counter.builder("seckill.mq.sent")
                .description("得到 Broker 确认的消息条数（含批量投递）")
                .register(registry);
        this.mqSendFailed = Counter.builder("seckill.mq.send.failed")
                .description("发送未获 Broker 确认的消息条数（按其实际规模计入：单条 1、批量整批）")
                .register(registry);

        // ---------------- 消费侧 ----------------
        this.consumeConfirmed = Counter.builder("seckill.consume.confirmed")
                .description("消费端成功确认订单并扣减数据库库存的条数")
                .register(registry);
        this.consumeDuplicate = Counter.builder("seckill.consume.duplicate")
                .description("重复投递、订单已结案（幂等命中，无许可证）的条数")
                .register(registry);
        this.consumeOrderMissing = Counter.builder("seckill.consume.order.missing")
                .description("待投递凭据对应的订单不存在，跳过扣减的条数（需人工过问）")
                .register(registry);
        this.consumeCancelled = Counter.builder("seckill.consume.cancelled")
                .description("重试耗尽后成功取消订单的次数")
                .register(registry);
        this.consumeFailed = Counter.builder("seckill.consume.failed")
                .description("重试耗尽、放弃本条消息的次数")
                .register(registry);
        this.consumeStockRestored = Counter.builder("seckill.consume.stock.restored")
                .description("订单已取消后归还 Redis 预扣成功的次数")
                .register(registry);

        // ---------------- 补偿侧 ----------------
        this.compensateEnqueued = Counter.builder("seckill.compensate.enqueued")
                .description("登记到待补偿任务表的条数")
                .register(registry);
        this.compensateRecovered = Counter.builder("seckill.compensate.recovered")
                .description("待补偿任务执行成功（或确认无需补偿）而结案的条数")
                .register(registry);
        this.compensateAbandoned = Counter.builder("seckill.compensate.abandoned")
                .description("待补偿任务重试耗尽、标记 FAILED 的条数（需人工介入）")
                .register(registry);

        // ---------------- 数据库事实 ----------------
        Gauge.builder("seckill.outbox.pending", outboxPending, AtomicLong::doubleValue)
                .description("status=PENDING 的 outbox 条数：已受理但还没投进 Broker 的真实欠账")
                .register(registry);
        Gauge.builder("seckill.outbox.failed", outboxFailed, AtomicLong::doubleValue)
                .description("status=FAILED 的 outbox 条数：投递重试耗尽，需人工介入")
                .register(registry);
        Gauge.builder("seckill.compensate.pending", compensatePending, AtomicLong::doubleValue)
                .description("status=PENDING 的待补偿任务条数：还没追回来的库存账")
                .register(registry);
    }

    // ================================================================ 请求侧

    /** 受理成功一次（预订单 + 待投递凭据已同一事务落库） */
    public void onQueued() {
        queued.increment();
    }

    /** Redis 不可用、触发降级 */
    public void onDegraded() {
        degraded.increment();
    }

    /** 降级路径下同步落库成功 */
    public void onSyncSuccess() {
        syncSuccess.increment();
    }

    /** 完整回滚（库存 + 标记）成功 */
    public void onRollbackAll() {
        rollbackAll.increment();
    }

    /** 仅回补库存（保留标记）成功 */
    public void onRestoreStockOnly() {
        restoreStockOnly.increment();
    }

    // ================================================================ 待投递侧

    public void onOutboxEnqueued() {
        outboxEnqueued.increment();
    }

    public void onOutboxSent(int count) {
        outboxSent.increment(count);
    }

    public void onOutboxRetried() {
        outboxRetried.increment();
    }

    public void onOutboxAbandoned() {
        outboxAbandoned.increment();
    }

    public void onOutboxPurged(int count) {
        outboxPurged.increment(count);
    }

    // ================================================================ MQ 投递侧

    public void onMqSent(int count) {
        mqSent.increment(count);
    }

    public void onMqSendFailed(int count) {
        mqSendFailed.increment(count);
    }

    // ================================================================ 消费侧

    public void onConsumeConfirmed() {
        consumeConfirmed.increment();
    }

    public void onConsumeDuplicate() {
        consumeDuplicate.increment();
    }

    public void onConsumeOrderMissing() {
        consumeOrderMissing.increment();
    }

    public void onConsumeCancelled() {
        consumeCancelled.increment();
    }

    public void onConsumeFailed() {
        consumeFailed.increment();
    }

    public void onConsumeStockRestored() {
        consumeStockRestored.increment();
    }

    // ================================================================ 补偿侧

    public void onCompensateEnqueued() {
        compensateEnqueued.increment();
    }

    public void onCompensateRecovered() {
        compensateRecovered.increment();
    }

    public void onCompensateAbandoned() {
        compensateAbandoned.increment();
    }

    // ================================================================ Gauge 刷新
    //
    // 调用方是 SeckillMetricsRefresher（唯一有权写这三个缓存的地方）。

    public void refreshOutboxGauges(long pending, long failed) {
        outboxPending.set(pending);
        outboxFailed.set(failed);
    }

    public void refreshCompensatePending(long pending) {
        compensatePending.set(pending);
    }

    // ================================================================ 读取出口
    //
    // 下面这些是给 /seckill/metrics 用的。它们读的是<b>注册表里的同一个 Counter</b>，
    // 因此两个出口不可能给出不一致的数字——这是「一份真相」的具体含义。
    // 返回 long 而不是 Counter.count() 的 double：这是给人看的排障视图，
    // "queued": 12345 比 "queued": 12345.0 好读。

    public long queuedCount() {
        return (long) queued.count();
    }

    public long degradedCount() {
        return (long) degraded.count();
    }

    public long syncSuccessCount() {
        return (long) syncSuccess.count();
    }

    public long rollbackAllCount() {
        return (long) rollbackAll.count();
    }

    public long restoreStockOnlyCount() {
        return (long) restoreStockOnly.count();
    }

    public long outboxEnqueuedCount() {
        return (long) outboxEnqueued.count();
    }

    public long outboxSentCount() {
        return (long) outboxSent.count();
    }

    public long outboxRetriedCount() {
        return (long) outboxRetried.count();
    }

    public long outboxAbandonedCount() {
        return (long) outboxAbandoned.count();
    }

    public long outboxPurgedCount() {
        return (long) outboxPurged.count();
    }

    public long mqSentCount() {
        return (long) mqSent.count();
    }

    public long mqSendFailedCount() {
        return (long) mqSendFailed.count();
    }

    public long consumeConfirmedCount() {
        return (long) consumeConfirmed.count();
    }

    public long consumeDuplicateCount() {
        return (long) consumeDuplicate.count();
    }

    public long consumeOrderMissingCount() {
        return (long) consumeOrderMissing.count();
    }

    public long consumeCancelledCount() {
        return (long) consumeCancelled.count();
    }

    public long consumeFailedCount() {
        return (long) consumeFailed.count();
    }

    public long consumeStockRestoredCount() {
        return (long) consumeStockRestored.count();
    }

    public long compensateEnqueuedCount() {
        return (long) compensateEnqueued.count();
    }

    public long compensateRecoveredCount() {
        return (long) compensateRecovered.count();
    }

    public long compensateAbandonedCount() {
        return (long) compensateAbandoned.count();
    }

    /** Gauge 缓存当前值，供 /seckill/metrics 免去一次查库 */
    public long outboxPendingGauge() {
        return outboxPending.get();
    }

    public long outboxFailedGauge() {
        return outboxFailed.get();
    }

    public long compensatePendingGauge() {
        return compensatePending.get();
    }

    /**
     * 已处理完的消息数：确认成功 / 重复投递 / 订单缺失 / 重试耗尽后已 ACK。
     * <p>
     * 与投递侧相减即可估算「在途消息数」。注意这是<b>单实例</b>视角的估算，
     * 多实例下只是粗略提示；对账使用的「在途预扣数」用的是数据库事实
     * {@code COUNT(orders WHERE status='PENDING')}，不依赖本方法。
     */
    public long resolvedCount() {
        return (long) (consumeConfirmed.count() + consumeDuplicate.count()
                + consumeOrderMissing.count() + consumeFailed.count());
    }
}
