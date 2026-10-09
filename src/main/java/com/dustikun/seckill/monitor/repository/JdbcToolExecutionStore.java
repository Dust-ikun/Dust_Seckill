package com.dustikun.seckill.monitor.repository;

import tools.jackson.databind.ObjectMapper;
import com.dustikun.seckill.monitor.tool.ToolResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code ai_tool_execution} 的 JDBC 实现（SPEC 第 14.3 节）。
 *
 * <h2>{@code result} 列里存的是什么（这一处值得单独说清）</h2>
 * <p>
 * 存的是「整形后的 envelope」<b>加上</b>那段交给模型的原文：
 * <pre>
 * {
 *   "status": "SUCCESS", "data": {...}, "notes": [...], "truncated": false,
 *   "elapsedMs": 132,
 *   "llmText": "&lt;untrusted_data tool=query_metric status=SUCCESS&gt;{...}&lt;/untrusted_data&gt;"
 * }
 * </pre>
 *
 * <p>【为什么要冗余存一份 {@code llmText}】因为它目前确实<b>可以</b>由 envelope
 * 重新渲染出来（{@code ResultShaper} 是纯函数），但「目前可以」正是问题：
 * 将来任何一次整形规则的改动（新增脱敏规则、调整体积预算、改变标签转义方式）
 * 都会让<b>历史行</b>无法还原成「模型当时真正看到的那一份」。
 * 而需要还原的，恰恰是那些在新规则上线之前产生的历史行 ——
 * 「上周那条结论是不是因为脱敏把它挡掉了」这类问题，只能靠当时那份原文回答。
 * 代价是单行变大（上限约 2 × {@code tool.max-result-chars}），
 * 换的是轨迹<b>自包含</b>。
 *
 * <p>【{@code arguments} 不脱敏】理由写在 {@code ToolResult#arguments()} 的注释里。
 */
public class JdbcToolExecutionStore implements ToolExecutionStore {

    /** {@code error_message} 列是 VARCHAR(1000) */
    private static final int MAX_ERROR_CHARS = 1000;

    private static final String COLUMNS = "id, task_id, sequence_no, tool_name, arguments, result, "
            + "status, execution_time_ms, error_message, created_at";

    private final JdbcTemplate jdbc;

    private final ObjectMapper objectMapper;

    private final RowMapper<ToolExecutionRow> mapper;

    public JdbcToolExecutionStore(JdbcTemplate monitorAiJdbcTemplate, ObjectMapper objectMapper) {
        this.jdbc = monitorAiJdbcTemplate;
        this.objectMapper = objectMapper;
        this.mapper = this::mapRow;
    }

    @Override
    public void record(long taskId, int sequenceNo, ToolResult result, Instant now) {
        jdbc.update("INSERT INTO ai_tool_execution "
                        + "(task_id, sequence_no, tool_name, arguments, result, status, "
                        + " execution_time_ms, error_message, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                taskId,
                sequenceNo,
                result.toolName(),
                JdbcJson.write(objectMapper, result.arguments()),
                JdbcJson.write(objectMapper, resultBody(result)),
                result.status().name(),
                result.elapsedMillis(),
                truncate(result.errorMessage()),
                JdbcJson.toDb(now));
    }

    @Override
    public List<ToolExecutionRow> findByTaskId(long taskId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM ai_tool_execution WHERE task_id = ? "
                // 按 sequence_no 排序而不是 created_at：同一秒内的多次调用靠它才有确定顺序，
                // 而「先查 DB 还是先查日志」正是轨迹要回答的问题（见 ToolExecutionRow 的注释）。
                + "ORDER BY sequence_no ASC, id ASC", mapper, taskId);
    }

    @Override
    public int countByTaskId(long taskId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_tool_execution WHERE task_id = ?", Integer.class, taskId);
        return count == null ? 0 : count;
    }

    /**
     * 启动自检：用生产 SELECT 的同一份列清单探测表结构。
     * <p>理由见 {@code JdbcDiagnosisTaskStore#verifySchema()}：
     * 这三张表只在空数据卷时由 {@code docker-entrypoint-initdb.d} 建出来，
     * 老卷上它们可能根本不存在。
     */
    public void verifySchema() {
        jdbc.queryForList("SELECT " + COLUMNS + " FROM ai_tool_execution LIMIT 0");
    }

    /**
     * 组装写进 {@code result} 列的 JSON 对象。
     * <p>注意 {@code llmText} 只在这里加，不放进 {@link ToolResult#envelope()} ——
     * envelope 的语义是「结构化返回体」，而 llmText 是它的<b>一种渲染</b>；
     * 把渲染塞进 envelope 会让 envelope 不再是「数据的形态」。
     */
    private static Map<String, Object> resultBody(ToolResult result) {
        Map<String, Object> body = new LinkedHashMap<>(result.envelope());
        if (result.llmText() != null && !result.llmText().isEmpty()) {
            body.put("llmText", result.llmText());
        }
        return body;
    }

    private ToolExecutionRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new ToolExecutionRow(
                rs.getLong("id"),
                rs.getLong("task_id"),
                rs.getInt("sequence_no"),
                rs.getString("tool_name"),
                JdbcJson.readMap(objectMapper, "ai_tool_execution.arguments", rs.getString("arguments")),
                JdbcJson.readMap(objectMapper, "ai_tool_execution.result", rs.getString("result")),
                rs.getString("status"),
                rs.getLong("execution_time_ms"),
                rs.getString("error_message"),
                JdbcJson.readInstant(rs, "created_at"));
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MAX_ERROR_CHARS ? value : value.substring(0, MAX_ERROR_CHARS) + "…";
    }
}
