package com.dustikun.seckill.monitor.tool.runtime;

import com.dustikun.seckill.Config.RocketMqProperties;
import com.dustikun.seckill.Metrics.SeckillMetrics;
import com.dustikun.seckill.monitor.tool.ToolArgumentException;
import com.dustikun.seckill.monitor.tool.ToolArguments;
import com.dustikun.seckill.monitor.tool.ToolResult;
import com.dustikun.seckill.monitor.tool.ToolStatus;
import com.dustikun.seckill.monitor.tool.metrics.FakePrometheusQuerier;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RuntimeTool（批次 5）：运行时观测的三个 operation。
 *
 * <h2>测试重点与工具本身的设计目标一一对应</h2>
 * <ul>
 *   <li><b>Case 3 判据</b>：活跃数打满 vs 不满，notes 必须直接给出判别方向；</li>
 *   <li><b>Case 5 判据</b>：降级标志位 + 事件速率，间歇性故障（rate &gt; 0 但标志为 0）
 *       必须被识别为独立形态；</li>
 *   <li><b>空 ≠ 0</b>：Prometheus 取不到序列时返回 null 并解释，绝不伪装成 0；</li>
 *   <li><b>双数据源</b>：Prometheus 不可达时进程内读数必须仍然成立。</li>
 * </ul>
 */
class RuntimeToolTest {

    private static final String WINDOW = "5m";

    private static final String Q_ACTIVE_AVG = "avg_over_time(seckill_consume_active_threads[" + WINDOW + "])";
    private static final String Q_ACTIVE_MAX = "max_over_time(seckill_consume_active_threads[" + WINDOW + "])";
    private static final String Q_PROCESS_COUNT = "sum(rate(seckill_consume_process_seconds_count[" + WINDOW + "]))";
    private static final String Q_PROCESS_SUM = "sum(rate(seckill_consume_process_seconds_sum[" + WINDOW + "]))";
    private static final String Q_P50 = "histogram_quantile(0.5, sum by (le) (rate(seckill_consume_process_seconds_bucket[" + WINDOW + "])))";
    private static final String Q_P95 = "histogram_quantile(0.95, sum by (le) (rate(seckill_consume_process_seconds_bucket[" + WINDOW + "])))";
    private static final String Q_P99 = "histogram_quantile(0.99, sum by (le) (rate(seckill_consume_process_seconds_bucket[" + WINDOW + "])))";
    private static final String Q_DEGRADED_RATE = "sum(rate(seckill_request_degraded_total[" + WINDOW + "]))";

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final SeckillMetrics metrics = new SeckillMetrics(registry);
    private final RocketMqProperties mqProperties = new RocketMqProperties();

    private RuntimeTool tool(FakePrometheusQuerier querier) {
        return new RuntimeTool(metrics, querier, mqProperties);
    }

    private static ToolArguments args(Map<String, Object> raw) {
        return ToolArguments.of(RuntimeTool.NAME, raw);
    }

    @Test
    @DisplayName("Prometheus 序列名常量与 Micrometer 命名约定严格对应（防两侧漂移）")
    void prometheusSeriesNamesMatchMicrometerConvention() {
        assertEquals(SeckillMetrics.METRIC_CONSUME_ACTIVE.replace('.', '_'),
                RuntimeTool.PROM_ACTIVE_THREADS);
        String base = SeckillMetrics.METRIC_CONSUME_PROCESS.replace('.', '_') + "_seconds";
        assertEquals(base + "_count", RuntimeTool.PROM_PROCESS_COUNT);
        assertEquals(base + "_sum", RuntimeTool.PROM_PROCESS_SUM);
        assertEquals(base + "_bucket", RuntimeTool.PROM_PROCESS_BUCKET);
        assertEquals(SeckillMetrics.METRIC_REQUEST_DEGRADED.replace('.', '_') + "_total",
                RuntimeTool.PROM_DEGRADED_TOTAL);
    }

    // ================================================================ GET_CONSUMER_THREAD_STATE

    @Test
    @DisplayName("线程池打满 → 直接给出「容量瓶颈」判别与调参边界")
    void saturatedThreadPoolIsCalledOut() {
        FakePrometheusQuerier querier = new FakePrometheusQuerier()
                .onInstant(Q_ACTIVE_AVG, FakePrometheusQuerier.value(7.2))
                .onInstant(Q_ACTIVE_MAX, FakePrometheusQuerier.value(8));

        ToolResult result = tool(querier).execute(args(Map.of(
                "operation", "GET_CONSUMER_THREAD_STATE")));

        assertEquals(ToolStatus.SUCCESS, result.status());
        assertEquals(8.0, (double) result.data().get("maxOverWindow"), 1e-9);
        assertEquals(7.2, (double) result.data().get("avgOverWindow"), 1e-9);
        assertEquals(0, ((Number) result.data().get("activeThreadsNow")).intValue());
        assertEquals(mqProperties.getConsumeThreadMax(),
                ((Number) result.data().get("configuredThreadMax")).intValue());
        assertTrue(result.notes().stream().anyMatch(n -> n.contains("线程池至少打满过一次")),
                result.notes().toString());
        assertTrue(result.notes().stream().anyMatch(n -> n.contains("Hikari")),
                "调参边界（不要超过连接池大小）必须随判别一起给出：" + result.notes());
    }

