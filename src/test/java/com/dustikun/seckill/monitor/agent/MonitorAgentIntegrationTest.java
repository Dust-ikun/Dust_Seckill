package com.dustikun.seckill.monitor.agent;

import com.dustikun.seckill.Common.result.Result;
import com.dustikun.seckill.monitor.alert.AlertIngestService;
import com.dustikun.seckill.monitor.alert.AlertWebhookController;
import com.dustikun.seckill.monitor.alert.AlertmanagerPayload;
import com.dustikun.seckill.monitor.alert.DiagnosisController;
import com.dustikun.seckill.monitor.alert.DiagnosisQueryService;
import com.dustikun.seckill.monitor.alert.IncidentAggregator;
import com.dustikun.seckill.monitor.alert.IncomingAlert;
import com.dustikun.seckill.monitor.analyzer.DiagnosisParser;
import com.dustikun.seckill.monitor.core.AiMonitorMetrics;
import com.dustikun.seckill.monitor.core.AlertEvent;
import com.dustikun.seckill.monitor.core.AlertType;
import com.dustikun.seckill.monitor.core.DiagnosisStatus;
import com.dustikun.seckill.monitor.core.MonitorAgentProperties;
import com.dustikun.seckill.monitor.repository.DiagnosisResultStore;
import com.dustikun.seckill.monitor.repository.DiagnosisTaskRow;
import com.dustikun.seckill.monitor.repository.DiagnosisTaskStore;
import com.dustikun.seckill.monitor.repository.JdbcDiagnosisResultStore;
import com.dustikun.seckill.monitor.repository.JdbcDiagnosisTaskStore;
import com.dustikun.seckill.monitor.repository.JdbcToolExecutionStore;
import com.dustikun.seckill.monitor.repository.ToolExecutionRow;
import com.dustikun.seckill.monitor.repository.ToolExecutionStore;
import com.dustikun.seckill.monitor.tool.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批次 3 在<b>真实中间件与真实数据库</b>上的端到端验收。
 *
 * <h2>它跑的是真东西（这一点是它存在的全部理由）</h2>
 * <pre>
 *   真实 Spring 上下文            证明整批 Bean 装配得起来（含三张表的列名）
 *   真实 MySQL                    ai_diagnosis_task / _result / ai_tool_execution 真的能读写
 *   真实 ToolRegistry（5 个工具）  工具调用真的经过白名单/参数校验/整形/超时
 *   真实 Prometheus / 日志缓冲     证据真的是从数据源取回来的，不是构造出来的
 *   脚本化的 LLM                  唯一被替换的一环 —— 因为真实 LLM 的输出不可复现
 * </pre>
 *
 * <p>【为什么只换掉 LLM】因为它是整条链路上<b>唯一无法离线复现</b>的一环：
 * 工具、数据库、白名单、整形这些都可能因为装配错误而失效，而它们失效的方式
 * （NPE、列名不存在、连不上）与逻辑错误完全不同 —— 只有真跑一遍才能发现。
 * 至于 LLM，它的输出本来就不可复现，用它做断言等于把测试的稳定性交给运气。
 *
 * <h2>profile 必须与其它测试类一致（{@code test}）</h2>
 * <p>
 * Spring 的上下文缓存以配置组合为键：任何一个测试类用了不同的配置，就会多建一份上下文，
 * 而每个上下文都会启动一个 RocketMQ 消费者 —— 同一消费组出现两个实例时 Broker 会把
 * 队列对半分，导致依赖消费进度的断言假失败（见 {@code DustIkunSeckillApplicationTests}）。
 */
@SpringBootTest
@ActiveProfiles("test")
class MonitorAgentIntegrationTest {

    /** 每个测试用一个独立的服务名，避免与库里已有的诊断记录互相聚合 */
    private static final String SERVICE = "it-agent-" + System.nanoTime();

    @Autowired
    private ToolRegistry registry;

    @Autowired
    private LlmClient llmClient;

    @Autowired
    private AgentExecutor agentExecutor;

    @Autowired
    private DiagnosisService diagnosisService;

    @Autowired
    private AlertIngestService alertIngestService;

