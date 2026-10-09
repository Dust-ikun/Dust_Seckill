package com.dustikun.seckill.monitor.log;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Logs Tool 的配置绑定（{@code seckill.monitor.tool.logs.*}）。
 *
 * <h2>为什么前缀是 {@code tool.logs} 而不是 {@code logs}</h2>
 * <p>
 * 这里原本写的是 {@code seckill.monitor.logs}，而配置文件里写的一直是
 * {@code seckill.monitor.tool.logs}（SPEC 第 23.3 节给的名字就是这个）。
 * 后果正是本项目反复记录的那类失效：<b>配置项被静默忽略</b> ——
 * 绑定到一个没人写过的前缀上，于是 {@code Get-Content application-docker.yaml} 里
 * 明明写着 {@code dedupe: true}，实际生效的却是代码里的默认值。
 * 两者当时的值恰好相同，因此没有任何症状；一旦有人把 {@code dedupe} 改成 false，
 * 他会发现「改了没用」，而那时排查方向很容易跑到「Go 到 LogSink 的传参」上去。
 *
 * <p>现在只保留<b>一个</b>前缀，并且它就是配置文件里实际存在的那一个。
 * 这比「两个前缀 + 合并规则」更好：合并规则需要在启动日志里说明「谁覆盖了谁」，
 * 而一个不需要解释的配置来源比一条解释更不容易出错。
 *
 * <h2>为什么这里只有「产出侧」的参数，没有「采集侧」的参数</h2>
 * <p>
 * 采集侧（容量、单条长度、最低级别、写锁超时）在 {@code logback-spring.xml} 里配 ——
 * 因为那些值必须在 Spring 容器起来之前就生效（Spring 自己的启动日志也要进缓冲）。
 * 本类管的是<b>读出来之后怎么给</b>：给多少条、怎么脱敏。
 * 这个分界不是随意的：写侧参数决定「内存占用」，读侧参数决定「LLM 上下文成本」，
 * 两者的风险与调参时机完全不同。
 *
 * @param serviceName     日志里 serviceName 字段的缺省值。与 {@code prometheus.yml} 里
 *                        采集目标 {@code service} 标签必须是同一个值 ——
 *                        不一致时 Logs Tool 按 service 过滤会查不到任何东西，
 *                        而 Metrics Tool 仍能查到指标，于是 Agent 会得出
 *                        「有指标异常但没有任何相关日志」这种错误结论。
 * @param queryLimit      单次查询默认返回条数上限（去重后）。对应
 *                        {@code seckill.monitor.tool.logs.max-samples}
 * @param maxSampleChars  单条样本在输出前的最大字符数（超长截断）。与写侧的
 *                        {@code maxMessageChars} 是两道不同的闸门：
 *                        写侧防内存，读侧防上下文 —— 同一条日志可能在缓冲里是完整的，
 *                        但给 LLM 时被截短
 * @param dedupe          是否按签名去重。默认 true；置 false 时返回原始条数，
 *                        用于「我就是要看每一次发生」的排查
 */
@ConfigurationProperties(prefix = "seckill.monitor.tool.logs")
public record MonitorLogProperties(
        String serviceName,
        Integer queryLimit,
        Integer maxSampleChars,
        Boolean dedupe
) {

    /** 与 prometheus.yml 的 job 标签同源。见类注释 */
    public static final String DEFAULT_SERVICE_NAME = "order-service";

    private static final int DEFAULT_QUERY_LIMIT = 20;

    private static final int DEFAULT_MAX_SAMPLE_CHARS = 500;

    /**
     * 逐项补默认值。
     * <p>为什么要显式补：{@code @ConfigurationProperties} 构造器绑定在
     * 「配置项缺失」时会给 {@code null}（对包装类型），而不是抛异常。
     * 若下游直接用 {@code props.queryLimit()} 做算术，得到的是 NPE ——
     * 一个和「配置没写」看起来毫无关系的错误。
     */
    public MonitorLogProperties normalized() {
        String service = (serviceName == null || serviceName.isBlank())
                ? DEFAULT_SERVICE_NAME : serviceName.trim();
        int limit = (queryLimit == null || queryLimit <= 0)
                ? DEFAULT_QUERY_LIMIT : Math.min(queryLimit, LogQuery.MAX_LIMIT);
        int chars = (maxSampleChars == null || maxSampleChars < 32)
                ? DEFAULT_MAX_SAMPLE_CHARS : maxSampleChars;
        boolean dedup = dedupe == null || dedupe;
        return new MonitorLogProperties(service, limit, chars, dedup);
    }
}
