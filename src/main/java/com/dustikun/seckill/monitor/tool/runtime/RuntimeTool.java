package com.dustikun.seckill.monitor.tool.runtime;

import com.dustikun.seckill.Config.RocketMqProperties;
import com.dustikun.seckill.Metrics.SeckillMetrics;
import com.dustikun.seckill.monitor.tool.MonitorTool;
import com.dustikun.seckill.monitor.tool.TimeParsing;
import com.dustikun.seckill.monitor.tool.ToolArguments;
import com.dustikun.seckill.monitor.tool.ToolArgumentException;
import com.dustikun.seckill.monitor.tool.ToolResult;
import com.dustikun.seckill.monitor.tool.ToolSchema;
import com.dustikun.seckill.monitor.tool.metrics.PromSeries;
import com.dustikun.seckill.monitor.tool.metrics.PrometheusQuerier;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 运行时观测 Tool（批次 5）：{@code query_runtime}。
 *
 * <h2>它补的是 Case 3 / Case 5 实测暴露的那块能力缺口</h2>
 * <p>
 * {@code docs/故障注入实验记录.md} §3.5 的原话：现有 5 个工具都查不到
 * <b>「应用侧运行时观测」</b>——消费线程池活跃数、单条消息处理耗时分布、降级标志位。
 * Case 3 里模型明明看到了 {@code DO SLEEP(0.12)} 事务，却因为只有一次瞬时快照、
 * 无法与「线程数不足」区分而诚实存疑（conf 0.25）。它缺的不是更强的 prompt，
 * 而是这三类证据；本工具就是让「下一次同类诊断」拿得到它们。
 *
 * <h2>三个 operation 分别回答什么问题</h2>
 * <table border="1">
 *   <caption>取证能力与它对应的判别</caption>
 *   <tr><th>operation</th><th>数据来源</th><th>回答什么</th></tr>
 *   <tr>
 *     <td>GET_CONSUMER_THREAD_STATE</td>
 *     <td>进程内 Gauge + Prometheus 的 over_time</td>
 *     <td>消费滞后时，是「线程池容量不够」（活跃数打满）还是「单条变慢」（活跃数不满）</td>
 *   </tr>
 *   <tr>
 *     <td>GET_MESSAGE_LATENCY</td>
 *     <td>进程内 Timer 直方图 + Prometheus histogram_quantile</td>
 *     <td>单条耗时的分布（P50/P95/P99），以及由平均耗时推算的单线程容量</td>
 *   </tr>
 *   <tr>
 *     <td>GET_DEGRADATION_STATE</td>
 *     <td>进程内标志位 + 计数器 + 事件速率</td>
 *     <td>最近有没有在走「Redis 故障 → MySQL 兜底」降级路径（Case 5 只能间接推断的那一项）</td>
 *   </tr>
 * </table>
 *
 * <h2>为什么数据源是「进程内 + Prometheus」双份，而不是只查 Prometheus</h2>
 * <p>
 * 进程内读数（Gauge / Timer）有三个 Prometheus 给不了的性质：
 * <ol>
 *   <li><b>实时</b>：不等抓取间隔，且不受「应用刚重启、序列还没被抓过」影响；</li>
 *   <li><b>永远可达</b>：Prometheus 不可达时进程内读数仍然成立 —— 而那恰恰是
 *       最需要证据的时刻（监控栈自己坏了）；</li>
 *   <li><b>分布是进程内直方图</b>：Timer 的 count/mean/max/percentile 来自同一台机器，
 *       没有「多实例聚合后单实例的长尾被抹平」的问题。</li>
 * </ol>
 * Prometheus 那一份提供<b>窗口视图</b>（rate / histogram_quantile / over_time）：
 * 瞬时值会被「恰好采到空隙」骗过，窗口视图才能回答「这段时间一直如此吗」。
 * 两个口径的差异本身也是线索，因此 {@code caliber} 字段写清了各自的范围。
 *
 * <h2>与既有约定的对齐</h2>
 * <ul>
 *   <li><b>空 ≠ 0</b>：Prometheus 取不到序列时返回 {@code null} 并说明原因，
 *       绝不把「没有序列」伪装成「值为 0」（批次 2 §5 的口径约定）；</li>
 *   <li><b>不自激</b>：本工具全部读数，不写指标、不写库、不打 ERROR 日志，
 *       不会让任何告警因为「Agent 查了一次」而再次成立（批次 2 §4.3 的判据）；</li>
 *   <li><b>指标名单一来源</b>：PromQL 里的序列名常量与本工具读的
 *       {@link SeckillMetrics} 常量一一对应，单测锁住两侧映射 —— 拼写错误在
 *       测试期暴露，而不是在「序列不存在 → 被读成没有异常」时暴露。</li>
 * </ul>
 */
