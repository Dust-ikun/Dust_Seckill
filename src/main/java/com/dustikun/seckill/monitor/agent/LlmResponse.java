package com.dustikun.seckill.monitor.agent;

import java.util.List;

/**
 * 一次 LLM 回复。
 *
 * <h2>{@link #content} 与 {@link #toolCalls} 的关系是本类的全部重点</h2>
 * <p>
 * 「是否有工具调用」决定 ReAct 循环的走向，而它<b>不能</b>靠
 * 「{@code content} 是不是空」来判断：
 * <ul>
 *   <li>模型可以在调用工具的同时写一段理由（{@code content} 非空 + {@code tool_calls} 非空）；</li>
 *   <li>也可以在「决定结束」时返回空字符串（{@code content} 为空 + 没有 {@code tool_calls}）——
 *       那是<b>故障</b>，不是「结论为空」，两者必须分开处理。</li>
 * </ul>
 * 因此判据只有 {@link #hasToolCalls()}，而「既没有工具调用、正文也是空的」
 * 由 {@link #isBlankFinal()} 单独识别 —— 它对应
 * 「LLM 返回了空内容」，最终落 {@code INSUFFICIENT_EVIDENCE} 而不是
 * 「解析失败」（两者的排查方向完全不同：前者要查模型/配额，后者要查 prompt）。
 *
 * @param content        正文。可能为 {@code null}
 * @param toolCalls      模型请求的调用（可能为空）
 * @param finishReason   {@code stop} / {@code tool_calls} / {@code length}。
 *                       {@code length} 意味着被 {@code max_tokens} 截断 ——
 *                       此时 JSON 一定是残缺的，提前知道能省一轮无效解析
 * @param promptTokens   输入 token 数（可能为 {@code null}，取决于服务端是否返回 usage）
 * @param completionTokens 输出 token 数
 * @param rawJson        原始响应体。落 {@code ai_diagnosis_result.raw_result}，
 *                       它是「Schema 校验为什么失败」的唯一可复查证据
 */
public record LlmResponse(
        String content,
        List<ToolCallRequest> toolCalls,
        String finishReason,
        Integer promptTokens,
        Integer completionTokens,
        String rawJson
) {

    public LlmResponse {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }

    /** 既没有工具调用、正文也是空的 —— 见类注释，它是故障而不是「结论为空」 */
    public boolean isBlankFinal() {
        return !hasToolCalls() && (content == null || content.isBlank());
    }

    /** 输出被 {@code max_tokens} 截断：此时别指望正文是完整 JSON */
    public boolean truncatedByLength() {
        return "length".equalsIgnoreCase(finishReason);
    }

    public int totalTokens() {
        return (promptTokens == null ? 0 : promptTokens) + (completionTokens == null ? 0 : completionTokens);
    }

    public String describe() {
        return "finish=" + finishReason
                + "，工具调用 " + toolCalls.size() + " 个"
                + "，正文 " + (content == null ? 0 : content.length()) + " 字符"
                + "，tokens=" + promptTokens + "+" + completionTokens;
    }
}
