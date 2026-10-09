package com.dustikun.seckill.monitor.tool;

import com.dustikun.seckill.Config.RocketMqProperties;
import com.dustikun.seckill.Metrics.SeckillMetrics;
import com.dustikun.seckill.Service.CompensateTaskService;
import com.dustikun.seckill.Service.OutboxService;
import com.dustikun.seckill.Service.StockReconcileService;
import com.dustikun.seckill.monitor.core.Masker;
import com.dustikun.seckill.monitor.core.MonitorAgentProperties;
import com.dustikun.seckill.monitor.log.MonitorLogProperties;
import com.dustikun.seckill.monitor.tool.business.BusinessTool;
import com.dustikun.seckill.monitor.tool.db.DbTool;
import com.dustikun.seckill.monitor.tool.logs.LogsTool;
import com.dustikun.seckill.monitor.tool.metrics.HttpPrometheusQuerier;
import com.dustikun.seckill.monitor.tool.metrics.MetricCatalog;
import com.dustikun.seckill.monitor.tool.metrics.MetricsTool;
import com.dustikun.seckill.monitor.tool.metrics.PrometheusQuerier;
import com.dustikun.seckill.monitor.tool.mq.MqTool;
import tools.jackson.databind.ObjectMapper;
import com.dustikun.seckill.monitor.log.LogSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;