    @Autowired
    private DiagnosisQueryService queryService;

    @Autowired
    private AlertmanagerPayload payloadParser;

    @Autowired
    private AlertWebhookController alertWebhookController;

    @Autowired
    private DiagnosisController diagnosisController;

    @Autowired
    private JdbcDiagnosisTaskStore taskStore;

    @Autowired
    private JdbcDiagnosisResultStore resultStore;

    @Autowired
    private JdbcToolExecutionStore toolStore;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MonitorAgentProperties agentProperties;

    @Autowired
    private MonitorLlmProperties llmProperties;

    // ================================================================ 装配

    @Test
    @DisplayName("★ 整批 Bean 装配得起来：白名单 5 个、五个工具都能通过注册表调用")
    void wiringIsComplete() {
        assertNotNull(agentExecutor);
        assertNotNull(diagnosisService);
        assertNotNull(alertIngestService);
        assertNotNull(queryService);
        assertNotNull(payloadParser);
        assertNotNull(alertWebhookController);
        assertEquals(5, registry.size(), "白名单：" + registry.names());
        assertEquals(5, registry.definitions().size());

        // 每个工具都真的调一次：证明「装配对了」而不是「Bean 建出来了」。
        // 参数全部是各工具的最小合法输入；断言只看「有没有经过整形出口」，
        // 不看数值 —— 数值取决于库里的历史数据与容器活了多久。
        for (String toolName : registry.names()) {
            var result = registry.invoke(toolName, Map.of("operation", "GET_ORDER_STATISTICS",
                    "metric", "order_success_rate"));
            assertNotNull(result.llmText(), toolName + " 的结果没有经过整形出口");
            assertTrue(result.llmText().contains("<untrusted_data"), toolName);
            assertNotNull(result.arguments(), toolName + " 的 arguments 没有回填");
        }
    }

    @Test
    @DisplayName("★ 三张 AI 表的列名与 Store 的 SELECT 一致（老数据卷缺表时这里就会失败）")
    void aiTablesMatchTheStores() {
        taskStore.verifySchema();
        resultStore.verifySchema();
        toolStore.verifySchema();
    }

    @Test
    @DisplayName("★ 测试 profile 没有 LLM 密钥 → 降级态（这正是 SPEC 第 18 节「AI 失败 ≠ 监控失败」）")
    void llmIsUnavailableInTestProfile() {
        assertFalse(llmClient.available());
        assertInstanceOf(UnavailableLlmClient.class, llmClient);
        assertTrue(llmClient.unavailableReason().contains("DEEPSEEK_API_KEY"),
                llmClient.unavailableReason());
        assertFalse(llmProperties.normalized().configured());
    }

    // ================================================================ 载荷解析

