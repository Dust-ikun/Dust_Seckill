package com.dustikun.seckill.monitor.repository;

import com.dustikun.seckill.monitor.core.AlertEvent;
import com.dustikun.seckill.monitor.core.DiagnosisStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * {@code ai_diagnosis_task} 的 JDBC 实现（SPEC 第 14.1 节）。
 *
 * <h2>它只做 SQL，不含任何聚合判断</h2>
 * <p>
 * 「这条告警该不该并进那个任务」是 {@code IncidentAggregator} 的事。
 * 把它们混在一处是这类代码最常见的一步走偏：窗口边界、终态边界这些判据
 * 一旦和 SQL 缠在一起，就只能起一个 MySQL 才测得出来 —— 于是实际上不会被测。
 *
 * <h2>三个写操作都是条件更新，返回 {@code boolean}</h2>
 * <p>
 * 因为「更新了 0 行」与「更新了 1 行」在诊断链路里是<b>不同的事实</b>：
 * 前者意味着任务不存在（或者已经被别的东西改掉了），把它当成成功会让
 * 「状态永远停在 RUNNING」这种问题变得不可见。
 */
public class JdbcDiagnosisTaskStore implements DiagnosisTaskStore {

    /** 取号冲突的重试次数。5 次意味着「同一秒内有 5 个并发请求抢同一个号」才会失败 */
    private static final int CREATE_ATTEMPTS = 5;

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private static final String COLUMNS = "id, incident_id, service_name, alert_type, severity, status, "
            + "start_time, end_time, alert_id, alert_count, created_at, updated_at";

    private static final String INSERT = "INSERT INTO ai_diagnosis_task "
            + "(incident_id, service_name, alert_type, severity, status, start_time, end_time, "
            + " alert_id, alert_count, created_at, updated_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private final JdbcTemplate jdbc;

    private final RowMapper<DiagnosisTaskRow> mapper = JdbcDiagnosisTaskStore::mapRow;

    public JdbcDiagnosisTaskStore(JdbcTemplate monitorAiJdbcTemplate) {
        this.jdbc = monitorAiJdbcTemplate;
    }

    // ================================================================ 写

    @Override
    public DiagnosisTaskRow create(AlertEvent alert, Instant now) {
        LocalDate day = now.atZone(ZoneId.systemDefault()).toLocalDate();
        DuplicateKeyException last = null;
        for (int attempt = 1; attempt <= CREATE_ATTEMPTS; attempt++) {
            String incidentId = nextIncidentId(day);
            try {
                jdbc.update(INSERT,
                        incidentId,
                        alert.serviceName(),
                        alert.alertType(),
                        // severity 是 NOT NULL。取不到就写 UNKNOWN 而不是空串 ——
                        // 空串在界面上看起来像「界面没渲染出来」，而 UNKNOWN 明确说明是数据缺失。
                        alert.severity() == null || alert.severity().isBlank() ? "UNKNOWN" : alert.severity(),
                        DiagnosisStatus.CREATED.name(),
                        JdbcJson.toDb(alert.timestamp() == null
                                ? now : Instant.ofEpochSecond(alert.timestamp())),
                        null,
                        alert.alertId(),
                        1,
                        JdbcJson.toDb(now),
                        JdbcJson.toDb(now));
                return findByIncidentId(incidentId).orElseThrow(() -> new IllegalStateException(
                        "刚写入的任务 " + incidentId + " 读不回来（同事务可见性异常？）"));
            } catch (DuplicateKeyException e) {
                // 并发取号撞车。重取之所以有效：冲突的那一行已经落库，nextIncidentId 会看到它。
                last = e;
            }
        }
        throw new IllegalStateException("连续 " + CREATE_ATTEMPTS + " 次生成的事故编号都已被占用，"
                + "请检查 ai_diagnosis_task 的 incident_id 是否存在异常数据", last);
    }

    @Override
    public boolean mergeInto(long taskId, Instant now) {
        return jdbc.update("UPDATE ai_diagnosis_task SET alert_count = alert_count + 1, updated_at = ? "
                + "WHERE id = ?", JdbcJson.toDb(now), taskId) > 0;
    }

    @Override
    public boolean updateStatus(long taskId, DiagnosisStatus status, Instant now) {
        return jdbc.update("UPDATE ai_diagnosis_task SET status = ?, updated_at = ? WHERE id = ?",
                status.name(), JdbcJson.toDb(now), taskId) > 0;
    }

    @Override
    public boolean markResolved(String incidentId, Instant endTime, Instant now) {
        // `AND end_time IS NULL` 让这个操作幂等：Alertmanager 的 resolved 通知可能重复投递
        // （group_interval 30s + send_resolved），没有这个条件的话，
        // 第二次投递会把 end_time 往后推 —— 于是「故障持续了多久」这个数会随时间漂移。
        return jdbc.update("UPDATE ai_diagnosis_task SET end_time = ?, updated_at = ? "
                + "WHERE incident_id = ? AND end_time IS NULL",
                JdbcJson.toDb(endTime), JdbcJson.toDb(now), incidentId) > 0;
    }

    // ================================================================ 读

