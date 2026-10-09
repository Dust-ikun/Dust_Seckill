package com.dustikun.seckill.monitor.repository;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code ai_tool_execution} 的一行（SPEC 第 14.3 节的 Tool Calling 轨迹）。
 *
 * <h2>这张表为什么是「Agent 可解释性」的全部</h2>
 * <p>
 * SPEC 的原话是「用于完整保存 Tool Calling Trace」。它的价值不在成功的那几行，
 * 而在下面这三类行，它们各自对应一种「无法解释」的失败模式：
 * <ul>
 *   <li>{@code REJECTED} 行：模型编了一个不存在的工具名，或参数越界。
 *       没有它，轨迹里会少了「Agent 曾经走错方向」这一步，
 *       而人看到的是一条从证据直接跳到结论的完美推理；</li>
 *   <li>{@code FAILED} 行：工具超时或数据源不可达。
 *       没有它，「Agent 为什么没查数据库」会变成一个只能猜的问题；</li>
 *   <li>{@code truncated = true}（在 {@code result} 里）：
 *       结果被体积上限裁剪过。没有它，人会以为证据本来就这么多。</li>
 * </ul>
 *
 * @param id              自增主键
 * @param taskId          {@code ai_diagnosis_task.id}
 * @param sequenceNo      同一 task 内的调用序号，从 1 开始。
 *                        <b>必需</b>：{@code created_at} 的精度只有秒，
 *                        而一次诊断里 Agent 完全可能在同一秒内连续调用两个工具 ——
 *                        只按时间排序会得到不确定的顺序，「先查 DB 还是先查日志」就答不上来
 * @param toolName        工具名
 * @param arguments       入参（取值规则见 {@code ToolResult#arguments()}）
 * @param result          工具返回（整形后的 envelope，含 shapes 后的 llmText）
 * @param status          {@code SUCCESS / FAILED / REJECTED}
 * @param executionTimeMs 单次调用耗时（对应 SPEC 第 25 节「Tool 单次调用 &lt; 1s」）
 * @param errorMessage    {@code status != SUCCESS} 时的原因
 * @param createdAt       发生时间
 */
public record ToolExecutionRow(
        long id,
        long taskId,
        int sequenceNo,
        String toolName,
        Map<String, Object> arguments,
        Map<String, Object> result,
        String status,
        long executionTimeMs,
        String errorMessage,
        Instant createdAt
) {

    public ToolExecutionRow {
        arguments = arguments == null ? Map.of() : arguments;
        result = result == null ? Map.of() : result;
    }

    /** 给 {@code GET /api/ai/diagnosis/{incidentId}/tools} 用的形态 */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>(14);
        map.put("sequenceNo", sequenceNo);
        map.put("toolName", toolName);
        map.put("status", status);
        map.put("arguments", arguments);
        map.put("result", result);
        map.put("executionTimeMs", executionTimeMs);
        map.put("errorMessage", errorMessage);
        map.put("createdAt", createdAt == null ? null : createdAt.toString());
        return map;
    }

    /** 一行摘要（启动日志与排障时读） */
    public String summarize() {
        return "#" + sequenceNo + " " + toolName + " " + status + "（" + executionTimeMs + "ms）"
                + (errorMessage == null || errorMessage.isBlank() ? "" : "：" + errorMessage);
    }

    /** 只读的空轨迹（任务还没跑任何工具时） */
    public static List<ToolExecutionRow> emptyTrace() {
        return List.of();
    }
}
