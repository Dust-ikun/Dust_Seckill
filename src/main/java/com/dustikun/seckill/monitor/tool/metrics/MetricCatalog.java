package com.dustikun.seckill.monitor.tool.metrics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 指标目录：<b>指标白名单 + PromQL 模板</b>（SPEC 第 9.1 节 Metrics Tool 的取值来源）。
 *
 * <h2>为什么不让 LLM 直接写 PromQL</h2>
 * <p>
 * SPEC 第 9.3 节对 DB 只读的要求写得很明确：「也不允许自由生成 SQL 后直接执行」。
 * 对 Prometheus 而言，接受自由 PromQL 是同一类问题，只是后果更轻：
 * <ul>
 *   <li><b>可靠性</b>：模型写的 PromQL 常常语法正确但语义错误 ——
 *       最常见的是对 Counter 直接取值而不用 {@code rate()}（得到一条永远增长的曲线），
 *       或者忘记 {@code histogram_quantile} 的 {@code le} 聚合（得到一个静默错几个数量级的值）。
 *       这两种错误都不会报错，只会得出错误结论。目录把「正确的口径」固化下来。</li>
 *   <li><b>一致性</b>：告警、看板、Agent 必须读同一条记录规则。
 *       若 Agent 自己写一遍 P99 的 PromQL，它算出来的值与告警触发的值就可能不同，
 *       于是出现「告警说 2.1s、Agent 说 0.3s，到底信谁」这种最难收场的争论。</li>
 *   <li><b>边界</b>：目录同时是一份「本项目有哪些指标」的清单。模型只能问清单里的东西，
 *       就不会去编造 {@code mysql_slow_query_total} 这种根本不存在的指标
 *       （SPEC 第 10 节诊断规则第 7 条：不得伪造不存在的指标）。</li>
 * </ul>
 *
 * <h2>模板机制：两个占位符</h2>
 * <pre>
 *   $SERVICE_SELECTOR$  →  空串，或 {service="order-service"}
 *   $TOPIC_SELECTOR$    →  空串，或 {topic="seckill-order-topic"}
 * </pre>
 * <p>用占位符而不是「拼字符串 + 判断有没有大括号」，是因为同一个模板里
 * 可能同时出现两处筛选（例如 {@code hikaricp_connections_active{...} / hikaricp_connections_max{...}}），
 * 而「在已有的 {} 里插入一个逗号」这种字符串手术迟早会写错。
 *
 * <h2>标签值必须校验，这是安全边界</h2>
 * <p>占位符的值来自 LLM（{@code service} / {@code topic} 参数）。若不校验，
 * 一个 {@code service = "x\"} or vector(1)"} 就能改变整条表达式 ——
 * 虽然 Prometheus 是只读的，但「Agent 能执行任意 PromQL」正是上面第一条要避免的事。
 * 因此这里对标签值做<b>白名单式</b>校验（只允许字母数字与 {@code _ . -}），
 * 而不是转义：转义要考虑的边界情况多，而真实的服务名/主题名根本不需要那些字符。
 */
public final class MetricCatalog {

    /** 标签值的合法字符集。见类注释「安全边界」 */
    private static final Pattern SAFE_LABEL_VALUE = Pattern.compile("[A-Za-z0-9_.\\-]{1,128}");

    private static final String SERVICE_SELECTOR = "$SERVICE_SELECTOR$";

    private static final String TOPIC_SELECTOR = "$TOPIC_SELECTOR$";

    /**
     * 指标口径的类别。它的用途只有一个但很重要：
     * <b>告诉模型该怎么读这个数</b>。
     * <pre>
     *   GAUGE    此刻的值，可以直接与阈值比
     *   RATE     每秒增量（已经过 rate()），比的是「快慢」
     *   RATIO    0~1 的比例，不要当百分数
     *   SECONDS  秒。本项目所有延迟指标都以秒为单位（与 SPEC 第 9.1 节示例一致）
     *   COUNT    个（条/次）
     * </pre>
     * 没有这一栏时，最常见的误读是把 ratio 当百分数（0.97 读成 0.97%）。
     */
    public enum Unit {
        GAUGE("个/当前值"),
        RATE("每秒"),
        RATIO("0~1 的比例"),
        SECONDS("秒"),
        COUNT("个"),
        SCORE("0~1 的评分");

        private final String text;

        Unit(String text) {
            this.text = text;
        }

        public String text() {
            return text;
        }
    }

