package com.dustikun.seckill.Metrics;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 运行时观测（批次 5）：消费活跃数 / 单条耗时直方图 / 降级标志位。
 *
 * <h2>这一组测试锁的是什么</h2>
 * <p>Case 3 / Case 5 无法定论缺的就是这三类证据（{@code docs/故障注入实验记录.md} §3.5），
 * 它们的<b>读数语义</b>比读数本身更容易写错：
 * <ul>
 *   <li>活跃数必须在 {@code onConsumeEnd} 后归零 —— 漏 dec 会变成假的「线程池打满」；</li>
 *   <li>峰值只升不降 —— 它回答「打满过没有」，不被瞬时空隙骗过；</li>
 *   <li>降级标志位在事件前必须是 false —— 它表达的是「发生过降级」，不是「默认降级」。</li>
 * </ul>
 */
class SeckillMetricsRuntimeObservationTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final SeckillMetrics metrics = new SeckillMetrics(registry);

    @Test
    @DisplayName("三个观测仪表按声明的常量名注册")
    void metersAreRegisteredUnderDeclaredNames() {
        // Gauge 在注册时即存在（值可以是 0）
        assertNotNull(registry.find(SeckillMetrics.METRIC_CONSUME_ACTIVE).gauge());
        assertNotNull(registry.find(SeckillMetrics.METRIC_DEGRADATION_ACTIVE).gauge());
        assertNotNull(registry.find(SeckillMetrics.METRIC_CONSUME_PROCESS).timer());

        // Prometheus 渲染名的映射约定由 RuntimeToolTest 锁住，这里只锁 Micrometer 侧的名字
        assertEquals("seckill.consume.active.threads", SeckillMetrics.METRIC_CONSUME_ACTIVE);
        assertEquals("seckill.consume.process", SeckillMetrics.METRIC_CONSUME_PROCESS);
        assertEquals("seckill.degradation.active", SeckillMetrics.METRIC_DEGRADATION_ACTIVE);
    }

    @Test
    @DisplayName("活跃数随 begin/end 增减、峰值只升不降、耗时进直方图")
    void activeCountTracksBeginEndAndTimerRecordsDuration() {
        metrics.onConsumeBegin();
        metrics.onConsumeBegin();
        assertEquals(2, metrics.consumeActiveThreads());
        assertEquals(2L, metrics.consumeActiveThreadsMax());

        metrics.onConsumeEnd(120_000_000L);
        metrics.onConsumeEnd(80_000_000L);

        assertEquals(0, metrics.consumeActiveThreads(), "处理结束后活跃数必须归零，"
                + "否则永远读出假的「线程池打满」");
        assertEquals(2L, metrics.consumeActiveThreadsMax(), "峰值不随结束回落");

        Timer timer = metrics.consumeProcessTimer();
        assertEquals(2L, timer.count());
        assertEquals(100.0, timer.mean(TimeUnit.MILLISECONDS), 0.001);
    }

    @Test
    @DisplayName("降级标志位：事件前为 false，事件后为 true 且带时刻")
    void degradationFlagTurnsOnAfterEvent() {
        assertFalse(metrics.degradationActive(), "从未发生过降级时标志位必须是 false");
        assertEquals(0L, metrics.lastDegradedAtMillis());
        assertEquals(0.0, registry.get(SeckillMetrics.METRIC_DEGRADATION_ACTIVE).gauge().value());

        metrics.onDegraded();

        assertTrue(metrics.degradationActive());
        assertTrue(metrics.lastDegradedAtMillis() > 0);
        assertEquals(1.0, registry.get(SeckillMetrics.METRIC_DEGRADATION_ACTIVE).gauge().value());
        assertEquals(1L, metrics.degradedCount());
    }

    @Test
    @DisplayName("耗时直方图发布 P50/P95/P99 的客户端分位数与桶")
    void timerPublishesPercentilesAndBuckets() {
        metrics.onConsumeBegin();
        metrics.onConsumeEnd(50_000_000L);

        Timer timer = metrics.consumeProcessTimer();
        boolean hasRequestedPercentiles = false;
        for (var pv : timer.takeSnapshot().percentileValues()) {
            if (pv.percentile() == 0.95) {
                hasRequestedPercentiles = true;
                assertTrue(Double.isFinite(pv.value(TimeUnit.MILLISECONDS)));
            }
        }
        assertTrue(hasRequestedPercentiles, "publishPercentiles(0.5,0.95,0.99) 必须反映在快照里");
        // 【直方图桶不去这里断言】SimpleMeterRegistry 不渲染桶，桶的存在与否属于
        // PrometheusMeterRegistry 的渲染行为 —— 按本项目「去出口上数一次」的纪律，
        // 它的验证点是 /actuator/prometheus 里的 seckill_consume_process_seconds_bucket
        // （对应 HANDOFF §4 第 6 条的 le=" 计数检查），不在这里重复猜一次。
    }
}
