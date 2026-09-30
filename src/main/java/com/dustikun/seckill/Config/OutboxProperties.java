package com.dustikun.seckill.Config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 本地消息表（Outbox）的投递与归档行为，对应 application.yaml 里的 {@code seckill.outbox.*}。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "seckill.outbox")
public class OutboxProperties {

    /**
     * 是否启用 Outbox 链路。
     * <p>
     * 置为 false 时请求线程退回同步投递 MQ 的行为，
     * 用于对比两种方案的差异，或在本机排查「是不是 Outbox 引起的问题」。
     * 注意：MQ 关闭（{@code seckill.mq.enabled=false}）时本开关无效——
     * 那条链路本来就退化为同步落库，不经过 Outbox。
     */
    private boolean enabled = true;

    /**
     * 投递器轮询间隔（毫秒）。
     * <p>
     * 它决定「记录落库 → 消息进入 MQ」之间的额外延迟。设得小意味着更接近实时，
     * 但也更频繁地空转查库。1 秒是这两者之间对本地开发足够好的折中；
     * 生产环境若要求更实时，应改用「写入后唤醒 + 定时兜底」的双触发模式。
     */
    private int dispatchIntervalMs = 1000;

    /**
     * 单轮投递的最大条数。
     * <p>
     * <b>这个值现在直接决定投递吞吐</b>：批量发送把一轮的成本压成「一次网络往返 + 一条 UPDATE」，
     * 于是吞吐 ≈ batchSize / (dispatch-interval-ms + 单轮开销)。
     * 早期逐条同步发送时每轮 200 条要 2~3.5 秒，吞吐只有约 60 条/秒；
     * 改批量后 500 条一轮约十几毫秒，吞吐约 490 条/秒，足以跟上请求侧的受理速率。
     * <p>
     * 上界受 RocketMQ 批量消息 1MB 限制的约束（见 {@code SeckillMessageProducer.MAX_BATCH_BYTES}，
     * 软上限 128KB）。单条消息约 160 字节，500 条约 80KB，仍有 1.6 倍余量；
     * 若将来消息体变大，必须同步下调本值。
     */
    private int batchSize = 500;

    /**
     * 最大投递重试次数。
     * <p>
     * 与消费端重试不同：这里重试的是「消息能不能进 Broker」，属于基础设施可用性问题，
     * 通常一次 Broker 抖动几秒就恢复，因此次数给得比消费重试宽。
     * 用尽后记为 FAILED 并回补 Redis 预扣——宁可让用户重抢，也不能让库存凭空消失。
     */
    private int maxRetry = 15;

    /**
     * 首次投递失败后的重试延迟（秒）。之后指数退避，上限 5 分钟。
     * <p>
     * 给一个非零初值是为了避免 Broker 短暂不可用时把重试全部打光。
     * 注意 {@code next_retry_time} 列是 DATETIME(0)，MySQL 会对小数秒向上取整，
     * 因此实际不会早于「设定时刻」，只会略晚（最多 1 秒）。
     */
    private int firstRetryDelaySeconds = 2;

    /** 归档清理的轮询间隔（毫秒） */
    private int purgeIntervalMs = 600_000;

    /**
     * 已投递记录的保留时长（小时）。
     * <p>
     * 不需要永久保留：订单本身已经落库，outbox 记录的价值只在「投递未完成」这段时间。
     * 但也不能马上删——保留一段时间才能在排查问题时回答「这条消息当初投出去了没有」。
     */
    private int retentionHours = 24;

    /** 归档每批删除的条数，避免长事务 */
    private int purgeBatchSize = 1000;
}
