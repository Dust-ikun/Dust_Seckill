package com.dustikun.seckill.monitor.core;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * AI 监控自身的指标（{@code seckill_ai_*}）。
 *
 * <h2>★ 这里有一条不能违反的纪律：这些指标不得被任何告警规则引用</h2>
 * <p>
 * 批次 2 发现过一个「自激闭环」：Business Tool 调用 {@code reconcile()} 会写
 * {@code seckill_reconcile_findings_total}，而告警 {@code ReconcileInconsistencyDetected}
 * 正是 {@code rate(seckill_reconcile_findings_total[10m]) > 0} ——
 * 于是「Agent 一调查，告警就再响一次」，而每一圈都要付费，且它<b>不会自己停下来</b>。
 *
 * <p>这一组指标与那次的情形在结构上完全一样（它们是「Agent 跑过」的痕迹），
 * 因此必须提前把闭环掐掉。做法是两道：
 * <ol>
 *   <li>本类只提供 Counter，<b>不</b>提供任何「Agent 健康度」这类派生指标。
 *       派生指标一定会有人拿去写告警（「诊断失败率 &gt; 50% 就该报警」听起来完全合理），
 *       而那一刻闭环就成立了：Agent 失败 → 告警 → 触发诊断 → 更容易失败；</li>
 *   <li>{@code monitoring/validate_config.py} 第 10 组会<b>静态检查</b>
 *       「告警规则与看板的 PromQL 里不得出现 {@code seckill_ai_}」。
 *       纪律写在注释里会被忘掉，写成检查才会被遵守。</li>
 * </ol>
 * 需要知道 Agent 的健康状况时，看 {@code ai_diagnosis_task.status} 与
 * {@code ai_tool_execution}（它们是事实表，不在 Prometheus 的反馈回路里）。
 *
 * <h2>基数是有界的</h2>
 * <p>
 * {@code tool} 取自白名单（5 个名字 + {@code "-"}），{@code status} 与各种
 * outcome 都是枚举。因此这里不需要担心「标签爆炸」，这一点值得写下来 ——
 * 它是「可以放心加标签」的前提，而不是运气。
 */
public class AiMonitorMetrics {

    /** 工具调用次数。标签：{@code tool}（白名单内的名字）、{@code status}（SUCCESS/FAILED/REJECTED） */
    public static final String TOOL_CALLS = "seckill.ai.tool.calls";

    /** LLM 往返次数。标签：{@code outcome}（SUCCESS/FAILED/UNAVAILABLE） */
    public static final String LLM_CALLS = "seckill.ai.llm.calls";

    /** 诊断结局。标签：{@code outcome}（COMPLETED/INSUFFICIENT_EVIDENCE/FAILED/SKIPPED） */
    public static final String DIAGNOSIS = "seckill.ai.diagnosis";

    private final MeterRegistry registry;

    public AiMonitorMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void toolCall(String toolName, String status) {
        registry.counter(TOOL_CALLS, "tool", safe(toolName), "status", safe(status)).increment();
    }

    public void llmCall(String outcome) {
        registry.counter(LLM_CALLS, "outcome", safe(outcome)).increment();
    }

    public void diagnosis(String outcome) {
        registry.counter(DIAGNOSIS, "outcome", safe(outcome)).increment();
    }

    /** 标签值不能为 null（Micrometer 会抛 NPE，而那个堆栈与根因看起来毫无关系） */
    private static String safe(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
