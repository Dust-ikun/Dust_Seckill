package com.dustikun.seckill.monitor.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 脚本化的 {@link LlmClient}：按预设顺序回放响应，并记录每一轮的请求。
 *
 * <h2>为什么它必须记录<b>请求</b>而不只是回放响应</h2>
 * <p>
 * 因为 ReAct 循环里最容易写错的地方全在「发出去的那段对话」上：
 * <pre>
 *   每一次 tool_call 是否都有对应的 role=tool 消息回应？
 *   assistant 的 tool_calls 是否被原样回灌（id 没丢、顺序没变）？
 *   预算用尽时是否追加了「别再调工具了」那条消息？
 *   工具被拒之后，模型下一轮是否真的能看到拒绝原因？
 * </pre>
 * 这四条都只能通过检查第二轮、第三轮请求体的内容来断言。
 * 只回放响应的话，一个「tool 消息全丢了」的实现也能让所有断言通过 ——
 * 而它在真实服务端会以 400 失败，且错误消息说「请求体不合法」。
 */
final class ScriptedLlmClient implements LlmClient {

    private final List<LlmResponse> script = new ArrayList<>();

    private final List<LlmRequest> requests = new CopyOnWriteArrayList<>();

    private final AtomicInteger calls = new AtomicInteger();

    private volatile boolean available = true;

    private volatile String unavailableReason = "";

    /** 第几次调用开始抛异常（1 起算）；0 表示不抛 */
    private volatile int failFromCall = 0;

    private volatile LlmException failure;

    static ScriptedLlmClient of(LlmResponse... responses) {
        ScriptedLlmClient client = new ScriptedLlmClient();
        for (LlmResponse response : responses) {
            client.script.add(response);
        }
        return client;
    }

    /** 造一个「请求工具调用」的响应 */
    static LlmResponse toolCall(String id, String toolName, String argumentsJson) {
        return new LlmResponse(null,
                List.of(new ToolCallRequest(id, toolName, argumentsJson)),
                "tool_calls", 100, 20, "{}");
    }

    static LlmResponse toolCalls(String finishReason, ToolCallRequest... calls) {
        return new LlmResponse(null, List.of(calls), finishReason, 100, 20, "{}");
    }

    static ToolCallRequest call(String id, String name, String argumentsJson) {
        return new ToolCallRequest(id, name, argumentsJson);
    }

    /** 造一个「收尾」的响应 */
    static LlmResponse finalAnswer(String content) {
        return new LlmResponse(content, List.of(), "stop", 100, 50, "{}");
    }

    ScriptedLlmClient unavailable(String reason) {
        this.available = false;
        this.unavailableReason = reason;
        return this;
    }

    ScriptedLlmClient failFrom(int callNumber, LlmException exception) {
        this.failFromCall = callNumber;
        this.failure = exception;
        return this;
    }

    @Override
    public boolean available() {
        return available;
    }

    @Override
    public String unavailableReason() {
        return unavailableReason;
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
        int callNumber = calls.incrementAndGet();
        requests.add(request);
        if (failFromCall > 0 && callNumber >= failFromCall) {
            throw failure == null ? new LlmException("脚本化的失败", false) : failure;
        }
        int index = callNumber - 1;
        if (index < script.size()) {
            return script.get(index);
        }
        // 脚本用完：给一个空白收尾（让循环走「模型返回空内容」那条分支）。
        // 刻意不抛异常：循环的健壮性不该依赖脚本恰好够长。
        return new LlmResponse("", List.of(), "stop", null, null, "{}");
    }

    int callCount() {
        return calls.get();
    }

    List<LlmRequest> requests() {
        return List.copyOf(requests);
    }

    LlmRequest request(int oneBasedIndex) {
        return requests.get(oneBasedIndex - 1);
    }

    /** 第 n 轮请求里的消息角色序列（断言「tool 消息有没有回」最直观的形态） */
    List<String> rolesOf(int oneBasedIndex) {
        return request(oneBasedIndex).messages().stream().map(m -> m.role().name()).toList();
    }

    /** 第 n 轮请求里所有 role=tool 的消息正文 */
    List<String> toolTextsOf(int oneBasedIndex) {
        return request(oneBasedIndex).messages().stream()
                .filter(m -> m.role() == ChatMessage.Role.TOOL)
                .map(ChatMessage::content)
                .toList();
    }

    /** 第 n 轮请求里所有 role=tool 的 toolCallId */
    List<String> toolCallIdsOf(int oneBasedIndex) {
        return request(oneBasedIndex).messages().stream()
                .filter(m -> m.role() == ChatMessage.Role.TOOL)
                .map(ChatMessage::toolCallId)
                .toList();
    }

    /** 第 n 轮请求里的 tools 数组（断言白名单下发的是哪一份） */
    List<java.util.Map<String, Object>> toolsOf(int oneBasedIndex) {
        return request(oneBasedIndex).tools();
    }
}
