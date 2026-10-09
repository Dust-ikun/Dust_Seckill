package com.dustikun.seckill.monitor.analyzer;

import java.util.List;

/**
 * 解析 Diagnosis 时的「权威默认值」。
 *
 * <h2>为什么这些值必须由调度侧传进来，而不能让模型决定</h2>
 * <p>
 * {@code incidentId} 与 {@code service} 是<b>主键级</b>的信息：它们决定这条结论
 * 挂在哪次事故上、属于哪个服务。模型在这两个字段上出错的表现是「结论看起来合理，
 * 但挂错了对象」—— 而那是排障时最难发现的一类错误（因为一切都说得通）。
 * 因此规则是：<b>模型给的这两个值只作为交叉验证</b>，不一致时以调度侧为准并记一条
 * {@code note}；一致时不记（那才是常态，不该在轨迹里制造噪声）。
 *
 * <p>{@code severity} 与 {@code symptom} 则是「模型可能没给」的补充信息：
 * 缺失时回落到告警自身的级别与描述 —— 它们是同一件事的两种表达，
 * 而告警里那份是<b>指标算出来的</b>，比模型复述得更可靠。
 *
 * @param incidentId      事故编号（权威值）
 * @param service         服务名（权威值）
 * @param severity        告警的严重级别，作为模型缺失时的回落值
 * @param symptom         告警的描述，作为模型缺失时的回落值
 */
public record DiagnosisDefaults(
        String incidentId,
        String service,
        String severity,
        String symptom
) {

    public DiagnosisDefaults {
        incidentId = incidentId == null ? "" : incidentId.trim();
        service = service == null ? "" : service.trim();
        severity = severity == null ? "" : severity.trim();
        symptom = symptom == null ? "" : symptom.trim();
    }

    /** 便于测试与调用方少写一个参数 */
    public static DiagnosisDefaults of(String incidentId, String service) {
        return new DiagnosisDefaults(incidentId, service, "", "");
    }

    public List<String> describe() {
        return List.of("incidentId=" + incidentId, "service=" + service,
                "severity=" + severity, "symptom=" + symptom);
    }
}
