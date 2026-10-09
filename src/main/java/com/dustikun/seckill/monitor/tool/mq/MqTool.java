package com.dustikun.seckill.monitor.tool.mq;

import com.dustikun.seckill.Config.RocketMqProperties;
import com.dustikun.seckill.monitor.tool.MonitorTool;
import com.dustikun.seckill.monitor.tool.ToolArguments;
import com.dustikun.seckill.monitor.tool.ToolResult;
import com.dustikun.seckill.monitor.tool.ToolSchema;
import com.dustikun.seckill.monitor.tool.metrics.MetricCatalog;
import com.dustikun.seckill.monitor.tool.metrics.PromSeries;
import com.dustikun.seckill.monitor.tool.metrics.PrometheusQuerier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * MQ Tool（SPEC 第 9.4 节）：{@code get_queue_lag} / {@code get_consumer_status} /
 * {@code get_message_failure_rate}。
 *
 * <h2>「堆积」有两个口径，本工具同时给出来</h2>
 * <table border="1">
 *   <caption>两个口径的来源与边界</caption>
 *   <tr><th>口径</th><th>指标</th><th>准确范围</th></tr>
 *   <tr>
 *     <td><b>精确</b>（首选）</td>
 *     <td>{@code rocketmq_consumer_lag_messages}（Broker 内置 OTel 导出器）</td>
 *     <td>Broker 侧的消费位点差 —— 即 SPEC 里 {@code mq_lag} 的定义</td>
 *   </tr>
 *   <tr>
 *     <td><b>近似</b>（兜底）</td>
 *     <td>{@code seckill:mq_lag_growth_rate:5m}（应用侧投递速率 − 确认速率）</td>
 *     <td>只在单实例准确。多实例时会把「本实例投出、别的实例消费」读成堆积</td>
 *   </tr>
 * </table>
 * <p>【为什么两个都给】只给近似值时，一个「平均速率恰好平衡但已经堆了 3 万条」的
 * 场景会被读成健康 —— 近似口径测的是<b>变化率</b>，对<b>存量</b>不敏感。
 * 只给精确值时，Broker 刚重启、还没消费活动的那段时间会得到空结果，
 * 而空结果又会被读成「没有堆积」。两者互补，且当它们不一致时，
 * 那份不一致本身就是最有价值的线索（说明存在多实例，或 Broker 侧数据异常）。
 *
 * <h2>为什么 {@code rocketmq_consumer_lag_messages} 为空不算故障</h2>
 * <p>
 * 这是本工具最容易被误判的一点，已记录在批次 1 的健康基线里：
 * OTel 的 Prometheus 导出器<b>不输出「从未被记录过」的仪表</b>，
 * 因此 Broker 刚启动、还没有任何消费者时，这条序列<b>不存在</b>而不是 0。
 * 本工具在这种情况下显式说明原因，而不是返回 {@code lag: 0} ——
 * 后者是一个看起来正常、实际没有依据的数字。
 *
 * <h2>producerRate / consumerRate 为什么用应用侧计数</h2>
 * <p>
 * 因为它们是<b>本应用</b>的吞吐，而 Broker 侧的 {@code messages_in/out} 混了
 * 所有主题与系统消息（重试、事务回查），用它算出的「消费者速率」会明显偏高。
 * 代价是这两个数只在单实例下准确 —— 这一点写在返回体的 {@code caliber} 字段里，
 * 而不是留给读者去猜。
 *
 * <h2>「没有序列」与「值为 0」必须分开表达</h2>
 * <p>
 * 本类里所有读取都走 {@link #lastValue}/{@link #sumOf} 这一对方法，
 * 它们返回 {@code null} 表示<b>一条序列都没有</b>，返回 0 表示<b>序列存在且为零</b>。
 * 整个 MQ 诊断的正确性都挂在这条区分上：把前者当后者，
 * 就会把「Broker 侧根本没数据」读成「队列是空的」。
 */
public final class MqTool implements MonitorTool {

    private static final Logger log = LoggerFactory.getLogger(MqTool.class);

    /** 工具名 */
    public static final String NAME = "query_mq";

    /**
     * 三个操作。SPEC 第 9.4 节把它们写成三个函数，这里合并成一个工具加 {@code operation} 参数。
     *
     * <p>【为什么合并】function-calling 的每个工具都要占 system prompt 里的一段说明，
     * 而这三个操作的参数完全相同（只有一个 topic）、数据来源也完全相同（同一批 Prometheus 序列）。
     * 拆成三个工具会让工具清单从 5 条变成 7 条，模型的选择负担上升，
     * 而它并不需要「选择」—— 它需要的是一次把 MQ 相关的证据都拿到。
     * SPEC 第 24 节的验收口径是「Agent 能成功调用至少 5 个 Tool」，
     * 指的五类是 Metrics / Logs / DB / MQ / Business，本类仍是其中的 MQ 那一类。
     */
    public enum Operation {
        /** 队列堆积（精确值与近似值两个口径） */
        GET_QUEUE_LAG,
        /** 消费组状态（按消费组下钻） */
        GET_CONSUMER_STATUS,
        /** 失败率（消费失败 / 重试 / 死信） */
        GET_MESSAGE_FAILURE_RATE
    }

    /** 与记录规则 seckill:order_success_rate:5m 相同的分母口径下的失败率告警门槛 */
    private static final double FAILURE_RATE_WARN = 0.01;

    private final PrometheusQuerier querier;

    private final MetricCatalog catalog;

    private final RocketMqProperties mqProperties;

    public MqTool(PrometheusQuerier querier, MetricCatalog catalog, RocketMqProperties mqProperties) {
        this.querier = querier;
        this.catalog = catalog;
        this.mqProperties = mqProperties;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "查询 RocketMQ 的消费堆积与消费健康度（SPEC 的 mq_lag / producerRate / "
                + "consumerRate / failureRate）。"
                + "operation=GET_QUEUE_LAG 看队列堆积（首选，同时给出精确值与近似值）；"
                + "GET_CONSUMER_STATUS 按消费组下钻，用来判断「是消费者挂了还是消费变慢了」；"
                + "GET_MESSAGE_FAILURE_RATE 看失败与重试（含死信）。"
                + "注意：lag 的精确序列只在有消费活动之后才存在，没有序列不等于 lag 为 0。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        Map<String, Object> properties = new LinkedHashMap<>(4);
        properties.put("operation", ToolSchema.enumeration(
                "要查询的内容。GET_QUEUE_LAG=队列堆积；GET_CONSUMER_STATUS=按消费组的状态；"
                        + "GET_MESSAGE_FAILURE_RATE=失败率与重试",
                List.of(Operation.GET_QUEUE_LAG.name(), Operation.GET_CONSUMER_STATUS.name(),
                        Operation.GET_MESSAGE_FAILURE_RATE.name())));
        properties.put("topic", ToolSchema.string(
                "Topic 名，默认 " + mqProperties.getTopic() + "。只允许字母、数字与 _ . -", 128));
        return ToolSchema.object(properties, "operation");
    }

    @Override
    public ToolResult execute(ToolArguments args) {
        Operation operation = args.enumOrDefault("operation", Operation.class,
                Operation.GET_QUEUE_LAG, List.of(Operation.values()));
        String given = args.optionalString("topic");
        String topic = given == null ? mqProperties.getTopic() : given;

        List<String> notes = new ArrayList<>(4);
        Map<String, Object> data = new LinkedHashMap<>(12);
        data.put("operation", operation.name());
        data.put("topic", topic);
        data.put("consumerGroup", mqProperties.getConsumerGroup());

        if (!querier.reachable()) {
            notes.add("Prometheus（" + querier.baseUrl() + "）当前不可达，无法取得任何 MQ 指标。"
                    + "这不是「队列为空」的证据，请在结论中说明该项证据缺失。");
            data.put("available", false);
            return ToolResult.ok(name(), data, notes);
        }
        data.put("available", true);

        switch (operation) {
            case GET_QUEUE_LAG -> queueLag(topic, data, notes);
            case GET_CONSUMER_STATUS -> consumerStatus(topic, data, notes);
            case GET_MESSAGE_FAILURE_RATE -> failureRate(topic, data, notes);
        }
        return ToolResult.ok(name(), data, notes);
    }

    // ================================================================ GET_QUEUE_LAG

    private void queueLag(String topic, Map<String, Object> data, List<String> notes) {
        List<PromSeries> lagSeries = query("mq_consumer_lag", topic);
        List<PromSeries> inflightSeries = query("mq_consumer_inflight", topic);
        List<PromSeries> readySeries = query("mq_consumer_ready", topic);
        List<PromSeries> lagLatencySeries = query("mq_consumer_lag_latency", topic);

        Double preciseLag = sumOf(lagSeries);
        data.put("lagHighPrecision", preciseLag);
        data.put("lagSource", lagSeries.isEmpty() ? "unavailable" : "broker");
        if (!lagSeries.isEmpty()) {
            data.put("lagByConsumerGroup", groupByLabel(lagSeries, "consumer_group"));
        } else {
            // 【这条 note 是本工具最重要的一句】见类注释里那段记录。
            notes.add("Broker 侧的精确堆积序列 rocketmq_consumer_lag_messages 当前不存在。"
                    + "按 RocketMQ 5.x 的行为，OTel 导出器不输出「从未被记录过」的仪表 —— "
                    + "这通常意味着启动后还没有任何消费活动，而不是「堆积为 0」。"
                    + "请结合下面的近似口径与 GET_CONSUMER_STATUS 判断。");
        }
        data.put("inflightMessages", sumOf(inflightSeries));
        data.put("readyMessages", sumOf(readySeries));
        data.put("lagLatencyMillis", maxOf(lagLatencySeries));

        // ---- 近似口径：应用侧净增长速率 ----
        data.put("lagGrowthPerSecond", lastOf(query("mq_lag_growth_rate", topic)));

        // ---- 速率：SPEC 第 9.4 节的返回体要求这两个字段 ----
        Double producer = lastOf(query("mq_send_rate", topic));
        Double consumer = lastOf(query("mq_consume_rate", topic));
        data.put("producerRate", producer);
        data.put("consumerRate", consumer);
        data.put("caliber", "producerRate/consumerRate 来自应用侧计数器（单实例准确）；"
                + "lagHighPrecision 来自 Broker（精确）。两者不一致时以精确值为准，"
                + "并注意应用侧口径在多实例部署下会偏低");

        // ---- 把「谁快谁慢」直接算出来，省掉模型的一次比较 ----
        if (producer != null && consumer != null) {
            double gap = producer - consumer;
            data.put("rateGapPerSecond", round(gap));
            if (gap > 0.1 && (producer > 0 || consumer > 0)) {
                notes.add("投递速率（" + producer + "/s）高于确认速率（" + consumer
                        + "/s），堆积正在增长。若 lagHighPrecision 同样为正，"
                        + "说明消费者跟不上生产；请用 GET_CONSUMER_STATUS 下钻到具体消费组。");
            } else if (gap < -0.1) {
                notes.add("确认速率高于投递速率，堆积正在被消化（可能刚经历过一次积压）。");
            }
        }
        if (preciseLag != null && preciseLag > 0) {
            Double growth = lastOf(query("mq_lag_growth_rate", topic));
            if (growth != null && growth <= 0) {
                notes.add("精确堆积为 " + preciseLag + " 但净增长速率不为正：存量还在，"
                        + "只是不再增长。两者并不矛盾，不要用「增长速率为 0」否定积压的存在。");
            }
        }
    }

    // ================================================================ GET_CONSUMER_STATUS

    private void consumerStatus(String topic, Map<String, Object> data, List<String> notes) {
        List<PromSeries> lagSeries = query("mq_consumer_lag", topic);
        if (lagSeries.isEmpty()) {
            notes.add("没有取到任何消费组数据（rocketmq_consumer_lag_messages 不存在）。"
                    + "最可能的原因：Broker 启动后还没有消费者连上来过。"
                    + "请先用 search_logs 确认消费者线程是否启动（搜 'RocketMQ' 或消费者组名）。");
            data.put("consumerGroups", List.of());
            data.put("groupCount", 0);
            return;
        }

        Map<String, Double> lag = groupByLabel(lagSeries, "consumer_group");
        Map<String, Double> inflight = groupByLabel(query("mq_consumer_inflight", topic), "consumer_group");
        Map<String, Double> ready = groupByLabel(query("mq_consumer_ready", topic), "consumer_group");
        Map<String, Double> retry = groupByLabel(queryRetryLag(topic), "consumer_group");

        List<Map<String, Object>> groups = new ArrayList<>(lag.size());
        for (Map.Entry<String, Double> entry : lag.entrySet()) {
            String group = entry.getKey();
            Map<String, Object> item = new LinkedHashMap<>(8);
            item.put("consumerGroup", group);
            item.put("lag", finiteOrNull(entry.getValue()));
            item.put("inflight", finiteOrNull(inflight.getOrDefault(group, 0.0)));
            item.put("ready", finiteOrNull(ready.getOrDefault(group, 0.0)));
            if (retry.containsKey(group)) {
                // 重试队列的堆积单独给：它说明「有消息在被反复重投」，
                // 比普通堆积严重得多（那些消息已经失败过至少一次）。
                item.put("retryLag", finiteOrNull(retry.get(group)));
            }
            groups.add(item);
        }
        groups.sort((a, b) -> Double.compare(number(b.get("lag")), number(a.get("lag"))));
        data.put("consumerGroups", groups);
        data.put("groupCount", groups.size());

        // Broker 采集目标是否可达：目标是 DOWN 时，上面所有数字都可能已经过期
        List<PromSeries> up = query("target_up", topic, "rocketmq-broker");
        if (!up.isEmpty() && up.get(0).lastValue() < 1) {
            notes.add("Broker 的采集目标（service=rocketmq-broker）当前为 DOWN，"
                    + "上面的消费组数据可能已经过期。请先排查采集链路。");
        }
        if (retry.values().stream().anyMatch(value -> value > 0)) {
            notes.add("存在重试队列堆积（retryLag > 0）：有消息在被反复重投，"
                    + "意味着这些消息已经消费失败过。请用 GET_MESSAGE_FAILURE_RATE 与 "
                    + "search_logs 定位失败原因。");
        }
    }

    // ================================================================ GET_MESSAGE_FAILURE_RATE

    private void failureRate(String topic, Map<String, Object> data, List<String> notes) {
        Double confirmed = lastOf(query("mq_consume_rate", topic));
        Double cancelled = lastOf(query("mq_consume_cancelled_rate", topic));
        Double failed = lastOf(query("mq_consume_failed_rate", topic));
        Double duplicate = lastOf(query("mq_consume_duplicate_rate", topic));
        Double sent = lastOf(query("mq_send_rate", topic));
        Double dlq = lastOf(query("mq_dlq_rate", topic));

        data.put("confirmedRate", confirmed);
        data.put("cancelledRate", cancelled);
        data.put("failedRate", failed);
        data.put("duplicateRate", duplicate);
        data.put("sentRate", sent);
        data.put("dlqRate", dlq);
        data.put("retryQueueLag", sumOf(queryRetryLag(topic)));

        // 【分母的口径必须与记录规则 seckill:order_success_rate:5m 完全一致】
        // 用「已结案总数」而不是「受理数」：受理数与结案数之间隔着投递与消费两级异步，
        // 用受理数做分母会在活动刚开始的几十秒内算出很低的成功率 —— 那是启动延迟，不是失败。
        // 口径一致，Agent 算出的失败率才与告警里显示的失败率是同一个数。
        double closed = orZero(confirmed) + orZero(cancelled) + orZero(failed);
        Double failureRate = closed > 0 ? round(orZero(failed) / closed) : null;
        data.put("failureRate", failureRate);
        data.put("caliber", "failureRate = 消费失败速率 / 已结案速率（确认+取消+失败），"
                + "与记录规则 seckill:order_success_rate:5m 的口径一致。"
                + "取消（库存不足等）是正常业务结局，不计入失败");

        if (failureRate != null && failureRate > FAILURE_RATE_WARN) {
            notes.add("消费失败率 " + failureRate + " 明显高于 0。请用 search_logs 搜 ERROR，"
                    + "并检查数据库连接池（query_metric 的 db_pool_pending）。");
        }
        if (dlq != null && dlq > 0) {
            notes.add("有消息进入死信队列（" + dlq + "/s）：这些消息已经重试耗尽，"
                    + "需要人工确认它们对应的订单状态（可能停在 PENDING）。");
        }
        if (closed == 0) {
            notes.add("窗口内没有任何「已结案」的消费结果（确认/取消/失败都为 0），"
                    + "因此失败率无法计算 —— 这里返回 null 而不是 0。");
        }
        if (orZero(duplicate) > 0 && orZero(confirmed) > 0 && orZero(duplicate) > orZero(confirmed)) {
            notes.add("重复投递速率高于确认速率：可能存在重复消费或重平衡风暴。"
                    + "虽然幂等挡住了后果，但它会消耗消费能力。");
        }
    }

    // ================================================================ 查询辅助

    private List<PromSeries> query(String metricKey, String topic) {
        return query(metricKey, topic, null);
    }

    /**
     * 按目录项渲染 PromQL 并做<b>瞬时</b>查询。
     * <p>用瞬时查询而不是区间查询：堆积与速率类问题的判据是<b>此刻的水位</b>，
     * 取回一串历史点只会让结果变长而结论不变。趋势由 Agent 隔一段时间再查一次得到 ——
     * 那也比在这里塞 60 个点更省上下文。
     */
    private List<PromSeries> query(String metricKey, String topic, String service) {
        MetricCatalog.Entry entry = catalog.find(metricKey).orElse(null);
        if (entry == null) {
            // 目录里缺少条目属于装配错误（启动自检会报），不是运行时故障。
            // 这里返回空并由上层说明，但不抛异常 —— 一次 MQ 取证失败不该让整次诊断崩掉。
            log.error("[MqTool] 指标目录里缺少条目 {}，请检查 MetricCatalog 的装配", metricKey);
            return List.of();
        }
        String expr = entry.render(entry.serviceScoped() ? service : null,
                entry.topicScoped() ? topic : null);
        return querier.queryInstant(expr);
    }

    /**
     * 重试队列的堆积。
     * <p>【为什么这条 PromQL 写在这里而不是目录里】它是同一族指标的一个标签过滤变体，
     * 只在「按消费组下钻」这一个动作里用到；而目录是给模型看的白名单 ——
     * 把 {@code is_retry} 这种实现细节铺进目录，只会稀释模型对真正重要指标的注意力。
     * <p>topic 已由 {@code ToolArguments} 校验过字符集（只允许字母数字与 {@code _ . -}），
     * 因此这里的字符串拼接不构成 PromQL 注入面。
     */
    private List<PromSeries> queryRetryLag(String topic) {
        return querier.queryInstant(
                "rocketmq_consumer_lag_messages{is_retry=\"true\",topic=\"" + topic + "\"}");
    }

    /** 多条序列求和。<b>一条序列都没有时返回 {@code null}</b>，与「求和为 0」区分开 */
    private static Double sumOf(List<PromSeries> series) {
        double total = 0;
        boolean any = false;
        for (PromSeries one : series) {
            double value = one.lastValue();
            if (Double.isFinite(value)) {
                total += value;
                any = true;
            }
        }
        // 序列存在但全是 NaN 时，any 为 false —— 那同样属于「没有可用数据」，
        // 返回 null 而不是 0，理由见类注释最后一段。
        return any ? round(total) : null;
    }

    private static Double lastOf(List<PromSeries> series) {
        return sumOf(series);
    }

    private static Double maxOf(List<PromSeries> series) {
        double best = Double.NEGATIVE_INFINITY;
        for (PromSeries one : series) {
            double value = one.lastValue();
            if (Double.isFinite(value)) {
                best = Math.max(best, value);
            }
        }
        return best == Double.NEGATIVE_INFINITY ? null : round(best);
    }

    /** 按某个标签分组求和。用 TreeMap 保序，让同一个消费组每次出现的位置稳定（便于人眼比对） */
    private static Map<String, Double> groupByLabel(List<PromSeries> series, String label) {
        Map<String, Double> grouped = new TreeMap<>();
        for (PromSeries one : series) {
            String key = one.labels().getOrDefault(label, "(未标注)");
            double value = one.lastValue();
            if (Double.isFinite(value)) {
                grouped.merge(key, value, Double::sum);
            }
        }
        return grouped;
    }

    private static double orZero(Double value) {
        return value == null ? 0d : value;
    }

    private static Double finiteOrNull(double value) {
        return Double.isFinite(value) ? round(value) : null;
    }

    private static Double round(double value) {
        return Math.round(value * 1_000_000d) / 1_000_000d;
    }

    private static double number(Object value) {
        return value instanceof Number n ? n.doubleValue() : Double.NEGATIVE_INFINITY;
    }
}