public final class RuntimeTool implements MonitorTool {

    private static final Logger log = LoggerFactory.getLogger(RuntimeTool.class);

    /** 工具名 */
    public static final String NAME = "query_runtime";

    // ------------------------------------------------------------------
    // Prometheus 侧的序列名。
    // 【它们为什么不在 MetricCatalog 里】目录是给模型看的「可查指标白名单」，
    // 而本工具的三个 operation 都不接受 metric 参数 —— 模型没有「选择序列」的需要。
    // 目录的启动自检（probeCatalog）要求条目在 Prometheus 索引里存在，而
    // seckill_consume_active_threads 这类新序列在应用首次抓取之前不存在，
    // 塞进目录会让启动自检在全新环境里误报。按 MqTool#queryRetryLag 的先例，
    // 「同一族指标的特定变体、只在本工具内部使用」的 PromQL 写在这里。
    // 名字必须与 SeckillMetrics 的 METRIC_* 常量按 Micrometer 命名约定严格对应，
    // RuntimeToolTest#prometheusSeriesNamesMatchMicrometerConvention 锁住这条映射。
    // ------------------------------------------------------------------

    /** {@link SeckillMetrics#METRIC_CONSUME_ACTIVE} 的 Prometheus 渲染 */
    static final String PROM_ACTIVE_THREADS = "seckill_consume_active_threads";
    /** {@link SeckillMetrics#METRIC_CONSUME_PROCESS} 的 Prometheus 渲染（Timer 基础单位秒） */
    static final String PROM_PROCESS_COUNT = "seckill_consume_process_seconds_count";
    static final String PROM_PROCESS_SUM = "seckill_consume_process_seconds_sum";
    static final String PROM_PROCESS_BUCKET = "seckill_consume_process_seconds_bucket";
    /** {@link SeckillMetrics#METRIC_REQUEST_DEGRADED} 的 Prometheus 渲染 */
    static final String PROM_DEGRADED_TOTAL = "seckill_request_degraded_total";

    /** window 参数允许的范围：太小被抓取间隔噪声支配，太大稀释突发（实验记录 §0 教训 3） */
    private static final long MIN_WINDOW_MILLIS = 60_000L;
    private static final long MAX_WINDOW_MILLIS = 3_600_000L;
    private static final long DEFAULT_WINDOW_MILLIS = 5 * 60_000L;

    private final SeckillMetrics metrics;
    private final PrometheusQuerier querier;
    private final RocketMqProperties mqProperties;

    public RuntimeTool(SeckillMetrics metrics, PrometheusQuerier querier, RocketMqProperties mqProperties) {
        this.metrics = metrics;
        this.querier = querier;
        this.mqProperties = mqProperties;
    }

