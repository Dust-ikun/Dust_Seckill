package com.dustikun.seckill.monitor.tool.metrics;

import com.dustikun.seckill.monitor.tool.ToolArgumentException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MetricCatalog}：指标白名单与 PromQL 模板渲染。
 *
 * <h2>为什么「标签值必须校验」值得单独测</h2>
 * <p>
 * {@code service} 与 {@code topic} 来自 LLM，而它们会被拼进 PromQL。
 * 不校验时 {@code service = "x\"} or vector(1)"} 就能改变整条表达式 ——
 * 而「Agent 能执行任意 PromQL」正是引入目录要消除的东西。
 * 这里锁住的是「非法字符被拒绝」，而不是「转义得对不对」：
 * 白名单式校验没有需要转义的边界。
 */
class MetricCatalogTest {

    private final MetricCatalog catalog = MetricCatalog.defaults();

    @Test
    @DisplayName("目录里没有重复的指标名（重名会让白名单的某一项永远取不到）")
    void keysAreUnique() {
        Set<String> seen = new HashSet<>();
        for (String key : catalog.keys()) {
            assertTrue(seen.add(key), "指标名重复：" + key);
        }
        assertTrue(catalog.keys().size() >= 20, "目录条目太少：" + catalog.keys().size());
    }

    @Test
    @DisplayName("每条目录项都有说明与单位（DSL 里没有说明的条目模型读不懂，等于不存在）")
    void everyEntryIsDescribed() {
        for (MetricCatalog.Entry entry : catalog.entries()) {
            assertFalse(entry.description().isBlank(), entry.key() + " 缺少说明");
            assertFalse(entry.template().isBlank(), entry.key() + " 缺少 PromQL 模板");
            assertFalse(entry.unit().text().isBlank(), entry.key() + " 缺少单位");
        }
    }

    @Test
    @DisplayName("渲染后不再残留任何占位符（残留会让 Prometheus 报语法错，而错误消息指向表达式）")
    void renderedQueriesContainNoPlaceholders() {
        for (MetricCatalog.Entry entry : catalog.entries()) {
            String query = entry.render("order-service", "seckill-order-topic");
            assertFalse(query.contains("$"), entry.key() + " 渲染后仍有占位符：" + query);
            assertFalse(query.contains("null"), entry.key() + " 渲染出 null：" + query);
        }
    }

    @Test
    @DisplayName("不传 service / topic 时选择器整体消失，而不是留下空的大括号")
    void emptySelectorsAreDroppedEntirely() {
        assertEquals("seckill:http_p99_latency:5m",
                find("http_p99_latency").render(null, null));
        assertEquals("seckill:http_p99_latency:5m{service=\"order-service\"}",
                find("http_p99_latency").render("order-service", null));
        assertEquals("rocketmq_consumer_lag_messages",
                find("mq_consumer_lag").render(null, null));
        assertEquals("rocketmq_consumer_lag_messages{topic=\"seckill-order-topic\"}",
                find("mq_consumer_lag").render(null, "seckill-order-topic"));
    }

    @Test
    @DisplayName("同一模板里出现两次选择器时两处都被替换（db_pool_usage 就是这种形态）")
    void everySelectorOccurrenceIsReplaced() {
        String query = find("db_pool_usage").render("order-service", null);
        assertEquals(2, countOccurrences(query, "service=\"order-service\""), query);
    }

    @Test
    @DisplayName("非法标签值被拒绝：service / topic 是注入面，且拒绝比忽略安全")
    void illegalLabelValuesAreRejected() {
        MetricCatalog.Entry entry = find("http_p99_latency");

        // 典型的注入尝试：先闭合字符串再拼表达式
        ToolArgumentException e = assertThrows(ToolArgumentException.class,
                () -> entry.render("x\"} or vector(1)", null));
        assertEquals("service", e.argumentName());
        assertTrue(e.getMessage().contains("非法字符"), e.getMessage());

        // 空白与控制字符同样不合法
        assertThrows(ToolArgumentException.class, () -> entry.render("a b", null));
        assertThrows(ToolArgumentException.class, () -> entry.render("a\nb", null));
        assertThrows(ToolArgumentException.class, () -> entry.render("x".repeat(200), null));

        // 而真实的服务名形态必须通过
        assertEquals("seckill:http_p99_latency:5m{service=\"rocketmq-broker\"}",
                entry.render("rocketmq-broker", null));
        assertTrue(entry.render("order_service.v1", null).contains("order_service.v1"));
    }

    @Test
    @DisplayName("查不到的指标名返回空 Optional（由 Tool 转成 REJECTED 并列出白名单）")
    void unknownKeyIsEmpty() {
        assertTrue(catalog.find("mysql_slow_query_total").isEmpty());
        assertTrue(catalog.find(null).isEmpty());
        assertFalse(catalog.find("http_p99_latency").isEmpty());
        // 大小写不敏感：名字不是业务数据，判错只会白白损失一次取证
        assertFalse(catalog.find("HTTP_P99_LATENCY").isEmpty());
    }

    @Test
    @DisplayName("describe() 覆盖全部条目，供「值非法」时的错误消息使用")
    void describeCoversAllEntries() {
        List<Map<String, Object>> described = catalog.describe();
        assertEquals(catalog.keys().size(), described.size());
        for (Map<String, Object> item : described) {
            assertTrue(item.containsKey("metric"));
            assertTrue(item.containsKey("unit"));
            assertTrue(item.containsKey("description"));
        }
    }

    @Test
    @DisplayName("指标名清单里包含排障最常用的那几个（它们的缺失会让 Agent 无从下手）")
    void coreMetricsArePresent() {
        assertTrue(catalog.keys().containsAll(List.of(
                "http_p99_latency", "http_error_rate_all", "order_success_rate",
                "outbox_pending", "mq_lag_growth_rate", "db_pool_usage",
                "redis_command_latency_p99", "target_up", "mq_consumer_lag")));
    }

    private MetricCatalog.Entry find(String key) {
        return catalog.find(key).orElseThrow();
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            count++;
            index = text.indexOf(needle, index + needle.length());
        }
        return count;
    }
}