    @Test
    @DisplayName("真实的 Alertmanager v4 载荷被正确解析：类型映射、数值注解、resolved 语义")
    void parsesRealisticAlertmanagerPayload() {
        String body = """
                {
                  "version": "4",
                  "status": "firing",
                  "groupKey": "{}/{service=\\"order-service\\"}",
                  "commonLabels": {"service": "order-service"},
                  "commonAnnotations": {"summary": "公共摘要"},
                  "alerts": [
                    {
                      "status": "firing",
                      "labels": {"alertname": "ApiP99LatencyHigh", "severity": "HIGH",
                                 "service": "order-service", "uri": "/seckill"},
                      "annotations": {"summary": "P99 超过 1s", "description": "接口 P99 达到 2.10s",
                                      "value": "2.10", "threshold": "1"},
                      "startsAt": "2026-10-09T12:00:00Z",
                      "endsAt": "0001-01-01T00:00:00Z",
                      "fingerprint": "fp-abc123"
                    },
                    {
                      "status": "resolved",
                      "labels": {"alertname": "RedisDegradationTriggered", "severity": "HIGH",
                                 "service": "order-service"},
                      "annotations": {"summary": "Redis 降级"},
                      "startsAt": "2026-10-09T11:00:00Z",
                      "endsAt": "2026-10-09T11:30:00Z",
                      "fingerprint": "fp-def456"
                    }
                  ]
                }
                """;

        AlertmanagerPayload.Parsed parsed = payloadParser.parse(body, "order-service");

        assertEquals(1, parsed.firingCount());
        assertEquals(1, parsed.resolvedCount());

        AlertEvent firing = parsed.alerts().get(0).event();
        assertEquals(AlertType.LATENCY_HIGH.name(), firing.alertType());
        assertEquals("order-service", firing.serviceName());
        assertEquals("HIGH", firing.severity());
        assertEquals(2.10, firing.currentValue(), "数值来自 annotations.value");
        assertEquals(1.0, firing.threshold());
        // Alertmanager 的载荷里**没有**指标名（它只带 labels/annotations），
        // 因此 annotations.metric_name 缺失时回落为告警名 —— 这一点要写死在测试里，
        // 否则将来有人「顺手」改成去 labels 里找 __name__ 也没人发现。
        assertEquals("ApiP99LatencyHigh", firing.metricName());
        assertEquals("接口 P99 达到 2.10s", firing.description(), "description 优先于 summary");

        // ★ Go 的 time 零值 0001-01-01 必须被规整成 null，否则写进 DATETIME 会变成
        // 「两千年前就结束了」
        assertNull(parsed.alerts().get(0).endsAt(), "未结束的告警不该有一个公元 1 年的结束时间");
        assertNotNull(parsed.alerts().get(0).startsAt());
        assertEquals("fp-abc123", parsed.alerts().get(0).fingerprint());
        assertEquals("fp-abc123", parsed.alerts().get(0).idempotencyKey());
        assertTrue(parsed.alerts().get(0).rawJson().contains("ApiP99LatencyHigh"),
                "原始 JSON 要保留，它是「这次诊断的输入到底是什么」的唯一证据");

        assertEquals(AlertType.REDIS_UNAVAILABLE.name(), parsed.alerts().get(1).event().alertType());
        assertTrue(parsed.alerts().get(1).resolved());
        assertNotNull(parsed.alerts().get(1).endsAt());
    }

    @Test
    @DisplayName("非 Alertmanager 载荷被拒绝（HTTP 400），而不是回 200 把那条告警吞掉")
    void malformedPayloadIsRejectedWith400() {
        ResponseEntity<Result<Map<String, Object>>> notJson =
                alertWebhookController.receive("这不是 JSON");
        assertEquals(HttpStatus.BAD_REQUEST, notJson.getStatusCode());

        ResponseEntity<Result<Map<String, Object>>> noAlerts =
                alertWebhookController.receive("{\"status\":\"firing\"}");
        assertEquals(HttpStatus.BAD_REQUEST, noAlerts.getStatusCode());

        ResponseEntity<Result<Map<String, Object>>> empty =
                alertWebhookController.receive("   ");
        assertEquals(HttpStatus.BAD_REQUEST, empty.getStatusCode());

        // 【这一条是真正的断言】项目全局的 GlobalExceptionHandler 会把任何异常转成 HTTP 200，
        // 而 webhook 回 200 意味着 Alertmanager 认为投递成功、不再重试 —— 那条告警就此消失。
        assertFalse(notJson.getBody() == null || notJson.getBody().isSuccess());
    }

    @Test
    @DisplayName("webhook 收下真实载荷 → 202，并且真的在库里建了任务")
    void webhookAcceptsAndPersists() {
        String service = SERVICE + "-hook";
        String body = payload(service, AlertType.LATENCY_HIGH, "fp-hook-1", "firing");

        ResponseEntity<Result<Map<String, Object>>> response = alertWebhookController.receive(body);

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertNotNull(response.getBody());
        Map<String, Object> data = response.getBody().getData();
        assertEquals(1, data.get("received"));
        assertEquals(1, data.get("created"));

        String incidentId = String.valueOf(((List<?>) data.get("incidentIds")).get(0));
        Optional<DiagnosisTaskRow> row = taskStore.findByIncidentId(incidentId);
        assertTrue(row.isPresent(), "响应里说建了任务，库里就必须真的有一行");
        assertEquals(service, row.get().serviceName());
    }

    // ================================================================ 聚合