    /**
     * 三个操作。合并成一个工具加 {@code operation} 参数，理由与 MqTool 相同：
     * 三者共享同一个参数（window）、同一批数据源（进程内指标 + Prometheus），
     * 拆开只会让工具清单变长、模型的选择负担变重。
     */
    public enum Operation {
        /** 消费线程池状态：活跃数（当前/峰值/窗口）与配置容量 */
        GET_CONSUMER_THREAD_STATE,
        /** 单条消息处理耗时分布：进程内直方图 + 窗口内 P50/P95/P99 */
        GET_MESSAGE_LATENCY,
        /** 降级标志位：最近是否在走 Redis 故障降级路径 */
        GET_DEGRADATION_STATE
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "应用侧运行时观测：消费线程池活跃数、单条消息处理耗时分布、Redis 故障降级标志位。"
                + "operation=GET_CONSUMER_THREAD_STATE 判断消费滞后是「线程不够」还是「单条变慢」"
                + "（活跃数打满=容量瓶颈，活跃数不满但 lag 增长=单条耗时瓶颈）；"
                + "GET_MESSAGE_LATENCY 给单条耗时的 P50/P95/P99 分布与单线程容量估算"
                + "（容量≈线程数/平均耗时，与投递速率对比即可定量定论）；"
                + "GET_DEGRADATION_STATE 给降级标志位（最近 5 分钟内是否发生过 Redis 故障降级）"
                + "与降级事件速率。注意：标志位是时间窗语义，不等于「此刻 Redis 不可用」。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        Map<String, Object> properties = new LinkedHashMap<>(4);
        properties.put("operation", ToolSchema.enumeration(
                "要观测的内容。GET_CONSUMER_THREAD_STATE=消费线程池活跃数；"
                        + "GET_MESSAGE_LATENCY=单条消息处理耗时分布；"
                        + "GET_DEGRADATION_STATE=降级标志位与降级事件速率",
                List.of(Operation.GET_CONSUMER_THREAD_STATE.name(), Operation.GET_MESSAGE_LATENCY.name(),
                        Operation.GET_DEGRADATION_STATE.name())));
        properties.put("window", ToolSchema.string(
                "Prometheus 窗口视图的统计区间，默认 5m。接受 30m / 1h 这类裸时长"
                        + "（1m~1h 之外会被拒绝：太小被抓取间隔噪声支配，太大稀释突发）", 16));
        return ToolSchema.object(properties, "operation");
    }

    @Override
    public ToolResult execute(ToolArguments args) {
        Operation operation = args.enumOrDefault("operation", Operation.class,
                Operation.GET_CONSUMER_THREAD_STATE, List.of(Operation.values()));
        long nowMillis = System.currentTimeMillis();
        long windowMillis = parseWindow(args.optionalString("window"), nowMillis);
        String window = renderWindow(windowMillis);

        List<String> notes = new ArrayList<>(4);
        Map<String, Object> data = new LinkedHashMap<>(16);
        data.put("operation", operation.name());
        data.put("window", window);

        boolean prometheusAvailable = querier.reachable();
        data.put("prometheusAvailable", prometheusAvailable);
        if (!prometheusAvailable) {
            // 【进程内读数不受影响】这正是双数据源设计的意义：监控栈不可达时，
            // 运行时观测仍然成立 —— 只是没有窗口视图，结论要相应保守。
            notes.add("Prometheus（" + querier.baseUrl() + "）当前不可达：窗口视图（over_time / "
                    + "histogram_quantile / rate）全部缺失，返回 null。"
                    + "本结果里的进程内实时读数（activeThreadsNow、sampleCountSinceStart、"
                    + "degradationActive 等）不受影响，仍然可信。");
        }

        switch (operation) {
            case GET_CONSUMER_THREAD_STATE -> threadState(data, notes, window, prometheusAvailable);
            case GET_MESSAGE_LATENCY -> messageLatency(data, notes, window, prometheusAvailable);
            case GET_DEGRADATION_STATE -> degradationState(data, notes, window, prometheusAvailable);
        }
        return ToolResult.ok(name(), data, notes);
    }

    // ================================================================ GET_CONSUMER_THREAD_STATE

