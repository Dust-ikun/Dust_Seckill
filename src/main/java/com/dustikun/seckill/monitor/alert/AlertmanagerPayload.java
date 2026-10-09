package com.dustikun.seckill.monitor.alert;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.dustikun.seckill.monitor.core.AlertEvent;
import com.dustikun.seckill.monitor.core.AlertType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Alertmanager webhook 载荷解析（{@code POST /api/ai/alerts} 的入口）。
 *
 * <h2>载荷长什么样（v4 格式，本类只依赖这几个字段）</h2>
 * <pre>
 * {
 *   "version": "4",
 *   "status": "firing",
 *   "groupKey": "...",
 *   "commonLabels": {"service": "order-service"},
 *   "commonAnnotations": {"summary": "..."},
 *   "alerts": [
 *     {
 *       "status": "firing",
 *       "labels": {"alertname": "ApiP99LatencyHigh", "severity": "HIGH", "service": "order-service"},
 *       "annotations": {"summary": "...", "description": "...", "value": "2.10", "threshold": "1"},
 *       "startsAt": "2026-10-09T13:00:00Z",
 *       "endsAt": "0001-01-01T00:00:00Z",
 *       "fingerprint": "a1b2c3"
 *     }
 *   ]
 * }
 * </pre>
 *
 * <h2>三个必须显式处理的现实</h2>
 * <ol>
 *   <li><b>{@code endsAt} 的零值是 {@code 0001-01-01T00:00:00Z}</b>。
 *       Go 的时间零值就是这样，而它不是「1970 年之前」的意思，是「没有结束时间」。
 *       直接 {@code Instant.parse} 会得到公元 1 年，写进 DATETIME 会变成
 *       MySQL 的最小可表示值 —— 于是「还在活跃的告警」在库里看起来像
 *       「两千年前就结束了」。本类把它规整成 {@code null}。</li>
 *   <li><b>数值来自 annotations，不来自 labels</b>。Prometheus 的模板
 *       （{@code {{ printf "%.2f" $value }}}）只能写进 annotations —— labels
 *       一旦变化就会产生新的时间序列（cardinality 爆炸），因此
 *       {@code value} / {@code threshold} 只可能出现在 annotations 里。</li>
 *   <li><b>逐条 alert 的 status 会覆盖顶层的 status</b>。顶层的是「这一批」的聚合状态；
 *       一批里可以同时有 firing 与 resolved（Alertmanager 的 group 语义）。</li>
 * </ol>
 */
public final class AlertmanagerPayload {

    private static final Logger log = LoggerFactory.getLogger(AlertmanagerPayload.class);

    /**
     * 单次投递最多处理的告警条数。
     * <p>【它防的是什么】这个端点是<b>对内网暴露</b>的、不带鉴权的 POST
     * （Alertmanager 不支持自定义头之外的认证方式）。没有上限时，
     * 一个 100 MB 的请求体就能让应用在处理告警时把内存吃光 ——
     * 而「AI 监控把交易系统拖垮」是 SPEC 第 5.2 节明令禁止的事。
     * Alertmanager 侧默认的 {@code max_alerts} 是 0（不限制），
     * 因此这道闸门只能放在应用侧。
     */
    public static final int MAX_ALERTS = 500;

    /** 单条告警原始 JSON 保留的字符数（写进 raw_result，不必保留整包） */
    private static final int MAX_RAW_ALERT_CHARS = 4000;

    private final ObjectMapper objectMapper;

