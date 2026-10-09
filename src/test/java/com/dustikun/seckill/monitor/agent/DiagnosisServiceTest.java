package com.dustikun.seckill.monitor.agent;

import tools.jackson.databind.ObjectMapper;
import com.dustikun.seckill.monitor.core.AiMonitorMetrics;
import com.dustikun.seckill.monitor.core.AlertEvent;
import com.dustikun.seckill.monitor.core.AlertType;
import com.dustikun.seckill.monitor.core.DiagnosisStatus;
import com.dustikun.seckill.monitor.core.MaskProperties;
import com.dustikun.seckill.monitor.core.Masker;
import com.dustikun.seckill.monitor.core.MonitorAgentProperties;
import com.dustikun.seckill.monitor.analyzer.DiagnosisParser;
import com.dustikun.seckill.monitor.repository.DiagnosisResultRow;
import com.dustikun.seckill.monitor.repository.DiagnosisTaskRow;
import com.dustikun.seckill.monitor.repository.InMemoryDiagnosisResultStore;
import com.dustikun.seckill.monitor.repository.InMemoryDiagnosisTaskStore;
import com.dustikun.seckill.monitor.tool.ResultShaper;
import com.dustikun.seckill.monitor.tool.ToolRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 诊断编排的落库边界（{@code ai_diagnosis_task} + {@code ai_diagnosis_result}）。
 *
 * <h2>这个类要锁住的三条不变量</h2>
 * <ol>
 *   <li><b>任何失败都必须留下一条可读的记录</b>：任务状态 + 原因。
 *       「任务失败了但没人知道为什么」是这一层唯一不可接受的结局；</li>
 *   <li><b>失败原因不能写进 root_cause</b>：那一列的含义是「Agent 判断的根因」，
 *       把「未配置密钥」写进去会污染按根因做的统计，也会让人以为 Agent 有过结论；</li>
 *   <li><b>跑在线程池里的异常不许逃逸</b>：逃逸的表现是任务永远停在 RUNNING，
 *       而界面上看就是「诊断卡住了」——没人会想到去看线程堆栈。</li>
 * </ol>
 */
class DiagnosisServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final AlertEvent ALERT = new AlertEvent("ALT-1", "order-service",
            "seckill:http_p99_latency:5m", AlertType.LATENCY_HIGH.name(), "HIGH",
            2.1, 1.0, 1_800_000_000L, "订单接口 P99 超过阈值");

    private static final String VALID_DIAGNOSIS = """
            {"root_cause":"MySQL 慢 SQL","confidence":0.9,
             "evidence":["DB P99 上升到 1900ms","慢 SQL 数量增加 8 倍"],
             "suggestions":["检查索引"]}
            """;

    /** 直接在当前线程跑的 Executor：让 submit 也变成可断言的同步调用 */
    private static final Executor DIRECT = Runnable::run;

    private static final Executor REJECTING = command -> {
        throw new RejectedExecutionException("测试构造的队列已满");
    };

    private static final class Fixture {

        private final DiagnosisService service;

        private final InMemoryDiagnosisTaskStore taskStore;

        private final InMemoryDiagnosisResultStore resultStore;

        private final ScriptedLlmClient llm;

        private Fixture(DiagnosisService service, InMemoryDiagnosisTaskStore taskStore,
                        InMemoryDiagnosisResultStore resultStore, ScriptedLlmClient llm) {
            this.service = service;
            this.taskStore = taskStore;
            this.resultStore = resultStore;
            this.llm = llm;
        }
    }

    private static Fixture fixture(ScriptedLlmClient llm) {
        return fixture(llm, DIRECT, new InMemoryDiagnosisResultStore());
    }

    private static Fixture fixture(ScriptedLlmClient llm, Executor pool,
                                   InMemoryDiagnosisResultStore resultStore) {
        FakeMonitorTool metric = new FakeMonitorTool("query_metric", Map.of("avg", 2.1));
        FakeMonitorTool logs = new FakeMonitorTool("search_logs", Map.of("count", 3));
        Masker masker = new Masker(new MaskProperties(null, null, null));
        ResultShaper shaper = new ResultShaper(masker, MAPPER, 8000);
        ToolRegistry registry = new ToolRegistry(List.of(metric, logs), shaper, 3000);

        AgentExecutor executor = new AgentExecutor(llm, registry,
                new PromptBuilder(10, 2), new DiagnosisParser(MAPPER),
                new InMemoryToolExecutionStore(),
                new AiMonitorMetrics(new SimpleMeterRegistry()), MAPPER,
                new MonitorLlmProperties(true, "http://localhost", "/chat/completions",
                        "test-model", "sk-test", 5000, 1000, 0, 0, 512, 0.0),
                new MonitorAgentProperties(10, 3000, 60_000, 2),
                Clock.systemUTC());

        InMemoryDiagnosisTaskStore taskStore = new InMemoryDiagnosisTaskStore();
        DiagnosisService service = new DiagnosisService(executor, taskStore, resultStore,
                new AiMonitorMetrics(new SimpleMeterRegistry()), pool, Clock.systemUTC());
        return new Fixture(service, taskStore, resultStore, llm);
    }

    /** 建一个任务并返回它的 id（模拟 AlertIngestService 已经建好任务） */
    private static DiagnosisTaskRow task(Fixture fixture) {
        return fixture.taskStore.create(ALERT, Instant.now());
    }

    // ================================================================ 成功路径

    @Test
    @DisplayName("有结论：写 ai_diagnosis_result，任务状态落 COMPLETED")
    void storesDiagnosisAndFinalStatus() {
        ScriptedLlmClient llm = ScriptedLlmClient.of(
                ScriptedLlmClient.toolCall("c1", "query_metric", "{\"metric\":\"http_p99\"}"),
                ScriptedLlmClient.finalAnswer(VALID_DIAGNOSIS));
        Fixture fixture = fixture(llm);
        DiagnosisTaskRow row = task(fixture);

        AgentRunResult result = fixture.service.diagnose(row.id(), row.incidentId(), ALERT);

        assertEquals(DiagnosisStatus.COMPLETED, result.status());
        DiagnosisResultRow saved = fixture.resultStore.findLatestByTaskId(row.id()).orElseThrow();
        assertEquals("MySQL 慢 SQL", saved.rootCause());
        assertEquals(0.9, saved.confidence());
        assertEquals(2, saved.evidence().size());
        assertNull(saved.failureReason(), "成功路径不该有 failure 标记");
        assertEquals(DiagnosisStatus.COMPLETED,
                fixture.taskStore.findByIncidentId(row.incidentId()).orElseThrow().status());
    }

    @Test
    @DisplayName("submit 走线程池：直接执行的池子让异步行为也可以同步断言")
    void submitRunsThroughPool() {
        ScriptedLlmClient llm = ScriptedLlmClient.of(ScriptedLlmClient.finalAnswer(VALID_DIAGNOSIS));
        Fixture fixture = fixture(llm);
        DiagnosisTaskRow row = task(fixture);

        fixture.service.submit(row.id(), row.incidentId(), ALERT);

        assertEquals(1, llm.callCount());
        assertEquals(DiagnosisStatus.COMPLETED,
                fixture.taskStore.findByIncidentId(row.incidentId()).orElseThrow().status());
    }

    // ================================================================ 失败路径

    @Test
    @DisplayName("★ 未配置 LLM：失败原因是「没配密钥」，不是 NPE，也不是「诊断过程异常」")
    void unavailableLlmRecordsExplicitReason() {
        ScriptedLlmClient llm = new ScriptedLlmClient()
                .unavailable("未配置 LLM 密钥（环境变量 DEEPSEEK_API_KEY 为空）");
        Fixture fixture = fixture(llm);
        DiagnosisTaskRow row = task(fixture);

        AgentRunResult result = fixture.service.diagnose(row.id(), row.incidentId(), ALERT);

        assertEquals(DiagnosisStatus.FAILED, result.status());
        assertTrue(result.failureReason().contains("DEEPSEEK_API_KEY"), result.failureReason());
        DiagnosisResultRow saved = fixture.resultStore.findLatestByTaskId(row.id()).orElseThrow();
        assertTrue(saved.failureReason().contains("DEEPSEEK_API_KEY"), saved.failureReason());
        assertNull(saved.rootCause(), "失败原因不能写进 root_cause —— 那一列是「Agent 判断的根因」");
        assertNull(saved.severity());
        assertEquals(0, llm.callCount());
    }

    @Test
    @DisplayName("★ 自监控告警不花 LLM 的钱，但仍然留下任务与一条明确的原因")
    void selfMonitoringAlertSkipsLlm() {
        ScriptedLlmClient llm = ScriptedLlmClient.of(ScriptedLlmClient.finalAnswer(VALID_DIAGNOSIS));
        Fixture fixture = fixture(llm);
        AlertEvent selfMonitoring = new AlertEvent("ALT-2", "order-service", "x",
                AlertType.SELF_MONITORING.name(), "WARNING", null, null, null, "Prometheus 规则求值失败");
        DiagnosisTaskRow row = fixture.taskStore.create(selfMonitoring, Instant.now());

        AgentRunResult result = fixture.service.diagnose(row.id(), row.incidentId(), selfMonitoring);

        assertEquals(DiagnosisStatus.FAILED, result.status());
        assertEquals(0, llm.callCount(), "自监控告警不该触发 LLM 调用");
        DiagnosisResultRow saved = fixture.resultStore.findLatestByTaskId(row.id()).orElseThrow();
        assertTrue(saved.failureReason().contains("监控系统自身"), saved.failureReason());
    }

    @Test
    @DisplayName("★ 输出不是 JSON：落 INSUFFICIENT_EVIDENCE，原始正文进 raw_result（调 prompt 的唯一依据）")
    void parseFailureKeepsRawContent() {
        ScriptedLlmClient llm = ScriptedLlmClient.of(
                ScriptedLlmClient.finalAnswer("我觉得是数据库的问题。"));
        Fixture fixture = fixture(llm);
        DiagnosisTaskRow row = task(fixture);

        AgentRunResult result = fixture.service.diagnose(row.id(), row.incidentId(), ALERT);

        assertEquals(DiagnosisStatus.INSUFFICIENT_EVIDENCE, result.status());
        DiagnosisResultRow saved = fixture.resultStore.findLatestByTaskId(row.id()).orElseThrow();
        assertNull(saved.rootCause());
        assertEquals("我觉得是数据库的问题。", saved.rawResult().get("rawContent"));
        assertNotNull(saved.rawResult().get("failure"));
        assertEquals(DiagnosisStatus.INSUFFICIENT_EVIDENCE,
                fixture.taskStore.findByIncidentId(row.incidentId()).orElseThrow().status());
    }

    @Test
    @DisplayName("证据不足但有结论：结论照样落库（那是「Agent 当时怎么想的」）")
    void insufficientEvidenceStillStoresDiagnosis() {
        ScriptedLlmClient llm = ScriptedLlmClient.of(ScriptedLlmClient.finalAnswer("""
                {"root_cause":"可能是 MySQL","confidence":0.2,"evidence":["只有一个现象"],
                 "suggestions":["再查一次"]}
                """));
        Fixture fixture = fixture(llm);
        DiagnosisTaskRow row = task(fixture);

        AgentRunResult result = fixture.service.diagnose(row.id(), row.incidentId(), ALERT);

        assertEquals(DiagnosisStatus.INSUFFICIENT_EVIDENCE, result.status());
        DiagnosisResultRow saved = fixture.resultStore.findLatestByTaskId(row.id()).orElseThrow();
        assertEquals("可能是 MySQL", saved.rootCause(), "结论必须保留，否则调 prompt 时看不到模型怎么想的");
        assertEquals(1, saved.evidence().size());
    }

    @Test
    @DisplayName("★ 编排过程抛出未预期异常（真库里可能是落库失败）：任务必须落 FAILED 而不是卡在 RUNNING")
    void unexpectedExceptionIsRecordedAsFailed() {
        ScriptedLlmClient llm = ScriptedLlmClient.of(ScriptedLlmClient.finalAnswer(VALID_DIAGNOSIS));
        // 结论落库直接失败 → diagnose 内部的 try/catch 必须把它变成一条 FAILED 记录
        Fixture fixture = fixture(llm, DIRECT, new InMemoryDiagnosisResultStore().failOnSave());
        DiagnosisTaskRow row = task(fixture);

        AgentRunResult result = fixture.service.diagnose(row.id(), row.incidentId(), ALERT);

        assertEquals(DiagnosisStatus.FAILED, result.status());
        assertTrue(result.failureReason().contains("诊断过程异常"), result.failureReason());
        assertEquals(DiagnosisStatus.FAILED,
                fixture.taskStore.findByIncidentId(row.incidentId()).orElseThrow().status());
    }

    @Test
    @DisplayName("★ 线程池队列已满：submit 必须留下一条明确的失败记录，而不是静默丢弃")
    void rejectedSubmissionIsRecorded() {
        ScriptedLlmClient llm = ScriptedLlmClient.of(ScriptedLlmClient.finalAnswer(VALID_DIAGNOSIS));
        Fixture fixture = fixture(llm, REJECTING, new InMemoryDiagnosisResultStore());
        DiagnosisTaskRow row = task(fixture);

        fixture.service.submit(row.id(), row.incidentId(), ALERT);

        assertEquals(0, llm.callCount(), "被拒绝的提交不该真的去调 LLM");
        assertEquals(DiagnosisStatus.FAILED,
                fixture.taskStore.findByIncidentId(row.incidentId()).orElseThrow().status(),
                "被丢弃的诊断在界面上与「还在排队」完全一样，因此必须落 FAILED");
        DiagnosisResultRow saved = fixture.resultStore.findLatestByTaskId(row.id()).orElseThrow();
        assertTrue(saved.failureReason().contains("线程池已满"), saved.failureReason());
    }

    @Test
    @DisplayName("submit 里的任务不存在时也不抛异常（异步线程里抛异常只会变成一行没人看的堆栈）")
    void submitWithMissingTaskDoesNotThrow() {
        ScriptedLlmClient llm = ScriptedLlmClient.of(
                new LlmResponse("", List.of(), "stop", null, null, "{}"));
        Fixture fixture = fixture(llm);

        fixture.service.submit(999L, "INC-not-exist", ALERT);

        assertEquals(1, llm.callCount());
        // 任务不存在 → updateStatus 返回 false，但流程不炸；结论行仍会写下（用不存在的 task_id）。
        // 这一点是刻意的：宁可多一条孤儿结论行（可用于排查），也不要让诊断无声中断。
        assertFalse(fixture.resultStore.all().isEmpty());
    }

    @Test
    @DisplayName("并发上限是一个常量而不是配置项：它是防付费失控的闸门，改它必须走 code review")
    void concurrencyLimitIsNotConfigurable() {
        assertEquals(2, DiagnosisService.MAX_CONCURRENT_DIAGNOSES);
    }
}