    /**
     * 一条目录项。
     *
     * @param key         模型使用的指标名（对外契约）
     * @param template    PromQL 模板，可含 {@link #SERVICE_SELECTOR} / {@link #TOPIC_SELECTOR}
     * @param unit        口径，见 {@link Unit}
     * @param description 给模型看的说明：这个数回答什么问题
     * @param metricNames 这条口径读取的<b>底层序列名</b>（通常是 1 个，比值型是 2 个）。
     *                    它的唯一用途是启动自检：把名字拿去与 Prometheus 的指标名索引对照，
     *                    当场指出「这个名字不存在」。之所以显式写出来而不是从模板里正则提取，
     *                    是因为模板里混着 {@code rate(} / {@code sum by (le)} / 标签选择器 ——
     *                    提取规则一旦写错，自检就会**安静地验错对象**，
     *                    而那样的自检比没有自检更糟（它给人「已经验证过」的错觉）。
     */
    public record Entry(String key, String template, Unit unit, String description,
                        List<String> metricNames) {

        /** 便捷构造：绝大多数条目只读一条序列。见 {@link Entry} 的 {@code metricNames} */
        public Entry(String key, String template, Unit unit, String description, String... metricNames) {
            this(key, template, unit, description, List.of(metricNames));
        }

        /** 渲染成最终 PromQL */
        public String render(String service, String topic) {
            String query = template;
            if (query.contains(SERVICE_SELECTOR)) {
                query = query.replace(SERVICE_SELECTOR, serviceSelector(service));
            }
            if (query.contains(TOPIC_SELECTOR)) {
                query = query.replace(TOPIC_SELECTOR, topicSelector(topic));
            }
            return query;
        }

        /** 该指标是否支持按 service 过滤（决定「传了 service 却被忽略」要不要提醒） */
        public boolean serviceScoped() {
            return template.contains(SERVICE_SELECTOR);
        }

        public boolean topicScoped() {
            return template.contains(TOPIC_SELECTOR);
        }
    }

    private final List<Entry> entries;

    private final Map<String, Entry> byKey;

    private MetricCatalog(List<Entry> entries) {
        this.entries = List.copyOf(entries);
        Map<String, Entry> map = new LinkedHashMap<>(entries.size() * 2);
        for (Entry entry : entries) {
            map.put(entry.key(), entry);
        }
        this.byKey = Map.copyOf(map);
    }

