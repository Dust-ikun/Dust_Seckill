package com.dustikun.seckill.monitor.agent;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.dustikun.seckill.monitor.core.AlertEvent;
import com.dustikun.seckill.monitor.analyzer.Diagnosis;
import com.dustikun.seckill.monitor.analyzer.DiagnosisDefaults;
import com.dustikun.seckill.monitor.analyzer.DiagnosisParseException;
import com.dustikun.seckill.monitor.analyzer.DiagnosisParser;
import com.dustikun.seckill.monitor.core.AiMonitorMetrics;
import com.dustikun.seckill.monitor.core.DiagnosisStatus;
import com.dustikun.seckill.monitor.core.MonitorAgentProperties;
import com.dustikun.seckill.monitor.repository.ToolExecutionStore;
import com.dustikun.seckill.monitor.tool.ToolRegistry;
import com.dustikun.seckill.monitor.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ReAct 诊断循环（SPEC 第 8.2 节的工作模式 + 第 22 节的伪代码）。
 *
 * <h2>SPEC 第 22 节的伪代码缺了 8 项，这里逐项落点如下</h2>
 * <pre>
 *   最大 Tool 调用次数   props.maxToolCalls（到点后强制收尾，见下）
 *   Timeout              props.maxDurationMs（墙钟，每轮 LLM 之前检查）
 *   Tool 白名单           ToolRegistry（唯一执行入口）
 *   参数校验             ToolArguments（在 ToolRegistry 内部）
 *   Result 大小限制       ResultShaper（在 ToolRegistry 内部）
 *   LLM Retry            OpenAiCompatibleLlmClient（内部按 SPEC 第 18 节重试 1~2 次）
 *   JSON Schema 校验      DiagnosisParser
 *   日志脱敏             Masker / ResultShaper（工具侧）；本类只把 llmText 回灌给模型
 * </pre>
 *
 * <h2>四个「不做就会出事」的实现细节</h2>
 * <ol>
 *   <li><b>每一次 {@code tool_call} 都要有一条 {@code tool} 消息回应</b>，
 *       包括<b>因预算用尽而没有执行</b>的那些。协议要求 tool_calls 与 tool 消息一一对应；
 *       少一条，服务端会以 400 拒绝下一轮请求 ——
 *       而错误消息会说「请求体不合法」，看不出真正的错因是「少回了一条结果」。</li>
 *   <li><b>预算用尽后必须显式告诉模型「别再调了」</b>，否则它只会看到工具莫名被拒，
 *       然后把同一个调用换个写法再试一遍 —— 那正是预算要防的行为。</li>
 *   <li><b>强制收尾只给一次机会</b>：已明确要求收尾后模型若仍返回工具调用，
 *       直接以 {@code FAILED} 结束。没有这条，循环就退化成「靠模型自觉」。</li>
 *   <li><b>工具入参不是合法 JSON 时，绝不能拿空参数去执行</b>。
 *       那会得到一次「成功」的调用，但它查的东西不是模型要的：
 *       结论有证据，证据是假的。这类失败必须走 {@code ToolRegistry#reject}
 *       （同一条整形出口，模型的下一轮才看得到发生了什么）。</li>
 * </ol>
 *
 * <h2>为什么墙钟用 {@link Clock} 注入</h2>
 * <p>
 * 因为「整次诊断超时」这条判据如果只能在真实时间里等 60 秒才测得出来，
 * 它就不会被测。注入 Clock 让「一进入循环就已经过期」这种用例变成毫秒级。
 */
public final class AgentExecutor {

    private static final Logger log = LoggerFactory.getLogger(AgentExecutor.class);

    /** 工具执行状态变化的观察者（推进 {@code ai_diagnosis_task.status}，SPEC 第 13 节） */
    @FunctionalInterface
    public interface StatusListener {
        void onStatus(DiagnosisStatus status);

        /** 不需要观察者时用这个（单测、以及「只要结果不要中间态」的调用方） */
        StatusListener NOOP = status -> { };
    }

    private final LlmClient llm;

    private final ToolRegistry registry;

    private final PromptBuilder prompts;

    private final DiagnosisParser parser;

    private final ToolExecutionStore traceStore;

    private final AiMonitorMetrics metrics;

    private final ObjectMapper objectMapper;

    private final MonitorLlmProperties llmProperties;

    private final int maxToolCalls;

    private final int minEvidenceCount;

    private final long maxDurationMillis;

    private final Clock clock;

    public AgentExecutor(LlmClient llm, ToolRegistry registry, PromptBuilder prompts,
                         DiagnosisParser parser, ToolExecutionStore traceStore,
                         AiMonitorMetrics metrics, ObjectMapper objectMapper,
                         MonitorLlmProperties llmProperties, MonitorAgentProperties agentProperties) {
        this(llm, registry, prompts, parser, traceStore, metrics, objectMapper, llmProperties,
                agentProperties, Clock.systemDefaultZone());
    }

    /** 供测试注入固定时钟（见类注释最后一段） */
    public AgentExecutor(LlmClient llm, ToolRegistry registry, PromptBuilder prompts,
                         DiagnosisParser parser, ToolExecutionStore traceStore,
                         AiMonitorMetrics metrics, ObjectMapper objectMapper,
                         MonitorLlmProperties llmProperties, MonitorAgentProperties agentProperties,
                         Clock clock) {
        MonitorAgentProperties agent = agentProperties.normalized();
        this.llm = llm;
        this.registry = registry;
        this.prompts = prompts;
        this.parser = parser;
        this.traceStore = traceStore;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
        this.llmProperties = llmProperties.normalized();
        this.maxToolCalls = agent.maxToolCalls();
        this.minEvidenceCount = agent.minEvidenceCount();
        this.maxDurationMillis = agent.maxDurationMs();
        this.clock = clock;
    }

    /**
     * 跑一次诊断。
     * <p>【本方法不抛异常】所有失败都编码进 {@link AgentRunResult}。
     * 理由是调用方是告警接收路径，而 SPEC 第 18 节要求「AI 失败 ≠ 监控失败」：
     * 让它 try/catch 才能继续，就等于给「AI 故障拖垮告警接收」留了一条路。
     *
     * @param taskId     {@code ai_diagnosis_task.id}，轨迹落库用
     * @param incidentId 事故编号（权威值，回灌进 prompt 并要求模型原样回填）
     * @param alert      触发本次诊断的告警（第一条，不是聚合进来的那些）
     * @param listener   状态机回调，见 {@link StatusListener}
     */
    public AgentRunResult run(long taskId, String incidentId, AlertEvent alert,
                              StatusListener listener) {
        StatusListener notify = listener == null ? StatusListener.NOOP : listener;
        long startNanos = System.nanoTime();
        Instant deadline = clock.instant().plusMillis(maxDurationMillis);
        List<String> steps = new ArrayList<>(16);
        List<ChatMessage> messages = new ArrayList<>(prompts.openConversation(alert));
        DiagnosisDefaults defaults = new DiagnosisDefaults(
                incidentId, alert.serviceName(), alert.severity(), alert.description());

        // ---------- 降级检查：没配 LLM 就不要假装在诊断 ----------
        if (!llm.available()) {
            String reason = llm.unavailableReason();
            steps.add("未开始：LLM 不可用（" + reason + "）");
            notify.onStatus(DiagnosisStatus.FAILED);
            metrics.diagnosis(DiagnosisStatus.FAILED.name());
            log.warn("[AgentExecutor] task={} 进入降级态：{}", taskId, reason);
            return failure(DiagnosisStatus.FAILED, reason, 0, 0, startNanos, steps, alert, null, null);
        }

        notify.onStatus(DiagnosisStatus.RUNNING);
        steps.add("开始诊断：" + alert.summarize());

        // tools 数组是白名单的唯一来源，且在一次诊断内不会变，因此只取一次。
        List<Map<String, Object>> toolDefinitions = registry.definitions();

        int toolCalls = 0;
        int llmCalls = 0;
        boolean collectingNotified = false;
        boolean finalAnswerForced = false;
        String lastFinishReason = null;

        while (true) {
            // ---------- 墙钟熔断：在每一次 LLM 往返之前检查 ----------
            if (!clock.instant().isBefore(deadline)) {
                String reason = "整次诊断超过墙钟上限 " + maxDurationMillis
                        + "ms，已保留已收集的证据与轨迹";
                steps.add(reason);
                notify.onStatus(DiagnosisStatus.FAILED);
                metrics.diagnosis(DiagnosisStatus.FAILED.name());
                return failure(DiagnosisStatus.FAILED, reason, toolCalls, llmCalls, startNanos,
                        steps, alert, lastFinishReason, null);
            }

            LlmRequest request = new LlmRequest(llmProperties.model(), List.copyOf(messages),
                    toolDefinitions, llmProperties.temperature(), llmProperties.maxTokens());

            LlmResponse response;
            try {
                llmCalls++;
                response = llm.chat(request);
                metrics.llmCall("SUCCESS");
            } catch (LlmException e) {
                metrics.llmCall("FAILED");
                String reason = "LLM 调用失败（已按 SPEC 第 18 节重试）：" + e.brief();
                steps.add(reason);
                notify.onStatus(DiagnosisStatus.FAILED);
                metrics.diagnosis(DiagnosisStatus.FAILED.name());
                log.warn("[AgentExecutor] task={} {}", taskId, reason);
                return failure(DiagnosisStatus.FAILED, reason, toolCalls, llmCalls, startNanos,
                        steps, alert, lastFinishReason, null);
            }
            lastFinishReason = response.finishReason();
            steps.add("LLM 第 " + llmCalls + " 轮：" + response.describe());

            // ---------- 没有工具调用 = 最终答复 ----------
            if (!response.hasToolCalls()) {
                if (response.isBlankFinal()) {
                    // 「模型返回了空内容」与「解析失败」是两件事：前者要查模型/配额，
                    // 后者要查 prompt。因此这里单独给一条原因，且结局是
                    // INSUFFICIENT_EVIDENCE（连内容都没有，谈不上「输出不合规」）。
                    String reason = "LLM 返回了空内容（既没有工具调用，也没有正文）"
                            + (response.truncatedByLength()
                                    ? "，且 finish_reason=length（被 max_tokens 截断）" : "");
                    steps.add(reason);
                    notify.onStatus(DiagnosisStatus.INSUFFICIENT_EVIDENCE);
                    metrics.diagnosis(DiagnosisStatus.INSUFFICIENT_EVIDENCE.name());
                    return failure(DiagnosisStatus.INSUFFICIENT_EVIDENCE, reason, toolCalls, llmCalls,
                            startNanos, steps, alert, lastFinishReason, response.content());
                }
                return conclude(notify, response, toolCalls, llmCalls, startNanos, steps,
                        defaults, alert, lastFinishReason);
            }

            // ---------- 有工具调用 ----------
            if (finalAnswerForced) {
                // 已经明确要求收尾，模型仍然要调工具：见类注释第 3 条，直接结束。
                String reason = "已明确要求收尾，模型仍返回工具调用（finish_reason="
                        + lastFinishReason + "），为避免无限循环已终止本次诊断";
                steps.add(reason);
                notify.onStatus(DiagnosisStatus.FAILED);
                metrics.diagnosis(DiagnosisStatus.FAILED.name());
                return failure(DiagnosisStatus.FAILED, reason, toolCalls, llmCalls, startNanos,
                        steps, alert, lastFinishReason, response.content());
            }

            // 原样回灌 tool_calls：丢掉 id 或换顺序会让模型认为「我请求的调用没有结果」，
            // 于是重复调用同一个工具（费用翻倍，而轨迹看起来完全正常）。
            messages.add(ChatMessage.assistant(response.content(), response.toolCalls()));

            for (ToolCallRequest call : response.toolCalls()) {
                if (toolCalls >= maxToolCalls) {
                    // 见类注释第 1 条：没执行的调用同样必须有回应。
                    messages.add(ChatMessage.tool(call.id(), call.name(),
                            "未执行：本次诊断的工具调用次数已达上限（" + maxToolCalls + " 次）。"));
                    continue;
                }
                toolCalls++;
                ToolResult result = executeCall(taskId, toolCalls, call);
                steps.add("工具 " + result.summarize());
                messages.add(ChatMessage.tool(call.id(), call.name(),
                        result.llmText() == null ? "(没有结果文本)" : result.llmText()));
                if (result.successful() && !collectingNotified) {
                    collectingNotified = true;
                    notify.onStatus(DiagnosisStatus.COLLECTING_EVIDENCE);
                }
            }

            if (toolCalls >= maxToolCalls && !finalAnswerForced) {
                finalAnswerForced = true;
                messages.add(ChatMessage.user(prompts.toolBudgetExhaustedMessage()));
                steps.add("工具预算用尽（" + maxToolCalls + " 次），已要求模型收尾");
            }
        }
    }

    // ================================================================ 收尾

    /**
     * 解析最终 JSON 并判定结局。
     *
     * <p>【两种 {@code INSUFFICIENT_EVIDENCE} 必须分开构造】
     * <ul>
     *   <li>解析成功但证据条数不够 → {@code diagnosis} 非空，调用方会把它<b>存进</b>
     *       {@code ai_diagnosis_result}（「Agent 当时怎么想的」是有价值的）；</li>
     *   <li>输出根本不是合法 JSON → {@code diagnosis} 为空，只能走 {@code saveFailure}，
     *       原始正文进 {@code raw_result}（可行性报告验收判据 3.4）。</li>
     * </ul>
     */
    private AgentRunResult conclude(StatusListener notify, LlmResponse response, int toolCalls,
                                    int llmCalls, long startNanos, List<String> steps,
                                    DiagnosisDefaults defaults, AlertEvent alert,
                                    String finishReason) {
        notify.onStatus(DiagnosisStatus.ANALYZING);
        String content = response.content();
        try {
            Diagnosis diagnosis = parser.parse(content, defaults, minEvidenceCount);
            boolean sufficient = diagnosis.evidenceSufficient(minEvidenceCount);
            DiagnosisStatus status = sufficient
                    ? DiagnosisStatus.COMPLETED : DiagnosisStatus.INSUFFICIENT_EVIDENCE;
            notify.onStatus(status);
            metrics.diagnosis(status.name());
            steps.add("产出结论：" + status + "，证据 " + diagnosis.evidence().size()
                    + " 条，置信度 " + diagnosis.confidenceText());
            log.info("[AgentExecutor] 诊断完成：{}（工具 {} 次，LLM {} 轮，{}ms）根因={}",
                    status, toolCalls, llmCalls, elapsedMillis(startNanos), diagnosis.rootCause());

            Map<String, Object> raw = commonRaw(llmProperties.model(), finishReason, llmCalls,
                    toolCalls, content, alert);
            return new AgentRunResult(status, diagnosis, toolCalls, llmCalls,
                    elapsedMillis(startNanos), null, content, raw, steps);
        } catch (DiagnosisParseException e) {
            notify.onStatus(DiagnosisStatus.INSUFFICIENT_EVIDENCE);
            metrics.diagnosis(DiagnosisStatus.INSUFFICIENT_EVIDENCE.name());
            String reason = "Diagnosis 解析失败：" + e.getMessage()
                    + (response.truncatedByLength()
                            ? "（finish_reason=length，输出被 max_tokens 截断）" : "");
            steps.add(reason);
            log.warn("[AgentExecutor] 诊断输出不合规：{}", reason);
            return failure(DiagnosisStatus.INSUFFICIENT_EVIDENCE, reason, toolCalls, llmCalls,
                    startNanos, steps, alert, finishReason, content);
        }
    }

    // ================================================================ 单次工具调用

    /**
     * 执行一次工具调用并落轨迹。
     * <p>轨迹的写入在<b>这里</b>，而不是在循环外批量写 —— 理由见
     * {@link ToolExecutionStore} 的类注释（超时失败时批量写会恰好丢掉轨迹）。
     */
    private ToolResult executeCall(long taskId, int sequenceNo, ToolCallRequest call) {
        ToolResult result = dispatch(call);
        try {
            traceStore.record(taskId, sequenceNo, result, clock.instant());
        } catch (RuntimeException e) {
            // 轨迹写不进去不能中断诊断：证据本身是有效的，丢的是「可解释性」。
            // 但这必须显式记录 —— 静默吞掉会让「轨迹缺了一段」永远查不出来。
            log.error("[AgentExecutor] task={} 轨迹落库失败（诊断继续）：tool={}, seq={}",
                    taskId, call.name(), sequenceNo, e);
        }
        metrics.toolCall(result.toolName(), result.status().name());
        return result;
    }

    private ToolResult dispatch(ToolCallRequest call) {
        Map<String, Object> arguments = parseArguments(call);
        if (arguments == null) {
            // 见类注释第 4 条：绝不拿空参数去执行。
            return registry.reject(call.name(),
                    "arguments 不是合法的 JSON 对象，因此没有执行。请重新生成一个合法的 JSON 参数对象。"
                            + "收到的原文：" + abbreviate(call.argumentsJson()));
        }
        return registry.invoke(call.name(), arguments);
    }

    /**
     * 解析模型给的参数串。
     *
     * @return 解析后的 Map；不是合法 JSON 对象时返回 {@code null}（由调用方转成一次 REJECTED）
     */
    private Map<String, Object> parseArguments(ToolCallRequest call) {
        if (call.blankArguments()) {
            // 无参调用是合法的（例如 query_db 可以全靠默认值）。
            return Map.of();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(call.argumentsJson(),
                    new TypeReference<LinkedHashMap<String, Object>>() { });
            return parsed == null ? Map.of() : parsed;
        } catch (RuntimeException e) {
            // 【Jackson 3 的一个 API 变化】JacksonException 现在继承 RuntimeException，
            // 因此不能写 `catch (JacksonException | RuntimeException e)`（多捕获不允许有子父关系）。
            // 直接捕获 RuntimeException 覆盖两者，也更稳：模型给的串还可能触发
            // StreamReadException 之外的解析期异常。
            log.debug("[AgentExecutor] tool={} 的参数不是合法 JSON 对象：{}",
                    call.name(), e.getMessage());
            return null;
        }
    }

    // ================================================================ 结果构造

    private AgentRunResult failure(DiagnosisStatus status, String reason, int toolCalls, int llmCalls,
                                   long startNanos, List<String> steps, AlertEvent alert,
                                   String finishReason, String rawContent) {
        Map<String, Object> raw = commonRaw(llmProperties.model(), finishReason, llmCalls, toolCalls,
                rawContent, alert);
        raw.put("failure", reason);
        return new AgentRunResult(status, null, toolCalls, llmCalls,
                elapsedMillis(startNanos), reason, rawContent, raw, steps);
    }

    /**
     * {@code raw_result} 的公共部分。
     * <p>「是哪个模型、几轮往返、几次工具、finish_reason 是什么」这四项放在一起，
     * 是因为它们共同回答一个实际问题：<b>这次输出为什么会是这个样子</b>
     * （被截断了？模型记错了？还是压根没问成？）。
     */
    private static Map<String, Object> commonRaw(String model, String finishReason, int llmCalls,
                                                 int toolCalls, String rawContent, AlertEvent alert) {
        Map<String, Object> raw = new LinkedHashMap<>(AgentRunResult.rawResultOf(
                model, finishReason, llmCalls, toolCalls, rawContent));
        raw.put("service", alert.serviceName());
        raw.put("alertType", alert.alertType());
        return raw;
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "(空)";
        }
        String oneLine = text.replace('\n', ' ').trim();
        return oneLine.length() <= 300 ? oneLine : oneLine.substring(0, 300) + "…";
    }
}