    @Test
    @DisplayName("★ 3 条同类告警 → 库里 1 个任务、alert_count=3、只触发 1 次诊断（SPEC 第 16 节）")
    void threeAlertsBecomeOneTaskInRealDatabase() {
        String service = SERVICE + "-agg";

        AlertIngestService.Outcome first = alertIngestService.ingestOne(
                incoming(service, AlertType.ERROR_RATE_HIGH, "fp-agg-1"));
        AlertIngestService.Outcome second = alertIngestService.ingestOne(
                incoming(service, AlertType.ERROR_RATE_HIGH, "fp-agg-2"));
        AlertIngestService.Outcome third = alertIngestService.ingestOne(
                incoming(service, AlertType.ERROR_RATE_HIGH, "fp-agg-3"));

        assertEquals(AlertIngestService.Kind.CREATED, first.kind());
        assertEquals(AlertIngestService.Kind.MERGED, second.kind());
        assertEquals(AlertIngestService.Kind.MERGED, third.kind());
        assertEquals(first.incidentId(), second.incidentId());
        assertEquals(first.incidentId(), third.incidentId());

        DiagnosisTaskRow row = taskStore.findByIncidentId(first.incidentId()).orElseThrow();
        assertEquals(3, row.alertCount(), "3 条告警通知都被聚合进了同一个任务");
        assertEquals(service, row.serviceName());
        assertEquals(AlertType.ERROR_RATE_HIGH.name(), row.alertType());

        // 反证：这个服务名下不该出现第二个任务
        List<DiagnosisTaskRow> all = taskStore.findCandidates(service, AlertType.ERROR_RATE_HIGH.name(), 30);
        assertEquals(1, all.size(), "同类告警必须只建一个任务：" + all);
    }

    @Test
    @DisplayName("恢复通知把 end_time 写进真库，且重复投递不改变已写入的时间")
    void resolvedNotificationWritesEndTime() {
        String service = SERVICE + "-resolved";
        AlertIngestService.Outcome created = alertIngestService.ingestOne(
                incoming(service, AlertType.MQ_LAG_HIGH, "fp-res-1"));

        long endsAt = Instant.now().getEpochSecond();
        AlertIngestService.Outcome resolved = alertIngestService.ingestOne(
                new IncomingAlert(resolvedEvent(service, AlertType.MQ_LAG_HIGH), 
                        IncomingAlert.State.RESOLVED, "fp-res-1", endsAt - 60, endsAt, "{}"));

        assertEquals(AlertIngestService.Kind.RESOLVED, resolved.kind());
        Instant firstEnd = taskStore.findByIncidentId(created.incidentId()).orElseThrow().endTime();
        assertNotNull(firstEnd);

        // 重复的 resolved（Alertmanager 会重投）不该把时间往后推
        alertIngestService.ingestOne(new IncomingAlert(resolvedEvent(service, AlertType.MQ_LAG_HIGH),
                IncomingAlert.State.RESOLVED, "fp-res-1", endsAt - 60, endsAt + 600, "{}"));
        Instant secondEnd = taskStore.findByIncidentId(created.incidentId()).orElseThrow().endTime();
        assertEquals(firstEnd, secondEnd);
    }

    // ================================================================ 真实工具 + 真实落库

