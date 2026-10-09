package com.dustikun.seckill.monitor.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * LLM 接入配置（{@code seckill.monitor.llm.*}，SPEC 第 18 节与可行性报告冲突 1 的落点）。
 *
 * <h2>为什么是「手写 OpenAI 兼容客户端」而不是 Spring AI</h2>
 * <p>
 * 本地 Maven 仓库里的 Spring AI 是 1.1.8，它依赖 Spring Boot 3.5.15（Spring Framework 6），
 * 而本项目是 Spring Boot 4.0.8（Framework 7）。这与 README 里记录的 RocketMQ 情形同构：
 * 强行引入会把 Framework 6 拖进 classpath，启动即失败。
 * 本项目的 LLM 交互只有两件事 —— 发一轮 {@code messages + tools}、解析 {@code tool_calls} ——
 * 用 JDK 21 自带的 {@link java.net.http.HttpClient} 加 Jackson 就够了，
 * 换来的是零版本冲突，以及 ReAct 循环、次数上限、参数校验、结果截断全部<b>显式可见</b>。
 *
 * <h2>{@code api-key} 的默认值是空字符串，这是刻意的</h2>
 * <p>
 * 空值时 {@link LlmClient} 装成 {@link UnavailableLlmClient}，Agent 进入
 * 「未配置 LLM」的降级态：仍然接收并落库 Alert、仍然做 Incident 聚合，
 * 只是不产出 Diagnosis。收益有两条，都不是「省事」：
 * <ol>
 *   <li>没有 Key 的人可以直接启动项目、跑通监控链路、看到告警 ——
 *       否则「想看一眼告警」的前提是先申请一个 API Key；</li>
 *   <li>它顺手把 SPEC 第 18 节「AI 失败 ≠ 监控失败」这条硬约束变成了<b>默认可复现</b>的状态，
 *       而不是一条只在故障时才被验证的声明。</li>
 * </ol>
 * 真实 Key 只通过环境变量注入（{@code DEEPSEEK_API_KEY}），不写进任何被提交的文件。
 *
 * <h2>为什么每个字段都是包装类型</h2>
 * <p>
 * 同 {@link com.dustikun.seckill.monitor.tool.ToolProperties}：构造器绑定在配置项缺失时
 * 给 {@code null} 而不抛异常，用基本类型会在「配置没写」时直接 NPE，
 * 而那个异常与根因看起来毫无关系。默认 profile（不带 docker）下整段配置都不存在，
 * 因此 {@link #normalized()} 必须对「整段缺失」也成立。
 *
 * @param enabled            LLM 总开关。置 false 与「api-key 为空」进入同一个降级态，
 *                           但对读者更直白：前者是「我不想用」，后者是「我还没配」
 * @param baseUrl            OpenAI 兼容端点。默认 DeepSeek；
 *                           换成 OpenAI / 通义 / Kimi / 本地 vLLM 只改这一行与 {@code model}
 * @param chatPath           会话补全路径。默认 {@code /chat/completions}
 * @param model              模型名。默认 {@code deepseek-chat}
 * @param apiKey             密钥。默认空 = 降级态。**只从环境变量注入**
 * @param timeoutMs          单次请求超时。SPEC 第 18 节要求「超时 → 重试 1~2 次 → 失败则保存原始 Alert」
 * @param connectTimeoutMs   建连超时。它必须小于 {@code timeoutMs}：
 *                           否则「连不上」与「推理慢」在日志里是同一条信息，而处理方式完全不同
 * @param maxRetries         重试次数（不含首次）。只对可重试的失败生效：超时、5xx、429
 * @param retryBackoffMs     首次重试前的等待；之后指数退避。0 表示不等待（测试用）
 * @param maxTokens          输出上限。限制它的目的不是省钱，而是限制 Agent 写出超长无效输出
 * @param temperature        采样温度。取低值是刻意的：诊断需要的是<b>可复现的推理</b>，
 *                           温度高会让同一份证据在两次诊断里得出不同结论
 */
@ConfigurationProperties(prefix = "seckill.monitor.llm")
public record MonitorLlmProperties(
        Boolean enabled,
        String baseUrl,
        String chatPath,
        String model,
        String apiKey,
        Integer timeoutMs,
        Integer connectTimeoutMs,
        Integer maxRetries,
        Integer retryBackoffMs,
        Integer maxTokens,
        Double temperature
) {

    private static final boolean DEFAULT_ENABLED = true;

    private static final String DEFAULT_BASE_URL = "https://api.deepseek.com/v1";

    private static final String DEFAULT_CHAT_PATH = "/chat/completions";

    private static final String DEFAULT_MODEL = "deepseek-chat";

    /**
     * 默认超时 30s。
     * <p>【它与 {@code agent.max-duration-ms}（60s）的关系】30s 是「一次带工具结果的推理」的
     * 合理上界：更短会把正常的慢推理误判成故障，更长会让单次诊断突破 SPEC 第 25 节的
     * 「单次诊断 &lt; 10s」目标。整次诊断的墙钟熔断是 60s，因此一次 LLM 超时（含重试）
     * 有可能吃掉整次诊断的预算 —— 这是有意的：先保证「拿不到结论时给出原因」，
     * 而不是「为了给出结论把时间全花完」。
     */
    private static final int DEFAULT_TIMEOUT_MS = 30_000;

    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 5_000;

    private static final int DEFAULT_MAX_RETRIES = 2;

    private static final int DEFAULT_RETRY_BACKOFF_MS = 1000;

    private static final int DEFAULT_MAX_TOKENS = 4096;

    private static final double DEFAULT_TEMPERATURE = 0.1;

    /**
     * 逐项补默认值。见类注释「为什么每个字段都是包装类型」。
     * <p>它对「整段配置缺失」与「单个字段缺失」走同一条路径 ——
     * 这一点是批次 2 在 {@code ToolProperties} 上踩过的坑：
     * 初版写成 {@code segment == null ? new Segment(null, ...) : ...}，
     * 于是整段缺失时补默认值这一步被整个跳过，真正的 NPE 出现在后面拆箱处。
     */
    public MonitorLlmProperties normalized() {
        return new MonitorLlmProperties(
                enabled == null ? DEFAULT_ENABLED : enabled,
                blankToDefault(baseUrl, DEFAULT_BASE_URL),
                blankToDefault(chatPath, DEFAULT_CHAT_PATH),
                blankToDefault(model, DEFAULT_MODEL),
                apiKey == null ? "" : apiKey.trim(),
                clamp(timeoutMs, 1000, 300_000, DEFAULT_TIMEOUT_MS),
                clamp(connectTimeoutMs, 200, 60_000, DEFAULT_CONNECT_TIMEOUT_MS),
                clamp(maxRetries, 0, 5, DEFAULT_MAX_RETRIES),
                clamp(retryBackoffMs, 0, 60_000, DEFAULT_RETRY_BACKOFF_MS),
                clamp(maxTokens, 128, 32_768, DEFAULT_MAX_TOKENS),
                clampTemperature(temperature));
    }

    /**
     * 是否具备调用 LLM 的条件。
     * <p>判据只有「开关打开」且「密钥非空」两条 —— 刻意<b>不做</b>一次连通性探测：
     * 启动时探测会在没有网络的环境里让应用启不来，也把「配置对不对」与
     * 「端点此刻通不通」两件事混成一件。真正的连通性由第一次诊断验证，
     * 且失败是任务级而不是应用级的（SPEC 第 18 节）。
     */
    public boolean configured() {
        return Boolean.TRUE.equals(enabled) && apiKey != null && !apiKey.isBlank();
    }

    /** 降级原因（写进任务失败原因与启动日志，让「为什么没有诊断」不用猜） */
    public String unavailableReason() {
        if (!Boolean.TRUE.equals(enabled)) {
            return "LLM 已在配置里关闭（seckill.monitor.llm.enabled=false）";
        }
        return "未配置 LLM 密钥（环境变量 DEEPSEEK_API_KEY 为空）";
    }

    /**
     * 给日志用的密钥指纹：只留末 4 位。
     * <p>【为什么连前 3 位都不给】{@code sk-} 是 DeepSeek 密钥的固定前缀，
     * 打印它等于确认「这里有一个真 Key」，但没有任何诊断价值。
     * 末 4 位则能回答一个真实的问题：「我换上去的是不是我以为的那把」。
     */
    public String apiKeyFingerprint() {
        if (apiKey == null || apiKey.isBlank()) {
            return "(empty)";
        }
        if (apiKey.length() <= 4) {
            return "****";
        }
        return "****" + apiKey.substring(apiKey.length() - 4);
    }

    /** 拼接完整端点，并抹掉两侧多余的 {@code /}（否则会出现 {@code https://host//chat/completions}） */
    public String endpoint() {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String path = chatPath.startsWith("/") ? chatPath : "/" + chatPath;
        return base + path;
    }

    private static int clamp(Integer value, int min, int max, int fallback) {
        if (value == null) {
            return fallback;
        }
        return Math.max(min, Math.min(max, value));
    }

    private static double clampTemperature(Double value) {
        if (value == null || value.isNaN()) {
            return DEFAULT_TEMPERATURE;
        }
        // 下限取 0 而不是 0.1：0 是「完全贪心」，它在这类任务里是合理的，
        // 而把它抬到 0.1 就等于偷偷改掉了调用方明确要求的确定性。
        return Math.max(0.0, Math.min(2.0, value));
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
