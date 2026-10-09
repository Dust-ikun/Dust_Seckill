package com.dustikun.seckill.monitor.agent;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 手写 OpenAI 兼容客户端的线协议验收（SPEC 第 18 节的重试、第 9 节的 tools 结构）。
 *
 * <p>全部离线：起一个 JDK 自带的 HTTP 服务端（{@link StubLlmServer}），
 * 读真的请求体、回真的响应体。理由见那个类的注释 ——
 * 这一层要验证的东西恰好就是「我们发出去的字节长什么样」。
 *
 * <p>【为什么重试退避配成 0】退避是为了生产环境的礼貌（避免拥塞时打爆对端）。
 * 测试里保留它会让每条重试用例慢 1 秒起，而慢测试的归宿是被跳过。
 * 0 是合法配置值，见 {@code OpenAiCompatibleLlmClient#sleepBeforeRetry}。
 */
class LlmClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static MonitorLlmProperties props(String baseUrl, int timeoutMs, int maxRetries) {
        return new MonitorLlmProperties(true, baseUrl, "/chat/completions", "test-model",
                "sk-test-key", timeoutMs, 1000, maxRetries, 0, 512, 0.0);
    }

    private static LlmRequest request(List<ChatMessage> messages) {
        return new LlmRequest("test-model", messages, List.of(
                Map.of("type", "function", "function", Map.of("name", "query_metric"))), 0.0, 512);
    }

    // ================================================================ 发出去的字节

    @Test
    @DisplayName("tool_calls 的 arguments 必须序列化成字符串，而不是嵌套对象")
    void toolCallArgumentsAreSerializedAsString() throws Exception {
        try (StubLlmServer server = new StubLlmServer()) {
            // 让客户端先拿到一轮工具调用，这样下一轮请求里就会带上 assistant 的 tool_calls
            server.enqueue(200, StubLlmServer.toolCallReply("query_metric", "{\"metric\":\"http_p99\"}"));
            server.enqueue(200, StubLlmServer.defaultContentReply("{}"));
            OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(
                    props(server.baseUrl(), 5000, 1), MAPPER);

            LlmResponse first = client.chat(request(List.of(ChatMessage.user("开始"))));
            assertEquals(1, first.toolCalls().size());
            ToolCallRequest call = first.toolCalls().get(0);
            assertEquals("query_metric", call.name());
            assertEquals("{\"metric\":\"http_p99\"}", call.argumentsJson());

            client.chat(request(List.of(
                    ChatMessage.user("开始"),
                    ChatMessage.assistant(null, List.of(call)),
                    ChatMessage.tool(call.id(), call.name(), "<untrusted_data>...</untrusted_data>"))));

            // 第二次请求体里，assistant 的 tool_calls[0].function.arguments 必须是字符串
            Map<String, Object> body = MAPPER.readValue(server.lastRequestBody(),
                    new TypeReference<Map<String, Object>>() { });
            List<Map<String, Object>> messages = (List<Map<String, Object>>) body.get("messages");
            Map<String, Object> assistant = messages.stream()
                    .filter(m -> "assistant".equals(m.get("role")))
                    .findFirst().orElseThrow();
            List<Map<String, Object>> calls = (List<Map<String, Object>>) assistant.get("tool_calls");
            Map<String, Object> function = (Map<String, Object>) calls.get(0).get("function");

            assertTrue(function.get("arguments") instanceof String,
                    "arguments 必须是字符串；放 Map 会被服务端 400 或读成空参数。实际="
                            + function.get("arguments").getClass());
            assertEquals(call.id(), calls.get(0).get("id"), "tool_call 的 id 必须原样回灌");
            assertEquals("function", calls.get(0).get("type"));
        }
    }

    @Test
    @DisplayName("tools 非空时必须带 tool_choice=auto；tools 为空时不得发这个字段")
    void toolChoiceIsExplicit() throws Exception {
        try (StubLlmServer server = new StubLlmServer()) {
            OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(
                    props(server.baseUrl(), 5000, 0), MAPPER);

            client.chat(request(List.of(ChatMessage.user("hi"))));
            Map<String, Object> withTools = MAPPER.readValue(server.lastRequestBody(),
                    new TypeReference<Map<String, Object>>() { });
            assertEquals("auto", withTools.get("tool_choice"));
            assertNotNull(withTools.get("tools"));

            client.chat(new LlmRequest("test-model", List.of(ChatMessage.user("hi")),
                    List.of(), 0.0, 512));
            Map<String, Object> withoutTools = MAPPER.readValue(server.lastRequestBody(),
                    new TypeReference<Map<String, Object>>() { });
            assertFalse(withoutTools.containsKey("tool_choice"),
                    "发一个空 tools 数组会让部分实现直接 400，因此宁可不发");
            assertFalse(withoutTools.containsKey("tools"));
        }
    }

    @Test
    @DisplayName("Authorization 头带 Bearer 密钥；端点由 base-url + chat-path 拼出且不出现双斜杠")
    void endpointAndAuthHeaderAreCorrect() throws Exception {
        try (StubLlmServer server = new StubLlmServer()) {
            OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(
                    props(server.baseUrl() + "/", 5000, 0), MAPPER);
            // base-url 结尾多一个斜杠时必须被抹掉，否则会出现 https://host//chat/completions
            assertEquals(server.baseUrl() + "/chat/completions", client.endpoint());
            client.chat(request(List.of(ChatMessage.user("hi"))));
            assertEquals(1, server.requestCount());
        }
    }

    // ================================================================ 解析响应

    @Test
    @DisplayName("带 tool_calls 且 content 缺失的响应能正常解析（模型请求工具时 content 常为 null）")
    void parsesToolCallWithoutContent() throws Exception {
        try (StubLlmServer server = new StubLlmServer()) {
            server.enqueue(200, StubLlmServer.toolCallReply("search_logs", "{\"keyword\":\"timeout\"}"));
            LlmResponse response = client(server, 5000, 0).chat(request(List.of(ChatMessage.user("hi"))));

            assertTrue(response.hasToolCalls());
            assertNull(response.content(), "content 缺失应当解析成 null，而不是空串或报错");
            assertEquals("search_logs", response.toolCalls().get(0).name());
            assertEquals(100, response.promptTokens());
            assertEquals(20, response.completionTokens());
            assertEquals(120, response.totalTokens());
            assertEquals("tool_calls", response.finishReason());
        }
    }

    @Test
    @DisplayName("没有名字的 tool_call 被跳过（它既无法执行也无法回灌）")
    void skipsUnnamedToolCalls() throws Exception {
        try (StubLlmServer server = new StubLlmServer()) {
            server.enqueue(200, "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,"
                    + "\"tool_calls\":[{\"id\":\"a\",\"type\":\"function\",\"function\":{\"arguments\":\"{}\"}},"
                    + "{\"id\":\"b\",\"type\":\"function\",\"function\":{\"name\":\"query_db\","
                    + "\"arguments\":\"{}\"}}]},\"finish_reason\":\"tool_calls\"}]}");
            LlmResponse response = client(server, 5000, 0).chat(request(List.of(ChatMessage.user("hi"))));

            assertEquals(1, response.toolCalls().size());
            assertEquals("query_db", response.toolCalls().get(0).name());
        }
    }

    @Test
    @DisplayName("finish_reason=length 被视为「输出被 max_tokens 截断」")
    void detectsLengthTruncation() throws Exception {
        try (StubLlmServer server = new StubLlmServer()) {
            server.enqueue(200, "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"root\"},"
                    + "\"finish_reason\":\"length\"}]}");
            LlmResponse response = client(server, 5000, 0).chat(request(List.of(ChatMessage.user("hi"))));
            assertTrue(response.truncatedByLength());
        }
    }

    // ================================================================ 重试

    @Test
    @DisplayName("500 → 重试一次后成功（SPEC 第 18 节的 Retry 1~2 次）")
    void retriesOnServerError() throws Exception {
        try (StubLlmServer server = new StubLlmServer()) {
            server.enqueue(StubLlmServer.Reply.status(500));
            server.enqueue(200, StubLlmServer.defaultContentReply("结论"));
            LlmResponse response = client(server, 5000, 2).chat(request(List.of(ChatMessage.user("hi"))));

            assertEquals(2, server.requestCount(), "应当恰好重试一次");
            assertEquals("结论", response.content());
        }
    }

    @Test
    @DisplayName("429（限流）也重试")
    void retriesOnTooManyRequests() throws Exception {
        try (StubLlmServer server = new StubLlmServer()) {
            server.enqueue(StubLlmServer.Reply.status(429));
            server.enqueue(200, StubLlmServer.defaultContentReply("结论"));
            client(server, 5000, 1).chat(request(List.of(ChatMessage.user("hi"))));
            assertEquals(2, server.requestCount());
        }
    }

    @Test
    @DisplayName("★ 400 不重试：重试一个「请求本身有问题」的调用只是再犯两次同样的错")
    void doesNotRetryOnClientError() throws Exception {
        try (StubLlmServer server = new StubLlmServer()) {
            server.enqueue(StubLlmServer.Reply.status(400));
            OpenAiCompatibleLlmClient client = client(server, 5000, 2);

            LlmException e = assertThrows(LlmException.class,
                    () -> client.chat(request(List.of(ChatMessage.user("hi")))));
            assertEquals(400, e.statusCode());
            assertFalse(e.retryable());
            assertEquals(1, server.requestCount(), "4xx 必须只请求一次");
        }
    }

    @Test
    @DisplayName("重试次数用尽后抛出可重试的失败，并带上响应体摘要（诊断「模型名写错」的唯一证据）")
    void exhaustsRetriesAndKeepsBodyExcerpt() throws Exception {
        try (StubLlmServer server = new StubLlmServer()) {
            server.enqueue(503, "{\"error\":{\"message\":\"模型不存在\"}}");
            server.enqueue(503, "{\"error\":{\"message\":\"模型不存在\"}}");
            OpenAiCompatibleLlmClient client = client(server, 5000, 1);

            LlmException e = assertThrows(LlmException.class,
                    () -> client.chat(request(List.of(ChatMessage.user("hi")))));
            assertTrue(e.retryable());
            assertEquals(503, e.statusCode());
            assertEquals(2, server.requestCount());
            assertTrue(e.getMessage().contains("模型不存在"), e.getMessage());
        }
    }

    @Test
    @DisplayName("超时是可重试的失败，且错误消息里带上超时上限")
    void timeoutIsRetryable() throws Exception {
        try (StubLlmServer server = new StubLlmServer()) {
            // 【为什么服务端要睡 1500ms 而不是 800ms】因为 normalized() 把超时下限钳在 1000ms：
            // 传 200 会生效成 1000。这个下限本身是有意的（见下面 clampFloor 那条用例），
            // 因此这里必须让服务端比下限更慢，否则测的是「请求成功了」。
            server.enqueue(new StubLlmServer.Reply(200,
                    StubLlmServer.defaultContentReply("慢"), 1500));
            OpenAiCompatibleLlmClient client = client(server, 200, 0);

            LlmException e = assertThrows(LlmException.class,
                    () -> client.chat(request(List.of(ChatMessage.user("hi")))));
            assertTrue(e.retryable(), "超时必须可重试（SPEC 第 18 节）");
            assertTrue(e.getMessage().contains("超时"), e.getMessage());
            assertTrue(e.getMessage().contains("1000"), "错误消息要带上真实生效的上限：" + e.getMessage());
        }
    }

    @Test
    @DisplayName("超时与重试次数都有下限/上限钳制：配置写歪了也不会变成「不超时」或「重试 100 次」")
    void clampsTimeoutsAndRetries() {
        MonitorLlmProperties tooSmall = new MonitorLlmProperties(true, null, null, null, "k",
                1, 1, 99, -5, 1, 5.0).normalized();
        assertEquals(1000, tooSmall.timeoutMs(), "超时下限 1000ms：再小会把正常推理误判成故障");
        assertEquals(200, tooSmall.connectTimeoutMs());
        assertEquals(5, tooSmall.maxRetries(), "重试上限 5 次");
        assertEquals(0, tooSmall.retryBackoffMs(), "退避可以为 0（本地端点不需要）");
        assertEquals(128, tooSmall.maxTokens());
        assertEquals(2.0, tooSmall.temperature(), "温度上限 2.0");

        MonitorLlmProperties empty = new MonitorLlmProperties(null, null, null, null, null,
                null, null, null, null, null, null).normalized();
        assertEquals("https://api.deepseek.com/v1", empty.baseUrl());
        assertEquals("deepseek-chat", empty.model());
        assertEquals(30_000, empty.timeoutMs());
        assertEquals(2, empty.maxRetries());
        assertEquals(0.1, empty.temperature());
        assertFalse(empty.configured(), "api-key 默认空 → 降级态");
    }

    // ================================================================ 坏响应

    @Test
    @DisplayName("200 但正文不是 JSON → 不可重试（同一个请求再发一次会得到同一个坏响应）")
    void malformedSuccessBodyIsNotRetryable() throws Exception {
        try (StubLlmServer server = new StubLlmServer()) {
            server.enqueue(200, "这不是 JSON");
            OpenAiCompatibleLlmClient client = client(server, 5000, 2);

            LlmException e = assertThrows(LlmException.class,
                    () -> client.chat(request(List.of(ChatMessage.user("hi")))));
            assertFalse(e.retryable());
            assertEquals(1, server.requestCount());
        }
    }

    @Test
    @DisplayName("200 但 choices 为空 → 不可重试")
    void emptyChoicesIsNotRetryable() throws Exception {
        try (StubLlmServer server = new StubLlmServer()) {
            server.enqueue(200, "{\"choices\":[]}");
            OpenAiCompatibleLlmClient client = client(server, 5000, 2);
            LlmException e = assertThrows(LlmException.class,
                    () -> client.chat(request(List.of(ChatMessage.user("hi")))));
            assertFalse(e.retryable());
            assertTrue(e.getMessage().contains("choices"), e.getMessage());
        }
    }

    @Test
    @DisplayName("200 里包着 error 对象（老版 OpenAI 形态）→ 报出真实原因，而不是「没有 choices」")
    void detectsErrorObjectInsideSuccessBody() throws Exception {
        try (StubLlmServer server = new StubLlmServer()) {
            server.enqueue(200, "{\"error\":{\"message\":\"Invalid API key\"}}");
            LlmException e = assertThrows(LlmException.class,
                    () -> client(server, 5000, 0).chat(request(List.of(ChatMessage.user("hi")))));
            assertTrue(e.getMessage().contains("Invalid API key"), e.getMessage());
        }
    }

    // ================================================================ 降级态

    @Test
    @DisplayName("★ 未配置密钥时是「不可用」而不是「抛连接错误」：降级态的报错必须能区分开")
    void unavailableClientExplainsItself() throws Exception {
        MonitorLlmProperties properties = new MonitorLlmProperties(true, "http://127.0.0.1:1/v1",
                "/chat/completions", "test-model", "  ", 5000, 1000, 0, 0, 512, 0.0);
        assertFalse(properties.configured());
        LlmClient client = new UnavailableLlmClient(properties.normalized().unavailableReason());

        assertFalse(client.available());
        assertTrue(client.unavailableReason().contains("DEEPSEEK_API_KEY"), client.unavailableReason());
        LlmException e = assertThrows(LlmException.class,
                () -> client.chat(request(List.of(ChatMessage.user("hi")))));
        assertFalse(e.retryable(), "「这台机器没配密钥」不该被重试");
    }

    @Test
    @DisplayName("密钥指纹只留末 4 位：连 sk- 前缀都不打印")
    void apiKeyFingerprintHidesPrefix() throws Exception {
        MonitorLlmProperties properties = props("http://localhost", 5000, 0).normalized();
        String fingerprint = properties.apiKeyFingerprint();
        assertEquals("****-key", fingerprint);
        assertFalse(fingerprint.contains("sk-test"), fingerprint);
    }

    private static OpenAiCompatibleLlmClient client(StubLlmServer server, int timeoutMs, int maxRetries) {
        return new OpenAiCompatibleLlmClient(props(server.baseUrl(), timeoutMs, maxRetries), MAPPER);
    }
}
