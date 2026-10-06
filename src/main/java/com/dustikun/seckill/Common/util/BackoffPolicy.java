package com.dustikun.seckill.Common.util;

import java.time.Duration;

/**
 * 指数退避策略（Outbox 投递链路与待补偿任务链路共用）。
 *
 * <p>两处重试机制的退避公式完全一致：{@code base << min(attempts, 6)} 秒。抽到这里，
 * 是为了让「退避到多久」这件事只有一个定义，改一处即两处生效。
 *
 * <p>两个魔数为何必须同时存在：
 * <ul>
 *   <li>{@link #MAX_SHIFT} = 6：防 {@code long} 左移溢出。{@code attempts} 是无界的
 *       （一次投递失败次数没有上限），若不封顶，{@code 1L << 63} 会变成负数，
 *       退避时间成为负值 → 立刻重试 → 退避机制直接失效。</li>
 *   <li>{@link #MAX_BACKOFF} = 5 分钟：防 Broker 长时间宕机时把下次重试推到几小时之后。
 *       5 分钟是一个经验上界 —— 既给下游足够的恢复窗口，又不至于让积压的消息饿死。</li>
 * </ul>
 *
 * <p>本类无状态，工具方法，构造器私有。
 */
public final class BackoffPolicy {

    /** 左移封顶，防 {@code 1L << 63} 溢出成负数导致退避失效。 */
    private static final int MAX_SHIFT = 6;

    /** 退避时间上限：5 分钟。 */
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(5);

    private BackoffPolicy() {
    }

    /**
     * 计算第 {@code attempts} 次重试前的等待时长。
     *
     * @param firstDelaySeconds 第一次重试的基准秒数（配置项，通常 10）
     * @param attempts          已失败次数，从 0 起算；负数按 0 处理
     * @return 退避时长，落在 [firstDelaySeconds, 5min] 区间内
     */
    public static Duration exponential(long firstDelaySeconds, int attempts) {
        // base 至少 1 秒，避免配置成 0 时 << 之后退化为 0（等于不退避、疯狂重试）。
        long base = Math.max(1L, firstDelaySeconds);
        // 先夹到 [0, MAX_SHIFT]，再左移，避免算术溢出。
        int shift = Math.min(Math.max(attempts, 0), MAX_SHIFT);
        long seconds = base << shift;
        // 最后再夹一次上界：base 本身可能就很大（配置宽松），左移后会超 5min。
        return Duration.ofSeconds(Math.min(seconds, MAX_BACKOFF.toSeconds()));
    }
}
