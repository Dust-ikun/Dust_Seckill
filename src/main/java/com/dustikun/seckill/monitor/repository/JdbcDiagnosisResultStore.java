package com.dustikun.seckill.monitor.repository;

import tools.jackson.databind.ObjectMapper;
import com.dustikun.seckill.monitor.analyzer.Diagnosis;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code ai_diagnosis_result} 的 JDBC 实现（SPEC 第 14.2 节）。
 *
 * <h2>{@code confidence} 的 NULL 语义（这里是最容易写错的一列）</h2>
 * <p>
 * 建表语句里它是 {@code DECIMAL(5,4)}，只能表示 {@code 0.0000 ~ 9.9999}。
 * 而 {@code DiagnosisParser} 已经把无法解释的值规整成 {@code null}（见那边的注释），
 * 因此这里要做的只有一件事：<b>把 {@code null} 原样写进去，绝不写 0</b>。
 * 写 0 会让「模型没给置信度」在界面上显示成「置信度 0」——
 * 那是一个明确的判断（「我完全不确定」），与「缺数据」是相反的意思。
 */
public class JdbcDiagnosisResultStore implements DiagnosisResultStore {

    private static final String COLUMNS = "id, task_id, severity, root_cause, confidence, impact, "
            + "evidence, suggestion, raw_result, created_at";

    private static final String INSERT = "INSERT INTO ai_diagnosis_result "
            + "(task_id, severity, root_cause, confidence, impact, evidence, suggestion, "
            + " raw_result, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private final JdbcTemplate jdbc;

    private final ObjectMapper objectMapper;

    private final RowMapper<DiagnosisResultRow> mapper;

    public JdbcDiagnosisResultStore(JdbcTemplate monitorAiJdbcTemplate, ObjectMapper objectMapper) {
        this.jdbc = monitorAiJdbcTemplate;
        this.objectMapper = objectMapper;
        this.mapper = this::mapRow;
    }

    @Override
    public void save(long taskId, Diagnosis diagnosis, Map<String, Object> rawResult, Instant now) {
        jdbc.update(INSERT,
                taskId,
                diagnosis.severity(),
                diagnosis.rootCause(),
                diagnosis.confidence(),
                JdbcJson.write(objectMapper, diagnosis.impact()),
                JdbcJson.write(objectMapper, diagnosis.evidence()),
                JdbcJson.write(objectMapper, diagnosis.suggestions()),
                JdbcJson.write(objectMapper, withNotes(rawResult, diagnosis)),
                JdbcJson.toDb(now));
    }

    @Override
    public void saveFailure(long taskId, String reason, Map<String, Object> rawResult, Instant now) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (rawResult != null) {
            body.putAll(rawResult);
        }
        body.put("failure", reason == null ? "未知原因" : reason);
        body.put("at", now.toString());
        jdbc.update(INSERT,
                taskId,
                null,
                null,
                null,
                null,
                null,
                null,
                JdbcJson.write(objectMapper, body),
                JdbcJson.toDb(now));
    }

    @Override
    public List<DiagnosisResultRow> findByTaskId(long taskId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM ai_diagnosis_result WHERE task_id = ? "
                + "ORDER BY id ASC", mapper, taskId);
    }

    @Override
    public Optional<DiagnosisResultRow> findLatestByTaskId(long taskId) {
        List<DiagnosisResultRow> rows = jdbc.query("SELECT " + COLUMNS
                + " FROM ai_diagnosis_result WHERE task_id = ? ORDER BY id DESC LIMIT 1", mapper, taskId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * 启动自检：用生产 SELECT 的同一份列清单探测表结构。
     * <p>理由见 {@code JdbcDiagnosisTaskStore#verifySchema()}。
     */
    public void verifySchema() {
        jdbc.queryForList("SELECT " + COLUMNS + " FROM ai_diagnosis_result LIMIT 0");
    }

    /**
     * 把解析期的规整说明并进 {@code raw_result}。
     * <p>【为什么不单独存一列】因为 schema 是 SPEC 定的三张表，加列会让
     * 「按 SPEC 建的表」这句话不再成立（建表文件里那三处 ★ 补强已经是上限，
     * 每加一处都要有它无法回避的理由）。而 {@code notes} 与 {@code raw_result}
     * 是同一件事的两个部分：前者是「我们对它做了什么」，后者是「它原本是什么」——
     * 分开存反而会让两者可能对不上。
     */
    private static Map<String, Object> withNotes(Map<String, Object> rawResult, Diagnosis diagnosis) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (rawResult != null) {
            body.putAll(rawResult);
        }
        if (!diagnosis.notes().isEmpty()) {
            body.put("parserNotes", diagnosis.notes());
        }
        return body;
    }

    private DiagnosisResultRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new DiagnosisResultRow(
                rs.getLong("id"),
                rs.getLong("task_id"),
                rs.getString("severity"),
                rs.getString("root_cause"),
                readConfidence(rs),
                JdbcJson.readMap(objectMapper, "ai_diagnosis_result.impact", rs.getString("impact")),
                JdbcJson.readStringList(objectMapper, "ai_diagnosis_result.evidence",
                        rs.getString("evidence")),
                JdbcJson.readStringList(objectMapper, "ai_diagnosis_result.suggestion",
                        rs.getString("suggestion")),
                JdbcJson.readMap(objectMapper, "ai_diagnosis_result.raw_result",
                        rs.getString("raw_result")),
                JdbcJson.readInstant(rs, "created_at"));
    }

    /**
     * 读置信度。
     * <p>{@code getDouble} 对 NULL 返回 0，必须先用 {@code wasNull()} 判断 ——
     * 这是 JDBC 里最容易静默出错的一处，而它的后果正是上面注释里说的
     * 「缺数据被读成完全不确定」。
     */
    private static Double readConfidence(ResultSet rs) throws SQLException {
        double value = rs.getDouble("confidence");
        if (rs.wasNull()) {
            return null;
        }
        return value;
    }
}
