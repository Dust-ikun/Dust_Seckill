package com.dustikun.seckill.monitor.agent;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OpenAI 兼容的 LLM 客户端（DeepSeek 默认），手写，零新增依赖。
 *
 * <h2>它为什么值得手写而不是引一个 SDK</h2>
 * <p>
 * 本类要做的只有两件事：把 {@link LlmRequest} 发出去、把 {@code tool_calls} 读回来。
 * 而它必须<b>显式</b>做对的事情有四件，每一件都是「引了 SDK 之后反而看不见」的：
 * <ol>
 *   <li>按 SPEC 第 18 节重试（且只重试可重试的失败，见 {@link LlmException#retryableStatus}）；</li>
 *   <li>把「超时」与「密钥错」区分开 —— 前者要重试，后者重试三次只是浪费三份配额；</li>
 *   <li>保留<b>原始响应体</b>，它是 {@code ai_diagnosis_result.raw_result} 的来源，
 *       也是「Schema 校验为什么失败」唯一可复查的证据；</li>
 *   <li>失败降噪：一次诊断会发 3~5 轮请求，同一故障若每轮都打完整堆栈，
 *       日志会被同一条信息淹掉（同 {@code HttpPrometheusQuerier} 的处理）。</li>
 * </ol>
 *
 * <h2>三个实测得来的实现细节</h2>
 * <ul>
 *   <li><b>{@code arguments} 是字符串</b>，不是对象。直接放 Map 会被序列化成嵌套对象，
 *       服务端会以 400 拒绝或读成空参数（见 {@link ChatMessage#toWire()}）。</li>
 *   <li><b>{@code content} 允许缺失</b>：模型请求工具调用时 {@code content} 常常是 {@code null}，
 *       此时 {@code path("content").isTextual()} 为 false，必须当作「没有正文」而不是空串错误。</li>
 *   <li><b>200 也可能是坏的</b>：正常协议下解析失败<b>不重试</b> ——
 *       同一个请求再发一次会得到同一个坏响应，重试只是把失败推迟三倍时间。
 *       这一点与 HTTP 层失败（超时/5xx）是<b>相反</b>的判断，因此分开处理。</li>
 * </ul>
 */
public final class OpenAiCompatibleLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleLlmClient.class);

    /** 失败原因里保留的响应体长度。它是唯一能区分「模型名写错」与「服务端异常」的证据 */
    private static final int ERROR_BODY_CHARS = 300;

    private final MonitorLlmProperties properties;

    private final ObjectMapper objectMapper;

    private final HttpClient httpClient;

    private final String endpoint;

    /** 「已报过失败」标志，避免同一故障刷满日志。恢复成功时复位 */
    private final AtomicBoolean failureLogged = new AtomicBoolean(false);

    public OpenAiCompatibleLlmClient(MonitorLlmProperties properties, ObjectMapper objectMapper) {
        this.properties = properties.normalized();
        this.objectMapper = objectMapper;
        this.endpoint = this.properties.endpoint();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(this.properties.connectTimeoutMs()))
                // 不跟随重定向：LLM 端点的 302 通常意味着 base-url 写错了
                // （例如少了 /v1）。跟随它只会把请求发到一个不认识的地址上，
                // 而错误消息会变成「响应格式不对」，与根因隔了一层。
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public String unavailableReason() {
        return "";
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
        String body;
        try {
            body = objectMapper.writeValueAsString(request.toWire());
        } catch (JacksonException e) {
            // 请求体序列化失败意味着我们自己构造的对象有问题：重试没有意义。
            throw new LlmException("请求体序列化失败：" + e.getMessage(), false, 0, e);
        }

        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofMillis(properties.timeoutMs()))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + properties.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        LlmException last = null;
        int attempts = properties.maxRetries() + 1;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                HttpResponse<String> response = httpClient.send(httpRequest,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() == 200) {
                    return parse(response.body(), attempt);
                }
                boolean retryable = LlmException.retryableStatus(response.statusCode());
                last = new LlmException(
                        "HTTP " + response.statusCode() + "：" + abbreviate(response.body()),
                        retryable, response.statusCode(), null);
                if (!retryable) {
                    // 不可重试的失败立刻上报：多试两次不会变好，只会让「诊断失败」
                    // 这个结论晚 2 倍时间到，还可能把配额耗尽。
                    break;
                }
            } catch (HttpTimeoutException e) {
                last = new LlmException("请求超时（上限 " + properties.timeoutMs() + "ms）", true, 0, e);
            } catch (IOException e) {
                last = new LlmException("网络失败：" + e, true, 0, e);
            } catch (InterruptedException e) {
                // 恢复中断标志：吞掉它会让上层（应用优雅停机、ReAct 的墙钟熔断）
                // 失去感知，而症状是「进程停不下来」。
                Thread.currentThread().interrupt();
                throw new LlmException("调用被中断", false, 0, e);
            }
            if (attempt < attempts) {
                sleepBeforeRetry(attempt, last);
            }
        }

        // 到这里 last 必然非空：循环只有「成功返回」或「记录失败」两条路
        LlmException failure = last == null
                ? new LlmException("调用失败且没有记录到原因（实现缺陷）", false)
                : last;
        noteFailure("重试 " + properties.maxRetries() + " 次后仍然失败：" + failure.brief());
        throw failure;
    }

    /**
     * 指数退避。
     * <p>【为什么 0 表示不等待】给它一个「必须大于 0」的下限会让
     * 「重试逻辑」的单测变成秒级起步的慢测试，而慢测试的归宿是被跳过。
     * 0 在这里也是合法的配置值（本地 vLLM / 自建端点通常不需要退避），
     * 因此不做下限保护。
     */
    private void sleepBeforeRetry(int attempt, LlmException cause) {
        long backoff = (long) properties.retryBackoffMs() * (1L << (attempt - 1));
        if (backoff <= 0) {
            return;
        }
        log.warn("[LlmClient] 第 {} 次调用失败，{}ms 后重试：{}", attempt, backoff,
                cause == null ? "未知原因" : cause.brief());
        try {
            Thread.sleep(backoff);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("重试等待被中断", false, 0, e);
        }
    }

    // ================================================================ 响应解析

    /**
     * 解析 200 的响应体。
     * <p>这里的每一条失败都是<b>不可重试</b>的（见类注释最后一条）。
     */
    private LlmResponse parse(String body, int attempt) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (JacksonException e) {
            throw new LlmException("响应不是合法 JSON：" + abbreviate(body), false, 200, e);
        }
        if (root == null || !root.isObject()) {
            throw new LlmException("响应不是 JSON 对象：" + abbreviate(body), false, 200, null);
        }

        // 有些兼容层会用 200 包一个错误对象（老版 OpenAI 的形态）。
        // 不识别它的话，后面会报成「没有 choices」，而真正的原因是模型名或密钥。
        JsonNode error = root.get("error");
        if (error != null && !error.isNull()) {
            String message = error.isObject()
                    ? text(error, "message", error.toString())
                    : error.toString();
            throw new LlmException("服务端返回错误：" + abbreviate(message), false, 200, null);
        }

        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new LlmException("响应里没有 choices：" + abbreviate(body), false, 200, null);
        }
        JsonNode choice = choices.get(0);
        JsonNode message = choice.path("message");

        String content = readText(message.path("content"));
        List<ToolCallRequest> toolCalls = readToolCalls(message.path("tool_calls"));
        String finishReason = text(choice, "finish_reason", null);

        JsonNode usage = root.path("usage");
        Integer promptTokens = readInt(usage.path("prompt_tokens"));
        Integer completionTokens = readInt(usage.path("completion_tokens"));

        if (failureLogged.compareAndSet(true, false)) {
            log.info("[LlmClient] LLM 调用已恢复：{}", endpoint);
        }
        LlmResponse response = new LlmResponse(content, toolCalls, finishReason,
                promptTokens, completionTokens, body);
        log.debug("[LlmClient] 第 {} 次调用成功：{}", attempt, response.describe());
        return response;
    }

    /**
     * 读取模型请求的调用列表。
     * <p>【为什么要跳过没有名字的元素】{@code name} 是执行与回灌时唯一的凭据；
     * 一个没有名字的调用既无法执行、也无法回灌成一条有意义的 tool 消息。
     * 保留它只会让循环里多一条「未注册的 Tool（名字为空）」的噪声记录。
     */
    private static List<ToolCallRequest> readToolCalls(JsonNode toolCalls) {
        if (toolCalls == null || !toolCalls.isArray() || toolCalls.isEmpty()) {
            return List.of();
        }
        List<ToolCallRequest> calls = new ArrayList<>(toolCalls.size());
        for (JsonNode call : toolCalls) {
            JsonNode function = call.path("function");
            String name = text(function, "name", "");
            if (name.isEmpty()) {
                continue;
            }
            calls.add(new ToolCallRequest(
                    text(call, "id", ""),
                    name,
                    text(function, "arguments", "")));
        }
        return calls;
    }

    private void noteFailure(String reason) {
        if (failureLogged.compareAndSet(false, true)) {
            log.warn("[LlmClient] LLM 调用失败（后续相同故障不再重复记录，恢复时会提示）："
                    + "endpoint={}，model={}，原因={}", endpoint, properties.model(), reason);
        } else {
            log.debug("[LlmClient] LLM 调用仍然失败：{}", reason);
        }
    }

    /** 装配期就能打印出来的事实（不发起任何网络请求，见启动自检） */
    public String describe() {
        return "endpoint=" + endpoint
                + "，model=" + properties.model()
                + "，key=" + properties.apiKeyFingerprint()
                + "，超时=" + properties.timeoutMs() + "ms"
                + "（建连 " + properties.connectTimeoutMs() + "ms）"
                + "，重试=" + properties.maxRetries() + " 次（退避 " + properties.retryBackoffMs() + "ms）"
                + "，max_tokens=" + properties.maxTokens()
                + "，temperature=" + properties.temperature();
    }

    private static String readText(JsonNode node) {
        return node != null && node.isTextual() ? node.asString() : null;
    }

    private static String text(JsonNode node, String field, String fallback) {
        if (node == null || !node.isObject()) {
            return fallback;
        }
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asString() : fallback;
    }

    private static Integer readInt(JsonNode node) {
        // 值可能是数字也可能是字符串：兼容层两种都出现过。
        // 必须用 isNumber() 判断再取值，不能直接 asInt() ——
        // asInt() 对非数字会静默返回 0，而「0 tokens」看起来像一条真实数据，比 null 危险。
        if (node == null) {
            return null;
        }
        if (node.isNumber()) {
            return node.asInt();
        }
        if (node.isTextual()) {
            try {
                return Integer.parseInt(node.asString().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replace('\n', ' ').replace('\r', ' ').trim();
        return oneLine.length() <= ERROR_BODY_CHARS
                ? oneLine
                : oneLine.substring(0, ERROR_BODY_CHARS) + "…";
    }

    /** 供测试断言端点拼接（不发起网络请求） */
    public String endpoint() {
        return endpoint;
    }
}
