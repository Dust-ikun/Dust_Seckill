package com.dustikun.seckill.monitor.core;

import java.util.Locale;
import java.util.Optional;

/**
 * 严重级别（SPEC 第 7.1 / 11 / 14.1 节）。
 *
 * <h2>为什么放在 {@code monitor.core} 而不是 {@code monitor.alert}</h2>
 * <p>
 * 因为它同时出现在两侧：告警侧从 Alertmanager 的 labels 里读它，
 * 解析侧要从模型返回的 JSON 里读它，而<b>这两处必须用同一套判定</b>。
 * 若各写一份「把字符串规范化」的逻辑，就会出现「告警是 HIGH、诊断结论是 high」
 * 这种看起来只是大小写不同、却让按级别聚合的查询漏掉一半数据的问题。
 * 同 {@code MonitorAgentProperties} 的注释：{@code core} 的含义是
 * 「跨批次共享的底座」，不是「工具专用」或「Agent 专用」。
 *
 * <h2>{@link #INFO} 与 {@link #UNKNOWN} 的区别</h2>
 * <p>
 * 前者是「明确被告知这是一条提示」；后者是「没读懂对方给的是什么」。
 * 把后者伪装成前者（例如默认 WARNING）会让一条「告警字段配错了」的配置问题
 * 永远不可见 —— 因为它在界面上永远显示为一个合理的级别。
 */
public enum Severity {

    CRITICAL,
    HIGH,
    WARNING,
    INFO,

    /** 没能识别出的级别。它存在的意义是「不猜」 */
    UNKNOWN;

    /** 解析一个任意来源的级别字符串；无法识别时返回 {@link #UNKNOWN} */
    public static Severity from(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNKNOWN;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        for (Severity severity : values()) {
            if (severity.name().equals(normalized)) {
                return severity;
            }
        }
        // 上游偶尔用英文别名（FATAL / ERROR / WARN）
        return switch (normalized) {
            case "FATAL", "ERROR", "EMERGENCY", "ALERT" -> CRITICAL;
            case "WARN", "MINOR" -> WARNING;
            case "NOTICE", "DEBUG" -> INFO;
            default -> UNKNOWN;
        };
    }

    /** 宽松解析：只接受能识别出的值，识别不了就返回空 —— 供「回落链」使用 */
    public static Optional<Severity> tryParse(String raw) {
        Severity severity = from(raw);
        return severity == UNKNOWN ? Optional.empty() : Optional.of(severity);
    }

    /** 是否是「需要人立刻看」的级别。用于日志措辞，不参与任何告警判定 */
    public boolean urgent() {
        return this == CRITICAL || this == HIGH;
    }
}
