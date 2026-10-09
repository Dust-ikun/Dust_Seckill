package com.dustikun.seckill.monitor.tool.metrics;

import com.dustikun.seckill.monitor.log.MonitorLogProperties;
import com.dustikun.seckill.monitor.tool.ToolArguments;
import com.dustikun.seckill.monitor.tool.ToolArgumentException;
import com.dustikun.seckill.monitor.tool.ToolProperties;
import com.dustikun.seckill.monitor.tool.ToolResult;
import com.dustikun.seckill.monitor.tool.ToolStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Metrics Tool：取值、聚合、以及「没有数据 ≠ 没有异常」这条纪律（SPEC 第 9.1 / 23.3 节）。
 *
 * <h2>两条最重要的断言</h2>
 * <ol>
 *   <li>{@link #unreachablePrometheusIsNotAnEmptyResult()} —— Prometheus 不可达时，
 *       结果必须显式说明「这是拿不到指标」而不是安静地返回空 series。
 *       后者会被 Agent 读成「一切正常」，而那正好是最危险的结论；</li>
 *   <li>{@link #nanSamplesAreExcludedNotTreatedAsZero()} —— NaN 被剔除，
 *       而不是当 0。当 0 会让一条「整窗没有数据」的 P99 显示为 avg=0、max=0，
 *       即「延迟为零，非常健康」。</li>
 * </ol>
 */
class MetricsToolTest {

    private static final String SERVICE = "order-service";

    private static final String P99_QUERY = "seckill:http_p99_latency:5m{service=\"" + SERVICE + "\"}";

    private final MetricCatalog catalog = MetricCatalog.defaults();

    private MetricsTool tool(FakePrometheusQuerier querier) {
        return new MetricsTool(querier, catalog,
                new ToolProperties(null, null, null),
                new MonitorLogProperties(SERVICE, 20, 500, true));
    }

    private static ToolArguments args(Map<String, Object> raw) {
        return ToolArguments.of(MetricsTool.NAME, raw);
    }

    @Test
    @DisplayName("返回 SPEC 第 9.1 节的结构：metric + 每条序列的 avg/min/max/p99/last")
    void returnsSpecShape() {
        FakePrometheusQuerier querier = new FakePrometheusQuerier()
                .onRange(P99_QUERY, FakePrometheusQuerier.series(
                        FakePrometheusQuerier.labels("uri", "/seckill"), 0.1, 0.2, 0.3, 2.1));

        ToolResult result = tool(querier).execute(args(Map.of("metric", "http_p99_latency")));

        assertEquals(ToolStatus.SUCCESS, result.status());
        assertEquals("http_p99_latency", result.data().get("metric"));
        assertEquals(P99_QUERY, result.data().get("query"));
        assertEquals("秒", result.data().get("unit"));
        assertEquals(SERVICE, result.data().get("service"));
        assertNotNull(result.data().get("window"));

        Map<String, Object> series = firstSeries(result);
        assertEquals(0.675, (double) series.get("avg"), 1e-9);
        assertEquals(0.1, (double) series.get("min"), 1e-9);
        assertEquals(2.1, (double) series.get("max"), 1e-9);
        assertEquals(2.1, (double) series.get("last"), 1e-9);
        assertEquals(2.1, (double) series.get("p99"), 1e-9);
        assertEquals(4, series.get("samples"));
        assertEquals("worsening", series.get("trend"));
        assertEquals(Map.of("uri", "/seckill"), series.get("labels"));

        Map<String, Object> summary = summary(result);
        assertEquals(1, summary.get("seriesCount"));
        assertEquals(2.1, (double) summary.get("worstMax"), 1e-9);
    }

    @Test
    @DisplayName("NaN 采样点被剔除并计数，而不是当 0（当 0 会得出「延迟为零，非常健康」）")
    void nanSamplesAreExcludedNotTreatedAsZero() {
        FakePrometheusQuerier querier = new FakePrometheusQuerier()
                .onRange(P99_QUERY, FakePrometheusQuerier.series(
                        FakePrometheusQuerier.labels("uri", "/seckill"),
                        0.2, Double.NaN, 0.4, Double.NaN));

        ToolResult result = tool(querier).execute(args(Map.of("metric", "http_p99_latency")));

        Map<String, Object> series = firstSeries(result);
        assertEquals(2, series.get("samples"), "NaN 不该被计入样本数");
        assertEquals(0.3, (double) series.get("avg"), 1e-9);
        assertEquals(0.4, (double) series.get("max"), 1e-9);
        assertTrue(result.notes().stream().anyMatch(n -> n.contains("NaN")), result.notes().toString());
    }

    @Test
    @DisplayName("整条序列都是 NaN 时，series 为空并说明「不等于该指标为 0」")
    void allNanBecomesEmptyWithExplanation() {
        FakePrometheusQuerier querier = new FakePrometheusQuerier()
                .onRange(P99_QUERY, FakePrometheusQuerier.series(
                        FakePrometheusQuerier.labels("uri", "/seckill"), Double.NaN, Double.NaN));

        ToolResult result = tool(querier).execute(args(Map.of("metric", "http_p99_latency")));

        assertEquals(ToolStatus.SUCCESS, result.status());
        assertEquals(List.of(), result.data().get("series"));
        assertTrue(result.data().get("available").equals(true));
        String notes = String.join(" ", result.notes());
        assertTrue(notes.contains("可用采样点"), notes);
        assertTrue(notes.contains("不等于该指标为 0"), notes);
        assertTrue(notes.contains("NaN"), "整条 NaN 的序列要说明原因：" + notes);
    }

    @Test
    @DisplayName("Prometheus 不可达 ≠ 空结果：available=false 且明确要求说明证据缺失")
    void unreachablePrometheusIsNotAnEmptyResult() {
        FakePrometheusQuerier querier = new FakePrometheusQuerier().reachable(false);

        ToolResult result = tool(querier).execute(args(Map.of("metric", "http_p99_latency")));

        assertEquals(ToolStatus.SUCCESS, result.status());
        assertEquals(Boolean.FALSE, result.data().get("available"));
        assertFalse(querier.asked(P99_QUERY), "不可达时不该再发查询");
        String notes = String.join(" ", result.notes());
        assertTrue(notes.contains("不可达"), notes);
        assertTrue(notes.contains("证据缺失") || notes.contains("不代表"), notes);
    }

    @Test
    @DisplayName("不在白名单里的指标名被拒绝，且错误消息列出可选值（不得伪造不存在的指标）")
    void unknownMetricIsRejectedWithTheWhitelist() {
        ToolArgumentException e = assertThrows(ToolArgumentException.class,
                () -> tool(new FakePrometheusQuerier()).execute(args(Map.of("metric", "mysql_slow_query_total"))));

        assertEquals("metric", e.argumentName());
        assertTrue(e.getMessage().contains("http_p99_latency"), e.getMessage());
        assertTrue(e.getMessage().contains("不在指标清单里"), e.getMessage());
    }

    @Test
    @DisplayName("非法 service 被拒绝（它会拼进 PromQL，是唯一的注入面）")
    void illegalServiceIsRejected() {
        assertThrows(ToolArgumentException.class,
                () -> tool(new FakePrometheusQuerier())
                        .execute(args(Map.of("metric", "http_p99_latency",
                                "service", "x\"} or vector(1)"))));
    }

    @Test
    @DisplayName("service 不传时按配置里的默认服务过滤（而不是不过滤 —— 否则会混进 Prometheus 自己的曲线）")
    void serviceDefaultsToConfiguredService() {
        FakePrometheusQuerier querier = new FakePrometheusQuerier()
                .onRange(P99_QUERY, FakePrometheusQuerier.series(Map.of(), 0.5));

        tool(querier).execute(args(Map.of("metric", "http_p99_latency")));

        assertTrue(querier.asked(P99_QUERY), "应当带上默认服务的选择器：" + querier.queries());
    }

    @Test
    @DisplayName("service=\"*\" 表示不按服务过滤（选择器整体消失）")
    void starServiceMeansNoFilter() {
        FakePrometheusQuerier querier = new FakePrometheusQuerier()
                .onRange("seckill:http_p99_latency:5m", FakePrometheusQuerier.series(Map.of(), 0.5));

        ToolResult result = tool(querier).execute(args(Map.of("metric", "http_p99_latency", "service", "*")));

        assertEquals("*", result.data().get("service"));
        assertTrue(querier.asked("seckill:http_p99_latency:5m"), querier.queries().toString());
    }

    @Test
    @DisplayName("时间窗给反了自动交换并留 note（静默返回空会被读成「那段时间没有异常」）")
    void reversedWindowIsSwapped() {
        FakePrometheusQuerier querier = new FakePrometheusQuerier()
                .onRange(P99_QUERY, FakePrometheusQuerier.series(Map.of(), 0.5));

        ToolResult result = tool(querier).execute(args(Map.of("metric", "http_p99_latency",
                "start_time", "now", "end_time", "now-30m")));

        assertTrue(result.notes().stream().anyMatch(n -> n.contains("自动交换")), result.notes().toString());
    }

    @Test
    @DisplayName("时间窗超过上限时被收窄并说明（否则一次查询会返回上万点，最后被截断干净）")
    void overlongWindowIsClamped() {
        FakePrometheusQuerier querier = new FakePrometheusQuerier()
                .onRange(P99_QUERY, FakePrometheusQuerier.series(Map.of(), 0.5));

        ToolResult result = tool(querier).execute(args(Map.of("metric", "http_p99_latency",
                "start_time", "7d", "end_time", "now")));

        assertTrue(result.notes().stream().anyMatch(n -> n.contains("小时")), result.notes().toString());
    }

    @Test
    @DisplayName("无法解析的时间被拒绝（而不是悄悄用默认窗口给出一个看似合理的结果）")
    void unparsableTimeIsRejected() {
        assertThrows(ToolArgumentException.class,
                () -> tool(new FakePrometheusQuerier()).execute(args(Map.of(
                        "metric", "http_p99_latency", "start_time", "昨天下午"))));
    }

    @Test
    @DisplayName("序列过多时按窗口最大值降序保留前 N 条（最可能相关的那几条最先被看到）")
    void manySeriesAreTruncatedByRelevance() {
        List<PromSeries> series = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            series.add(FakePrometheusQuerier.series(
                    FakePrometheusQuerier.labels("uri", "/uri-" + i), i));
        }
        FakePrometheusQuerier querier = new FakePrometheusQuerier()
                .onRange(P99_QUERY, series.toArray(new PromSeries[0]));

        ToolResult result = tool(querier).execute(args(Map.of("metric", "http_p99_latency")));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> out = (List<Map<String, Object>>) result.data().get("series");
        assertEquals(20, out.size());
        // 最大值排在最前：/uri-29 的值是 29，最大
        assertEquals(Map.of("uri", "/uri-29"), out.get(0).get("labels"));
        assertTrue(result.notes().stream().anyMatch(n -> n.contains("上限")), result.notes().toString());
    }

    @Test
    @DisplayName("p99 是「窗口内采样点的第 99 百分位」，口径必须在 note 里说明")
    void p99CaliberIsExplained() {
        FakePrometheusQuerier querier = new FakePrometheusQuerier()
                .onRange(P99_QUERY, FakePrometheusQuerier.series(Map.of(), 1, 2, 3, 4, 5));

        ToolResult result = tool(querier).execute(args(Map.of("metric", "http_p99_latency")));

        assertTrue(result.notes().stream().anyMatch(n -> n.contains("百分位")), result.notes().toString());
        // 最近秩：5 个点取第 5 个（ceil(0.99*5)=5）
        assertEquals(5.0, (double) firstSeries(result).get("p99"), 1e-9);
    }

    @Test
    @DisplayName("数值被规整到 6 位小数（0.30000000000000004 这类噪音不进上下文）")
    void numbersAreRounded() {
        FakePrometheusQuerier querier = new FakePrometheusQuerier()
                .onRange(P99_QUERY, FakePrometheusQuerier.series(Map.of(), 0.1, 0.2));

        ToolResult result = tool(querier).execute(args(Map.of("metric", "http_p99_latency")));
        assertEquals(0.15, (double) firstSeries(result).get("avg"), 1e-9);
    }

    @Test
    @DisplayName("schema 与 SPEC 第 9.1 节的参数一致（metric/service/start_time/end_time）")
    void parameterNamesMatchSpec() {
        Map<String, Object> schema = tool(new FakePrometheusQuerier()).parametersSchema();
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertTrue(properties.keySet().containsAll(
                List.of("metric", "service", "start_time", "end_time")));
        assertEquals(List.of("metric"), schema.get("required"));
        assertEquals("query_metric", tool(new FakePrometheusQuerier()).name());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstSeries(ToolResult result) {
        List<Map<String, Object>> series = (List<Map<String, Object>>) result.data().get("series");
        assertFalse(series.isEmpty(), "应当有至少一条序列");
        return series.get(0);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> summary(ToolResult result) {
        Map<String, Object> summary = (Map<String, Object>) result.data().get("summary");
        assertNotNull(summary);
        return summary;
    }
}