    private void threadState(Map<String, Object> data, List<String> notes,
                             String window, boolean prometheusAvailable) {
        int threadMin = mqProperties.getConsumeThreadMin();
        int threadMax = mqProperties.getConsumeThreadMax();

        data.put("configuredThreadMin", threadMin);
        data.put("configuredThreadMax", threadMax);
        data.put("activeThreadsNow", metrics.consumeActiveThreads());
        data.put("activeThreadsMaxSinceStart", metrics.consumeActiveThreadsMax());
        data.put("caliber", "activeThreadsNow / activeThreadsMaxSinceStart 是本进程的实时读数"
                + "（≈ 消费线程池活跃线程数，单实例口径）；avgOverWindow / maxOverWindow 来自 "
                + "Prometheus（跨实例聚合，但滞后最多一个抓取间隔）");

        Double avgOverWindow = null;
        Double maxOverWindow = null;
        if (prometheusAvailable) {
            avgOverWindow = lastOf(querier.queryInstant(
                    "avg_over_time(" + PROM_ACTIVE_THREADS + "[" + window + "])"));
            maxOverWindow = lastOf(querier.queryInstant(
                    "max_over_time(" + PROM_ACTIVE_THREADS + "[" + window + "])"));
            data.put("avgOverWindow", avgOverWindow);
            data.put("maxOverWindow", maxOverWindow);
            if (avgOverWindow == null && maxOverWindow == null) {
                notes.add("窗口视图取不到序列（应用未被 Prometheus 抓取过，或窗口内应用没在运行）。"
                        + "按「空 ≠ 0」的口径返回 null：这不是「活跃数为 0」，是「这段时间没有数据」。");
            }
        }

        // ---- 把 Case 3 的判别逻辑直接给出来，省掉模型一轮推理 ----
        Double saturation = maxOverWindow != null ? maxOverWindow
                : (double) metrics.consumeActiveThreadsMax();
        if (saturation != null && saturation >= threadMax) {
            notes.add("窗口内活跃线程峰值（" + saturation + "）已达到配置上限 consumeThreadMax="
                    + threadMax + "：线程池至少打满过一次。若此时消费 lag 在增长，"
                    + "说明吞吐上限受线程池容量约束（调大 consume-thread-max 可能有效，"
                    + "但不要超过 Hikari 连接池的 maximum-pool-size，否则消费线程会互相等连接）。");
        } else if (saturation != null && saturation < threadMin) {
            notes.add("活跃线程峰值（" + saturation + "）从未达到下限 consumeThreadMin=" + threadMin
                    + "：线程并没有全部忙起来。若此时消费 lag 在增长，瓶颈不在线程数，"
                    + "而在单条处理耗时 —— 请用 GET_MESSAGE_LATENCY 定量验证"
                    + "（单线程容量 ≈ 1/平均耗时，与投递速率对比）。");
        } else if (saturation != null) {
            notes.add("活跃线程峰值（" + saturation + "）介于 consumeThreadMin(" + threadMin
                    + ") 与 consumeThreadMax(" + threadMax + ") 之间：部分线程在忙。"
                    + "单凭这个数无法区分「容量瓶颈」与「单条耗时瓶颈」，"
                    + "请结合 lag 增长方向与 GET_MESSAGE_LATENCY 的分布交叉判断。");
        }
    }

    // ================================================================ GET_MESSAGE_LATENCY

