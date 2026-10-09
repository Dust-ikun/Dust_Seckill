package com.dustikun.seckill.monitor.tool.mq;

import com.dustikun.seckill.Config.RocketMqProperties;
import com.dustikun.seckill.monitor.tool.ToolArguments;
import com.dustikun.seckill.monitor.tool.ToolArgumentException;
import com.dustikun.seckill.monitor.tool.ToolResult;
import com.dustikun.seckill.monitor.tool.ToolStatus;
import com.dustikun.seckill.monitor.tool.metrics.FakePrometheusQuerier;
import com.dustikun.seckill.monitor.tool.metrics.MetricCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MQ Tool：两个口径的堆积、消费组下钻、失败率（SPEC 第 9.4 节）。
 *
 * <h2>最关键的一条：没有序列 ≠ 值为 0</h2>
 * <p>
 * 见 {@link #missingLagSeriesIsNotReportedAsZero()}。这是批次 1 健康基线里记录的
 * RocketMQ 5.x 行为（OTel 导出器不输出「从未被记录过」的仪表），
 * 而把「没有序列」报成 {@code lag: 0} 会让 Agent 得出「队列是空的」这个
 * 与事实相反、且看起来完全正常的结论。
 */
class MqToolTest {

    private static final String TOPIC = "seckill-order-topic";

    private static final String LAG_QUERY = "rocketmq_consumer_lag_messages{topic=\"" + TOPIC + "\"}";

    private static final String INFLIGHT_QUERY =
            "rocketmq_consumer_inflight_messages{topic=\"" + TOPIC + "\"}";

    private static final String RETRY_QUERY =
            "rocketmq_consumer_lag_messages{is_retry=\"true\",topic=\"" + TOPIC + "\"}";

    private final MetricCatalog catalog = MetricCatalog.defaults();

    private MqTool tool(FakePrometheusQuerier querier) {
        RocketMqProperties properties = new RocketMqProperties();
        properties.setTopic(TOPIC);
        properties.setConsumerGroup("seckill-order-consumer-group");
        return new MqTool(querier, catalog, properties);
    }

    private static ToolArguments args(Map<String, Object> raw) {
        return ToolArguments.of(MqTool.NAME, raw);
    }

    @Test
    @DisplayName("GET_QUEUE_LAG 返回 SPEC 第 9.4 节的 topic/lag/producerRate/consumerRate/failureRate 族")
    void queueLagReturnsSpecFields() {
        FakePrometheusQuerier querier = new FakePrometheusQuerier()
                .onInstant(LAG_QUERY, FakePrometheusQuerier.point(
                        FakePrometheusQuerier.labels("consumer_group", "seckill-order-consumer-group"), 32800))
                .onInstant(INFLIGHT_QUERY, FakePrometheusQuerier.value(12))
                .onInstant("rocketmq_consumer_ready_messages{topic=\"" + TOPIC + "\"}",
                        FakePrometheusQuerier.value(3))
                .onInstant("rocketmq_consumer_lag_latency_milliseconds{topic=\"" + TOPIC + "\"}",
                        FakePrometheusQuerier.value(4200))
                .onInstant("seckill:mq_lag_growth_rate:5m", FakePrometheusQuerier.value(35.5))
                .onInstant("rate(seckill_mq_sent_total[5m])", FakePrometheusQuerier.value(4300))
                .onInstant("rate(seckill_consume_confirmed_total[5m])", FakePrometheusQuerier.value(1200));

        ToolResult result = tool(querier).execute(args(Map.of("operation", "GET_QUEUE_LAG")));

        assertEquals(ToolStatus.SUCCESS, result.status());
        assertEquals(TOPIC, result.data().get("topic"));
        assertEquals(32800.0, (double) result.data().get("lagHighPrecision"), 1e-9);
        assertEquals("broker", result.data().get("lagSource"));
        assertEquals(12.0, (double) result.data().get("inflightMessages"), 1e-9);
        assertEquals(4200.0, (double) result.data().get("lagLatencyMillis"), 1e-9);
        assertEquals(4300.0, (double) result.data().get("producerRate"), 1e-9);
        assertEquals(1200.0, (double) result.data().get("consumerRate"), 1e-9);
        assertEquals(35.5, (double) result.data().get("lagGrowthPerSecond"), 1e-9);
        // producerRate > consumerRate：必须直接给出「堆积正在增长」这条判断
        assertTrue(result.notes().stream().anyMatch(n -> n.contains("堆积正在增长")),
                result.notes().toString());
        assertEquals(Map.of("seckill-order-consumer-group", 32800.0),
                result.data().get("lagByConsumerGroup"));
    }

    @Test
    @DisplayName("没有精确堆积序列时给出原因，而不是报 lag=0（没有序列 ≠ 队列为空）")
    void missingLagSeriesIsNotReportedAsZero() {
        FakePrometheusQuerier querier = new FakePrometheusQuerier()
                .onInstant("seckill:mq_lag_growth_rate:5m", FakePrometheusQuerier.value(0.035));

        ToolResult result = tool(querier).execute(args(Map.of("operation", "GET_QUEUE_LAG")));

        assertNull(result.data().get("lagHighPrecision"),
                "取不到就必须是 null，绝不能是 0");
        assertEquals("unavailable", result.data().get("lagSource"));
        assertNull(result.data().get("inflightMessages"), "没有 inflight 序列时也应当是 null");

        String notes = String.join(" ", result.notes());
        assertTrue(notes.contains("还没有任何消费活动"), notes);
        // 它必须明确否定「堆积为 0」这个误读（空序列最危险的读法）
        assertTrue(notes.contains("而不是「堆积为 0」"), notes);
    }

    @Test
    @DisplayName("精确堆积为正而增长速率为 0 时明确指出「存量还在，只是不再增长」")
    void nonzeroLagWithZeroGrowthIsExplained() {
        FakePrometheusQuerier querier = new FakePrometheusQuerier()
                .onInstant(LAG_QUERY, FakePrometheusQuerier.value(500))
                .onInstant("seckill:mq_lag_growth_rate:5m", FakePrometheusQuerier.value(0));

        ToolResult result = tool(querier).execute(args(Map.of("operation", "GET_QUEUE_LAG")));

        assertTrue(result.notes().stream().anyMatch(n -> n.contains("存量还在")),
                result.notes().toString());
    }

    @Test
    @DisplayName("GET_CONSUMER_STATUS 按消费组下钻，并按堆积降序（最该看的排在前面）")
    void consumerStatusGroupsByConsumerGroup() {
        FakePrometheusQuerier querier = new FakePrometheusQuerier()
                .onInstant(LAG_QUERY,
                        FakePrometheusQuerier.point(
                                FakePrometheusQuerier.labels("consumer_group", "group-a"), 10),
                        FakePrometheusQuerier.point(
                                FakePrometheusQuerier.labels("consumer_group", "group-b"), 900))
                .onInstant(RETRY_QUERY, FakePrometheusQuerier.point(
                        FakePrometheusQuerier.labels("consumer_group", "group-b"), 7));

        ToolResult result = tool(querier).execute(args(Map.of("operation", "GET_CONSUMER_STATUS")));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> groups = (List<Map<String, Object>>) result.data().get("consumerGroups");
        assertEquals(2, groups.size());
        assertEquals("group-b", groups.get(0).get("consumerGroup"));
        assertEquals(900.0, (double) groups.get(0).get("lag"), 1e-9);
        assertEquals(7.0, (double) groups.get(0).get("retryLag"), 1e-9);
        assertEquals("group-a", groups.get(1).get("consumerGroup"));
        assertEquals(2, result.data().get("groupCount"));

        assertTrue(result.notes().stream().anyMatch(n -> n.contains("重试队列堆积")),
                result.notes().toString());
    }

    @Test
    @DisplayName("没有消费组数据时说明最可能的原因，并给出下一步该查什么")
    void consumerStatusExplainsEmpty() {
        ToolResult result = tool(new FakePrometheusQuerier())
                .execute(args(Map.of("operation", "GET_CONSUMER_STATUS")));

        assertEquals(List.of(), result.data().get("consumerGroups"));
        String notes = String.join(" ", result.notes());
        assertTrue(notes.contains("还没有消费者连上来"), notes);
        assertTrue(notes.contains("search_logs"), notes);
    }

    @Test
    @DisplayName("失败率的分母与记录规则 seckill:order_success_rate:5m 口径一致（已结案）")
    void failureRateUsesClosedDenominator() {
        FakePrometheusQuerier querier = new FakePrometheusQuerier()
                .onInstant("rate(seckill_consume_confirmed_total[5m])", FakePrometheusQuerier.value(96))
                .onInstant("rate(seckill_consume_cancelled_total[5m])", FakePrometheusQuerier.value(3))
                .onInstant("rate(seckill_consume_failed_total[5m])", FakePrometheusQuerier.value(1))
                .onInstant("rate(rocketmq_send_to_dlq_messages_total{topic=\"" + TOPIC + "\"}[5m])",
                        FakePrometheusQuerier.value(0.5));

        ToolResult result = tool(querier).execute(args(Map.of("operation", "GET_MESSAGE_FAILURE_RATE")));

        // 1 / (96 + 3 + 1) = 0.01
        assertEquals(0.01, (double) result.data().get("failureRate"), 1e-9);
        assertNotNull(result.data().get("dlqRate"), "实际查询=" + querier.queries());
        assertEquals(0.5, (double) result.data().get("dlqRate"), 1e-9);
        assertTrue(result.data().get("caliber").toString().contains("已结案"), "口径必须写清楚");
        assertTrue(result.notes().stream().anyMatch(n -> n.contains("死信")), result.notes().toString());
    }

    @Test
    @DisplayName("没有任何已结案结果时失败率返回 null 而不是 0（两者是相反的事实）")
    void failureRateIsNullWhenNothingClosed() {
        ToolResult result = tool(new FakePrometheusQuerier())
                .execute(args(Map.of("operation", "GET_MESSAGE_FAILURE_RATE")));

        assertNull(result.data().get("failureRate"));
        assertTrue(result.notes().stream().anyMatch(n -> n.contains("无法计算")),
                result.notes().toString());
    }

    @Test
    @DisplayName("Prometheus 不可达时 available=false，并说明这不是「队列为空」的证据")
    void unreachablePrometheusIsExplicit() {
        FakePrometheusQuerier querier = new FakePrometheusQuerier().reachable(false);

        ToolResult result = tool(querier).execute(args(Map.of("operation", "GET_QUEUE_LAG")));

        assertEquals(Boolean.FALSE, result.data().get("available"));
        String notes = String.join(" ", result.notes());
        assertTrue(notes.contains("不可达"), notes);
        assertTrue(notes.contains("不是「队列为空」的证据"), notes);
        assertTrue(querier.queries().isEmpty(), "不可达时不该再发查询");
    }

    @Test
    @DisplayName("topic 可覆盖，且非法 topic 被拒绝（它会拼进 PromQL）")
    void topicIsValidated() {
        FakePrometheusQuerier querier = new FakePrometheusQuerier()
                .onInstant("rocketmq_consumer_lag_messages{topic=\"seckill-order-topic-v2\"}",
                        FakePrometheusQuerier.value(5));

        ToolResult result = tool(querier).execute(args(Map.of(
                "operation", "GET_QUEUE_LAG", "topic", "seckill-order-topic-v2")));
        assertEquals("seckill-order-topic-v2", result.data().get("topic"));
        assertEquals(5.0, (double) result.data().get("lagHighPrecision"), 1e-9);

        assertThrows(ToolArgumentException.class, () -> tool(new FakePrometheusQuerier()).execute(
                args(Map.of("operation", "GET_QUEUE_LAG", "topic", "x\"} or vector(1)"))));
    }

    @Test
    @DisplayName("operation 缺失时默认查堆积（最常用的那个），非法值被拒绝并列出全部操作")
    void operationDefaultsAndValidates() {
        FakePrometheusQuerier querier = new FakePrometheusQuerier()
                .onInstant(LAG_QUERY, FakePrometheusQuerier.value(1));

        ToolResult result = tool(querier).execute(args(Map.of()));
        assertEquals("GET_QUEUE_LAG", result.data().get("operation"));

        ToolArgumentException e = assertThrows(ToolArgumentException.class,
                () -> tool(new FakePrometheusQuerier())
                        .execute(args(Map.of("operation", "GET_EVERYTHING"))));
        assertTrue(e.getMessage().contains("GET_CONSUMER_STATUS"), e.getMessage());
    }

    @Test
    @DisplayName("schema 与工具名稳定")
    void schemaIsStable() {
        MqTool tool = tool(new FakePrometheusQuerier());
        assertEquals("query_mq", tool.name());
        assertEquals(List.of("operation", "topic"), tool.parameterNames());
        assertTrue(tool.description().contains("lag"));
    }
}
