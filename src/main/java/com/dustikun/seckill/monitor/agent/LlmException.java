package com.dustikun.seckill.monitor.agent;

/**
 * LLM 调用失败。SPEC 第 18 节要求「LLM 不得成为核心业务单点故障」，
 * 因此这个异常<b>永远只在 AI 侧被捕获</b>，绝不允许传播到业务链路 ——
 * 它存在的意义是把「为什么没有诊断」变成一条可落库、可展示的事实。
 *
 * <h2>为什么带一个 {@link #retryable()} 标志</h2>
 * <p>
 * 因为「重试」这个动作对不同失败是相反的操作：
 * <ul>
 *   <li><b>可重试</b>：超时、连接失败、429（限流）、5xx。它们描述的是「此刻不行」，
 *       重试是唯一正确的反应，且 SPEC 第 18 节明确要求重试 1~2 次；</li>
 *   <li><b>不可重试</b>：400（请求体不合法）、401（密钥错）、403、404（模型名错）。
 *       它们是「请求本身有问题」，重试只是把同一个错误再犯两次，
 *       还额外花掉两份配额与两倍等待时间。</li>
 * </ul>
 * 把这条判断放在<b>异常产生的地方</b>（也就是真正看到 HTTP 状态码的那一层），
 * 而不是让重试循环去猜 —— 猜的结果一定是「全都重试」。
 */
public class LlmException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final boolean retryable;

    /** HTTP 状态码；非 HTTP 失败（超时/连接/解析）为 0 */
    private final int statusCode;

    public LlmException(String message, boolean retryable) {
        this(message, retryable, 0, null);
    }

    public LlmException(String message, boolean retryable, int statusCode, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
        this.statusCode = statusCode;
    }

    public boolean retryable() {
        return retryable;
    }

    public int statusCode() {
        return statusCode;
    }

    /**
     * 按 HTTP 状态码判定可重试性。
     * <p>这是全项目<b>唯一</b>一处做这个判断的地方 —— 客户端、测试与文档都引用它，
     * 避免「测试里按 500 可重试写、实现里按 500 不可重试写」这种谁也发现不了的偏差。
     */
    public static boolean retryableStatus(int status) {
        if (status == 429) {
            return true;
        }
        return status >= 500 && status <= 599;
    }

    /** 给日志与任务失败原因用的一句话（不带堆栈） */
    public String brief() {
        String code = statusCode == 0 ? "" : "HTTP " + statusCode + "：";
        return code + getMessage();
    }
}