    private void messageLatency(Map<String, Object> data, List<String> notes,
                                String window, boolean prometheusAvailable) {
        Timer timer = metrics.consumeProcessTimer();
        long count = timer.count();
        data.put("caliber", "sampleCountSinceStart 族来自本进程的 Timer 直方图（seckill.consume.process，"
                + "单实例、应用启动以来的累计）；throughput/avg/p50/p95/p99 来自 Prometheus 对同一"
                + "直方图的窗口聚合（跨实例）。两族的样本集不同（自启动 vs 窗口），数值不可直接相比；"
                + "同一族内部对比才有意义");
        if (count > 0) {
            data.put("sampleCountSinceStart", count);
            data.put("meanMillisSinceStart", round(timer.mean(TimeUnit.MILLISECONDS)));
            data.put("maxMillisSinceStart", round(timer.max(TimeUnit.MILLISECONDS)));
            putPercentilesSinceStart(data, timer);
        } else {
            data.put("sampleCountSinceStart", 0L);
            notes.add("本进程自启动以来没有记录过任何一条消息的耗时（应用刚重启且尚无消费活动）。"
                    + "这里是确知为 0，与 Prometheus 侧取不到序列的 null 含义不同。");
        }

        boolean haveWindowed = false;
        if (prometheusAvailable) {
            Double countRate = lastOf(querier.queryInstant(
                    "sum(rate(" + PROM_PROCESS_COUNT + "[" + window + "]))"));
            Double sumRate = lastOf(querier.queryInstant(
                    "sum(rate(" + PROM_PROCESS_SUM + "[" + window + "]))"));
            Double avgSeconds = (countRate != null && sumRate != null && countRate > 0)
                    ? round(sumRate / countRate) : null;
            Double p50 = histogramQuantile(0.5, window);
            Double p95 = histogramQuantile(0.95, window);
            Double p99 = histogramQuantile(0.99, window);

            if (countRate != null) {
                haveWindowed = true;
                data.put("throughputPerSecond", countRate);
                data.put("avgMillis", avgSeconds == null ? null : round(avgSeconds * 1000));
                data.put("p50Millis", p50 == null ? null : round(p50 * 1000));
                data.put("p95Millis", p95 == null ? null : round(p95 * 1000));
                data.put("p99Millis", p99 == null ? null : round(p99 * 1000));
            } else {
                notes.add("窗口内取不到耗时直方图序列：应用未被 Prometheus 抓取过，或该窗口内"
                        + "应用没在运行。按「空 ≠ 0」返回 null —— 不是「没有耗时数据可看」以外的任何含义。");
            }

            // ---- 定量判据：单线程容量（Little's law 的最简形式） ----
            Double avgForCapacity = avgSeconds != null ? avgSeconds
                    : (count > 0 ? timer.mean(TimeUnit.SECONDS) : null);
            if (avgForCapacity != null && avgForCapacity > 0) {
                double capacity = round(1 / avgForCapacity);
                data.put("estimatedSingleThreadCapacityPerSecond", capacity);
                notes.add("按窗口平均耗时推算：单线程容量 ≈ " + capacity + " 条/秒，"
                        + "线程池容量 ≈ " + round(capacity * mqProperties.getConsumeThreadMax())
                        + " 条/秒（consumeThreadMax=" + mqProperties.getConsumeThreadMax() + "）。"
                        + "若投递速率（query_mq 的 producerRate）持续高于线程池容量，堆积必然增长；"
                        + "若投递速率低于容量而 lag 仍增长，请检查是否有个别消息超长重试。");
            }
            if (avgSeconds != null && p99 != null && p99 > 3 * avgSeconds) {
                notes.add("P99（" + round(p99 * 1000) + "ms）远高于均值（" + round(avgSeconds * 1000)
                        + "ms）：长尾分布。多数消息正常、少数消息极慢 —— 常见于个别消息撞上"
                        + "锁等待 / 触发器 / 重放，而不是「整条链路均匀变慢」。"
                        + "用 search_logs 搜耗时异常的消息（orderNo 可从消费日志反查）。");
            }
        }
        if (!haveWindowed && count > 0) {
            // Prometheus 没有窗口数据时，进程内直方图至少给出「单实例分布」的量级
            notes.add("窗口视图不可用，上面 SinceStart 族是唯一可用的分布证据（单实例、自启动累计）。"
                    + "它的均值/分位数仍可与「预期单条耗时」比较，但不能代表多实例口径。");
        }
    }

    // ================================================================ GET_DEGRADATION_STATE

    private void degradationState(Map<String, Object> data, List<String> notes,
                                  String window, boolean prometheusAvailable) {
        boolean active = metrics.degradationActive();
        long lastAt = metrics.lastDegradedAtMillis();
        long windowSeconds = SeckillMetrics.DEGRADATION_ACTIVE_WINDOW_MILLIS / 1000;

        data.put("degradationActive", active);
        data.put("flagWindowSeconds", windowSeconds);
        data.put("lastDegradedAtMillis", lastAt > 0 ? lastAt : null);
        data.put("lastDegradedAt", lastAt > 0 ? TimeParsing.format(lastAt) : null);
        data.put("degradedEventsTotal", metrics.degradedCount());
        data.put("syncSuccessTotal", metrics.syncSuccessCount());
        data.put("caliber", "degradationActive 是本进程的实时标志位：最近一次降级事件距今是否在 "
                + windowSeconds + " 秒窗口内。它不探测 Redis 此刻是否可用；"
                + "degradedEventsTotal / syncSuccessTotal 是进程启动以来的累计计数");

        if (lastAt == 0) {
            notes.add("本进程自启动以来从未触发过 Redis 故障降级（事件计数为 0 是确知的事实，"
                    + "不是「没查到」）。降级路径未被触发 ≠ Redis 一定健康，"
                    + "请结合 query_metric 的 redis 延迟口径交叉确认。");
        } else if (active) {
            notes.add("标志位为 1：最近 " + windowSeconds + " 秒内发生过 Redis 故障降级，"
                    + "这段时间的下单在走「MySQL 条件扣减」兜底路径。降级是设计行为不是故障，"
                    + "但它绕过了 Redis 的写路径 —— 期间发生的订单会造成 Redis 与 DB 的口径漂移，"
                    + "对账大概率出现 MARK_MISSING 类差异；恢复对齐由人工重新预热完成，"
                    + "Agent 不应建议自动修复。");
        } else {
            notes.add("标志位为 0：最近一次降级事件在 " + windowSeconds + " 秒窗口之外"
                    + "（" + TimeParsing.format(lastAt) + "）。窗口语义见 caliber 字段 —— "
                    + "它说明「最近没有在降级」，不证明「Redis 此刻健康」。");
        }

        if (prometheusAvailable) {
            Double rate = lastOf(querier.queryInstant(
                    "sum(rate(" + PROM_DEGRADED_TOTAL + "[" + window + "]))"));
            data.put("degradedRatePerSecond", rate);
            if (rate != null && rate > 0 && !active) {
                notes.add("窗口内降级事件速率（" + rate + "/s）大于 0，但标志位为 0："
                        + "降级发生过、最近 5 分钟没有新事件 —— 这是「间歇性 Redis 故障已缓解」的形态。"
                        + "若对账差异（如 MARK_MISSING）集中出现在那个时间窗内，就是降级路径留下的漂移。");
            }
        }
    }

