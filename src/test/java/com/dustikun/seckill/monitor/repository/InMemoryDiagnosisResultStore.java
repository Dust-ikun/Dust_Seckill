package com.dustikun.seckill.monitor.repository;

import com.dustikun.seckill.monitor.analyzer.Diagnosis;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存版的 {@link DiagnosisResultStore}。
 *
 * <p>它的主要用途是让「有结论走 {@code save}、没结论走 {@code saveFailure}」
 * 这条分岔可以被直接断言 —— 这两个入口写进去的东西差别很大
 * （前者有 root_cause，后者 {@code root_cause} 必须为 NULL、原因在 {@code raw_result.failure} 里），
 * 而它们最容易被写反的地方正是「失败也塞进 root_cause」。
 */
public class InMemoryDiagnosisResultStore implements DiagnosisResultStore {

    private final List<DiagnosisResultRow> rows = new ArrayList<>();

    private final AtomicLong ids = new AtomicLong(1);

    /** 置 true 后所有写入抛异常，用来验证「落库失败也不能把异常漏出去」 */
    private volatile boolean failOnSave;

    public InMemoryDiagnosisResultStore failOnSave() {
        this.failOnSave = true;
        return this;
    }

    @Override
    public void save(long taskId, Diagnosis diagnosis, Map<String, Object> rawResult, Instant now) {
        check();
        Map<String, Object> body = new LinkedHashMap<>(rawResult == null ? Map.of() : rawResult);
        if (!diagnosis.notes().isEmpty()) {
            body.put("parserNotes", diagnosis.notes());
        }
        rows.add(new DiagnosisResultRow(ids.getAndIncrement(), taskId, diagnosis.severity(),
                diagnosis.rootCause(), diagnosis.confidence(), diagnosis.impact(),
                diagnosis.evidence(), diagnosis.suggestions(), body, now));
    }

    @Override
    public void saveFailure(long taskId, String reason, Map<String, Object> rawResult, Instant now) {
        check();
        Map<String, Object> body = new LinkedHashMap<>(rawResult == null ? Map.of() : rawResult);
        body.put("failure", reason == null ? "未知原因" : reason);
        body.put("at", now.toString());
        rows.add(new DiagnosisResultRow(ids.getAndIncrement(), taskId, null, null, null,
                Map.of(), List.of(), List.of(), body, now));
    }

    private void check() {
        if (failOnSave) {
            throw new IllegalStateException("测试构造的结论落库失败");
        }
    }

    @Override
    public List<DiagnosisResultRow> findByTaskId(long taskId) {
        return rows.stream().filter(row -> row.taskId() == taskId).toList();
    }

    @Override
    public Optional<DiagnosisResultRow> findLatestByTaskId(long taskId) {
        List<DiagnosisResultRow> matching = findByTaskId(taskId);
        return matching.isEmpty() ? Optional.empty() : Optional.of(matching.get(matching.size() - 1));
    }

    public List<DiagnosisResultRow> all() {
        return List.copyOf(rows);
    }
}