    /**
     * 默认目录。
     *
     * <p>【这些名字是从哪来的】全部取自 {@code monitoring/prometheus/rules/*.yml}
     * 与 {@code /actuator/prometheus} 的<b>实际</b>名字，而不是 SPEC 第 6.1 节的建议名 ——
     * 可行性报告 §3.1 冲突 2 裁定「保留现有指标名」，§3.3 给了完整对照表。
     * 每一个名字都会在 {@code MetricsTool#probeCatalog} 的启动自检里
     * 与 Prometheus 的指标名索引对照一次：写错时启动日志会直接指出来
     * （这是本项目最大的坑源 —— 「配置被静默忽略」的通用判据是去出口上数一次）。
     */
    public static MetricCatalog defaults() {
        List<Entry> list = new ArrayList<>(42);

        // ---------------- HTTP 层（记录规则，SPEC §6.1 的 http_request_duration_*）----------------
        list.add(new Entry("http_p99_latency", "seckill:http_p99_latency:5m" + SERVICE_SELECTOR,
                Unit.SECONDS, "接口 P99 延迟（5m 窗口）。这是告警 ApiP99LatencyHigh 用的同一条序列",
                "seckill:http_p99_latency:5m"));
        list.add(new Entry("http_p95_latency", "seckill:http_p95_latency:5m" + SERVICE_SELECTOR,
                Unit.SECONDS, "接口 P95 延迟（5m 窗口）",
                "seckill:http_p95_latency:5m"));
        list.add(new Entry("http_qps", "seckill:http_qps:1m" + SERVICE_SELECTOR,
                Unit.RATE, "接口每秒请求数（按 uri 分组）",
                "seckill:http_qps:1m"));
        list.add(new Entry("http_request_rate",
                "sum by (service) (rate(http_server_requests_seconds_count" + SERVICE_SELECTOR + "[5m]))",
                Unit.RATE, "全服务每秒请求数（不按 uri 分组，用于看总体流量）",
                "http_server_requests_seconds_count"));
        list.add(new Entry("http_error_rate", "seckill:http_error_rate:5m" + SERVICE_SELECTOR,
                Unit.RATIO, "5xx 错误率（按 uri 分组）。注意 4xx 是正常业务响应，不在其中",
                "seckill:http_error_rate:5m"));
        list.add(new Entry("http_error_rate_all", "seckill:http_error_rate_all:5m",
                Unit.RATIO, "全服务 5xx 错误率（不按 uri 分组）",
                "seckill:http_error_rate_all:5m"));
        list.add(new Entry("http_slow_request_rate", "seckill:http_slow_request_rate:5m" + SERVICE_SELECTOR,
                Unit.RATIO, "超过 1s 的请求占比（按 uri 分组）",
                "seckill:http_slow_request_rate:5m"));

        // ---------------- 业务链路（SPEC §6.1 的 order_* / outbox_* / mq_lag）----------------
        list.add(new Entry("order_accept_rate", "seckill:order_accept_rate:1m",
                Unit.RATE, "下单受理速率（对应 SPEC 的 order_total）",
                "seckill:order_accept_rate:1m"));
        list.add(new Entry("order_success_rate", "seckill:order_success_rate:5m",
                Unit.RATIO, "订单成功率：分母是「消费侧已结案」（确认+取消+放弃），不是受理数。"
                        + "刚启动时它偏低属于异步链路的启动延迟",
                "seckill:order_success_rate:5m"));
        list.add(new Entry("outbox_pending", "seckill:outbox_pending_now" + SERVICE_SELECTOR,
                Unit.COUNT, "待投递欠账（数据库 COUNT，多实例下依然准确）",
                "seckill:outbox_pending_now"));
        list.add(new Entry("outbox_failed", "seckill_outbox_failed" + SERVICE_SELECTOR,
                Unit.COUNT, "投递重试耗尽的终态数量。不为 0 就意味着需要人工介入",
                "seckill_outbox_failed"));
        list.add(new Entry("compensate_pending", "seckill_compensate_pending" + SERVICE_SELECTOR,
                Unit.COUNT, "未了结的补偿任务数（PENDING + FAILED）",
                "seckill_compensate_pending"));
        list.add(new Entry("manual_intervention_backlog",
                "seckill:manual_intervention_backlog" + SERVICE_SELECTOR,
                Unit.COUNT, "需人工介入的欠账总量（outbox 放弃 + 待补偿）。它不会自愈",
                "seckill:manual_intervention_backlog"));
        list.add(new Entry("mq_lag_growth_rate", "seckill:mq_lag_growth_rate:5m",
                Unit.RATE, "投递速率与确认速率之差（mq_lag 的近似口径，单实例准确）。"
                        + "精确堆积请用 query_mq 的 GET_QUEUE_LAG",
                "seckill:mq_lag_growth_rate:5m"));
        list.add(new Entry("pipeline_health_score", "seckill:pipeline_health_score",
                Unit.SCORE, "链路健康总分（0~1）。只看总分看不出哪一项坏了，请配合分量指标",
                "seckill:pipeline_health_score"));
        list.add(new Entry("reconcile_findings_rate",
                "sum by (status) (rate(seckill_reconcile_findings_total" + SERVICE_SELECTOR + "[5m]))",
                Unit.RATE, "对账发现问题的速率（按结论分组：REDIS_AHEAD / MARK_MISSING / ...）",
                "seckill_reconcile_findings_total"));

        // ---------------- 数据库连接池（Boot 内建 HikariCP 指标）----------------
        list.add(new Entry("db_pool_active", "hikaricp_connections_active" + SERVICE_SELECTOR,
                Unit.COUNT, "当前活跃数据库连接数",
                "hikaricp_connections_active"));
        list.add(new Entry("db_pool_max", "hikaricp_connections_max" + SERVICE_SELECTOR,
                Unit.COUNT, "连接池上限（本项目 50）",
                "hikaricp_connections_max"));
        list.add(new Entry("db_pool_pending", "hikaricp_connections_pending" + SERVICE_SELECTOR,
                Unit.COUNT, "正在等待连接的线程数。持续大于 0 说明池太小或查询变慢",
                "hikaricp_connections_pending"));
        list.add(new Entry("db_pool_usage",
                "hikaricp_connections_active" + SERVICE_SELECTOR + " / hikaricp_connections_max" + SERVICE_SELECTOR,
                Unit.RATIO, "连接池使用率（活跃/上限）。远低于 1 是健康态",
                "hikaricp_connections_active", "hikaricp_connections_max"));
        list.add(new Entry("db_pool_acquire_seconds",
                "rate(hikaricp_connections_acquire_seconds_sum" + SERVICE_SELECTOR + "[5m])"
                        + " / rate(hikaricp_connections_acquire_seconds_count" + SERVICE_SELECTOR + "[5m])",
                Unit.SECONDS, "获取连接的平均等待时长。它比「池使用率」更早暴露争用",
                "hikaricp_connections_acquire_seconds_sum",
                "hikaricp_connections_acquire_seconds_count"));
        list.add(new Entry("db_pool_timeout_rate",
                "rate(hikaricp_connections_timeout_total" + SERVICE_SELECTOR + "[5m])",
                Unit.RATE, "获取连接超时的速率。大于 0 就是明确的故障信号",
                "hikaricp_connections_timeout_total"));

        // ---------------- Redis（Spring Data Redis 的 Observation）----------------
        list.add(new Entry("redis_command_latency_avg",
                "rate(lettuce_seconds_sum" + SERVICE_SELECTOR + "[5m])"
                        + " / rate(lettuce_seconds_count" + SERVICE_SELECTOR + "[5m])",
                Unit.SECONDS, "Redis 命令平均耗时（lettuce 观测）",
                "lettuce_seconds_sum", "lettuce_seconds_count"));
        list.add(new Entry("redis_command_latency_p99",
                "histogram_quantile(0.99, sum by (le) (rate(lettuce_seconds_bucket"
                        + SERVICE_SELECTOR + "[5m])))",
                Unit.SECONDS, "Redis 命令 P99 耗时。RedisOperationSlow 告警用的是同一族数据",
                "lettuce_seconds_bucket"));
        list.add(new Entry("redis_command_rate",
                "sum by (service) (rate(lettuce_seconds_count" + SERVICE_SELECTOR + "[5m]))",
                Unit.RATE, "Redis 命令每秒次数",
                "lettuce_seconds_count"));

        // ---------------- JVM 与进程（判断「是应用自己慢还是依赖慢」）----------------
        list.add(new Entry("jvm_heap_used", "sum(jvm_memory_used_bytes{area=\"heap\"})",
                Unit.COUNT, "JVM 堆已用字节数",
                "jvm_memory_used_bytes"));
        list.add(new Entry("jvm_gc_pause_rate",
                "rate(jvm_gc_pause_seconds_sum" + SERVICE_SELECTOR + "[5m])",
                Unit.RATE, "GC 停顿占用的秒数/秒。接近 1 说明几乎全在 GC",
                "jvm_gc_pause_seconds_sum"));
        list.add(new Entry("jvm_threads_live", "jvm_threads_live_threads" + SERVICE_SELECTOR,
                Unit.COUNT, "存活线程数",
                "jvm_threads_live_threads"));
        list.add(new Entry("process_cpu_usage", "process_cpu_usage" + SERVICE_SELECTOR,
                Unit.RATIO, "进程 CPU 使用率（0~1，可能超过 1 表示多核）",
                "process_cpu_usage"));

        // ---------------- 采集目标可达性（「没数据」时第一个该看的指标）----------------
        list.add(new Entry("target_up", "up" + SERVICE_SELECTOR,
                Unit.GAUGE, "采集目标的存活状态：1 = 抓得到，0 = 抓不到。"
                        + "任何指标异常都应先看它 —— 0 时其它指标的空缺不代表业务异常",
                "up"));

        // ---------------- RocketMQ（Broker 内置 OTel 导出器）----------------
        // 【为什么单位与标签要写清楚】这批指标由 OTel 导出，单位后缀是 OTel 加的
        // （..._latency_milliseconds），与 BrokerMetricsConstant 里的常量名不一致。
        // 可行性报告 §3.1 冲突 3 记下了这个坑：不要按常量名猜。
        list.add(new Entry("mq_consumer_lag", "rocketmq_consumer_lag_messages" + TOPIC_SELECTOR,
                Unit.COUNT, "消费堆积（精确值，来自 Broker）。**只在有消费活动之后才存在**，"
                        + "序列不存在不等于 lag 为 0",
                "rocketmq_consumer_lag_messages"));
        list.add(new Entry("mq_consumer_inflight", "rocketmq_consumer_inflight_messages" + TOPIC_SELECTOR,
                Unit.COUNT, "已投递给消费者但尚未确认的消息数",
                "rocketmq_consumer_inflight_messages"));
        list.add(new Entry("mq_consumer_ready", "rocketmq_consumer_ready_messages" + TOPIC_SELECTOR,
                Unit.COUNT, "可被消费但还没投递的消息数",
                "rocketmq_consumer_ready_messages"));
        list.add(new Entry("mq_consumer_lag_latency",
                "rocketmq_consumer_lag_latency_milliseconds" + TOPIC_SELECTOR,
                Unit.COUNT, "堆积消息的滞留时长（毫秒）",
                "rocketmq_consumer_lag_latency_milliseconds"));
        list.add(new Entry("mq_messages_in_rate",
                "rate(rocketmq_messages_in_total" + TOPIC_SELECTOR + "[5m])",
                Unit.RATE, "Broker 侧消息写入速率",
                "rocketmq_messages_in_total"));
        list.add(new Entry("mq_messages_out_rate",
                "rate(rocketmq_messages_out_total" + TOPIC_SELECTOR + "[5m])",
                Unit.RATE, "Broker 侧消息消费速率",
                "rocketmq_messages_out_total"));
        list.add(new Entry("mq_dlq_rate",
                "rate(rocketmq_send_to_dlq_messages_total" + TOPIC_SELECTOR + "[5m])",
                Unit.RATE, "进入死信队列的速率",
                "rocketmq_send_to_dlq_messages_total"));
        list.add(new Entry("mq_send_rate", "rate(seckill_mq_sent_total" + SERVICE_SELECTOR + "[5m])",
                Unit.RATE, "应用侧投递速率（producerRate）",
                "seckill_mq_sent_total"));
        list.add(new Entry("mq_consume_rate",
                "rate(seckill_consume_confirmed_total" + SERVICE_SELECTOR + "[5m])",
                Unit.RATE, "应用侧确认速率（consumerRate）",
                "seckill_consume_confirmed_total"));
        list.add(new Entry("mq_consume_failed_rate",
                "rate(seckill_consume_failed_total" + SERVICE_SELECTOR + "[5m])",
                Unit.RATE, "应用侧消费失败速率",
                "seckill_consume_failed_total"));
        list.add(new Entry("mq_consume_cancelled_rate",
                "rate(seckill_consume_cancelled_total" + SERVICE_SELECTOR + "[5m])",
                Unit.RATE, "应用侧因业务原因取消的速率（库存不足等，属正常结局）",
                "seckill_consume_cancelled_total"));
        list.add(new Entry("mq_consume_duplicate_rate",
                "rate(seckill_consume_duplicate_total" + SERVICE_SELECTOR + "[5m])",
                Unit.RATE, "重复投递被幂等挡下的速率",
                "seckill_consume_duplicate_total"));

        return new MetricCatalog(list);
    }

