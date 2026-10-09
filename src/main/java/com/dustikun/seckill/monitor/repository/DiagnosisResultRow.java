package com.dustikun.seckill.monitor.repository;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code ai_diagnosis_result} 的一行（SPEC 第 14.2 节）。
 *
 * @param id          自增主键
 * @param taskId      {@code ai_diagnosis_task.id}
 * @param severity    严重级别；失败记录为 {@code null}
 * @param rootCause   根因（自然语言）；失败记录为 {@code null}
 * @param confidence  置信度，已规整到 {@code [0,1]}；无法解释时为 {@code null}
 * @param impact      影响范围
 * @param evidence    证据列表
 * @param suggestions 处理建议
 * @param rawResult   原始返回。<b>失败记录的原因也在这里</b>（{@code failure} 键），
 *                    见 {@link DiagnosisResultStore#saveFailure}
 * @param createdAt   落库时间
 */
public record DiagnosisResultRow(
        long id,
        long taskId,
        String severity,
        String rootCause,
        Double confidence,
        Map<String, Object> impact,
        List<String> evidence,
        List<String> suggestions,
        Map<String, Object> rawResult,
        Instant createdAt
) {

    public DiagnosisResultRow {
        impact = impact == null ? Map.of() : impact;
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
        rawResult = rawResult == null ? Map.of() : rawResult;
    }

    /**
     * 失败原因（从 {@code raw_result.failure} 里取）。
     * <p>它在 API 响应里是一个独立的字段，因为「任务失败了」与
     * 「任务失败了因为没配密钥」是两条不同的信息（后者直接指向修复动作）。
     */
    public String failureReason() {
        Object failure = rawResult.get("failure");
        return failure == null ? null : String.valueOf(failure);
    }

    /** 给 {@code GET /api/ai/diagnosis/{incidentId}} 用的形态 */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>(16);
        map.put("severity", severity);
        map.put("rootCause", rootCause);
        // confidence 为 null 时保留 null：0 会让「没给置信度」看起来像「完全不确定」，
        // 而它们是两件事（前者是缺数据，后者是一个明确的判断）。
        map.put("confidence", confidence);
        map.put("impact", impact);
        map.put("evidence", evidence);
        map.put("evidenceCount", evidence.size());
        map.put("suggestions", suggestions);
        String failure = failureReason();
        if (failure != null) {
            map.put("failureReason", failure);
        }
        map.put("rawResult", rawResult);
        map.put("createdAt", createdAt == null ? null : createdAt.toString());
        return map;
    }
}