    // ================================================================ 辅助

    /**
     * 解析 window 参数。裸时长（"30m"）经 {@link TimeParsing} 解释为「最近 N 分钟」，
     * 窗口长度 = now − 解析值。超出 [1m, 1h] 拒绝 —— 取值范围问题按约定一律拒绝而不是静默夹紧。
     */
    private long parseWindow(String raw, long nowMillis) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_WINDOW_MILLIS;
        }
        Long parsed = TimeParsing.parse(raw, nowMillis);
        if (parsed == null) {
            throw new ToolArgumentException("window", "值 \"" + raw
                    + "\" 无法解析为窗口时长。接受 30m / 2h / 1d 这类裸时长。");
        }
        long windowMillis = nowMillis - parsed;
        if (windowMillis < MIN_WINDOW_MILLIS || windowMillis > MAX_WINDOW_MILLIS) {
            throw new ToolArgumentException("window", "窗口长度 " + renderWindow(windowMillis)
                    + " 超出允许范围 [1m, 1h]。太小的窗口被抓取间隔噪声支配，太大的窗口"
                    + "会把短促突发稀释到看不出（故障注入实验记录 §0 教训 3）。");
        }
        return windowMillis;
    }

    private static String renderWindow(long windowMillis) {
        long seconds = windowMillis / 1000;
        if (seconds % 3600 == 0) {
            return (seconds / 3600) + "h";
        }
        if (seconds % 60 == 0) {
            return (seconds / 60) + "m";
        }
        return seconds + "s";
    }

    /**
     * 窗口分位数：{@code histogram_quantile} 需要 {@code by (le)} 保留桶标签。
     * 求和（sum by）是跨实例聚合的标准做法。
     */
    private Double histogramQuantile(double quantile, String window) {
        return lastOf(querier.queryInstant("histogram_quantile(" + quantile
                + ", sum by (le) (rate(" + PROM_PROCESS_BUCKET + "[" + window + "])))"));
    }

    private void putPercentilesSinceStart(Map<String, Object> data, Timer timer) {
        for (ValueAtPercentile pv : timer.takeSnapshot().percentileValues()) {
            double millis = pv.value(TimeUnit.MILLISECONDS);
            if (!Double.isFinite(millis)) {
                // 样本数太少时 Micrometer 会给 NaN：如实置 null，不让 NaN 混进 JSON
                data.put("p" + Math.round(pv.percentile() * 100) + "MillisSinceStart", null);
            } else {
                data.put("p" + Math.round(pv.percentile() * 100) + "MillisSinceStart", round(millis));
            }
        }
    }

    /** 多条序列求和；一条都没有（或全 NaN）返回 null —— 「空 ≠ 0」 */
    private static Double lastOf(List<PromSeries> series) {
        double total = 0;
        boolean any = false;
        for (PromSeries one : series) {
            double value = one.lastValue();
            if (Double.isFinite(value)) {
                total += value;
                any = true;
            }
        }
        return any ? round(total) : null;
    }

    private static Double round(double value) {
        return Math.round(value * 1_000d) / 1_000d;
    }
}
