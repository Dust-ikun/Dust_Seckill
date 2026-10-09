package com.dustikun.seckill.monitor.agent;

/**
 * 「未配置 LLM」的降级实现（SPEC 第 18 节「LLM 不可用」）。
 *
 * <h2>它不是测试替身，它是生产路径</h2>
 * <p>
 * {@code seckill.monitor.llm.api-key} 的默认值是空字符串，因此<b>任何</b>没有配
 * {@code DEEPSEEK_API_KEY} 的部署（包括 CI、包括别人第一次 clone 下来直接启动）
 * 走的都是这个实现。它要保证的事情只有一件：
 *
 * <pre>
 *   AI 失败 ≠ 监控失败
 * </pre>
 *
 * 具体到行为上就是：{@code POST /api/ai/alerts} 依然收下告警、依然落库、
 * 依然做 Incident 聚合、依然可以被查询 API 读到；只是任务最终是
 * {@code FAILED} 并且原因写得很具体，而不是抛一个
 * 「Connection refused」让人以为网络坏了。
 *
 * <h2>为什么 {@link #chat} 抛异常而不是返回一个空回复</h2>
 * <p>
 * 返回空回复会让 ReAct 循环走到「模型没有工具调用、正文也是空的」这条分支，
 * 于是任务被标成 {@code INSUFFICIENT_EVIDENCE}（证据不足）——
 * 而事实是<b>根本没有问过模型</b>。这两件事对排查者是相反的方向：
 * 前者要去调 prompt，后者要去看环境变量。
 */
public final class UnavailableLlmClient implements LlmClient {

    private final String reason;

    public UnavailableLlmClient(String reason) {
        this.reason = reason == null || reason.isBlank() ? "LLM 不可用" : reason;
    }

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public String unavailableReason() {
        return reason;
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
        // 不可重试：这不是「此刻不行」，而是「这台机器根本没有这个能力」。
        throw new LlmException(reason, false);
    }
}