    public AlertmanagerPayload(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * @param body          HTTP 请求体
     * @param defaultService 载荷里没有 {@code labels.service} 时的回落服务名
     */
    public Parsed parse(String body, String defaultService) {
        if (body == null || body.isBlank()) {
            throw new AlertmanagerPayloadException("请求体为空");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (JacksonException e) {
            throw new AlertmanagerPayloadException("请求体不是合法 JSON：" + e.getMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new AlertmanagerPayloadException("请求体不是 JSON 对象");
        }

        JsonNode alertsNode = root.path("alerts");
        if (!alertsNode.isArray()) {
            throw new AlertmanagerPayloadException(
                    "请求体里没有 alerts 数组 —— 它不是 Alertmanager 的 webhook 格式");
        }

        List<JsonNode> rawAlerts = new ArrayList<>();
        for (JsonNode alert : alertsNode) {
            if (rawAlerts.size() >= MAX_ALERTS) {
                break;
            }
            rawAlerts.add(alert);
        }
        int dropped = alertsNode.size() - rawAlerts.size();
        if (dropped > 0) {
            log.warn("[AlertWebhook] 单次投递的告警条数 {} 超过上限 {}，已丢弃 {} 条", 
                    alertsNode.size(), MAX_ALERTS, dropped);
        }

        IncomingAlert.State groupState = IncomingAlert.State.from(text(root, "status"));
        String groupKey = text(root, "groupKey");
        JsonNode commonLabels = root.path("commonLabels");
        JsonNode commonAnnotations = root.path("commonAnnotations");

        List<IncomingAlert> parsed = new ArrayList<>(rawAlerts.size());
        for (JsonNode alert : rawAlerts) {
            IncomingAlert incoming = toIncoming(alert, groupState, commonLabels,
                    commonAnnotations, defaultService);
            if (incoming != null) {
                parsed.add(incoming);
            }
        }
        return new Parsed(groupKey, groupState, parsed, dropped);
    }

    /**
     * 把一条 alert 转成领域对象。
     * <p>返回 {@code null} 表示这条被跳过（没有 alertname 的告警无法归因，
     * 保留它只会制造一条「类型 UNKNOWN、描述为空」的噪声任务）。
     */
    private IncomingAlert toIncoming(JsonNode alert, IncomingAlert.State groupState,
                                     JsonNode commonLabels, JsonNode commonAnnotations,
                                     String defaultService) {
        if (alert == null || !alert.isObject()) {
            return null;
        }
        JsonNode labels = alert.path("labels");
        JsonNode annotations = alert.path("annotations");

        String alertName = text(labels, "alertname");
        if (alertName.isBlank()) {
            log.warn("[AlertWebhook] 收到一条没有 labels.alertname 的告警，已跳过");
            return null;
        }

        IncomingAlert.State state = alert.has("status")
                ? IncomingAlert.State.from(text(alert, "status"))
                : groupState;

        String service = firstNonBlank(text(labels, "service"), text(commonLabels, "service"),
                defaultService);

        // ★ 先读规则作者显式声明的 `alert_type` 标签，没有才回落到「告警名 → 类型」映射。
        // 见 AlertType.resolve 的注释：标签是唯一真相，名字映射只是一张静态校验过的兜底表。
        String typeLabel = firstNonBlank(text(labels, "alert_type"), text(commonLabels, "alert_type"));
        AlertType alertType = AlertType.resolve(typeLabel, alertName);
        if (!typeLabel.isBlank()) {
            AlertType byName = AlertType.fromAlertName(alertName);
            if (byName != alertType) {
                // 标签与名字映射不一致：不改变判定（以标签为准），但必须出声 ——
                // 否则两边分叉会一直静默存在，直到某次聚合把两个事故并在一起才被发现。
                // 静态防线在 validate_config.py 第 10 组（它把两边逐个对照）。
                log.warn("[AlertWebhook] 告警 {} 的 alert_type 标签（{}）与名字映射（{}）不一致，"
                        + "已按标签取值。请同步 AlertType 的索引表与告警规则。",
                        alertName, typeLabel, byName);
            }
        }

        String description = firstNonBlank(
                text(annotations, "description"),
                text(annotations, "summary"),
                text(commonAnnotations, "description"),
                text(commonAnnotations, "summary"));

        String metricName = firstNonBlank(
                text(annotations, "metric_name"),
                firstNonBlank(text(labels, "__name__"), alertName));

        Long startsAt = epochSeconds(text(alert, "startsAt"));
        Long endsAt = epochSeconds(text(alert, "endsAt"));

        // 【零值处理】Go 的 time.Time 零值 = 0001-01-01，它不是「很早以前」而是「没有值」。
        // 判据用「公元 1970 年之前」而不是精确匹配那一串：Alertmanager 的时区写法有变体。
        if (endsAt != null && endsAt <= 0) {
            endsAt = null;
        }
        if (startsAt == null || startsAt <= 0) {
            startsAt = Instant.now().getEpochSecond();
        }

        String fingerprint = text(alert, "fingerprint");
        String alertId = fingerprint.isBlank()
                ? alertName + "-" + service + "-" + startsAt
                : fingerprint;

        AlertEvent event = new AlertEvent(
                alertId,
                service,
                metricName,
                alertType.name(),
                firstNonBlank(text(labels, "severity"), text(commonLabels, "severity")),
                readNumber(annotations, "value"),
                readNumber(annotations, "threshold"),
                startsAt,
                description);

        return new IncomingAlert(event, state, fingerprint, startsAt, endsAt,
                abbreviate(alert.toString(), MAX_RAW_ALERT_CHARS));
    }

    // ================================================================ 读取辅助

    /**
     * 读取一个「应当是数字」的注解。
     * <p>【刻意不做单位换算】{@code "1900ms"} 会被判为读不出来（而不是读成 1900）。
     * 理由与 Handler 里反复出现的口径一致：静默地把毫秒当成秒、
     * 或者把 {@code "2.1s"} 读成 {@code 2.1}（其实是 2100ms），
     * 会产出一个<b>看起来合理但差三个数量级</b>的数字，而 Agent 会拿它当证据。
     * 注解必须写纯数字，这一点由 {@code validate_config.py} 第 10 组静态检查。
     */
    private static Double readNumber(JsonNode annotations, String field) {
        String raw = text(annotations, field);
        if (raw.isBlank()) {
            return null;
        }
        try {
            double value = Double.parseDouble(raw.trim());
            return Double.isNaN(value) || Double.isInfinite(value) ? null : value;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long epochSeconds(String rfc3339) {
        if (rfc3339 == null || rfc3339.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(rfc3339.trim()).getEpochSecond();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            return "";
        }
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asString().trim() : "";
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return "";
    }

    private static String abbreviate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    /**
     * 解析结果。
     *
     * @param groupKey   Alertmanager 的分组键（诊断任务可用它解释「为什么这些告警是一起到的」）
     * @param groupState 顶层的聚合状态
     * @param alerts     解析出的告警（已剔除无法归因的）
     * @param dropped    因超过 {@link #MAX_ALERTS} 而未处理的条数
     */
    public record Parsed(String groupKey, IncomingAlert.State groupState,
                         List<IncomingAlert> alerts, int dropped) {

        public Parsed {
            alerts = alerts == null ? List.of() : List.copyOf(alerts);
        }

        public int firingCount() {
            return (int) alerts.stream().filter(IncomingAlert::firing).count();
        }

        public int resolvedCount() {
            return (int) alerts.stream().filter(IncomingAlert::resolved).count();
        }
    }
}
