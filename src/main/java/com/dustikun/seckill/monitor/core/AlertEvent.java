package com.dustikun.seckill.monitor.core;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 统一异常事件（SPEC 第 7.1 节）。
 *
 * <h2>字段与 Alertmanager 的对应关系（有一处必须说清）</h2>
 * <p>
 * SPEC 第 7.1 节列了 9 个字段，而 Alertmanager 的 webhook 载荷里<b>没有数值</b>：
 * 它只带 labels、annotations 与时间。因此：
 *
 * <table border="1">
 *   <caption>来源对照</caption>
 *   <tr><th>字段</th><th>来源</th></tr>
 *   <tr><td>{@code alertId}</td><td>Alertmanager 的 {@code fingerprint}（它对同一组 labels
 *       是稳定的，正好可以当幂等键用）</td></tr>
 *   <tr><td>{@code serviceName}</td><td>{@code labels.service}</td></tr>
 *   <tr><td>{@code metricName}</td><td>{@code annotations.metric_name}；缺失时回落为告警名</td></tr>
 *   <tr><td>{@code alertType}</td><td>{@link AlertType#fromAlertName(String)}</td></tr>
 *   <tr><td>{@code severity}</td><td>{@code labels.severity}</td></tr>
 *   <tr><td>{@code currentValue} / {@code threshold}</td><td>{@code annotations.value} /
 *       {@code annotations.threshold}（<b>本项目为此给告警规则补了这两个注解</b>）</td></tr>
 *   <tr><td>{@code timestamp}</td><td>{@code startsAt} 的 epoch 秒</td></tr>
 *   <tr><td>{@code description}</td><td>{@code annotations.description} 优先，其次 {@code summary}</td></tr>
 * </table>
 *
 * <p>【为什么 {@code currentValue} 是可空的 {@link Double} 而不是 {@code double}】
 * 因为这正是本项目反复踩到的那一类问题：Alertmanager 的载荷里<b>本来就没有</b>
 * 这个数，如果给个默认 {@code 0.0}，那么「没取到」与「真的是 0」在数据里
 * 完全一样。而这个区别在诊断场景下是决定性的：{@code currentValue = 0.0}
 * 与「P99 是 2.1s」会让 Agent 得出相反的结论。
 * 取不到就 {@code null}，并且 {@code validate_config.py} 第 10 组会检查
 * 每一条路由到 AI 的告警规则都写了 {@code value} / {@code threshold} 注解 ——
 * 让「取不到」在静态检查里就暴露，而不是等到某次诊断时才发现。
 *
 * <h2>为什么它在 {@code monitor.core}</h2>
 * <p>
 * 它是整个 AI 子系统的<b>共享词汇</b>：告警接收侧（{@code monitor.alert}）构造它、
 * Agent 侧（{@code monitor.agent}）用它写 prompt 与落 {@code raw_result}、
 * 仓储层（{@code monitor.repository}）读它的字段建任务。
 * 放在任何一侧都会让包依赖成环。
 *
 * @param alertId      告警唯一标识（Alertmanager fingerprint）
 * @param serviceName  服务名
 * @param metricName   指标名
 * @param alertType    异常类型，见 {@link AlertType}
 * @param severity     严重级别字符串（CRITICAL / HIGH / WARNING）
 * @param currentValue 触发时的值；取不到为 {@code null}
 * @param threshold    阈值；取不到为 {@code null}
 * @param timestamp    异常开始时间（epoch 秒，与 SPEC 示例一致）
 * @param description  人类可读描述
 */
public record AlertEvent(
        String alertId,
        String serviceName,
        String metricName,
        String alertType,
        String severity,
        Double currentValue,
        Double threshold,
        Long timestamp,
        String description
) {

    public AlertEvent {
        alertId = alertId == null ? "" : alertId.trim();
        serviceName = serviceName == null ? "" : serviceName.trim();
        metricName = metricName == null ? "" : metricName.trim();
        alertType = alertType == null || alertType.isBlank() ? AlertType.UNKNOWN.name()
                : alertType.trim().toUpperCase(Locale.ROOT);
        severity = severity == null ? "" : severity.trim();
        description = description == null ? "" : description.trim();
    }

    /** 解析成枚举。名字来自构造器，因此这里不会失败 */
    public AlertType type() {
        try {
            return AlertType.valueOf(alertType);
        } catch (IllegalArgumentException e) {
            return AlertType.UNKNOWN;
        }
    }

    /**
     * 写进 LLM Prompt 的动态上下文（SPEC 第 10 节）。
     * <p>【为什么要在没有值时明确写「未提供」】因为把 {@code currentValue} 静默省掉，
     * 模型会倾向于「自己推一个数出来」—— 它见过太多类似的告警描述。
     * 明确写「未提供（告警规则里没有 value 注解）」既阻止了编造，
     * 又直接指明了修复方向。
     */
    public String describeForPrompt() {
        StringBuilder sb = new StringBuilder(256);
        sb.append("当前服务：").append(blankTo(serviceName, "(未知)")).append('\n');
        sb.append("异常类型：").append(alertType).append('\n');
        sb.append("指标：").append(blankTo(metricName, "(未提供)")).append('\n');
        sb.append("当前值：").append(numberOrHint(currentValue, "未提供（告警规则里没有 value 注解）")).append('\n');
        sb.append("阈值：").append(numberOrHint(threshold, "未提供（告警规则里没有 threshold 注解）")).append('\n');
        sb.append("告警ID：").append(blankTo(alertId, "(无)")).append('\n');
        sb.append("开始时间：").append(formatTimestamp()).append('\n');
        sb.append("告警描述：").append(blankTo(description, "(无)"));
        return sb.toString();
    }

    /** 给日志与 API 展示用的形态 */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>(12);
        map.put("alertId", alertId);
        map.put("serviceName", serviceName);
        map.put("metricName", metricName);
        map.put("alertType", alertType);
        map.put("severity", severity);
        map.put("currentValue", currentValue);
        map.put("threshold", threshold);
        map.put("timestamp", timestamp);
        map.put("description", description);
        return map;
    }

    public String summarize() {
        return alertType + " " + serviceName + " " + metricName
                + "（" + severity + "，值 " + currentValue + " / 阈值 " + threshold + "）";
    }

    private String formatTimestamp() {
        if (timestamp == null || timestamp <= 0) {
            return "(未提供)";
        }
        return java.time.Instant.ofEpochSecond(timestamp)
                .atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private static String numberOrHint(Double value, String hint) {
        if (value == null) {
            return hint;
        }
        if (value == Math.rint(value) && !value.isInfinite()) {
            return String.valueOf(value.longValue());
        }
        return String.valueOf(value);
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
