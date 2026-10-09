package com.dustikun.seckill.monitor.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一条对话消息（OpenAI 兼容协议的 {@code messages[]} 元素）。
 *
 * <h2>为什么要有 {@link #toWire()}，而不是直接用 Map 拼</h2>
 * <p>
 * 因为这一层最容易出的错是<b>字段名与协议不一致</b>，而它的症状极难定位：
 * 服务端可能忽略一个不认识的字段（于是 {@code tool_calls} 悄悄没带上，
 * 模型下一轮就开始重复问同一个问题），也可能直接 400
 * （而错误消息指向的是「请求体不合法」，看不出是哪个字段错了）。
 * 把「领域对象」与「线上格式」的换算收在一个方法里，就能对它写单测 ——
 * {@code LlmClientTest} 断言的就是这里产出的字节。
 *
 * <h2>四种角色各自的必填字段（协议里没有明写，但错一样报 400）</h2>
 * <pre>
 *   system    : content
 *   user      : content
 *   assistant : content 或 tool_calls（二者至少要有一个）
 *               └─ 带 tool_calls 时 content 允许为 null，但**必须原样回灌 tool_calls**
 *   tool      : tool_call_id + content（name 可选，带上更容易排查）
 * </pre>
 * 其中「必须原样回灌 {@code tool_calls}」这一条是本类存在的主要理由：
 * 模型是靠 {@code id} 把结果与请求对上号的，回灌时丢掉 {@code id} 或换个顺序，
 * 模型就会认为「我请求的调用没有结果」，然后<b>重复调用同一个工具</b> ——
 * 一次诊断的费用会凭空翻倍，而轨迹看起来完全正常。
 *
 * @param role     角色
 * @param content  正文。{@code assistant} 带 {@code tool_calls} 时可为空
 * @param toolCalls 仅 {@code assistant} 使用：模型请求的调用列表
 * @param toolCallId 仅 {@code tool} 使用：回灌它对应哪次调用
 * @param name     仅 {@code tool} 使用：函数名（可选，但强烈建议带上，见下）
 */
public record ChatMessage(
        Role role,
        String content,
        List<ToolCallRequest> toolCalls,
        String toolCallId,
        String name
) {

    /** 协议里的四种角色。刻意用枚举而不是字符串：写错一个字就会被服务端忽略或拒绝 */
    public enum Role {
        SYSTEM("system"),
        USER("user"),
        ASSISTANT("assistant"),
        TOOL("tool");

        private final String wireName;

        Role(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }
    }

    public ChatMessage {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public static ChatMessage system(String content) {
        return new ChatMessage(Role.SYSTEM, content, List.of(), null, null);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(Role.USER, content, List.of(), null, null);
    }

    /** 模型的普通回复（纯文本，没有工具调用） */
    public static ChatMessage assistant(String content) {
        return new ChatMessage(Role.ASSISTANT, content, List.of(), null, null);
    }

    /**
     * 模型的工具调用回复。
     * <p>【为什么要保留 {@code content}】模型经常在调用工具前先写一句理由
     * （「先看指标再决定」）。丢掉它会让轨迹失去<b>动机</b>，
     * 而「Agent 为什么查这个」正是复盘时要回答的第一个问题。
     */
    public static ChatMessage assistant(String content, List<ToolCallRequest> toolCalls) {
        return new ChatMessage(Role.ASSISTANT, content, toolCalls, null, null);
    }

    /** 一次工具调用的结果，回灌给模型 */
    public static ChatMessage tool(String toolCallId, String name, String content) {
        return new ChatMessage(Role.TOOL, content, List.of(), toolCallId, name);
    }

    /** 是否是携带工具调用的 assistant 消息 */
    public boolean hasToolCalls() {
        return role == Role.ASSISTANT && !toolCalls.isEmpty();
    }

    /**
     * 换算成线上 JSON 结构。
     * <p>【为什么用 {@link LinkedHashMap}】字段顺序在 JSON 里没有语义，
     * 但在<b>抓包对照</b>与测试断言里有：稳定的顺序让「两次请求的 diff」
     * 只在真正变化的地方显示差异，而不是整段重排。
     */
    public Map<String, Object> toWire() {
        Map<String, Object> wire = new LinkedHashMap<>(6);
        wire.put("role", role.wireName());

        if (role == Role.ASSISTANT && !toolCalls.isEmpty()) {
            // content 允许为 null，但空串与 null 在部分实现里语义不同，
            // 因此只在真的非空时才写这个字段（缺失 = null，是协议允许的写法）。
            if (content != null && !content.isBlank()) {
                wire.put("content", content);
            }
            List<Map<String, Object>> calls = new ArrayList<>(toolCalls.size());
            for (ToolCallRequest call : toolCalls) {
                Map<String, Object> function = new LinkedHashMap<>(4);
                function.put("name", call.name());
                // 【这一行不能省】arguments 必须是**字符串**。
                // 直接放 Map 会被序列化成嵌套对象，服务端会以 400 拒绝，
                // 或（更糟）把它读成空参数。
                function.put("arguments", call.argumentsJson());
                Map<String, Object> entry = new LinkedHashMap<>(4);
                entry.put("id", call.id());
                entry.put("type", "function");
                entry.put("function", function);
                calls.add(entry);
            }
            wire.put("tool_calls", calls);
            return wire;
        }

        if (role == Role.TOOL) {
            wire.put("tool_call_id", toolCallId == null ? "" : toolCallId);
            wire.put("content", content == null ? "" : content);
            if (name != null && !name.isBlank()) {
                wire.put("name", name);
            }
            return wire;
        }

        wire.put("content", content == null ? "" : content);
        return wire;
    }

    /** 日志用的一行摘要：不打印正文（正文可能几千字符，且已在轨迹里） */
    public String describe() {
        StringBuilder sb = new StringBuilder(role.wireName());
        if (hasToolCalls()) {
            sb.append(" tool_calls=").append(toolCalls.stream().map(ToolCallRequest::name).toList());
        }
        if (content != null && !content.isBlank()) {
            sb.append(" content=").append(content.length()).append(" 字符");
        }
        return sb.toString();
    }
}
