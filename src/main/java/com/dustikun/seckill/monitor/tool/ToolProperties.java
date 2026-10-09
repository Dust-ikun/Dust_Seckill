package com.dustikun.seckill.monitor.tool;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tool 层配置（{@code seckill.monitor.tool.*}，SPEC 第 23.3 节「Token 限制」的落点）。
 *
 * <h2>为什么这些参数在配置里，而「白名单」在代码里</h2>
 * <p>
 * 两者都是约束，但性质不同：
 * <ul>
 *   <li><b>体积类</b>（返回多少条、多少字符）改的是<b>成本与信噪比</b>，
 *       属于「按环境和钱包调」的事，必须在配置里；</li>
 *   <li><b>白名单</b>改的是<b>能力边界</b>。把它放进配置就意味着它可以在运行时被扩展，
 *       而一个「可以在运行时被扩展的能力边界」不是边界 —— 它是一条
 *       迟早会被写进某个环境变量里的注释。因此白名单是
 *       {@code MonitorToolConfiguration} 里的 Bean 装配，改它必须改代码并走 review。</li>
 * </ul>
 *
 * <h2>为什么每个字段都是包装类型</h2>
 * <p>
 * {@code @ConfigurationProperties} 的构造器绑定在配置项缺失时给 {@code null}，
 * 而不是抛异常（同 {@code MonitorLogProperties} 的注释）。用 {@code int} 会在
 * 「配置没写」时直接 NPE，而那个异常与根因看起来毫无关系。
 * 统一的补默认值入口是 {@link #normalized()}。
 *
 * @param maxResultChars 单次 Tool 结果给 LLM 的文本上限（字符）。
 *                       对应 SPEC 第 23.3 节「不要直接把大量原始日志全部传给 LLM」
 * @param prometheus     Prometheus 查询参数（Metrics / MQ Tool 共用同一份口径）
 * @param db             数据库类 Tool 的读取参数
 *
 * <p>【为什么这里没有 {@code logs} 段】日志产出侧的参数
 * （{@code max-samples} / {@code max-sample-chars} / {@code dedupe}）绑在
 * {@code MonitorLogProperties} 上（前缀 {@code seckill.monitor.tool.logs}），
 * 因为它们同时被 {@code LogSink} 使用。若在这里再声明一份，
 * 就会出现「同一个键、两个 Bean、谁生效取决于注入顺序」——
 * 这类问题不会报错，只会在某次调参后表现为「改了配置没反应」。
 */
@ConfigurationProperties(prefix = "seckill.monitor.tool")
public record ToolProperties(
        Integer maxResultChars,
        Prometheus prometheus,
        Db db
) {

    private static final int DEFAULT_MAX_RESULT_CHARS = 8000;

    /**
     * Prometheus 查询参数。
     *
     * @param baseUrl          Prometheus 的 HTTP 地址。默认 {@code http://localhost:9090} ——
     *                         容器把端口原样发布到了宿主机，因此应用（跑在宿主机）用 localhost 即可
     * @param timeoutMs        单次 HTTP 查询的超时
     * @param queryStepSeconds 区间查询的步长。15s 与 {@code prometheus.yml} 的采集周期一致：
     *                         更小只会拿到重复点，更大则可能整段跳过一次尖峰
     * @param maxPoints        区间查询返回的最大点数（超出时自动放大步长）。
     *                         它是「时间窗被 LLM 写成 7 天」时的兜底 —— 否则一次查询会返回
     *                         上万点，全部经过整形与截断，最后什么都没剩下
     */
    public record Prometheus(String baseUrl, Integer timeoutMs, Integer queryStepSeconds,
                             Integer maxPoints) {
    }

    /**
     * 数据库类 Tool 的读取参数。
     *
     * @param maxRows             单条只读语句最多取回的行数
     * @param slowSqlTopN         慢 SQL 排行返回的条数
     * @param slowSqlMinSeconds   慢 SQL 的入选门槛（平均耗时秒数）
     * @param queryTimeoutSeconds JDBC 查询超时（秒）。它是「Tool 超时」之外的第二道闸门：
     *                            Tool 超时让 Agent 不必再等，而这道闸门让<b>数据库侧</b>
     *                            也停下来。两道都要有，理由见 {@link ToolRegistry} 的类注释
     */
    public record Db(Integer maxRows, Integer slowSqlTopN, Double slowSqlMinSeconds,
                     Integer queryTimeoutSeconds) {
    }

    /**
     * 逐项补默认值。见类注释「为什么每个字段都是包装类型」。
     *
     * <p>【这一段必须对「整段缺失」也成立】初版写成
     * {@code prometheus == null ? new Prometheus(null, null, null, null) : ...}，
     * 于是当配置里<b>没有</b> {@code seckill.monitor.tool.prometheus} 整段时，
     * 补默认值这一步被整个跳过了 —— 真正的 NPE 出现在后面
     * {@code prometheus.queryStepSeconds()} 的拆箱处，与根因隔了好几层。
     * 默认 profile（不带 docker）下恰好没有这一段，所以这个缺陷只在
     * 「用默认 profile 启动」时才暴露 —— 而那是别人接手项目后最可能用的启动方式。
     * 现在的写法是先取一个「全 null 的同类对象」，再逐项补默认值，
     * 因此「段落缺失」与「字段缺失」走的是同一条路径。
     */
    public ToolProperties normalized() {
        Prometheus rawPrometheus = prometheus == null
                ? new Prometheus(null, null, null, null) : prometheus;
        Prometheus normalizedProm = new Prometheus(
                blankToDefault(rawPrometheus.baseUrl(), "http://localhost:9090"),
                clamp(rawPrometheus.timeoutMs(), 200, 30_000, 2000),
                clamp(rawPrometheus.queryStepSeconds(), 5, 3600, 15),
                clamp(rawPrometheus.maxPoints(), 10, 2000, 240));

        Db rawDb = db == null ? new Db(null, null, null, null) : db;
        Db normalizedDb = new Db(
                clamp(rawDb.maxRows(), 1, 500, 50),
                clamp(rawDb.slowSqlTopN(), 1, 100, 10),
                rawDb.slowSqlMinSeconds() == null || rawDb.slowSqlMinSeconds() < 0
                        ? 1.0 : rawDb.slowSqlMinSeconds(),
                clamp(rawDb.queryTimeoutSeconds(), 1, 30, 2));

        return new ToolProperties(
                clamp(maxResultChars, 512, 100_000, DEFAULT_MAX_RESULT_CHARS),
                normalizedProm, normalizedDb);
    }

    private static int clamp(Integer value, int min, int max, int fallback) {
        if (value == null) {
            return fallback;
        }
        return Math.max(min, Math.min(max, value));
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