    @Test
    @DisplayName("活跃数从未打满 → 指向「单条耗时瓶颈」并让 Agent 用耗时分布定量验证")
    void unsaturatedThreadPoolPointsAtPerMessageLatency() {
        FakePrometheusQuerier querier = new FakePrometheusQuerier()
                .onInstant(Q_ACTIVE_AVG, FakePrometheusQuerier.value(1.1))
                .onInstant(Q_ACTIVE_MAX, FakePrometheusQuerier.value(2));

        ToolResult result = tool(querier).execute(args(Map.of(
                "operation", "GET_CONSUMER_THREAD_STATE")));

        assertEquals(ToolStatus.SUCCESS, result.status());
        assertTrue(result.notes().stream().anyMatch(n -> n.contains("单条处理耗时")),
                result.notes().toString());
        assertTrue(result.notes().stream().anyMatch(n -> n.contains("GET_MESSAGE_LATENCY")),
                result.notes().toString());
    }

    @Test
    @DisplayName("取不到 over_time 序列 → 窗口字段为 null 而不是 0，进程内读数仍在")
    void missingWindowSeriesIsNullOrNotZero() {
        ToolResult result = tool(new FakePrometheusQuerier()).execute(args(Map.of(
                "operation", "GET_CONSUMER_THREAD_STATE")));

        assertEquals(ToolStatus.SUCCESS, result.status());
        assertNull(result.data().get("avgOverWindow"), "「没有序列」必须表达为 null");
        assertNull(result.data().get("maxOverWindow"));
        assertNotNull(result.data().get("activeThreadsNow"));
        assertTrue(result.notes().stream().anyMatch(n -> n.contains("null")),
                result.notes().toString());
    }

    @Test
    @DisplayName("Prometheus 不可达 → 窗口视图缺失但进程内实时读数仍然成立")
    void prometheusOutageDoesNotDisableInProcessReadings() {
        metrics.onConsumeBegin();

        ToolResult result = tool(new FakePrometheusQuerier().reachable(false))
                .execute(args(Map.of("operation", "GET_CONSUMER_THREAD_STATE")));

        assertEquals(ToolStatus.SUCCESS, result.status());
        assertFalse((boolean) result.data().get("prometheusAvailable"));
        assertEquals(1, ((Number) result.data().get("activeThreadsNow")).intValue(),
                "进程内读数不依赖 Prometheus —— 这正是双数据源设计的意义");
        assertFalse(result.data().containsKey("avgOverWindow"));
        assertTrue(result.notes().stream().anyMatch(n -> n.contains("不可达")), result.notes().toString());
    }

    // ================================================================ GET_MESSAGE_LATENCY

    @Test
    @DisplayName("耗时分布：窗口 P50/P95/P99 + 吞吐 + 单线程容量估算 + 长尾判别")
    void latencyDistributionWithCapacityEstimateAndLongTailNote() {
        metrics.onConsumeBegin();
        metrics.onConsumeEnd(120_000_000L);

        // avg = sumRate/countRate = 0.12s；p99=0.4s > 3×avg → 长尾
        FakePrometheusQuerier querier = new FakePrometheusQuerier()
                .onInstant(Q_PROCESS_COUNT, FakePrometheusQuerier.value(100))
                .onInstant(Q_PROCESS_SUM, FakePrometheusQuerier.value(12))
                .onInstant(Q_P50, FakePrometheusQuerier.value(0.1))
                .onInstant(Q_P95, FakePrometheusQuerier.value(0.3))
                .onInstant(Q_P99, FakePrometheusQuerier.value(0.4));

        ToolResult result = tool(querier).execute(args(Map.of(
                "operation", "GET_MESSAGE_LATENCY")));

        assertEquals(ToolStatus.SUCCESS, result.status());
        assertEquals(100.0, (double) result.data().get("throughputPerSecond"), 1e-9);
        assertEquals(120.0, (double) result.data().get("avgMillis"), 1e-9);
        assertEquals(300.0, (double) result.data().get("p95Millis"), 1e-9);
        assertEquals(400.0, (double) result.data().get("p99Millis"), 1e-9);
        assertEquals(120.0, (double) result.data().get("meanMillisSinceStart"), 1e-9);
        assertEquals(1L, ((Number) result.data().get("sampleCountSinceStart")).longValue());
        // 单线程容量 = 1 / 0.12s ≈ 8.333 条/秒
        assertEquals(8.333, (double) result.data().get("estimatedSingleThreadCapacityPerSecond"), 0.001);
        assertTrue(result.notes().stream().anyMatch(n -> n.contains("长尾分布")), result.notes().toString());
        assertTrue(result.notes().stream().anyMatch(n -> n.contains("条/秒")),
                "容量估算必须给出来（Case 3 的定量判据）：" + result.notes());
    }

