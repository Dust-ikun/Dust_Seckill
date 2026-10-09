package com.dustikun.seckill.monitor.agent;

import tools.jackson.databind.ObjectMapper;
import com.dustikun.seckill.monitor.alert.AlertIngestService;
import com.dustikun.seckill.monitor.alert.AlertmanagerPayload;
import com.dustikun.seckill.monitor.alert.DiagnosisQueryService;
import com.dustikun.seckill.monitor.alert.IncidentAggregator;
import com.dustikun.seckill.monitor.alert.MonitorIncidentProperties;
import com.dustikun.seckill.monitor.analyzer.DiagnosisParser;
import com.dustikun.seckill.monitor.core.AiMonitorMetrics;
import com.dustikun.seckill.monitor.core.MonitorAgentProperties;
import com.dustikun.seckill.monitor.repository.DiagnosisResultStore;
import com.dustikun.seckill.monitor.repository.DiagnosisTaskStore;
import com.dustikun.seckill.monitor.repository.JdbcDiagnosisResultStore;
import com.dustikun.seckill.monitor.repository.JdbcDiagnosisTaskStore;
import com.dustikun.seckill.monitor.repository.JdbcToolExecutionStore;
import com.dustikun.seckill.monitor.repository.ToolExecutionStore;
import com.dustikun.seckill.monitor.tool.ToolRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 批次 3 的装配：LLM 客户端 → Prompt → ReAct 执行器 → 落库 → 告警接收与查询 API。
 *
 * <h2>为什么整批只放在这一个类里</h2>
 * <p>
 * 与 {@code MonitorToolConfiguration} 同一条理由：<b>想知道 Agent 有哪些能力、
 * 它被允许做什么，读这一个类即可</b>。散成七八个 @Configuration 之后，
 * 「这个 Bean 到底是谁建的、用的是哪份配置」就只能靠搜索 ——
 * 而搜索出来的是「有哪些地方提到了它」，不是「它最终是什么」。
 *
 * <h2>三个刻意的设计决定，都在这里可见</h2>
 * <ol>
 *   <li><b>LLM 客户端是一个分支 Bean</b>：配了密钥是
 *       {@link OpenAiCompatibleLlmClient}，没配是 {@link UnavailableLlmClient}。
 *       两者都是<b>生产</b>实现（后者是 SPEC 第 18 节的降级态），
 *       因此这里不用 {@code @ConditionalOnProperty}（那会让「没配密钥」时
 *       整个 Agent 层消失，连告警都不收）；</li>
 *   <li><b>诊断线程池是<b>有界</b>的</b>：并发上限 + 有界队列。理由见
 *       {@link DiagnosisService#MAX_CONCURRENT_DIAGNOSES} 的注释；</li>
 *   <li><b>AI 监控用的是独立的 JdbcTemplate</b>，见
 *       {@link #monitorAiJdbcTemplate(DataSource)} 的注释。</li>
 * </ol>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({MonitorLlmProperties.class, MonitorIncidentProperties.class})
@ConditionalOnProperty(prefix = "seckill.monitor", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class MonitorAgentConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MonitorAgentConfiguration.class);

    /** 诊断任务的排队深度。它决定「告警风暴」时最多积压多少个待诊断任务 */
    private static final int DIAGNOSIS_QUEUE_CAPACITY = 64;

    // ================================================================ LLM

    /**
     * LLM 客户端。
     * <p>构造期只读配置、<b>不发任何网络请求</b>：
     * 一次连通性探测会让「没有出网权限」的环境启动变慢甚至失败，
     * 而且它把「配置对不对」与「端点此刻通不通」两件事混成一件。
     * 真正的连通性由第一次诊断验证，且失败是任务级的（SPEC 第 18 节）。
     */
    @Bean
    public LlmClient monitorLlmClient(MonitorLlmProperties properties, ObjectMapper objectMapper) {
        MonitorLlmProperties normalized = properties.normalized();
        if (!normalized.configured()) {
            // 不是错误路径，是降级态。日志口吻与 ToolSelfCheck 一致。
            log.info("[LlmClient] 进入「未配置 LLM」降级态：{}。"
                    + "告警仍然会被接收、落库与聚合，但不会产出 Diagnosis。", normalized.unavailableReason());
            return new UnavailableLlmClient(normalized.unavailableReason());
        }
        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(normalized, objectMapper);
        log.info("[LlmClient] 已启用：endpoint={}, model={}, key={}, 超时={}ms, 重试={} 次",
                client.endpoint(), normalized.model(), normalized.apiKeyFingerprint(),
                normalized.timeoutMs(), normalized.maxRetries());
        return client;
    }

    @Bean
    public PromptBuilder monitorPromptBuilder(MonitorAgentProperties agentProperties) {
        MonitorAgentProperties agent = agentProperties.normalized();
        PromptBuilder builder = new PromptBuilder(agent.maxToolCalls(), agent.minEvidenceCount());
        log.info("[PromptBuilder] 提示词就绪：{}", builder.describe());
        return builder;
    }

    @Bean
    public DiagnosisParser monitorDiagnosisParser(ObjectMapper objectMapper) {
        return new DiagnosisParser(objectMapper);
    }

    // ================================================================ 存储

    /**
     * AI 监控专用的 JdbcTemplate。
     *
     * <h2>★ 为什么必须标 {@code defaultCandidate = false}</h2>
     * <p>
     * 因为它<b>不是</b>容器里唯一的 {@code JdbcTemplate}：Tool 层的
     * {@code monitorReadOnlyJdbcTemplate} 一直存在，而项目里（以及既有测试里）
     * 大量使用「按类型注入」的写法 {@code @Autowired JdbcTemplate}。
     * 多出第二个候选之后，这些注入点会直接变成
     * {@code No qualifying bean ... expected single matching bean but found 2}
     * —— 而报错出现在<b>与 AI 监控毫无关系的测试类</b>里（实体映射、补偿、并发……），
     * 排查时第一反应绝不会是「AI 层多了一个 Bean」。
     *
     * <p>两个替代方案都不行：
     * <ul>
     *   <li>把它标成 {@code @Primary}：那会让既有代码在不知不觉中改用
     *       「5 秒超时 / maxRows=500」这套参数，而它们原本跑在
     *       「2 秒 / 50 行」上 —— 一个静默的行为变更；</li>
     *   <li>干脆复用 {@code monitorReadOnlyJdbcTemplate}：{@code maxRows=50} 是
     *       JdbcTemplate 层的硬上界，SQL 里写 {@code LIMIT 200} 也会被截断，
     *       于是历史诊断永远只显示 50 条，而那看起来像分页问题。</li>
     * </ul>
     * {@code defaultCandidate = false} 表达的正是我们想要的语义：
     * <b>这个 Bean 存在，但只允许被显式点名使用</b>（三个 Store 都用
     * {@code @Qualifier("monitorAiJdbcTemplate")} 注它）。
     *
     * <h2>为什么不能复用 {@code monitorReadOnlyJdbcTemplate}</h2>
     * <p>
     * 那个模板的两道闸门是按 {@code performance_schema} 的读取场景调的
     * （{@code maxRows=50}、{@code queryTimeout=2s}），直接拿来读
     * {@code ai_diagnosis_task} 会有两个后果：
     * <ul>
     *   <li>{@code maxRows=50} 会<b>静默截断</b>历史查询 ——
     *       它是 JdbcTemplate 层的硬上界，SQL 里写 {@code LIMIT 200} 也没用；</li>
     *   <li>{@code queryTimeout=2s} 是给「工具必须快」这条约束用的。
     *       我们自己的诊断表读取没有这个约束，把 2s 套上去只会在
     *       MySQL 偶尔抖动时让查询 API 报错。</li>
     * </ul>
     * 三道闸门各自独立取值，且都在这里可见。
     *
     * <p>【仍然共用同一个 {@link DataSource}】不新建连接池：连接是稀缺资源，
     * 而监控写入量与业务查询相比可以忽略（一次诊断几十行）。
     */
    @Bean(defaultCandidate = false)
    public JdbcTemplate monitorAiJdbcTemplate(DataSource dataSource) {
        JdbcTemplate template = new JdbcTemplate(dataSource);
        template.setQueryTimeout(5);
        // 比查询 API 的上限（200）宽裕，但不设成「无上限」：诊断表会持续增长，
        // 一次没有 LIMIT 的查询迟早会在某天变成全表扫描。
        template.setMaxRows(500);
        log.info("[AiRepository] AI 监控 JdbcTemplate 就绪：queryTimeout=5s, maxRows=500"
                + "（与 Tool 层的 2s/50 相互独立；已标 defaultCandidate=false，"
                + "避免既有代码的 @Autowired JdbcTemplate 出现两个候选）");
        return template;
    }

    @Bean
    public JdbcDiagnosisTaskStore monitorDiagnosisTaskStore(
            @Qualifier("monitorAiJdbcTemplate") JdbcTemplate jdbcTemplate) {
        return new JdbcDiagnosisTaskStore(jdbcTemplate);
    }

    @Bean
    public JdbcDiagnosisResultStore monitorDiagnosisResultStore(
            @Qualifier("monitorAiJdbcTemplate") JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        return new JdbcDiagnosisResultStore(jdbcTemplate, objectMapper);
    }

    @Bean
    public JdbcToolExecutionStore monitorToolExecutionStore(
            @Qualifier("monitorAiJdbcTemplate") JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        return new JdbcToolExecutionStore(jdbcTemplate, objectMapper);
    }

    // ================================================================ 指标与聚合

    @Bean
    public AiMonitorMetrics monitorAiMetrics(MeterRegistry meterRegistry) {
        return new AiMonitorMetrics(meterRegistry);
    }

    @Bean
    public IncidentAggregator monitorIncidentAggregator(MonitorIncidentProperties properties) {
        // 装配期校验：把「配置被静默忽略」变成「启动即失败」。
        // aggregate-by 只支持 service,alertType，理由写在 MonitorIncidentProperties 的注释里。
        properties.validate();
        MonitorIncidentProperties normalized = properties.normalized();
        return new IncidentAggregator(normalized.window());
    }

    @Bean
    public AlertmanagerPayload monitorAlertmanagerPayload(ObjectMapper objectMapper) {
        return new AlertmanagerPayload(objectMapper);
    }

    // ================================================================ 执行与编排

    /**
     * 诊断线程池。
     * <p>【用虚拟线程 + 有界队列】虚拟线程让「一次诊断占一个线程」的代价可以忽略，
     * 而真正需要限制的是<b>并发数</b>（每次并发都要付费）与<b>排队深度</b>
     * （无限积压会在告警风暴时把内存吃光）。因此核心/最大线程数都等于
     * {@link DiagnosisService#MAX_CONCURRENT_DIAGNOSES}，队列有界，
     * 拒绝策略用默认的 AbortPolicy —— 由 {@code DiagnosisService} 捕获
     * {@code RejectedExecutionException} 并落一条明确的失败记录（而不是静默丢弃）。
     *
     * <p>{@code destroyMethod} 让 Spring 关闭时停掉线程池，
     * 否则测试里反复建上下文会累积线程（与 {@code ToolRegistry} 的处理一致）。
     */
    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService monitorDiagnosisExecutor() {
        int concurrency = DiagnosisService.MAX_CONCURRENT_DIAGNOSES;
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                concurrency, concurrency, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(DIAGNOSIS_QUEUE_CAPACITY),
                Thread.ofVirtual().name("monitor-agent-", 0).factory());
        // 允许核心线程超时回收：诊断是突发性的（一次故障几条），
        // 长期空闲时不该常驻线程（虚拟线程本身很便宜，这条是显式表达意图）。
        executor.allowCoreThreadTimeOut(true);
        log.info("[DiagnosisService] 诊断线程池就绪：并发上限 {}，队列深度 {}（拒绝时落 FAILED 并写明原因）",
                concurrency, DIAGNOSIS_QUEUE_CAPACITY);
        return executor;
    }

    @Bean
    public AgentExecutor monitorAgentExecutor(LlmClient llmClient, ToolRegistry registry,
                                              PromptBuilder promptBuilder, DiagnosisParser parser,
                                              ToolExecutionStore toolExecutionStore,
                                              AiMonitorMetrics metrics, ObjectMapper objectMapper,
                                              MonitorLlmProperties llmProperties,
                                              MonitorAgentProperties agentProperties) {
        AgentExecutor executor = new AgentExecutor(llmClient, registry, promptBuilder, parser,
                toolExecutionStore, metrics, objectMapper, llmProperties, agentProperties);
        MonitorAgentProperties agent = agentProperties.normalized();
        log.info("[AgentExecutor] ReAct 循环就绪：最多 {} 次工具调用，墙钟上限 {}ms，"
                        + "最少独立证据 {} 条；工具清单取 ToolRegistry（{} 个）",
                agent.maxToolCalls(), agent.maxDurationMs(), agent.minEvidenceCount(), registry.size());
        return executor;
    }

    /**
     * 诊断服务。
     * <p>Bean 类型声明为具体的 {@link DiagnosisService}（而不是 {@code DiagnosisTrigger}）：
     * 这样测试既能按接口注入（{@code AlertIngestService} 需要的就是接口），
     * 也能拿到同步入口 {@code diagnose(...)} 直接断言结果。
     */
    @Bean
    public DiagnosisService monitorDiagnosisService(AgentExecutor agentExecutor,
                                                    DiagnosisTaskStore taskStore,
                                                    DiagnosisResultStore resultStore,
                                                    AiMonitorMetrics metrics,
                                                    Executor monitorDiagnosisExecutor) {
        return new DiagnosisService(agentExecutor, taskStore, resultStore, metrics,
                monitorDiagnosisExecutor);
    }

    @Bean
    public AlertIngestService monitorAlertIngestService(DiagnosisTaskStore taskStore,
                                                        IncidentAggregator aggregator,
                                                        DiagnosisTrigger diagnosisTrigger,
                                                        AiMonitorMetrics metrics) {
        return new AlertIngestService(taskStore, aggregator, diagnosisTrigger, metrics);
    }

    @Bean
    public DiagnosisQueryService monitorDiagnosisQueryService(DiagnosisTaskStore taskStore,
                                                              DiagnosisResultStore resultStore,
                                                              ToolExecutionStore toolExecutionStore) {
        return new DiagnosisQueryService(taskStore, resultStore, toolExecutionStore);
    }

    /** 启动自检（应用就绪后执行），见 {@link AgentSelfCheck} */
    @Bean
    public AgentSelfCheck monitorAgentSelfCheck(LlmClient llmClient, PromptBuilder promptBuilder,
                                                ToolRegistry registry,
                                                JdbcDiagnosisTaskStore taskStore,
                                                JdbcDiagnosisResultStore resultStore,
                                                JdbcToolExecutionStore toolStore,
                                                MonitorIncidentProperties incidentProperties) {
        return new AgentSelfCheck(llmClient, promptBuilder, registry, taskStore, resultStore,
                toolStore, incidentProperties);
    }
}