    @Test
    @DisplayName("★ 真实工具链 + 真实轨迹落库：参数/结果/状态/序号四件套都对得上")
    void realToolCallsArePersistedAsTrace() {
        String service = SERVICE + "-trace";
        DiagnosisTaskRow task = taskStore.create(event(service, AlertType.LATENCY_HIGH), Instant.now());

        // 脚本：先查指标（真 Prometheus）→ 再查日志（真环形缓冲）→ 再编一个未注册的工具
        // → 最后收尾。第四步刻意用 SPEC 第 9 节的旧命名 get_slow_sql()，
        // 它正好是「模型照记忆编工具名」的真实形态。
        ScriptedLlmClient llm = ScriptedLlmClient.of(
                ScriptedLlmClient.toolCalls("tool_calls",
                        ScriptedLlmClient.call("t1", "query_metric",
                                "{\"metric\":\"order_success_rate\",\"service\":\"*\"}"),
                        ScriptedLlmClient.call("t2", "search_logs", "{\"keyword\":\"seckill\"}")),
                ScriptedLlmClient.toolCall("t3", "get_slow_sql", "{}"),
                ScriptedLlmClient.finalAnswer(diagnosisJson()));

        AgentExecutor executor = new AgentExecutor(llm, registry,
                new PromptBuilder(agentProperties.normalized().maxToolCalls(),
                        agentProperties.normalized().minEvidenceCount()),
                new DiagnosisParser(objectMapper), toolStore,
                new AiMonitorMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
                objectMapper, llmProperties, agentProperties);

        AgentRunResult result = executor.run(task.id(), task.incidentId(), event(service, AlertType.LATENCY_HIGH),
                AgentExecutor.StatusListener.NOOP);

        assertEquals(DiagnosisStatus.COMPLETED, result.status(), result.summarize());
        assertEquals(3, result.toolCalls());

        List<ToolExecutionRow> trace = toolStore.findByTaskId(task.id());
        assertEquals(3, trace.size(), trace.toString());
        assertEquals(List.of(1, 2, 3), trace.stream().map(ToolExecutionRow::sequenceNo).toList(),
                "序号必须是 1,2,3 —— created_at 的精度只有秒，靠它才能确定调用顺序");
        assertEquals("query_metric", trace.get(0).toolName());
        assertEquals("SUCCESS", trace.get(0).status());
        assertEquals("search_logs", trace.get(1).toolName());
        assertEquals("get_slow_sql", trace.get(2).toolName());
        assertEquals("REJECTED", trace.get(2).status(), "编造的工具名必须被白名单拒绝并留下记录");
        assertTrue(trace.get(2).errorMessage().contains("未注册"), trace.get(2).errorMessage());

        // ★ 落库的入参是 accepted()：query_metric 的 metric 与 service 都在里面
        assertEquals("order_success_rate", trace.get(0).arguments().get("metric"));
        assertEquals("*", trace.get(0).arguments().get("service"));

        // ★ result 列里同时有结构化数据与「模型当时看到的那一份」原文
        assertNotNull(trace.get(0).result().get("llmText"), "result 里必须保留给模型的原文（轨迹要自包含）");
        assertTrue(String.valueOf(trace.get(0).result().get("llmText")).contains("<untrusted_data"));
        assertTrue(((Number) trace.get(0).result().get("elapsedMs")).longValue() >= 0,
                "elapsedMs 不该是负数");
        // 轨迹里的耗时列与 result 里的 elapsedMs 应当同源（两处不一致会让「到底多慢」无法回答）
        assertEquals(((Number) trace.get(0).result().get("elapsedMs")).longValue(),
                trace.get(0).executionTimeMs());
    }

