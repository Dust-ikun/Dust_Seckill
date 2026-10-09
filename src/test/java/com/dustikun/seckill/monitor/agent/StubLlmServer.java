package com.dustikun.seckill.monitor.agent;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 假的 LLM 端点（JDK 自带的 {@link HttpServer}，零依赖、离线可跑）。
 *
 * <h2>为什么必须有它，而不是想办法 Mock 掉 HttpClient</h2>
 * <p>
 * 手写 LLM 客户端要证明的四件事全都发生在<b>线协议</b>这一层：
 * <ul>
 *   <li>{@code tool_calls[].function.arguments} 必须是<b>字符串</b>而不是嵌套对象
 *       —— 写成对象服务端会 400，而错误消息只会说「请求体不合法」；</li>
 *   <li>{@code tool_choice: auto} 必须显式发出去 —— 不发时部分实现会退化成
 *       「不调用工具」，Agent 第一轮就编一个结论出来；</li>
 *   <li>带 {@code tool_calls} 的 assistant 消息里 {@code content} 允许缺失；</li>
 *   <li>429/5xx 要重试、4xx 不能重试。</li>
 * </ul>
 * 这四件事里有三件是「我们发出去的字节长什么样」，Mock 掉 HttpClient
 * 恰好把要验证的东西替换掉了。因此这里起一个真的 HTTP 服务端，读真的请求体。
 */
final class StubLlmServer implements AutoCloseable {

    /** 一次脚本化的响应 */
    record Reply(int status, String body, long delayMs) {

        static Reply ok(String body) {
            return new Reply(200, body, 0L);
        }

        static Reply status(int status) {
            return new Reply(status, "{\"error\":{\"message\":\"stub\"}}", 0L);
        }
    }

    private final HttpServer server;

    private final Queue<Reply> script = new ConcurrentLinkedQueue<>();

    private final List<String> requestBodies = new CopyOnWriteArrayList<>();

    private final AtomicInteger requests = new AtomicInteger();

    StubLlmServer() throws IOException {
        // 端口 0 = 让操作系统分配一个空闲端口。写死端口会在并发跑测试时撞车，
        // 而那种失败看起来像「网络不可用」。
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/v1/chat/completions", this::handle);
        this.server.setExecutor(null);
        this.server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    /** 追加一个脚本响应（按顺序消费）。脚本用完后的默认响应是一个合法的「无工具调用」回复 */
    StubLlmServer enqueue(Reply reply) {
        script.add(reply);
        return this;
    }

    StubLlmServer enqueue(int status, String body) {
        return enqueue(new Reply(status, body, 0L));
    }

    int requestCount() {
        return requests.get();
    }

    List<String> requestBodies() {
        return List.copyOf(requestBodies);
    }

    String lastRequestBody() {
        return requestBodies.isEmpty() ? "" : requestBodies.get(requestBodies.size() - 1);
    }

    private void handle(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        try (InputStream in = exchange.getRequestBody()) {
            requestBodies.add(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        Reply reply = script.poll();
        if (reply == null) {
            reply = Reply.ok(defaultContentReply("收尾：证据不足，无法确定根因"));
        }
        if (reply.delayMs() > 0) {
            try {
                Thread.sleep(reply.delayMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(reply.status(), bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** 造一个「模型直接用一段正文收尾」的响应 */
    static String defaultContentReply(String content) {
        return "{\"id\":\"stub\",\"model\":\"test-model\",\"choices\":[{\"index\":0,"
                + "\"message\":{\"role\":\"assistant\",\"content\":" + jsonString(content) + "},"
                + "\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":50}}";
    }

    /** 造一个「模型请求一次工具调用」的响应 */
    static String toolCallReply(String toolName, String argumentsJson) {
        return toolCallReply("call-1", toolName, argumentsJson, null);
    }

    static String toolCallReply(String id, String toolName, String argumentsJson, String content) {
        String contentPart = content == null ? "" : "\"content\":" + jsonString(content) + ",";
        return "{\"id\":\"stub\",\"model\":\"test-model\",\"choices\":[{\"index\":0,"
                + "\"message\":{\"role\":\"assistant\"," + contentPart
                + "\"tool_calls\":[{\"id\":" + jsonString(id) + ",\"type\":\"function\","
                + "\"function\":{\"name\":" + jsonString(toolName)
                + ",\"arguments\":" + jsonString(argumentsJson) + "}}]},"
                + "\"finish_reason\":\"tool_calls\"}],"
                + "\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":20}}";
    }

    /** 极简 JSON 字符串转义（够测试用：只需处理引号、反斜杠与换行） */
    static String jsonString(String raw) {
        StringBuilder sb = new StringBuilder(raw.length() + 8);
        sb.append('"');
        for (char c : raw.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
