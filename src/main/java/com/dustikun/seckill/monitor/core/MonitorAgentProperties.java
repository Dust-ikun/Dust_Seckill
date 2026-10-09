package com.dustikun.seckill.monitor.core;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent 执行约束（{@code seckill.monitor.agent.*}，SPEC 第 23.2 节与第 25 节）。
 *
 * <h2>为什么放在 {@code monitor.core} 而不是 {@code monitor.agent}</h2>
 * <p>
 * 因为它的第一个使用者出现在 Tool 层：一次 Tool 调用的超时必须由
 * {@code ToolRegistry} 强制执行（否则超时就退化成「每个工具自觉遵守」）。
 * 批次 3 的 ReAct 循环会用到这里的其余三项。把它放在 {@code core} 是有意的 ——
 * 这个包的含义是「跨批次共享的底座」，而不是「工具专用」或「Agent 专用」。
 * 若放进 {@code monitor.agent}，Tool 层就会依赖 Agent 层，方向是反的。
 *
 * <h2>四个数值各自的「防的是什么」</h2>
 * <pre>
 *   max-tool-calls   防无限循环（模型在证据不足时反复重试同一个工具，且每次都付费）
 *   tool-timeout-ms  防单次调用挂死（SPEC 第 25 节：Tool 单次调用 &lt; 1s）
 *   max-duration-ms  防整次诊断挂死（SPEC 第 25 节：单次诊断 &lt; 10s）
 *   min-evidence-count 它是提示词的约束而不是算法的约束（SPEC 第 10 节诊断规则第 3 条）
 * </pre>
 *
 * <p>【时间类参数一律取「熔断值」而不是「目标值」】配置里 tool-timeout-ms=3000 而目标是 1s，
 * max-duration-ms=60000 而目标是 10s。把熔断值设成等于目标值，会让任何一次轻微抖动
 * 都变成失败 —— 而「Agent 拿不到证据却必须下结论」比「慢一点」糟得多
 * （这条裁定见可行性报告 §3.2 对 SPEC 第 25 节的重定义）。
 *
 * @param maxToolCalls     一次诊断最多允许的 Tool 调用次数
 * @param toolTimeoutMs    单次 Tool 执行的超时（毫秒）
 * @param maxDurationMs    整次诊断的墙钟超时（毫秒）
 * @param minEvidenceCount 判定「证据充分」所需的最少独立证据条数
 */
@ConfigurationProperties(prefix = "seckill.monitor.agent")
public record MonitorAgentProperties(
        Integer maxToolCalls,
        Integer toolTimeoutMs,
        Integer maxDurationMs,
        Integer minEvidenceCount
) {

    private static final int DEFAULT_MAX_TOOL_CALLS = 10;

    private static final int DEFAULT_TOOL_TIMEOUT_MS = 3000;

    private static final int DEFAULT_MAX_DURATION_MS = 60_000;

    private static final int DEFAULT_MIN_EVIDENCE_COUNT = 2;

    public MonitorAgentProperties normalized() {
        return new MonitorAgentProperties(
                clamp(maxToolCalls, 1, 100, DEFAULT_MAX_TOOL_CALLS),
                clamp(toolTimeoutMs, 100, 120_000, DEFAULT_TOOL_TIMEOUT_MS),
                clamp(maxDurationMs, 1000, 600_000, DEFAULT_MAX_DURATION_MS),
                clamp(minEvidenceCount, 1, 20, DEFAULT_MIN_EVIDENCE_COUNT));
    }

    private static int clamp(Integer value, int min, int max, int fallback) {
        if (value == null) {
            return fallback;
        }
        return Math.max(min, Math.min(max, value));
    }
}
