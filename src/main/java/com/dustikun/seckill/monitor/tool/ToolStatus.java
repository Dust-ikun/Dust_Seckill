package com.dustikun.seckill.monitor.tool;

/**
 * 一次 Tool 调用的结局（对应 {@code ai_tool_execution.status}，SPEC 第 14.3 节）。
 *
 * <h2>为什么 {@code REJECTED} 必须与 {@code FAILED} 分开</h2>
 * <p>
 * 这两者在排障时指向完全不同的方向：
 * <ul>
 *   <li>{@link #REJECTED} —— <b>Agent 用错了工具</b>：名字不在白名单里、参数越界、
 *       参数类型不对。请求根本没有到达数据源，重试同样的调用只会再被拒一次；
 *       它说明 prompt 里的工具说明需要改，或者模型在编造工具名。</li>
 *   <li>{@link #FAILED} —— <b>工具本身出错了</b>：Prometheus 连不上、SQL 报错、超时。
 *       请求到了数据源但没拿到结果，Agent 应当换一个证据来源或如实说明「证据不足」。</li>
 * </ul>
 * 混成一个状态之后，「为什么这次诊断没有证据」这个问题就答不上来了 ——
 * 而 SPEC 第 14.3 节把这张表定义为「Agent 可解释性的关键」，可解释性的第一层
 * 就是「失败是哪一类失败」。
 *
 * <p>{@code REJECTED} 也是 SPEC 第 24 节安全项「Tool 参数全部经过校验」的<b>可观测证据</b>：
 * 拦截发生时它在轨迹里留了一行，而不是安静地返回一个空结果。
 */
public enum ToolStatus {

    /** 正常返回 */
    SUCCESS,

    /** 白名单或参数校验拦截。**没有触达数据源** */
    REJECTED,

    /** 触达了数据源但执行失败（超时、异常、数据源不可用） */
    FAILED
}
