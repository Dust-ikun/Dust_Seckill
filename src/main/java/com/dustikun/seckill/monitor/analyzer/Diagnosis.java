package com.dustikun.seckill.monitor.analyzer;

import com.dustikun.seckill.monitor.core.SafeCollections;

import java.util.List;
import java.util.Map;

/**
 * 结构化诊断结论（SPEC 第 11 节），也是 {@code ai_diagnosis_result} 一行的领域形态。
 *
 * <h2>为什么 {@code confidence} 是可空的 {@link Double}</h2>
 * <p>
 * 因为「模型没给置信度」与「模型给了一个我们解释不了的置信度」都必须能被表达为
 * <b>没有置信度</b>，而不是被塞进一个看起来合理的数字里。
 * 建表语句里的 {@code DECIMAL(5,4)} 只能表示 {@code 0.0000 ~ 9.9999}，
 * 因此模型返回 {@code 95}（把百分数当小数）时，MySQL 在非严格模式下会把它
 * <b>静默截断成 9.9999</b> —— 那是一个「看起来合理但完全错误」的值，
 * 比报错危险得多。所以规整只在这里做，且做不了的就不猜（存 NULL）。
 *
 * <h2>{@link #notes} 是给谁看的</h2>
 * <p>
 * 给「复盘 prompt 的人」。模型漏字段、字段类型不对、给了越界的置信度，
 * 这些都不值得丢掉整份结论（那是把一次付费的推理扔了），但必须留痕：
 * 否则调 prompt 时看到的只有「结论还行」，看不出模型在哪一步偏离了 Schema。
 *
 * @param incidentId 事故编号。<b>以调度侧传入的为准</b>，不接受模型改写 ——
 *                   否则模型可以靠改一个字符串把结论挂到别的事故上
 * @param severity   严重级别（CRITICAL / HIGH / WARNING / INFO）
 * @param service    服务名
 * @param symptom    现象描述
 * @param rootCause  根因（自然语言）
 * @param confidence 置信度，已规整到 {@code [0,1]}；无法解释时为 {@code null}
 * @param impact     影响范围，形如 {@code {success_rate_change, affected_requests}}
 * @param evidence   证据列表（≥ {@code agent.min-evidence-count} 条才可能被判为充分）
 * @param suggestions 处理建议（低风险、只读）
 * @param notes      解析期的规整说明（见类注释）
 * @param rawContent 模型给出的原始正文，原样保存以便复查 Schema 校验失败的原因
 */
public record Diagnosis(
        String incidentId,
        String severity,
        String service,
        String symptom,
        String rootCause,
        Double confidence,
        Map<String, Object> impact,
        List<String> evidence,
        List<String> suggestions,
        List<String> notes,
        String rawContent
) {

    public Diagnosis {
        // 【不能用 Map.copyOf】impact 是模型给的 JSON，完全可能写成 {"affected_requests": null}；
        // Map.copyOf 会在读回那一刻抛 NPE（详见 SafeCollections 的类注释）。
        impact = SafeCollections.map(impact);
        evidence = SafeCollections.list(evidence);
        suggestions = SafeCollections.list(suggestions);
        notes = SafeCollections.list(notes);
    }

    /**
     * 证据是否足够（SPEC 第 10 节诊断规则第 3 条：「根因判断至少需要两个独立证据」）。
     * <p>【为什么这是一条独立判据而不是「解析失败」】证据不足时模型<b>给出了</b>一个根因，
     * 只是它自己列出的证据撑不住。这两件事的处理方式相反：前者要落
     * {@code INSUFFICIENT_EVIDENCE} 让人知道「别信这条结论」，
     * 后者（JSON 都解析不出来）连结论都没有。把前者也报成失败，
     * 就会丢掉「Agent 当时是怎么想的」这条对调 prompt 最有价值的信息。
     */
    public boolean evidenceSufficient(int minEvidenceCount) {
        return evidence.size() >= Math.max(1, minEvidenceCount);
    }

    /** 置信度的展示形态（{@code null} 时给出「未提供」而不是 0） */
    public String confidenceText() {
        return confidence == null ? "未提供" : String.valueOf(confidence);
    }
}