/**
 * Tool 层的装配（SPEC 第 9 节与第 23.1 节的白名单）。
 *
 * <h2>为什么白名单必须是这里的 Bean 装配，而不能是配置项</h2>
 * <p>
 * 见 {@link ToolProperties} 的类注释：可配置的能力边界不是边界。
 * 这五个 Bean 就是「Agent 能做什么」的完整清单 ——
 * 想知道 Agent 有哪些能力，读这一个类即可，不必去翻配置或猜。
 *
 * <h2>为什么 Tool 之间不互相注入</h2>
 * <p>
 * 目前五个 Tool 各自只依赖「数据源」，没有 Tool 依赖另一个 Tool。
 * 这是刻意的：一旦 MQ Tool 去调用 Metrics Tool，就会出现
 * 「一次诊断里同一个指标被查两遍、两遍之间状态还变了」这类问题，
 * 而工具之间的组合是 <b>Agent 的职责</b>（它可以在轨迹里显式地调用两次）。
 * 保持 Tool 之间无依赖，也让每个 Tool 都能被单测独立装配（见各自的测试）。
 *
 * <h2>Bean 的销毁</h2>
 * <p>
 * {@link ToolRegistry} 持有一个虚拟线程执行器（用于强制 Tool 超时），
 * 它实现了 {@link AutoCloseable}，Spring 会在容器关闭时自动调用 {@code close()}，
 * 避免测试里反复建上下文时累积线程。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ToolProperties.class, MonitorAgentProperties.class})
@ConditionalOnProperty(prefix = "seckill.monitor", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class MonitorToolConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MonitorToolConfiguration.class);

    /**
     * Prometheus 客户端。
     * <p>它的超时取配置里的 {@code tool.prometheus.timeout-ms}，与
     * {@code agent.tool-timeout-ms} 是两道独立的闸门：
     * 前者管「一次 HTTP 查询等多久」，后者管「一次 Tool 调用等多久」。
     * 前者必须<b>小于</b>后者，否则超时会由外层先触发，而里层的 HTTP 请求
     * 还在占用一个虚拟线程 —— 结果一样，但日志里会失去「是 Prometheus 慢」这条线索。
     * 默认值 2000ms &lt; 3000ms 满足这个关系。
     */
    @Bean
    public PrometheusQuerier monitorPrometheusQuerier(ToolProperties properties,
                                                      ObjectMapper objectMapper) {
        ToolProperties.Prometheus prometheus = properties.normalized().prometheus();
        HttpPrometheusQuerier querier = new HttpPrometheusQuerier(
                prometheus.baseUrl(), prometheus.timeoutMs(), objectMapper);
        log.info("[MetricsTool] Prometheus 客户端就绪：baseUrl={}, 查询超时={}ms, 步长={}s, 最大点数={}",
                prometheus.baseUrl(), prometheus.timeoutMs(), prometheus.queryStepSeconds(),
                prometheus.maxPoints());
        return querier;
    }

    /** 指标目录（白名单）。见 {@link MetricCatalog#defaults()} 里「这些名字是从哪来的」 */
    @Bean
    public MetricCatalog monitorMetricCatalog() {
        return MetricCatalog.defaults();
    }

    /**
     * 给监控用的只读 JdbcTemplate。
     *
     * <h3>为什么不直接用 Spring Boot 自动装配的那个</h3>
     * <p>
     * 两个理由，都是「不要影响业务」：
     * <ol>
     *   <li><b>超时设置是全局的</b>：{@code queryTimeout} 设在上面的语句都会被它限制。
     *       业务查询（落库、对账）绝不能因为监控需要 2 秒上限而被一起改掉；</li>
     *   <li><b>行数上限</b>：{@code maxRows} 同样会影响所有调用。</li>
     * </ol>
     * 因此这里基于同一个 {@link DataSource}（<b>不新建连接池</b> —— 那会让连接数翻倍，
     * 而连接本身是稀缺资源）另建一个 JdbcTemplate，把两道闸门设在自己身上。
     *
     * <h3>为什么闸门设在 JdbcTemplate 上，而不只写在 SQL 里</h3>
     * <p>
     * SQL 里的 {@code LIMIT} 只对该条语句有效；而 {@code performance_schema} 这类表
     * 在异常情况下可能返回比预期多得多的行。两道闸门的分工是：
     * SQL 的 LIMIT 表达<b>业务意图</b>（我只要前 N 条），
     * JdbcTemplate 的 maxRows 表达<b>硬上界</b>（无论如何都不超过 N 条）。
     */
    @Bean
    public JdbcTemplate monitorReadOnlyJdbcTemplate(DataSource dataSource, ToolProperties properties) {
        ToolProperties.Db db = properties.normalized().db();
        JdbcTemplate template = new JdbcTemplate(dataSource);
        template.setQueryTimeout(db.queryTimeoutSeconds());
        template.setMaxRows(db.maxRows());
        log.info("[DbTool] 只读 JdbcTemplate 就绪：queryTimeout={}s, maxRows={}, "
                        + "慢 SQL 门槛={}s, 慢 SQL 条数={}",
                db.queryTimeoutSeconds(), db.maxRows(), db.slowSqlMinSeconds(), db.slowSqlTopN());
        return template;
    }

    // ================================================================ 结果整形

    /**
     * 结果整形器：脱敏 → 体积收缩 → {@code <untrusted_data>} 包裹。
     * <p>它是所有 Tool 的<b>共同出口</b>，因此刻意在这里作为独立 Bean 暴露出来 ——
     * 单测可以直接构造它来验证脱敏与截断，不必启动整个容器。
     */
    @Bean
    public ResultShaper monitorResultShaper(Masker masker, ObjectMapper objectMapper,
                                            ToolProperties properties) {
        int maxChars = properties.normalized().maxResultChars();
        log.info("[ToolRegistry] 结果整形器就绪：单次结果上限={} 字符（含 <untrusted_data> 包裹），"
                + "单字符串上限={} 字符", maxChars, Math.max(256, maxChars / 8));
        return new ResultShaper(masker, objectMapper, maxChars);
    }

    // ================================================================ 五个 Tool

    @Bean
    public MetricsTool metricsTool(PrometheusQuerier querier, MetricCatalog catalog,
                                   ToolProperties properties, MonitorLogProperties logProperties) {
        return new MetricsTool(querier, catalog, properties, logProperties);
    }

    @Bean
    public LogsTool logsTool(LogSink sink, MonitorLogProperties logProperties) {
        return new LogsTool(sink, logProperties);
    }

    @Bean
    public DbTool dbTool(JdbcTemplate monitorReadOnlyJdbcTemplate, ToolProperties properties) {
        return new DbTool(monitorReadOnlyJdbcTemplate, properties);
    }

    @Bean
    public MqTool mqTool(PrometheusQuerier querier, MetricCatalog catalog,
                         RocketMqProperties mqProperties) {
        return new MqTool(querier, catalog, mqProperties);
    }

    @Bean
    public BusinessTool businessTool(StockReconcileService reconcileService, OutboxService outboxService,
                                     CompensateTaskService compensateTaskService, SeckillMetrics metrics,
                                     PrometheusQuerier querier, MetricCatalog catalog) {
        return new BusinessTool(reconcileService, outboxService, compensateTaskService, metrics,
                querier, catalog);
    }

    // ================================================================ 注册表

    /**
     * 白名单注册表，同时是 Tool 的唯一执行入口。
     *
     * <p>【为什么参数是 {@code List<MonitorTool>}】Spring 会把容器里所有
     * {@link MonitorTool} 实现按声明顺序注入。收益是<b>新增一个 Tool 只需加一个 @Bean</b>，
     * 不必回来改这个方法的参数列表 —— 而「忘了改」正是白名单类配置最常见的失效方式
     * （新工具注册不上，表现为「Agent 说它调了，但轨迹里没有」）。
     * 代价是失去显式的顺序控制，因此这里额外断言「至少 5 个」（SPEC 第 24 节），
     * 把「少注册了一个」变成启动期失败而不是运行期的静默缺失。
     */
    @Bean
    public ToolRegistry monitorToolRegistry(List<MonitorTool> tools, ResultShaper shaper,
                                            ObjectProvider<MonitorAgentProperties> agentProperties) {
        long timeout = agentProperties.getIfAvailable(
                        () -> new MonitorAgentProperties(null, null, null, null))
                .normalized().toolTimeoutMs();
        ToolRegistry registry = new ToolRegistry(tools, shaper, timeout);

        // SPEC 第 24 节的验收项：「Agent 能成功调用至少 5 个 Tool」。
        // 它是启动期断言而不是运行期检查：缺一个 Tool 属于装配缺陷，
        // 让应用起不来比让它「少一个能力地跑起来」更诚实 ——
        // 后者会在某次诊断里表现为「Agent 拿不到证据却要下结论」。
        if (registry.size() < 5) {
            throw new IllegalStateException("注册的 Tool 只有 " + registry.size()
                    + " 个，少于 SPEC 第 24 节要求的 5 个：" + registry.names());
        }
        log.info("[ToolRegistry] 工具白名单就绪：{} 个，单次调用超时={}ms。清单：{}",
                registry.size(), timeout, registry.describe());
        return registry;
    }

    /**
     * 启动自检（应用就绪后执行）。见 {@link ToolSelfCheck} 的类注释。
     * <p>它做成独立 Bean 而不是在这里直接 new：单测可以只测注册表而不触发网络自检。
     */
    @Bean
    public ToolSelfCheck monitorToolSelfCheck(ToolRegistry registry, MetricsTool metricsTool,
                                              ResultShaper shaper, LogSink sink) {
        return new ToolSelfCheck(registry, metricsTool, shaper, sink);
    }
}
