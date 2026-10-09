package com.dustikun.seckill.monitor.core;

/**
 * 诊断任务状态机（SPEC 第 13 节）。
 *
 * <pre>
 *   CREATED → RUNNING → COLLECTING_EVIDENCE → ANALYZING → COMPLETED
 *                  ↘ FAILED                        ↘ INSUFFICIENT_EVIDENCE
 * </pre>
 *
 * <h2>为什么状态要真的写进数据库，而不是只放在内存里</h2>
 * <p>
 * 因为一次诊断要跑几秒到几十秒，而它中间的那些状态回答的是
 * 「Agent 现在卡在哪一步」这个问题：
 * <ul>
 *   <li>停在 {@code RUNNING} → 卡在<b>第一次</b> LLM 往返（端点不通 / 密钥错 / 模型名错）；</li>
 *   <li>停在 {@code COLLECTING_EVIDENCE} → 工具在慢或数据源不可达；</li>
 *   <li>停在 {@code ANALYZING} → 证据齐了但模型不肯收尾（或输出太长被截断）。</li>
 * </ul>
 * 只把最终状态落库，这三个问题就都退化成一个 {@code FAILED} ——
 * 而它们的排查方向完全不同。
 *
 * <h2>为什么没有 {@code RESOLVED}</h2>
 * <p>
 * SPEC 第 13 节给的状态机里没有它，而 {@code ai_diagnosis_task.end_time}
 * 又是靠 Alertmanager 的 resolved 通知填充的（可行性报告冲突 4 的裁定）。
 * 这两件事并不矛盾：<b>「告警恢复了」与「诊断这个动作结束了」是两个正交的事实</b>。
 * 一次诊断可以在告警恢复之后仍然跑完（而且那时它更有价值 ——
 * 它解释的是一段已经过去的时间）。因此 {@code end_time} 由 resolved 通知写，
 * {@code status} 只反映诊断本身的进度。
 */
public enum DiagnosisStatus {

    /** 任务已建，尚未开始（Alert 已落库；也可能因为没配 LLM 而停在这里） */
    CREATED,

    /** 已开始，正在等第一次 LLM 决策 */
    RUNNING,

    /** 已拿到至少一条工具证据，继续取证中 */
    COLLECTING_EVIDENCE,

    /** 证据收集停止，正在让模型给出结构化结论 */
    ANALYZING,

    /** 已产出结构化 Diagnosis */
    COMPLETED,

    /** 执行失败（LLM 不可用/超时/次数用尽/解析不出 JSON） */
    FAILED,

    /** 模型给了结论，但它自己列出的证据撑不住（SPEC 第 10 节规则 3） */
    INSUFFICIENT_EVIDENCE;

    /**
     * 是否已经结束。
     * <p>【谁在用它】Incident 聚合。判据是「这条告警能不能并进那个任务」——
     * 只有在<b>还没结束</b>的任务上并才有意义：并进一个已完成的诊断，
     * 既不会触发新的诊断，也不会改变它的结论，等于把第二次故障悄悄丢掉
     * （它本来该触发一条新的、独立的诊断）。
     */
    public boolean terminal() {
        return this == COMPLETED || this == FAILED || this == INSUFFICIENT_EVIDENCE;
    }

    /** 是否是一条「有结论」的结局 —— 用于日志与指标的口径说明 */
    public boolean concluded() {
        return this == COMPLETED || this == INSUFFICIENT_EVIDENCE;
    }
}