    @Test
    @DisplayName("窗口无样本 → 直方图字段缺失并说明；样本数为 0 是确知的事实")
    void zeroSamplesIsStatedExplicitly() {
        ToolResult result = tool(new FakePrometheusQuerier()).execute(args(Map.of(
                "operation", "GET_MESSAGE_LATENCY")));

        assertEquals(ToolStatus.SUCCESS, result.status());
        assertEquals(0L, ((Number) result.data().get("sampleCountSinceStart")).longValue());
        assertFalse(result.data().containsKey("avgMillis"));
        assertFalse(result.data().containsKey("estimatedSingleThreadCapacityPerSecond"),
                "没有样本时不得给出 1/0 之类的伪容量");
        assertTrue(result.notes().stream().anyMatch(n -> n.contains("没有记录过")), result.notes().toString());
    }

    // ================================================================ GET_DEGRADATION_STATE

    @Test
    @DisplayName("从未降级：事件计数 0 是确知事实，且不据此断言 Redis 健康")
    void neverDegradedIsAFactNotAHealthClaim() {
        ToolResult result = tool(new FakePrometheusQuerier()).execute(args(Map.of(
                "operation", "GET_DEGRADATION_STATE")));

        assertEquals(ToolStatus.SUCCESS, result.status());
        assertEquals(false, result.data().get("degradationActive"));
        assertEquals(0L, ((Number) result.data().get("degradedEventsTotal")).longValue());
        assertNull(result.data().get("lastDegradedAt"));
        assertTrue(result.notes().stream().anyMatch(n -> n.contains("从未触发过")), result.notes().toString());
        assertTrue(result.notes().stream().anyMatch(n -> n.contains("≠ Redis 一定健康")),
                result.notes().toString());
    }

    @Test
    @DisplayName("降级标志位为 1：给出窗口语义与「对账漂移、人工预热对齐」的预期")
    void activeDegradationCarriesDesignContext() {
        metrics.onDegraded();

        ToolResult result = tool(new FakePrometheusQuerier()).execute(args(Map.of(
                "operation", "GET_DEGRADATION_STATE")));

        assertEquals(ToolStatus.SUCCESS, result.status());
        assertEquals(true, result.data().get("degradationActive"));
        assertEquals(1L, ((Number) result.data().get("degradedEventsTotal")).longValue());
        assertNotNull(result.data().get("lastDegradedAt"));
        assertTrue(result.notes().stream().anyMatch(n -> n.contains("设计行为")), result.notes().toString());
        assertTrue(result.notes().stream().anyMatch(n -> n.contains("MARK_MISSING")), result.notes().toString());
        assertTrue(result.notes().stream().anyMatch(n -> n.contains("人工")), result.notes().toString());
    }

    @Test
    @DisplayName("间歇性故障形态：窗口内速率 > 0 但标志位为 0，作为独立形态指出")
    void pastDegradationWithQuietFlagIsCalledOut() {
        // 进程从未降级（标志位 0），但 Prometheus 显示窗口内发生过降级事件
        FakePrometheusQuerier querier = new FakePrometheusQuerier()
                .onInstant(Q_DEGRADED_RATE, FakePrometheusQuerier.value(0.4));

        ToolResult result = tool(querier).execute(args(Map.of(
                "operation", "GET_DEGRADATION_STATE")));

        assertEquals(ToolStatus.SUCCESS, result.status());
        assertEquals(false, result.data().get("degradationActive"));
        assertEquals(0.4, (double) result.data().get("degradedRatePerSecond"), 1e-9);
        assertTrue(result.notes().stream().anyMatch(n -> n.contains("间歇性") && n.contains("漂移")),
                result.notes().toString());
    }

    // ================================================================ 参数校验

    @Test
    @DisplayName("window 越界（30s / 2h）被拒绝，1h 合法")
    void windowRangeIsEnforced() {
        ToolResult withOneHour = tool(new FakePrometheusQuerier())
                .execute(args(Map.of("operation", "GET_CONSUMER_THREAD_STATE", "window", "1h")));
        assertEquals(ToolStatus.SUCCESS, withOneHour.status());
        assertEquals("1h", withOneHour.data().get("window"));

        assertThrows(ToolArgumentException.class, () -> tool(new FakePrometheusQuerier())
                .execute(args(Map.of("operation", "GET_CONSUMER_THREAD_STATE", "window", "30s"))));
        assertThrows(ToolArgumentException.class, () -> tool(new FakePrometheusQuerier())
                .execute(args(Map.of("operation", "GET_CONSUMER_THREAD_STATE", "window", "2h"))));
        assertThrows(ToolArgumentException.class, () -> tool(new FakePrometheusQuerier())
                .execute(args(Map.of("operation", "GET_CONSUMER_THREAD_STATE", "window", "不是时长"))));
    }
}
