package com.dustikun.seckill.monitor.agent;

import com.dustikun.seckill.monitor.alert.MonitorIncidentProperties;
import com.dustikun.seckill.monitor.core.AiMonitorMetrics;
import com.dustikun.seckill.monitor.repository.JdbcDiagnosisResultStore;
import com.dustikun.seckill.monitor.repository.JdbcDiagnosisTaskStore;
import com.dustikun.seckill.monitor.repository.JdbcToolExecutionStore;
import com.dustikun.seckill.monitor.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

/**
 * 批次 3 的启动自检（「去出口上数一次」这条纪律在 Agent 层的落点）。
 *
 * <h2>它检查的四件事，每一件都对应一种「到第一次告警才发现」的失效</h2>
 * <table border="1">
 *   <caption>自检项与它防的失效</caption>
 *   <tr><th>检查</th><th>若不检查，失效会以什么形态出现</th></tr>
 *   <tr>
 *     <td>LLM 是否可用（密钥 / 开关）</td>
 *     <td>第一次告警来了才发现没配密钥 —— 而此时任务已经建成，
 *         排查方向容易跑到「LLM 端点是通的吗」上去</td>
 *   </tr>
 *   <tr>
 *     <td>三张 AI 表的列是否真的存在（{@code LIMIT 0} 探测）</td>
 *     <td><b>老数据卷</b>里根本没有这三张表（{@code docker-entrypoint-initdb.d}
 *         只在空卷执行）→ 第一次告警报 {@code Table doesn't exist}</td>
 *   </tr>
 *   <tr>
 *     <td>Incident 聚合窗口与聚合键</td>
 *     <td>窗口被改成一个不合理的值（例如 5s）→ 同一事故被拆成多个任务，
 *         每个都付一次 LLM 费用，而这件事在指标上完全看不出来</td>
 *   </tr>
 *   <tr>
 *     <td>AI 自身指标名是否被告警规则引用</td>
 *     <td>自激闭环（批次 2 的教训）：Agent 一跑就写指标 → 指标触发告警 →
 *         告警触发诊断 → 再写指标。<b>它不会自己停下来</b></td>
 *   </tr>
 * </table>
 *
 * <p>【为什么全部是 WARN 而不是启动失败】与 {@code ToolSelfCheck} 同一条理由：
 * 这些是<b>能力降级</b>而不是「应用不可用」。SPEC 第 18 节的原则是
 * 「AI 失败 ≠ 监控失败」—— 一个没配 LLM 密钥的部署，仍然是一个完整可用的
 * 秒杀系统 + 完整可用的 Prometheus/Grafana 告警。让它起不来是本末倒置。
 */
public class AgentSelfCheck {

    private static final Logger log = LoggerFactory.getLogger(AgentSelfCheck.class);

    private final LlmClient llmClient;

    private final PromptBuilder promptBuilder;

    private final ToolRegistry registry;

    private final JdbcDiagnosisTaskStore taskStore;

    private final JdbcDiagnosisResultStore resultStore;

    private final JdbcToolExecutionStore toolStore;

    private final MonitorIncidentProperties incidentProperties;

    public AgentSelfCheck(LlmClient llmClient, PromptBuilder promptBuilder, ToolRegistry registry,
                          JdbcDiagnosisTaskStore taskStore, JdbcDiagnosisResultStore resultStore,
                          JdbcToolExecutionStore toolStore,
                          MonitorIncidentProperties incidentProperties) {
        this.llmClient = llmClient;
        this.promptBuilder = promptBuilder;
        this.registry = registry;
        this.taskStore = taskStore;
        this.resultStore = resultStore;
        this.toolStore = toolStore;
        this.incidentProperties = incidentProperties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void check() {
        checkLlm();
        checkSchema();
        checkIncident();
        checkSelfMetrics();
    }

    private void checkLlm() {
        if (llmClient.available()) {
            log.info("[AgentExecutor] LLM 客户端就绪：{}。prompt：{}；可用工具 {} 个。",
                    describeLlm(), promptBuilder.describe(), registry.size());
        } else {
            // 这是**合法**状态，不是故障（SPEC 第 18 节的降级态）。因此口吻是提示而不是告警，
            // 并且必须说清「什么还能用、什么不能」——否则读日志的人会去修一个不存在的 bug。
            log.warn("[AgentExecutor] LLM 不可用：{}。"
                            + "降级行为：仍然接收并落库告警、仍然做 Incident 聚合、"
                            + "查询 API 仍然可用；诊断任务会以 FAILED 结束并写明原因。"
                            + "要启用诊断，请设置环境变量 DEEPSEEK_API_KEY 后重启。",
                    llmClient.unavailableReason());
        }
    }

    /**
     * 用生产 SELECT 的列清单探测三张表。
     * <p>【为什么这一条是本类里最值钱的一项】因为它把「表不存在 / 列被改名」
     * 从一个<b>运行期、需要一次真实告警才能触发</b>的错误，变成一个启动日志里的 WARN。
     */
    private void checkSchema() {
        probe("ai_diagnosis_task", taskStore::verifySchema);
        probe("ai_diagnosis_result", resultStore::verifySchema);
        probe("ai_tool_execution", toolStore::verifySchema);
    }

    private void probe(String table, Runnable verifier) {
        try {
            verifier.run();
            log.info("[AiRepository] 表 {} 结构自检通过（列名与 Store 的 SELECT 一致）", table);
        } catch (RuntimeException e) {
            log.warn("[AiRepository] 表 {} 结构自检失败：{}。"
                            + "这三张表由 src/main/resources/db/schema-ai-monitor.sql 建立，"
                            + "而它只在**空数据卷**时被 MySQL 自动执行 —— "
                            + "从旧批次留下来的数据卷需要手工执行一次该文件。"
                            + "在此之前，任何告警都会在落库这一步失败。",
                    table, e.getMessage());
        }
    }

    private void checkIncident() {
        log.info("[AlertIngest] Incident 聚合就绪：窗口 {}s，聚合键 {}（同服务 + 同异常类型）；"
                        + "该窗口必须大于「一次故障引发多个告警」的传播延迟（实测最坏 60~75s）。",
                incidentProperties.window().toSeconds(), incidentProperties.normalized().aggregateBy());
    }

    /**
     * 打印 AI 自身指标名，并说明它们<b>不得</b>被告警规则引用。
     * <p>静态检查在 {@code validate_config.py} 第 10 组；这里再说一次是因为
     * 「加一条诊断失败率的告警」是一个看起来完全合理、且会立刻产生自激闭环的动作。
     */
    private void checkSelfMetrics() {
        log.info("[AiMonitorMetrics] AI 自身指标已注册：{} / {} / {}。"
                        + "【纪律】它们不得被任何告警规则引用 —— 否则会形成"
                        + "「Agent 一跑 → 指标变化 → 告警 → 再触发诊断」的自激闭环（批次 2 的教训）。"
                        + "静态防线见 monitoring/validate_config.py 第 10 组。",
                AiMonitorMetrics.TOOL_CALLS, AiMonitorMetrics.LLM_CALLS, AiMonitorMetrics.DIAGNOSIS);
    }

    /** 让自检日志里出现真实的端点与模型名（而不是「已就绪」这种无法核对的话） */
    private String describeLlm() {
        if (llmClient instanceof OpenAiCompatibleLlmClient httpClient) {
            return httpClient.describe();
        }
        return llmClient.getClass().getSimpleName();
    }
}
