package com.dustikun.seckill.monitor.alert;

import com.dustikun.seckill.monitor.repository.DiagnosisResultRow;
import com.dustikun.seckill.monitor.repository.DiagnosisResultStore;
import com.dustikun.seckill.monitor.repository.DiagnosisTaskRow;
import com.dustikun.seckill.monitor.repository.DiagnosisTaskStore;
import com.dustikun.seckill.monitor.repository.ToolExecutionRow;
import com.dustikun.seckill.monitor.repository.ToolExecutionStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 诊断记录的只读查询（SPEC 第 21 节的三个查询端点 + 一个活跃事故列表）。
 *
 * <h2>为什么查询侧单独一层，而不是让 Controller 直接拿 Store</h2>
 * <p>
 * 因为这个接口要回答的问题不是「查一张表」，而是
 * <b>「这次事故到底发生了什么」</b>—— 它需要把三张表拼成一个能读的东西：
 * <pre>
 *   ai_diagnosis_task      这次事故是什么、到哪一步了
 *   ai_diagnosis_result    Agent 得出的结论（或它为什么没得出）
 *   ai_tool_execution      它查了什么、查到了什么
 * </pre>
 * 拼装规则（缺结论时怎么表达、工具轨迹怎么按序展开）是<b>业务规则</b>，
 * 让它散在 Controller 里，就会出现「详情页有、历史列表没有」这种不一致。
 *
 * <h2>查询的边界</h2>
 * <p>
 * 所有列表都有上限（{@link #MAX_LIMIT}）。理由不是性能，而是这一层的输出会被
 * 前端一次性渲染、也会被人 copy 进工单 —— 一个「不限条数」的接口迟早会被
 * 一个不带参数的请求打满，而那时的表现是「诊断页面打不开」。
 */
public class DiagnosisQueryService {

    /** 列表接口的硬上限 */
    public static final int MAX_LIMIT = 200;

    private static final int DEFAULT_LIMIT = 50;

    /** 历史列表里每条附带的最新结论的取数深度（与列表条数一致，因此是逐条查） */
    private final DiagnosisTaskStore taskStore;

    private final DiagnosisResultStore resultStore;

    private final ToolExecutionStore toolStore;

    public DiagnosisQueryService(DiagnosisTaskStore taskStore, DiagnosisResultStore resultStore,
                                 ToolExecutionStore toolStore) {
        this.taskStore = taskStore;
        this.resultStore = resultStore;
        this.toolStore = toolStore;
    }

    /**
     * 单次诊断的完整详情（{@code GET /api/ai/diagnosis/{incidentId}}）。
     * <p>它是 SPEC 第 24 节「前端可以展示诊断结果」的验收口径
     * （可行性报告冲突 5 的裁定：页面 1、2 交给 Grafana，结构化 JSON 由这里提供）。
     */
    public Optional<Map<String, Object>> detail(String incidentId) {
        Optional<DiagnosisTaskRow> task = taskStore.findByIncidentId(incidentId);
        if (task.isEmpty()) {
            return Optional.empty();
        }
        DiagnosisTaskRow row = task.get();
        Optional<DiagnosisResultRow> latest = resultStore.findLatestByTaskId(row.id());
        int toolCallCount = toolStore.countByTaskId(row.id());
        int resultCount = resultStore.findByTaskId(row.id()).size();

        Map<String, Object> body = new LinkedHashMap<>(16);
        body.put("task", row.toMap());
        // diagnosis 为 null 与「有 diagnosis 但没有根因」是两件事，因此这里保留 null
        // 并额外给出 resultCount —— 后者能回答「它重试诊断过几次」。
        body.put("diagnosis", latest.map(DiagnosisResultRow::toMap).orElse(null));
        body.put("resultCount", resultCount);
        body.put("toolCallCount", toolCallCount);
        body.put("summary", summarize(row, latest.orElse(null), toolCallCount));
        return Optional.of(body);
    }

    /** Tool Calling 轨迹（{@code GET /api/ai/diagnosis/{incidentId}/tools}） */
    public Optional<Map<String, Object>> toolTrace(String incidentId) {
        Optional<DiagnosisTaskRow> task = taskStore.findByIncidentId(incidentId);
        if (task.isEmpty()) {
            return Optional.empty();
        }
        DiagnosisTaskRow row = task.get();
        List<ToolExecutionRow> trace = toolStore.findByTaskId(row.id());
        List<Map<String, Object>> traceMaps = new ArrayList<>(trace.size());
        for (ToolExecutionRow execution : trace) {
            traceMaps.add(execution.toMap());
        }

        Map<String, Object> body = new LinkedHashMap<>(12);
        body.put("incidentId", row.incidentId());
        body.put("taskId", row.id());
        body.put("status", row.status() == null ? null : row.status().name());
        body.put("toolCallCount", trace.size());
        // 这条字段是给「面试演示与排障」用的：它把轨迹按顺序串成一条链路，
        // 而人读 12 条 JSON 与读一行「query_metric → query_db → search_logs」的
        // 认知成本差一个数量级。
        body.put("chain", trace.stream().map(ToolExecutionRow::summarize).toList());
        body.put("trace", traceMaps);
        return Optional.of(body);
    }

    /**
     * 历史诊断（{@code GET /api/ai/diagnosis/history}）。
     * <p>每条附带最新结论的摘要（根因 / 置信度 / 证据条数）——
     * 不带摘要的话，这份列表就只能回答「有过哪些事故」，
     * 而人真正想知道的是「上次那个 P99 高最后是什么原因」。
     */
    public List<Map<String, Object>> history(Integer limit, Integer offset) {
        int safeLimit = clampLimit(limit);
        int safeOffset = offset == null || offset < 0 ? 0 : offset;
        List<DiagnosisTaskRow> rows = taskStore.history(safeLimit, safeOffset);
        return attachSummaries(rows);
    }

    /** 仍然活跃的事故（{@code GET /api/ai/incidents}）：SPEC 第 20 节页面 1 的「当前告警」数据源 */
    public List<Map<String, Object>> activeIncidents(Integer limit) {
        return attachSummaries(taskStore.findActive(clampLimit(limit)));
    }

    // ================================================================ 内部

    /**
     * 给任务列表补上「最新结论摘要」。
     * <p>逐条查（N+1）。{@code limit} 上限 200 且这张表是诊断记录（一天能有几十条就很热闹），
     * 因此它是有界的；换成一次 JOIN 反而会让「一条任务有多个结论时取哪个」这件事
     * 从「最新的那条」变成「SQL 恰好返回的那条」。
     */
    private List<Map<String, Object>> attachSummaries(List<DiagnosisTaskRow> rows) {
        List<Map<String, Object>> result = new ArrayList<>(rows.size());
        for (DiagnosisTaskRow row : rows) {
            DiagnosisResultRow latest = resultStore.findLatestByTaskId(row.id()).orElse(null);
            Map<String, Object> item = new LinkedHashMap<>(12);
            item.put("task", row.toMap());
            item.put("rootCause", latest == null ? null : latest.rootCause());
            item.put("confidence", latest == null ? null : latest.confidence());
            item.put("evidenceCount", latest == null ? 0 : latest.evidence().size());
            item.put("failureReason", latest == null ? null : latest.failureReason());
            item.put("summary", summarize(row, latest, -1));
            result.add(item);
        }
        return result;
    }

    /**
     * 一句话结论。
     * <p>【它为什么值得单独一个方法】因为「任务状态」与「有没有结论」是两个维度，
     * 组合起来有 4 种情况，而其中最容易被误读的是
     * 「{@code COMPLETED} 但结论行不存在」（落库失败或正在写）——
     * 那种情况必须说出来，否则界面会显示一个没有根因的「已完成」。
     */
    private static String summarize(DiagnosisTaskRow row, DiagnosisResultRow latest, int toolCalls) {
        StringBuilder sb = new StringBuilder(128);
        sb.append(row.incidentId()).append(' ')
                .append(row.serviceName()).append('/').append(row.alertType())
                .append(" 状态=").append(row.status() == null ? "?" : row.status().name());
        if (toolCalls >= 0) {
            sb.append(" 工具调用=").append(toolCalls).append(" 次");
        }
        if (latest == null) {
            sb.append("：尚无诊断结论");
        } else if (latest.failureReason() != null) {
            sb.append("：未产出结论（").append(latest.failureReason()).append("）");
        } else {
            sb.append("：根因=").append(latest.rootCause())
                    .append("，置信度=").append(latest.confidence() == null ? "未提供" : latest.confidence())
                    .append("，证据 ").append(latest.evidence().size()).append(" 条");
        }
        return sb.toString();
    }

    private static int clampLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}
