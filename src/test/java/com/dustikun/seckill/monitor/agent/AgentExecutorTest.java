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
import com.dustikun.seckill.monitor.tool.ResultShaper;
import com.dustikun.seckill.monitor.tool.ToolRegistry;
import com.dustikun.seckill.monitor.tool.ToolStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ReAct 循环的行为验收（SPEC 第 8.2 / 22 / 23.2 节）。
 *
 * <h2>这一组用例的分工</h2>
 * <p>
 * 用的是<b>真的</b> {@code ToolRegistry} + 真的 {@code ToolArguments} + 真的
 * {@code ResultShaper}，只把「数据源」换成一个假 Tool、把「模型」换成一个脚本。
 * 理由是循环里最容易错的四件事全都发生在这些真实组件的交界处（见
 * {@link ScriptedLlmClient} 的注释）：少回一条 tool 消息、丢掉 tool_call 的 id、
 * 拿坏 JSON 去执行工具、预算用尽后不告诉模型。
 *
 * <h2>关于墙钟超时怎么测</h2>
 * <p>用 {@link StepClock}：每次读时间就前进 5 秒。这样「整次诊断超时」不需要真的等 60 秒 ——
 * 需要等 60 秒才能验的判据，实际上不会被验。
 */
class AgentExecutorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final AlertEvent ALERT = new AlertEvent("ALT-1", "order-service",
            "seckill:http_p99_latency:5m", AlertType.LATENCY_HIGH.name(), "HIGH",
            2.1, 1.0, 1_800_000_000L, "订单创建接口 P99 超过阈值");

    private static final String VALID_DIAGNOSIS = """
            {"incident_id":"INC-20261009-001","severity":"HIGH","service":"order-service",
             "symptom":"P99 2.1s","root_cause":"MySQL 慢 SQL","confidence":0.92,
             "impact":{"success_rate_change":-5.7},
             "evidence":["DB P99 上升到 1900ms","慢 SQL 数量增加 8 倍"],
             "suggestions":["检查索引"]}
            """;

    /** 每次读时间前进固定步长，用来在零成本下验证墙钟熔断 */
    static final class StepClock extends Clock {

        private final Instant base;

        private final long stepMillis;

        private long reads;

        StepClock(long stepMillis) {
            this.base = Instant.parse("2026-10-09T12:00:00Z");
            this.stepMillis = stepMillis;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            reads++;
            return base.plusMillis(reads * stepMillis);
        }
    }

    // ================================================================ 正常路径

    @Test
    @DisplayName("★ 三轮 Tool Calling 后产出结构化结论：SPEC 第 24 节「至少 2~3 轮」的验收")
    void threeRoundsProduceDiagnosis() {
        ScriptedLlmClient llm = ScriptedLlmClient.of(
                ScriptedLlmClient.toolCalls("tool_calls",
                        ScriptedLlmClient.call("c1", "query_metric", "{\"metric\":\"http_p99\"}"),
                        ScriptedLlmClient.call("c2", "search_logs", "{\"keyword\":\"timeout\"}")),
                ScriptedLlmClient.toolCall("c3", "query_business", "{\"operation\":\"GET_ORDER_STATISTICS\"}"),
                ScriptedLlmClient.finalAnswer(VALID_DIAGNOSIS));

        Fixture fixture = fixture(llm, 10, 2);
        AgentRunResult result = fixture.executor.run(7L, "INC-20261009-001", ALERT, null);

        assertEquals(DiagnosisStatus.COMPLETED, result.status(), result.summarize());
        assertEquals(3, result.toolCalls());
        assertEquals(3, result.llmCalls());
        assertNotNull(result.diagnosis());
        assertEquals("MySQL 慢 SQL", result.diagnosis().rootCause());
        assertEquals(2, result.diagnosis().evidence().size());
        assertEquals("INC-20261009-001", result.diagnosis().incidentId());

        assertEquals(List.of("1:query_metric:SUCCESS", "2:search_logs:SUCCESS", "3:query_business:SUCCESS"),
                fixture.trace.sequence(), fixture.trace.toString());
        assertEquals(3, llm.callCount());
        assertFalse(result.steps().isEmpty(), "执行时间线必须留下痕迹");
    }

    @Test
    @DisplayName("★ 每一次 tool_call 都有对应的 role=tool 消息回应，且 tool_call 的 id 原样回灌")
    void everyToolCallGetsAResponse() {
        ScriptedLlmClient llm = ScriptedLlmClient.of(
                ScriptedLlmClient.toolCalls("tool_calls",
                        ScriptedLlmClient.call("c1", "query_metric", "{\"metric\":\"a\"}"),
                        ScriptedLlmClient.call("c2", "search_logs", "{\"keyword\":\"b\"}")),
                ScriptedLlmClient.finalAnswer(VALID_DIAGNOSIS));

        fixture(llm, 10, 2).executor.run(1L, "INC-1", ALERT, null);

        // 第二轮请求里应当有：system, user, assistant, tool, tool
        assertEquals(List.of("SYSTEM", "USER", "ASSISTANT", "TOOL", "TOOL"), llm.rolesOf(2));
        assertEquals(List.of("c1", "c2"), llm.toolCallIdsOf(2),
                "id 顺序错了模型会认为「我请求的调用没有结果」，于是重复调用（费用翻倍）");
        // assistant 消息必须带上 tool_calls 原文
        ChatMessage assistant = llm.request(2).messages().stream()
                .filter(m -> m.role() == ChatMessage.Role.ASSISTANT).findFirst().orElseThrow();
        assertEquals(2, assistant.toolCalls().size());
    }

    @Test
    @DisplayName("每轮请求都下发完整白名单（tools 数组），模型无从编造未注册的工具")
    void toolDefinitionsAreSentEveryRound() {
        ScriptedLlmClient llm = ScriptedLlmClient.of(
                ScriptedLlmClient.toolCall("c1", "query_metric", "{}"),
                ScriptedLlmClient.finalAnswer(VALID_DIAGNOSIS));
        fixture(llm, 10, 2).executor.run(1L, "INC-1", ALERT, null);

        assertEquals(5, llm.toolsOf(1).size());
        assertEquals(5, llm.toolsOf(2).size());
        String first = String.valueOf(llm.toolsOf(1).get(0));
        assertTrue(first.contains("query_metric"), first);
    }

    @Test
    @DisplayName("落库的 arguments 是校验后的实际值：未定义字段被忽略，且模型能看到这条说明")
    void traceKeepsAcceptedArguments() {
        ScriptedLlmClient llm = ScriptedLlmClient.of(
                // 多传一个未定义字段 + 一个合法字段
                ScriptedLlmClient.toolCall("c1", "query_metric",
                        "{\"metric\":\"http_p99\",\"bogus\":\"x\"}"),
                ScriptedLlmClient.finalAnswer(VALID_DIAGNOSIS));
        Fixture fixture = fixture(llm, 10, 2);
        fixture.executor.run(1L, "INC-1", ALERT, null);

        assertEquals(Map.of("metric", "http_p99"), fixture.trace.argumentsOf(1),
                "落库的应当是 accepted()：未定义字段不该出现在里面");
        String toolText = llm.toolTextsOf(2).get(0);
        assertTrue(toolText.contains("未定义字段"), "模型必须被告知哪个字段被忽略了：" + toolText);
        assertEquals("http_p99", fixture.metric.lastAccepted().get("metric"));
    }

    @Test
    @DisplayName("工具执行失败也落轨迹，并把失败原因回灌给模型（它才能转向别的证据）")
    void failedToolIsRecordedAndReported() {
        ScriptedLlmClient llm = ScriptedLlmClient.of(
                ScriptedLlmClient.toolCall("c1", "query_db", "{\"operation\":\"GET_SLOW_SQL\"}"),
                ScriptedLlmClient.finalAnswer(VALID_DIAGNOSIS));
        Fixture fixture = fixture(llm, 10, 2);
        fixture.executor.run(1L, "INC-1", ALERT, null);

        assertEquals(List.of("1:query_db:FAILED"), fixture.trace.sequence());
        assertEquals("测试构造的失败", fixture.trace.all().get(0).errorMessage());
        assertTrue(llm.toolTextsOf(2).get(0).contains("测试构造的失败"),
                "失败原因必须出现在模型看到的那段文本里");
    }

    // ================================================================ 坏入参

    @Test
    @DisplayName("★ 入参不是合法 JSON 时绝不执行工具，而是记一次 REJECTED 并把原因回灌")
    void badJsonArgumentsAreRejectedWithoutExecuting() {
        ScriptedLlmClient llm = ScriptedLlmClient.of(
                ScriptedLlmClient.toolCall("c1", "query_metric", "{\"metric\": \"http_p99\""),
                ScriptedLlmClient.finalAnswer(VALID_DIAGNOSIS));
        Fixture fixture = fixture(llm, 10, 2);
        AgentRunResult result = fixture.executor.run(1L, "INC-1", ALERT, null);

        assertEquals(0, fixture.metric.invocations(),
                "参数坏了还去执行，会得到一次「成功」的调用 —— 结论有证据，证据是假的");
        assertEquals(List.of("1:query_metric:REJECTED"), fixture.trace.sequence());
        String failure = fixture.trace.all().get(0).errorMessage();
        assertTrue(failure.contains("JSON"), failure);

        String toolText = llm.toolTextsOf(2).get(0);
        assertTrue(toolText.contains("REJECTED"), toolText);
        assertNotNull(result.diagnosis(), "一次坏参数不该让整次诊断失败");
    }

    @Test
    @DisplayName("未注册的工具名被拒绝，且错误消息里带上白名单（唯一能让模型自己纠正的信息）")
    void unregisteredToolIsRejectedWithWhitelist() {
        ScriptedLlmClient llm = ScriptedLlmClient.of(
                // SPEC 第 9 节写的是 get_slow_sql()，它是**工具名之外的**旧命名，正好用来当幻觉调用
                ScriptedLlmClient.toolCall("c1", "get_slow_sql", "{}"),
                ScriptedLlmClient.finalAnswer(VALID_DIAGNOSIS));
        Fixture fixture = fixture(llm, 10, 2);
        fixture.executor.run(1L, "INC-1", ALERT, null);

        assertEquals(List.of("1:get_slow_sql:REJECTED"), fixture.trace.sequence());
        String toolText = llm.toolTextsOf(2).get(0);
        assertTrue(toolText.contains("未注册"), toolText);
        assertTrue(toolText.contains("query_db"), "拒绝消息里要有白名单：" + toolText);
    }

    // ================================================================ 预算与墙钟

    @Test
    @DisplayName("★ 工具预算用尽后追加「别再调工具」的消息，并给模型一次收尾机会")
    void budgetExhaustionForcesFinalAnswer() {
        ScriptedLlmClient llm = ScriptedLlmClient.of(
                ScriptedLlmClient.toolCall("c1", "query_metric", "{}"),
                ScriptedLlmClient.toolCall("c2", "query_db", "{}"),
                // 预算（2 次）已用尽，模型这一轮才去收尾
                ScriptedLlmClient.finalAnswer(VALID_DIAGNOSIS));
        Fixture fixture = fixture(llm, 2, 2);
        AgentRunResult result = fixture.executor.run(1L, "INC-1", ALERT, null);

        assertEquals(2, result.toolCalls(), "工具调用次数不得超过 maxToolCalls");
        assertEquals(DiagnosisStatus.COMPLETED, result.status(), result.summarize());

        // 第三轮请求里必须出现那条「别再调工具」的 user 消息
        String thirdRound = llm.request(3).messages().stream()
                .filter(m -> m.role() == ChatMessage.Role.USER)
                .map(ChatMessage::content).reduce("", (a, b) -> a + "\n" + b);
        assertTrue(thirdRound.contains("上限"), thirdRound);
        assertTrue(thirdRound.contains("不要再调用"), thirdRound);
    }

    @Test
    @DisplayName("★ 预算用尽后模型仍然要调工具 → FAILED（不能靠模型自觉，否则循环不会停）")
    void modelIgnoringBudgetFails() {
        // 脚本里每一轮都是工具调用
        ScriptedLlmClient llm = ScriptedLlmClient.of(
                ScriptedLlmClient.toolCall("c1", "query_metric", "{}"),
                ScriptedLlmClient.toolCall("c2", "query_metric", "{}"),
                ScriptedLlmClient.toolCall("c3", "query_metric", "{}"),
                ScriptedLlmClient.toolCall("c4", "query_metric", "{}"));
        Fixture fixture = fixture(llm, 2, 2);
        AgentRunResult result = fixture.executor.run(1L, "INC-1", ALERT, null);

        assertEquals(DiagnosisStatus.FAILED, result.status());
        assertEquals(2, result.toolCalls());
        assertTrue(result.failureReason().contains("已明确要求收尾"), result.failureReason());

        // ★ 本用例真正要锁住的不变量：凡是**发出去过**的请求，
        // 里面的 tool_calls 与 role=tool 消息必须一一对应。
        // 少一条时服务端会以 400 拒绝下一轮，而错误消息只说「请求体不合法」。
        for (int round = 1; round <= llm.callCount(); round++) {
            long toolMessages = llm.rolesOf(round).stream().filter("TOOL"::equals).count();
            assertEquals(countToolCallsInRound(llm, round), toolMessages,
                    "第 " + round + " 轮请求里 tool_calls 与 tool 消息数量不一致：" + llm.rolesOf(round));
        }
    }

    private static long countToolCallsInRound(ScriptedLlmClient llm, int oneBasedIndex) {
        return llm.request(oneBasedIndex).messages().stream()
                .filter(m -> m.role() == ChatMessage.Role.ASSISTANT)
                .mapToLong(m -> m.toolCalls().size())
                .sum();
    }

    @Test
    @DisplayName("★ 整次诊断超过墙钟上限 → FAILED，且已收集的轨迹保留（超时的诊断仍是有用的排障材料）")
    void wallClockTimeoutFails() {
        // StepClock 每次读时间前进 5 分钟，而墙钟上限是 60 秒：
        // 第 1 次读（算 deadline）在 base+5min，第 2 次读（循环检查）在 base+10min > base+6min → 立即超时。
        // 这样「整次诊断超时」不需要真的等 60 秒才验得出来。
        StepClock clock = new StepClock(5 * 60_000L);
        ScriptedLlmClient llm = ScriptedLlmClient.of(ScriptedLlmClient.finalAnswer(VALID_DIAGNOSIS));
        Fixture fixture = fixture(llm, 10, 2, clock);

        AgentRunResult result = fixture.executor.run(1L, "INC-1", ALERT, null);

        assertEquals(DiagnosisStatus.FAILED, result.status(), result.summarize());
        assertTrue(result.failureReason().contains("墙钟上限"), result.failureReason());
        assertEquals(0, llm.callCount(), "超时后不该再发起 LLM 往返（那是在花没有意义的钱）");
    }

    // ================================================================ 降级与失败

    @Test
    @DisplayName("★ 未配置 LLM 时直接进入降级态：报明确原因，一次 LLM 与工具都不调用")
    void unavailableLlmDegrades() {
        ScriptedLlmClient llm = new ScriptedLlmClient()
                .unavailable("未配置 LLM 密钥（环境变量 DEEPSEEK_API_KEY 为空）");
        Fixture fixture = fixture(llm, 10, 2);
        AgentRunResult result = fixture.executor.run(1L, "INC-1", ALERT, null);

        assertEquals(DiagnosisStatus.FAILED, result.status());
        assertTrue(result.failureReason().contains("DEEPSEEK_API_KEY"), result.failureReason());
        assertEquals(0, llm.callCount());
        assertEquals(0, result.toolCalls());
        assertTrue(fixture.trace.all().isEmpty());
    }

    @Test
    @DisplayName("LLM 调用失败（重试后仍失败）→ FAILED，且已经拿到的工具证据保留在轨迹里")
    void llmFailureKeepsCollectedEvidence() {
        ScriptedLlmClient llm = ScriptedLlmClient.of(
                        ScriptedLlmClient.toolCall("c1", "query_metric", "{}"))
                .failFrom(2, new LlmException("HTTP 401：Invalid API key", false, 401, null));
        Fixture fixture = fixture(llm, 10, 2);
        AgentRunResult result = fixture.executor.run(1L, "INC-1", ALERT, null);

        assertEquals(DiagnosisStatus.FAILED, result.status());
        assertTrue(result.failureReason().contains("LLM 调用失败"), result.failureReason());
        assertTrue(result.failureReason().contains("Invalid API key"), result.failureReason());
        assertEquals(1, result.toolCalls());
        assertEquals(1, fixture.trace.countByTaskId(1L), "失败前收集到的证据必须保留");
    }

    // ================================================================ 输出不合规

    @Test
    @DisplayName("★ 最终输出不是 JSON → INSUFFICIENT_EVIDENCE，且原始正文被完整保留在 rawResult 里")
    void nonJsonFinalAnswerIsInsufficientEvidence() {
        String prose = "我觉得是数据库的问题，但没有拿到足够证据。";
        ScriptedLlmClient llm = ScriptedLlmClient.of(ScriptedLlmClient.finalAnswer(prose));
        AgentRunResult result = fixture(llm, 10, 2).executor.run(1L, "INC-1", ALERT, null);

        assertEquals(DiagnosisStatus.INSUFFICIENT_EVIDENCE, result.status());
        assertNull(result.diagnosis(), "解析不出来就没有结论可存 —— 调用方应当走 saveFailure");
        assertEquals(prose, result.rawContent());
        assertEquals(prose, result.rawResult().get("rawContent"),
                "原始正文是调 prompt 时唯一有用的东西，必须落库");
        assertNotNull(result.rawResult().get("failure"));
        assertFalse(result.rawResult().containsKey("root_cause"));
    }

    @Test
    @DisplayName("★ 证据不足时仍然保留结论：那是「Agent 当时怎么想的」，比「没有结论」有用得多")
    void insufficientEvidenceKeepsDiagnosis() {
        ScriptedLlmClient llm = ScriptedLlmClient.of(ScriptedLlmClient.finalAnswer("""
                {"root_cause":"可能是 MySQL","confidence":0.3,"evidence":["只有一个现象"],
                 "suggestions":["再查一次"]}
                """));
        AgentRunResult result = fixture(llm, 10, 2).executor.run(1L, "INC-1", ALERT, null);

        assertEquals(DiagnosisStatus.INSUFFICIENT_EVIDENCE, result.status());
        assertNotNull(result.diagnosis(), "有结论但证据不足，与「解析失败」是两件事");
        assertEquals("可能是 MySQL", result.diagnosis().rootCause());
        assertEquals(1, result.diagnosis().evidence().size());
        assertTrue(result.diagnosis().notes().stream().anyMatch(n -> n.contains("少于要求")),
                result.diagnosis().notes().toString());
    }

    @Test
    @DisplayName("模型返回空内容 → INSUFFICIENT_EVIDENCE，原因是「空内容」而不是「解析失败」")
    void blankFinalAnswerIsReportedSeparately() {
        ScriptedLlmClient llm = ScriptedLlmClient.of(
                new LlmResponse("   ", List.of(), "stop", 10, 0, "{}"));
        AgentRunResult result = fixture(llm, 10, 2).executor.run(1L, "INC-1", ALERT, null);

        assertEquals(DiagnosisStatus.INSUFFICIENT_EVIDENCE, result.status());
        assertTrue(result.failureReason().contains("空内容"), result.failureReason());
    }

    @Test
    @DisplayName("输出被 max_tokens 截断时，失败原因里要指出这一点（它指向调 max-tokens 而不是调 prompt）")
    void truncatedOutputIsAttributed() {
        ScriptedLlmClient llm = ScriptedLlmClient.of(new LlmResponse(
                "{\"root_cause\":\"MySQL", List.of(), "length", 10, 4096, "{}"));
        AgentRunResult result = fixture(llm, 10, 2).executor.run(1L, "INC-1", ALERT, null);

        assertEquals(DiagnosisStatus.INSUFFICIENT_EVIDENCE, result.status());
        assertTrue(result.failureReason().contains("max_tokens"), result.failureReason());
    }

    // ================================================================ 不变量

    @Test
    @DisplayName("轨迹落库失败不能中断诊断（但对可解释性的损失必须记下来）")
    void traceWriteFailureDoesNotBreakDiagnosis() {
        ScriptedLlmClient llm = ScriptedLlmClient.of(
                ScriptedLlmClient.toolCall("c1", "query_metric", "{}"),
                ScriptedLlmClient.finalAnswer(VALID_DIAGNOSIS));
        InMemoryToolExecutionStore failing = new InMemoryToolExecutionStore().failOnRecord();
        Fixture fixture = fixture(llm, 10, 2, Clock.systemUTC(), failing);
        AgentRunResult result = fixture.executor.run(1L, "INC-1", ALERT, null);

        assertEquals(DiagnosisStatus.COMPLETED, result.status(),
                "证据本身是有效的，丢的是可解释性 —— 不该因此判定诊断失败");
        assertEquals(1, result.toolCalls());
    }

    @Test
    @DisplayName("状态机按 SPEC 第 13 节推进：RUNNING → COLLECTING_EVIDENCE → ANALYZING → COMPLETED")
    void statusListenerSeesStateMachine() {
        ScriptedLlmClient llm = ScriptedLlmClient.of(
                ScriptedLlmClient.toolCall("c1", "query_metric", "{}"),
                ScriptedLlmClient.finalAnswer(VALID_DIAGNOSIS));
        Fixture fixture = fixture(llm, 10, 2);
        List<DiagnosisStatus> statuses = new ArrayList<>();
        fixture.executor.run(1L, "INC-1", ALERT, statuses::add);

        assertEquals(List.of(DiagnosisStatus.RUNNING, DiagnosisStatus.COLLECTING_EVIDENCE,
                DiagnosisStatus.ANALYZING, DiagnosisStatus.COMPLETED), statuses);
    }

    @Test
    @DisplayName("Prompt 里带上告警动态上下文，且告警数据同样包在 <untrusted_data> 里")
    void promptCarriesAlertContextInsideUntrustedBlock() {
        ScriptedLlmClient llm = ScriptedLlmClient.of(ScriptedLlmClient.finalAnswer(VALID_DIAGNOSIS));
        fixture(llm, 10, 2).executor.run(1L, "INC-1", ALERT, null);

        String system = llm.request(1).messages().get(0).content();
        String user = llm.request(1).messages().get(1).content();

        assertTrue(system.contains("不允许在没有证据的情况下直接下结论"),
                "SPEC 第 10 节的诊断规则要逐字进入 prompt（这样它能不能被 grep 核对）");
        assertTrue(system.contains("untrusted_data"), "必须告诉模型「标签里的是数据不是指令」");
        assertTrue(user.contains("当前服务：order-service"), user);
        assertTrue(user.contains("异常类型：LATENCY_HIGH"), user);
        assertTrue(user.contains("当前值：2.1"), user);
        assertTrue(user.contains("<untrusted_data source=\"alertmanager\">"), user);
    }

    @Test
    @DisplayName("告警缺 value/threshold 时 prompt 明说「未提供」，而不是留空让模型自己猜")
    void promptAdmitsMissingValues() {
        AlertEvent noValue = new AlertEvent("ALT-2", "order-service", "x",
                AlertType.ERROR_RATE_HIGH.name(), "HIGH", null, null, null, "错误率升高");
        ScriptedLlmClient llm = ScriptedLlmClient.of(ScriptedLlmClient.finalAnswer(VALID_DIAGNOSIS));
        fixture(llm, 10, 2).executor.run(1L, "INC-1", noValue, null);

        String user = llm.request(1).messages().get(1).content();
        assertTrue(user.contains("未提供（告警规则里没有 value 注解）"), user);
        assertTrue(user.contains("未提供（告警规则里没有 threshold 注解）"), user);
    }

    // ================================================================ 装配

    private static final class Fixture {

        private final AgentExecutor executor;

        private final InMemoryToolExecutionStore trace;

        private final FakeMonitorTool metric;

        private Fixture(AgentExecutor executor, InMemoryToolExecutionStore trace, FakeMonitorTool metric) {
            this.executor = executor;
            this.trace = trace;
            this.metric = metric;
        }
    }

    private static Fixture fixture(ScriptedLlmClient llm, int maxToolCalls, int minEvidence) {
        return fixture(llm, maxToolCalls, minEvidence, Clock.systemUTC(),
                new InMemoryToolExecutionStore());
    }

    private static Fixture fixture(ScriptedLlmClient llm, int maxToolCalls, int minEvidence, Clock clock) {
        return fixture(llm, maxToolCalls, minEvidence, clock, new InMemoryToolExecutionStore());
    }

    /**
     * 组装一套「真注册表 + 假数据源」的执行器。
     * <p>白名单用的就是生产里的五个名字，这样「未注册工具」的用例才与真实白名单一致。
     */
    private static Fixture fixture(ScriptedLlmClient llm, int maxToolCalls, int minEvidence,
                                   Clock clock, InMemoryToolExecutionStore trace) {
        FakeMonitorTool metric = new FakeMonitorTool("query_metric",
                Map.of("metric", "http_p99", "avg", 2.1));
        FakeMonitorTool logs = new FakeMonitorTool("search_logs", Map.of("count", 12));
        FakeMonitorTool db = new FakeMonitorTool("query_db", Map.of("rows", 3), true, 0L);
        FakeMonitorTool mq = new FakeMonitorTool("query_mq", Map.of("lag", 0));
        FakeMonitorTool business = new FakeMonitorTool("query_business", Map.of("orderCount", 100));

        Masker masker = new Masker(new MaskProperties(null, null, null));
        ResultShaper shaper = new ResultShaper(masker, MAPPER, 8000);
        ToolRegistry registry = new ToolRegistry(
                List.of(metric, logs, db, mq, business), shaper, 3000);

        MonitorLlmProperties llmProperties = new MonitorLlmProperties(true, "http://localhost",
                "/chat/completions", "test-model", "sk-test", 5000, 1000, 0, 0, 512, 0.0);
        MonitorAgentProperties agentProperties = new MonitorAgentProperties(
                maxToolCalls, 3000, 60_000, minEvidence);

        AgentExecutor executor = new AgentExecutor(llm, registry,
                new PromptBuilder(maxToolCalls, minEvidence),
                new DiagnosisParser(MAPPER), trace,
                new AiMonitorMetrics(new SimpleMeterRegistry()), MAPPER,
                llmProperties, agentProperties, clock);

        return new Fixture(executor, trace, metric);
    }

    /** 一个不会被用到的静态引用，避免「Map/LinkedHashMap 只在一处用到」时 import 被误删 */
    @SuppressWarnings("unused")
    private static Map<String, Object> unused() {
        return new LinkedHashMap<>();
    }

    @Test
    @DisplayName("ToolStatus 的取值与白名单拒绝用的枚举一致（防止将来改名后轨迹里的字符串对不上）")
    void toolStatusNamesAreStable() {
        assertEquals("SUCCESS", ToolStatus.SUCCESS.name());
        assertEquals("FAILED", ToolStatus.FAILED.name());
        assertEquals("REJECTED", ToolStatus.REJECTED.name());
    }
}
