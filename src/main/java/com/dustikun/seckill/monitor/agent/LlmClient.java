package com.dustikun.seckill.monitor.agent;

/**
 * LLM 客户端（OpenAI 兼容的 {@code /chat/completions}）。
 *
 * <h2>为什么这是一个接口，而不是一个具体的 HTTP 类</h2>
 * <p>
 * 三个使用者需要三种实现，而它们都不是「为了测试而测试」的产物：
 * <ol>
 *   <li>{@link OpenAiCompatibleLlmClient}：真实调用；</li>
 *   <li>{@link UnavailableLlmClient}：没配密钥时的降级态（它是<b>生产</b>路径，
 *       不是测试替身 —— 没有 Key 的人跑起来走的就是它）；</li>
 *   <li>测试里的脚本化实现：让「三轮工具调用」「参数是坏 JSON」「返回非 JSON 正文」
 *       这些分支可以在<b>离线</b>条件下被断言。若没有这个接口，
 *       这些分支就只能靠真实 LLM 去碰，而它们恰恰是真实 LLM 最难稳定复现的。</li>
 * </ol>
 *
 * <h2>契约上的两条硬要求</h2>
 * <ul>
 *   <li><b>不抛 {@link LlmException} 以外的异常</b>。调用方（ReAct 循环）只需要处理一种失败形态；
 *       让 IOException / JacksonException 漏出去，意味着每个调用点都要写三个 catch，
 *       而漏掉的那个必然是「将来新加的调用点」。</li>
 *   <li><b>重试在实现内部完成</b>（SPEC 第 18 节的「Retry 1~2 次」）。
 *       放在循环里重试会让「一次 LLM 往返」的计数与 {@code max-tool-calls} 混淆，
 *       而后者是<b>工具调用</b>的次数上限，不是 LLM 往返的次数上限。</li>
 * </ul>
 */
public interface LlmClient {

    /**
     * 是否具备调用条件（开关打开且密钥非空）。
     * <p>调用方应当先问这一句再调用 —— 这样「没配 LLM」在轨迹里是一条<b>明确的降级原因</b>，
     * 而不是一条看起来像网络故障的超时记录。
     */
    boolean available();

    /** 不可用的原因（装配时已确定，不需要网络往返）。可用时返回空串 */
    String unavailableReason();

    /**
     * 发一轮对话。
     *
     * @param request 请求（含 {@code messages} 与 {@code tools}）
     * @return 回复，永不为 {@code null}
     * @throws LlmException 调用失败（已按 SPEC 第 18 节重试过）
     */
    LlmResponse chat(LlmRequest request) throws LlmException;
}