    @Test
    @DisplayName("★ 结论真的落进 ai_diagnosis_result，并且能被查询 API 读回来（SPEC 第 21 节）")
    void diagnosisIsQueryableThroughTheApi() {
        String service = SERVICE + "-query";
        DiagnosisTaskRow task = taskStore.create(event(service, AlertType.LATENCY_HIGH), Instant.now());

        ScriptedLlmClient llm = ScriptedLlmClient.of(ScriptedLlmClient.finalAnswer(diagnosisJson()));
        AgentExecutor executor = new AgentExecutor(llm, registry,
                new PromptBuilder(agentProperties.normalized().maxToolCalls(),
                        agentProperties.normalized().minEvidenceCount()),
                new DiagnosisParser(objectMapper), toolStore,
                new AiMonitorMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
                objectMapper, llmProperties, agentProperties);
        // 用同步入口落库（与 DiagnosisService 相同的收尾逻辑，但可控）
        AgentRunResult result = executor.run(task.id(), task.incidentId(),
                event(service, AlertType.LATENCY_HIGH), AgentExecutor.StatusListener.NOOP);
        assertTrue(result.hasDiagnosis());
        resultStore.save(task.id(), result.diagnosis(), result.rawResult(), Instant.now());
        taskStore.updateStatus(task.id(), result.status(), Instant.now());

        // ---- 查询诊断结果 ----
        Map<String, Object> detail = queryService.detail(task.incidentId()).orElseThrow();
        assertEquals(task.incidentId(), ((Map<?, ?>) detail.get("task")).get("incidentId"));
        Map<?, ?> diagnosis = (Map<?, ?>) detail.get("diagnosis");
        assertNotNull(diagnosis);
        assertEquals("MySQL 慢 SQL", diagnosis.get("rootCause"));
        assertEquals(2, diagnosis.get("evidenceCount"));
        assertNotNull(detail.get("summary"));

        // ---- 查询 Tool Trace ----
        Map<String, Object> trace = queryService.toolTrace(task.incidentId()).orElseThrow();
        assertEquals(task.id(), trace.get("taskId"));
        assertNotNull(trace.get("chain"), "chain 是给人读的一行链路（面试演示与排障都用它）");
        assertNotNull(trace.get("trace"));

        // ---- 不存在的事故编号 ----
        assertTrue(queryService.detail("INC-not-exists").isEmpty());
        assertEquals(HttpStatus.NOT_FOUND,
                diagnosisController.detail("INC-not-exists").getStatusCode());
        assertEquals(HttpStatus.OK, diagnosisController.detail(task.incidentId()).getStatusCode());

        // ---- 历史与活跃列表 ----
        List<Map<String, Object>> history = queryService.history(50, 0);
        assertFalse(history.isEmpty());
        assertTrue(queryService.activeIncidents(200).stream()
                        .anyMatch(item -> task.incidentId().equals(((Map<?, ?>) item.get("task")).get("incidentId"))),
                "未 resolved 的任务必须出现在活跃事故列表里");
    }

    @Test
    @DisplayName("★ 测试 profile 下真实走一遍降级：任务落 FAILED，原因是「没配密钥」而不是别的")
    void degradedDiagnosisEndsFailedWithExplicitReason() {
        String service = SERVICE + "-degraded";
        DiagnosisTaskRow task = taskStore.create(event(service, AlertType.LATENCY_HIGH), Instant.now());

        AgentRunResult result = diagnosisService.diagnose(task.id(), task.incidentId(),
                event(service, AlertType.LATENCY_HIGH));

        assertEquals(DiagnosisStatus.FAILED, result.status());
        assertTrue(result.failureReason().contains("DEEPSEEK_API_KEY"), result.failureReason());
        DiagnosisTaskRow reloaded = taskStore.findByIncidentId(task.incidentId()).orElseThrow();
        assertEquals(DiagnosisStatus.FAILED, reloaded.status());

        var stored = resultStore.findLatestByTaskId(task.id()).orElseThrow();
        assertNull(stored.rootCause(), "失败原因不能写进 root_cause");
        assertTrue(stored.failureReason().contains("DEEPSEEK_API_KEY"), stored.failureReason());
    }

    @Test
    @DisplayName("自监控告警在真实链路上被跳过诊断，但仍然留下任务与原因（不静默丢弃）")
    void selfMonitoringAlertIsSkippedButRecorded() {
        String service = SERVICE + "-self";
        AlertEvent selfMonitoring = new AlertEvent("fp-self", service, "up",
                AlertType.SELF_MONITORING.name(), "WARNING", null, null,
                Instant.now().getEpochSecond(), "Prometheus 规则求值失败");
        DiagnosisTaskRow task = taskStore.create(selfMonitoring, Instant.now());

        AgentRunResult result = diagnosisService.diagnose(task.id(), task.incidentId(), selfMonitoring);

        assertEquals(DiagnosisStatus.FAILED, result.status());
        assertEquals(0, result.llmCalls());
        assertTrue(result.failureReason().contains("监控系统自身"), result.failureReason());
        assertEquals(0, toolStore.countByTaskId(task.id()), "跳过诊断就不该有任何工具调用");
    }

    // ================================================================ 手工接口

