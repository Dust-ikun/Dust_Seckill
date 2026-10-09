package com.dustikun.seckill.monitor.agent;

/**
 * 模型请求的一次函数调用（OpenAI 兼容协议里 assistant 消息的 {@code tool_calls[]} 元素）。
 *
 * <h2>为什么 {@code arguments} 是字符串而不是 Map</h2>
 * <p>
 * 因为模型返回的就是一个<b>字符串</b>（{@code "arguments":"{\"metric\":\"http_p99\"}"}），
 * 而它<b>完全可能是坏的 JSON</b>：被 {@code max_tokens} 截断、混进中文引号、
 * 或者干脆是 {@code {"metric": }}。
 * <p>
 * 若在解析响应时就把它变成 {@code Map}，那一步失败就只剩两个选择：
 * 抛异常（整次诊断中断，而模型可能只是这一个小工具的参数写错了）
 * 或者传一个空 Map（工具会拿默认参数执行，于是<b>轨迹里留下一次「成功」的调用，
 * 但它查的东西根本不是模型想要的</b> —— 这是最坏的形态：结论有证据，
 * 证据是假的）。
 * <p>
 * 因此这里保留原始串，把「解析失败」这件事推迟到 {@code AgentExecutor} 里显式处理：
 * 它会被记成一次 {@code REJECTED} 的调用并回灌给模型让它重试。
 * 字符串长度上限由 {@link #MAX_ARGUMENTS_CHARS} 兜住，防止一个畸形响应把
 * 日志与数据库写爆。
 *
 * @param id             调用 id。回灌 {@code role=tool} 消息时必须原样带回，
 *                       否则模型无法把结果与自己的请求对应起来
 * @param name           函数名（工具名）
 * @param argumentsJson  原始参数串。可能为空白（无参调用），也可能不是合法 JSON
 */
public record ToolCallRequest(String id, String name, String argumentsJson) {

    /**
     * 单个调用的参数串上限。
     * <p>取 8000 与 {@code seckill.monitor.tool.max-result-chars} 同量级是刻意的：
     * 入参与出参是同一场对话的两侧，任何一侧能写出 10 万字符都意味着上限没生效。
     * 而模型正常生成的参数是几十个字符 —— 撞上这个上限必然是出了别的问题
     * （例如它在 {@code arguments} 里写了一段解释性散文），那时截断比放行更有价值。
     */
    public static final int MAX_ARGUMENTS_CHARS = 8000;

    public ToolCallRequest {
        id = id == null ? "" : id.trim();
        name = name == null ? "" : name.trim();
        argumentsJson = truncate(argumentsJson);
    }

    /** 是否是一个「有名字」的调用 —— 无名调用只可能是响应解析出了别的问题 */
    public boolean named() {
        return !name.isEmpty();
    }

    /** 没有参数串（无参调用）还是坏 JSON，交给调用方判断，这里只报告事实 */
    public boolean blankArguments() {
        return argumentsJson.isEmpty();
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= MAX_ARGUMENTS_CHARS
                ? value
                : value.substring(0, MAX_ARGUMENTS_CHARS);
    }
}
