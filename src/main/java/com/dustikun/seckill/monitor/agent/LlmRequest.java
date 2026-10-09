package com.dustikun.seckill.monitor.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次 LLM 请求（OpenAI 兼容的 {@code POST /chat/completions} 请求体）。
 *
 * <p>它刻意<b>不是</b>一个通用的 OpenAI 客户端抽象：本项目的 LLM 交互只有
 * 「发一轮 messages + tools」这一种形态，因此这里只保留这一种形态需要的字段。
 * 少掉的那部分（stream / response_format / logprobs / 多模态）不是被遗漏，
 * 而是「不需要」—— 一个不支持的特性比一个支持得半吊子的特性更容易排查。
 *
 * @param model      模型名。放在请求里而不是只放在客户端字段里，
 *                   是为了让轨迹能回答「这条结论是哪个模型给的」
 * @param messages   对话历史
 * @param tools      {@code tools} 数组（直接取 {@code ToolRegistry#definitions()}）。
 *                   为空时不发送 {@code tools} 字段 —— 发一个空数组会让部分实现直接 400
 * @param temperature 采样温度
 * @param maxTokens  输出上限
 */
public record LlmRequest(
        String model,
        List<ChatMessage> messages,
        List<Map<String, Object>> tools,
        Double temperature,
        Integer maxTokens
) {

    public LlmRequest {
        messages = messages == null ? List.of() : List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    /** 线上请求体 */
    public Map<String, Object> toWire() {
        Map<String, Object> body = new LinkedHashMap<>(8);
        body.put("model", model);

        List<Map<String, Object>> wireMessages = new ArrayList<>(messages.size());
        for (ChatMessage message : messages) {
            wireMessages.add(message.toWire());
        }
        body.put("messages", wireMessages);

        if (!tools.isEmpty()) {
            body.put("tools", tools);
            // 显式写 auto：部分实现（含 DeepSeek 的某些兼容层）在不带这个字段时
            // 会退化成「不调用工具」，于是 Agent 第一轮就编一个结论出来 ——
            // 而症状是「诊断很快、没有轨迹」，很难往「少发了一个字段」上想。
            body.put("tool_choice", "auto");
        }
        if (temperature != null) {
            body.put("temperature", temperature);
        }
        if (maxTokens != null && maxTokens > 0) {
            body.put("max_tokens", maxTokens);
        }
        return body;
    }

    /** 请求规模的一行摘要，用于日志与「上下文膨胀」的早期发现 */
    public String describe() {
        int chars = 0;
        for (ChatMessage message : messages) {
            chars += message.content() == null ? 0 : message.content().length();
        }
        return "model=" + model
                + "，消息 " + messages.size() + " 条"
                + "，正文约 " + chars + " 字符"
                + "，工具 " + tools.size() + " 个";
    }
}