    @Test
    @DisplayName("POST /api/ai/diagnosis：手工建任务（故障注入用），alertType 走白名单")
    void manualCreateEndpoint() {
        String service = SERVICE + "-manual";
        String body = """
                {"alertId":"ALT-manual-1","service":"%s","alertType":"LATENCY_HIGH",
                 "severity":"HIGH","metricName":"seckill:http_p99_latency:5m",
                 "value":2.1,"threshold":1.0,"description":"手工触发的诊断"}
                """.formatted(service);

        ResponseEntity<Result<Map<String, Object>>> response = alertWebhookController.create(body);

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        String incidentId = String.valueOf(response.getBody().getData().get("incidentId"));
        assertTrue(taskStore.findByIncidentId(incidentId).isPresent());

        // alertType 不在白名单 → 400（它是 Incident 聚合的键，不能自由输入）
        ResponseEntity<Result<Map<String, Object>>> bad = alertWebhookController.create(
                "{\"service\":\"" + service + "\",\"alertType\":\"MADE_UP_TYPE\"}");
        assertEquals(HttpStatus.BAD_REQUEST, bad.getStatusCode());

        // 请求体不是 JSON → 400
        assertEquals(HttpStatus.BAD_REQUEST, alertWebhookController.create("{").getStatusCode());
    }

    @Test
    @DisplayName("GET /api/ai/tools：白名单与 definitions 直接可取（模型看到的 tools 就是这一份）")
    void toolsEndpointExposesWhitelist() {
        Result<Map<String, Object>> result = diagnosisController.toolDefinitions();

        assertTrue(result.isSuccess());
        assertEquals(5, result.getData().get("count"));
        assertEquals(registry.names(), result.getData().get("names"));
        assertEquals(5, ((List<?>) result.getData().get("definitions")).size());
    }

    // ================================================================ 辅助

    private static AlertEvent event(String service, AlertType type) {
        return new AlertEvent("fp-" + service, service, "seckill:http_p99_latency:5m",
                type.name(), "HIGH", 2.1, 1.0, Instant.now().getEpochSecond(), "测试用告警");
    }

    private static AlertEvent resolvedEvent(String service, AlertType type) {
        return new AlertEvent("fp-" + service, service, "metric", type.name(), "HIGH",
                null, null, Instant.now().minusSeconds(600).getEpochSecond(), "测试用告警（已恢复）");
    }

    private static IncomingAlert incoming(String service, AlertType type, String fingerprint) {
        AlertEvent event = new AlertEvent(fingerprint, service, "seckill:http_p99_latency:5m",
                type.name(), "HIGH", 2.1, 1.0, Instant.now().getEpochSecond(), "测试用告警");
        return new IncomingAlert(event, IncomingAlert.State.FIRING, fingerprint,
                event.timestamp(), null, "{}");
    }

    /** 一条面向真实 Prometheus / 日志缓冲的脚本化结论（证据两条，满足 min-evidence-count） */
    private static String diagnosisJson() {
        return """
                {"root_cause":"MySQL 慢 SQL","severity":"HIGH","confidence":0.88,
                 "impact":{"success_rate_change":-5.7,"affected_requests":18231},
                 "evidence":["DB P99 从 20ms 上升到 1900ms","慢 SQL 数量增加 8 倍"],
                 "suggestions":["检查订单查询 SQL 是否命中索引","检查数据库连接池"]}
                """;
    }

    private static String payload(String service, AlertType type, String fingerprint, String status) {
        return """
                {"version":"4","status":"%s","groupKey":"g","alerts":[
                  {"status":"%s",
                   "labels":{"alertname":"%s","severity":"HIGH","service":"%s"},
                   "annotations":{"summary":"测试摘要","value":"2.1","threshold":"1"},
                   "startsAt":"2026-10-09T12:00:00Z",
                   "endsAt":"0001-01-01T00:00:00Z",
                   "fingerprint":"%s"}]}
                """.formatted(status, status, alertNameOf(type), service, fingerprint);
    }

    /** 反向映射：类型 → 一个真实的告警名（用来验证 fromAlertName 那一侧） */
    private static String alertNameOf(AlertType type) {
        return AlertType.index().entrySet().stream()
                .filter(entry -> entry.getValue() == type)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("没有映射到告警名的类型：" + type));
    }
}