    @Override
    public Optional<DiagnosisTaskRow> findByIncidentId(String incidentId) {
        List<DiagnosisTaskRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM ai_diagnosis_task WHERE incident_id = ?",
                mapper, incidentId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public Optional<DiagnosisTaskRow> findById(long taskId) {
        List<DiagnosisTaskRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM ai_diagnosis_task WHERE id = ?", mapper, taskId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public List<DiagnosisTaskRow> findCandidates(String serviceName, String alertType, int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM ai_diagnosis_task "
                        + "WHERE service_name = ? AND alert_type = ? "
                        // 同一秒内建两个任务时 created_at 相同，补一个 id 作为决胜键 ——
                        // 否则「最新的是哪一个」在两次查询之间可能不同，聚合会随机并错任务。
                        + "ORDER BY created_at DESC, id DESC LIMIT ?",
                mapper, serviceName, alertType, Math.max(1, limit));
    }

    @Override
    public List<DiagnosisTaskRow> history(int limit, int offset) {
        return jdbc.query("SELECT " + COLUMNS + " FROM ai_diagnosis_task "
                        + "ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                mapper, Math.max(1, limit), Math.max(0, offset));
    }

    @Override
    public List<DiagnosisTaskRow> findActive(int limit) {
        // 用 end_time IS NULL 而不是 status：见接口注释（那是两条正交的时间轴）。
        return jdbc.query("SELECT " + COLUMNS + " FROM ai_diagnosis_task "
                        + "WHERE end_time IS NULL ORDER BY created_at DESC, id DESC LIMIT ?",
                mapper, Math.max(1, limit));
    }

    @Override
    public String nextIncidentId(Instant now) {
        return nextIncidentId(now.atZone(ZoneId.systemDefault()).toLocalDate());
    }

    /**
     * 取当天已用的最大序号 + 1。
     *
     * <p>【排序为什么要按长度再按字典序】因为序号是零填充的 {@code %03d}，
     * 一旦超过 999 就变成 4 位。此时纯字典序会把 {@code INC-20261009-999} 排在
     * {@code INC-20261009-1000} 后面（{'9' &gt; '1'}），于是取号会<b>倒退</b>并反复撞车。
     * 先按长度比较正好修掉这个断层，而它只在跨过 999 的那一天起作用 ——
     * 那种「一年后才出现、出现时很难复现」的缺陷，值得多写一个排序键。
     */
    private String nextIncidentId(LocalDate day) {
        String prefix = "INC-" + DAY.format(day) + "-";
        List<String> existing = jdbc.queryForList(
                "SELECT incident_id FROM ai_diagnosis_task WHERE incident_id LIKE ? "
                        + "ORDER BY LENGTH(incident_id) DESC, incident_id DESC LIMIT 1",
                String.class, prefix + "%");

        int next = 1;
        if (!existing.isEmpty() && existing.get(0) != null) {
            String last = existing.get(0);
            try {
                next = Integer.parseInt(last.substring(prefix.length())) + 1;
            } catch (NumberFormatException | IndexOutOfBoundsException e) {
                // 号段被人手工改过。退回 1，靠唯一键与重试把冲突解决掉 ——
                // 抛异常会让「一天里有一条脏数据」变成「这一天的告警全都建不了任务」。
                next = 1;
            }
        }
        return prefix + String.format("%03d", next);
    }

    // ================================================================ 自检

    /**
     * 用<b>生产 SELECT 的同一份列清单</b>探测表结构。
     * <p>【为什么这件事必须在启动时做一次】因为这三张表来自
     * {@code schema-ai-monitor.sql}，而它只在<b>空数据卷</b>时被 MySQL 初始化执行
     * （{@code docker-entrypoint-initdb.d} 的语义）。一个从批次 1/2 留下来的数据卷
     * 里可能根本没有这三张表 —— 而那时的症状是「第一条告警进来才报
     * {@code Table doesn't exist}」，排查它需要先想到「卷没重建」。
     * 启动时数一次，问题就出现在启动日志里。
     *
     * <p>用 {@code LIMIT 0} 而不是 {@code COUNT(*)}：前者会校验<b>每一个列名</b>
     * （列写错、列被改名都会失败），而后者只证明表存在。
     * 这也是复用 {@link #COLUMNS} 常量的原因 —— 自己另写一份列清单，
     * 就正好复现了这个项目反复记录的「两处各写一遍必然分叉」。
     */
    public void verifySchema() {
        jdbc.queryForList("SELECT " + COLUMNS + " FROM ai_diagnosis_task LIMIT 0");
    }

    // ================================================================ 映射

    private static DiagnosisTaskRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new DiagnosisTaskRow(
                rs.getLong("id"),
                rs.getString("incident_id"),
                rs.getString("service_name"),
                rs.getString("alert_type"),
                rs.getString("severity"),
                parseStatus(rs.getString("status")),
                JdbcJson.readInstant(rs, "start_time"),
                JdbcJson.readInstant(rs, "end_time"),
                rs.getString("alert_id"),
                rs.getInt("alert_count"),
                JdbcJson.readInstant(rs, "created_at"),
                JdbcJson.readInstant(rs, "updated_at"));
    }

    /**
     * 状态字符串 → 枚举。
     * <p>未知值回落为 {@code FAILED} 而不是抛异常：数据库里出现一个不认识的 status，
     * 正确的反应是让这条记录「不可再被聚合」（FAILED 是终态），
     * 而不是让整个历史查询报错。同时它保证 {@code terminal()} 不会因为 null 而 NPE。
     */
    private static DiagnosisStatus parseStatus(String raw) {
        if (raw == null) {
            return DiagnosisStatus.FAILED;
        }
        try {
            return DiagnosisStatus.valueOf(raw.trim());
        } catch (IllegalArgumentException e) {
            return DiagnosisStatus.FAILED;
        }
    }
}