    // ================================================================ 查询

    public Optional<Entry> find(String key) {
        if (key == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byKey.get(key.trim().toLowerCase(Locale.ROOT)));
    }

    public List<Entry> entries() {
        return entries;
    }

    public List<String> keys() {
        return entries.stream().map(Entry::key).toList();
    }

    /** 目录里声明引用的全部底层序列名（去重）。供启动自检一次问清 */
    public List<String> referencedMetricNames() {
        List<String> names = new ArrayList<>();
        for (Entry entry : entries) {
            for (String name : entry.metricNames()) {
                if (!names.contains(name)) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    /** 给模型看的「有哪些指标可选」清单，用于参数说明与「值非法」时的错误消息 */
    public List<Map<String, Object>> describe() {
        List<Map<String, Object>> described = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            Map<String, Object> item = new LinkedHashMap<>(4);
            item.put("metric", entry.key());
            item.put("unit", entry.unit().text());
            item.put("description", entry.description());
            described.add(item);
        }
        return described;
    }

    // ================================================================ 标签选择器

    /** {@code $SERVICE_SELECTOR$} 的展开。{@code service} 为空时展开为空串（= 不过滤） */
    static String serviceSelector(String service) {
        String safe = safeLabelValue("service", service);
        return safe == null ? "" : "{service=\"" + safe + "\"}";
    }

    static String topicSelector(String topic) {
        String safe = safeLabelValue("topic", topic);
        return safe == null ? "" : "{topic=\"" + safe + "\"}";
    }

    /**
     * 校验标签值。合法返回原值，为空返回 {@code null}，非法<b>抛异常</b>。
     * <p>非法时抛而不是忽略：忽略会让「service 写错」表现为「查回来一堆别的服务的曲线」，
     * 那是比报错危险得多的结果（模型会拿别的服务的数据下结论）。
     */
    static String safeLabelValue(String labelName, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (!SAFE_LABEL_VALUE.matcher(trimmed).matches()) {
            throw new com.dustikun.seckill.monitor.tool.ToolArgumentException(labelName,
                    "值 \"" + value + "\" 含非法字符。只允许字母、数字与 _ . -（最多 128 字符）。");
        }
        return trimmed;
    }
}
