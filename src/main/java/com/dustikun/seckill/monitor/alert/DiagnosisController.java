package com.dustikun.seckill.monitor.alert;

import com.dustikun.seckill.Common.result.Result;
import com.dustikun.seckill.monitor.tool.ToolRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 诊断记录的查询 API（SPEC 第 21 节）。
 *
 * <table border="1">
 *   <caption>端点与 SPEC 的对应</caption>
 *   <tr><th>端点</th><th>SPEC 第 21 节</th></tr>
 *   <tr><td>{@code GET /api/ai/diagnosis/{incidentId}}</td><td>查询诊断结果 ✅</td></tr>
 *   <tr><td>{@code GET /api/ai/diagnosis/{incidentId}/tools}</td><td>查询 Tool Trace ✅</td></tr>
 *   <tr><td>{@code GET /api/ai/diagnosis/history}</td><td>查询历史诊断 ✅</td></tr>
 *   <tr><td>{@code GET /api/ai/incidents}</td><td>附加：活跃事故列表（SPEC 第 20 节页面 1
 *       的「当前告警」数据源）</td></tr>
 *   <tr><td>{@code GET /api/ai/tools}</td><td>附加：工具白名单（自检与前端展示）</td></tr>
 * </table>
 *
 * <p>【为什么 {@code /history} 与 {@code /{incidentId}} 不会冲突】
 * Spring 的路径匹配优先选择更具体的字面量段，因此 {@code /diagnosis/history}
 * 不会落到 {@code /diagnosis/{incidentId}} 上。这一点值得写下来 ——
 * 它依赖框架的匹配顺序，而不是我们的命名约定；万一将来换了匹配策略，
 * 症状会是「查询历史时返回 404 或某个不存在的事故」。
 */
@RestController
@RequestMapping("/api/ai")
@ConditionalOnProperty(prefix = "seckill.monitor", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class DiagnosisController {

    private final DiagnosisQueryService queryService;

    private final ToolRegistry registry;

    public DiagnosisController(DiagnosisQueryService queryService, ToolRegistry registry) {
        this.queryService = queryService;
        this.registry = registry;
    }

    /** 诊断结果（SPEC 第 24 节「前端可以展示诊断结果」的验收口径） */
    @GetMapping("/diagnosis/{incidentId}")
    public ResponseEntity<Result<Map<String, Object>>> detail(@PathVariable String incidentId) {
        Optional<Map<String, Object>> detail = queryService.detail(incidentId);
        return detail.map(body -> ResponseEntity.ok(Result.success(body)))
                .orElseGet(() -> ResponseEntity.status(404)
                        .body(Result.fail("404", "没有这个事故编号：" + incidentId)));
    }

    /** Tool Calling 轨迹 */
    @GetMapping("/diagnosis/{incidentId}/tools")
    public ResponseEntity<Result<Map<String, Object>>> tools(@PathVariable String incidentId) {
        Optional<Map<String, Object>> trace = queryService.toolTrace(incidentId);
        return trace.map(body -> ResponseEntity.ok(Result.success(body)))
                .orElseGet(() -> ResponseEntity.status(404)
                        .body(Result.fail("404", "没有这个事故编号：" + incidentId)));
    }

    /** 历史诊断 */
    @GetMapping("/diagnosis/history")
    public Result<Map<String, Object>> history(
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "offset", required = false) Integer offset) {
        List<Map<String, Object>> items = queryService.history(limit, offset);
        Map<String, Object> body = new LinkedHashMap<>(6);
        body.put("count", items.size());
        body.put("limit", limit == null ? 50 : Math.min(limit, DiagnosisQueryService.MAX_LIMIT));
        body.put("offset", offset == null ? 0 : Math.max(0, offset));
        body.put("items", items);
        return Result.success(body);
    }

    /** 活跃事故（{@code end_time IS NULL}） */
    @GetMapping("/incidents")
    public Result<Map<String, Object>> incidents(
            @RequestParam(name = "limit", required = false) Integer limit) {
        List<Map<String, Object>> items = queryService.activeIncidents(limit);
        Map<String, Object> body = new LinkedHashMap<>(4);
        body.put("activeCount", items.size());
        body.put("items", items);
        return Result.success(body);
    }

    /**
     * 工具白名单。
     * <p>它同时服务于三件事：前端展示「Agent 有哪些能力」、
     * 排障时确认「模型当时看到的 tools 是这一份」、
     * 以及批次 4 演示「白名单只有这 5 个，编造的名字会被拒」。
     */
    @GetMapping("/tools")
    public Result<Map<String, Object>> toolDefinitions() {
        Map<String, Object> body = new LinkedHashMap<>(6);
        body.put("count", registry.size());
        body.put("names", registry.names());
        body.put("definitions", registry.definitions());
        return Result.success(body);
    }
}
