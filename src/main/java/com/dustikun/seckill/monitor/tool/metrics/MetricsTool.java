package com.dustikun.seckill.monitor.tool.metrics;

import com.dustikun.seckill.monitor.log.MonitorLogProperties;
import com.dustikun.seckill.monitor.tool.MonitorTool;
import com.dustikun.seckill.monitor.tool.TimeParsing;
import com.dustikun.seckill.monitor.tool.ToolArguments;
import com.dustikun.seckill.monitor.tool.ToolResult;
import com.dustikun.seckill.monitor.tool.ToolSchema;
import com.dustikun.seckill.monitor.tool.ToolProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Metrics Tool（SPEC 第 9.1 节）：{@code query_metric(metric, service, start_time, end_time)}。
 *
 * <h2>返回结构（对齐 SPEC 第 9.1 节的示例，并补上「结论对应哪次查询」）</h2>
 * <pre>
 * {
 *   "metric":  "http_p99_latency",          ← 回显，避免结论与查询对不上
 *   "query":   "seckill:http_p99_latency:5m{service=\"order-service\"}",  ← 实际 PromQL
 *   "unit":    "秒",
 *   "service": "order-service",
 *   "window":  { "startMillis":…, "endMillis":…, "startText":…, "endText":…, "stepSeconds":15 },
 *   "available": true,
 *   "series": [ { "labels":{...}, "avg":…, "min":…, "max":…, "last":…, "p99":…, "samples":… } ],
 *   "summary": { "seriesCount":…, "worstMax":…, "avgOfAvg":…, "windowTrend":… },
 *   "note": "…"
 * }
 * </pre>
 * <p>SPEC 的示例只有 {@code avg / p99 / max} 三个数。多出来的字段各有理由：
 * <ul>
 *   <li>{@code query}：没有它，Agent 的结论与「它到底查了什么」无法对应 ——
 *       而这两者不一致正是复盘时最难查的一类问题（与 {@code LogPage.query} 同源的理由）；</li>
 *   <li>{@code available}：区分「Prometheus 不可达」与「这条指标没有数据」。
 *       两者都会得到空 series，但结论完全相反；</li>
 *   <li>{@code labels}：同一个指标按 uri 分组后可能有多条序列，
 *       不带标签时 Agent 只能得出「系统某处慢」，带上它才能指向具体接口；</li>
 *   <li>{@code summary.windowTrend}：把「窗口首末值之比」压成一个数，
 *       用来回答「是在恶化还是在恢复」—— 这是 SPEC 第 12 节示例里
 *       Agent 需要自己看出来的东西，直接在数据里给出可以减少一次工具调用。</li>
 * </ul>
 *
 * <h2>时间参数为什么必须容错</h2>
 * <p>
 * {@code start_time} / {@code end_time} 是模型最容易写错的参数，实测形态包括：
 * {@code "2026-10-09T13:00:00Z"}、{@code "2026-10-09 13:00:00"}、
 * {@code "now-30m"}、{@code "30m"}、{@code 1760000000000}（毫秒）、
 * {@code 1760000000}（秒）。这些形态的语义都<b>没有歧义</b>，
 * 因此全部接受（见 {@link #parseTime}）；但给反了的时间窗（start &gt; end）
 * 会像 {@code LogQuery#normalized()} 一样自动交换并留一条 note ——
 * 静默返回空结果会被读成「这段时间没有异常」。
 */
public final class MetricsTool implements MonitorTool {

    private static final Logger log = LoggerFactory.getLogger(MetricsTool.class);

    /** 工具名。SPEC 第 9.1 节给的就是这个名字 */
    public static final String NAME = "query_metric";

    /** 不传时间窗时的默认回看时长（分钟）。与 Prometheus 最常见的 5m/1m 记录规则窗口对齐 */
    private static final long DEFAULT_LOOKBACK_MINUTES = 15;

    /** 单次返回的序列条数上限。按 uri 分组时序列可能很多，超出后按「最大值」降序保留 */
    private static final int MAX_SERIES = 20;

    /** 时间窗上限（小时）。防止模型写一个 7 天窗口把整库拉出来 */
    private static final long MAX_WINDOW_HOURS = 24;

    private final PrometheusQuerier querier;

    private final MetricCatalog catalog;

    private final ToolProperties properties;

    /** 默认 service 名。见 {@link #resolveService} —— 刻意复用日志侧的配置，避免两处各写一份 */
    private final MonitorLogProperties logProperties;

    public MetricsTool(PrometheusQuerier querier, MetricCatalog catalog, ToolProperties properties,
                       MonitorLogProperties logProperties) {
        this.querier = querier;
        this.catalog = catalog;
        this.properties = properties.normalized();
        this.logProperties = logProperties == null
                ? new MonitorLogProperties(null, null, null, null) : logProperties.normalized();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "查询系统与业务的监控指标（数据来自 Prometheus）。返回窗口内的 avg/min/max/p99/last，"
                + "按标签分组。metric 只能取指标清单里的名字；不确定有哪些可选时，"
                + "请先用与本参数说明配套的指标清单（或在 DB/日志工具之外先查 order_success_rate、"
                + "http_p99_latency、target_up 这三个最常用的）。"
                + "注意：series 为空时先看 available 字段 —— false 表示 Prometheus 不可达，"
                + "那时「没有数据」不代表系统正常。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        Map<String, Object> properties = new LinkedHashMap<>(8);
        properties.put("metric", ToolSchema.enumeration(
                "要查询的指标名。可选值与含义见指标清单（本工具的 metric 白名单）",
                catalog.keys()));
        properties.put("service", ToolSchema.string(
                "服务名，默认 " + logProperties.serviceName() + "。传 \"*\" 表示不按服务过滤。"
                        + "只允许字母、数字与 _ . -", 128));
        properties.put("start_time", ToolSchema.string(
                "窗口下界。" + TimeParsing.FORMAT_HINT + "。不传则默认最近 "
                        + DEFAULT_LOOKBACK_MINUTES + " 分钟", 40));
        properties.put("end_time", ToolSchema.string(
                "窗口上界，" + TimeParsing.FORMAT_HINT + "。不传则为此刻", 40));
        properties.put("step_seconds", ToolSchema.integer(
                "采样步长（秒）。不传则由窗口长度自动推算（" + this.properties.prometheus().queryStepSeconds()
                        + "s 起，最多 " + this.properties.prometheus().maxPoints() + " 个点）", 5, 3600));
        return ToolSchema.object(properties, "metric");
    }

    @Override
    public ToolResult execute(ToolArguments args) {
        String metricKey = args.requireString("metric", 64);
        MetricCatalog.Entry entry = catalog.find(metricKey).orElseThrow(() ->
                new com.dustikun.seckill.monitor.tool.ToolArgumentException("metric",
                        "值 \"" + metricKey + "\" 不在指标清单里。可选值："
                                + String.join(", ", catalog.keys())
                                + "。请不要使用清单以外的指标名。"));

        String service = resolveService(args);
        List<String> notes = new ArrayList<>(4);

        long now = System.currentTimeMillis();
        long start = parseTime(args, "start_time", now - DEFAULT_LOOKBACK_MINUTES * 60_000L, now);
        long end = parseTime(args, "end_time", now, now);
        if (start > end) {
            long tmp = start;
            start = end;
            end = tmp;
            notes.add("start_time 晚于 end_time，已自动交换（否则会返回空结果，"
                    + "而空结果会被误读为「这段时间没有异常」）。");
        }
        if (end - start > MAX_WINDOW_HOURS * 3_600_000L) {
            long clamped = end - MAX_WINDOW_HOURS * 3_600_000L;
            notes.add("时间窗超过 " + MAX_WINDOW_HOURS + " 小时，下界已收到 "
                    + format(clamped) + "。更长的窗口请分多次查询。");
            start = clamped;
        }

        long stepSeconds = resolveStep(args, start, end);
        String query = entry.render(service, null);

        Map<String, Object> data = new LinkedHashMap<>(12);
        data.put("metric", entry.key());
        data.put("query", query);
        data.put("unit", entry.unit().text());
        data.put("service", service == null ? "*" : service);
        data.put("window", window(start, end, stepSeconds));

        boolean reachable = querier.reachable();
        data.put("available", reachable);
        if (!reachable) {
            // 【这一条 note 是整个 Tool 层最重要的一句话】
            // 没有它，模型会把「查不到」当成「没问题」—— 而这正是 SPEC 第 10 节
            // 诊断规则第 1 条（不允许在没有证据的情况下下结论）要禁止的行为。
            notes.add("Prometheus（" + querier.baseUrl() + "）当前不可达，本次查询不代表"
                    + "「该指标为空」，而是「拿不到任何指标」。请改用日志/数据库/业务工具取证，"
                    + "并在结论中说明指标证据缺失。");
            data.put("series", List.of());
            data.put("summary", Map.of("seriesCount", 0));
            return ToolResult.ok(name(), data, notes);
        }

        List<PromSeries> raw = querier.queryRange(query, start, end, stepSeconds);
        List<PromSeries> withData = new ArrayList<>();
        int nonFinite = 0;
        int allNanSeries = 0;
        for (PromSeries series : raw) {
            int finite = 0;
            int nonFiniteHere = 0;
            for (PromPoint point : series.points()) {
                if (point.finite()) {
                    finite++;
                } else {
                    nonFiniteHere++;
                }
            }
            // 【整条序列都是 NaN 时把它丢掉，而不是留一条「全是 null」的序列】
            // Prometheus 对没有数据的窗口会整体返回 NaN（例如刚启动、5m 窗口还没填满，
            // histogram_quantile 就是全 NaN）。留下它会让 Agent 看到一条
            // labels 齐全、avg/max/p99 全为 null 的序列 —— 那种「看起来有数据」的空壳
            // 比直接不放进去更容易被当成「值缺失」去解读。
            if (finite == 0 && nonFiniteHere > 0) {
                allNanSeries++;
                nonFinite += nonFiniteHere;
                continue;
            }
            if (series.points().isEmpty()) {
                continue;
            }
            withData.add(series);
            nonFinite += nonFiniteHere;
        }

        if (withData.isEmpty()) {
            notes.add("这条指标在窗口内没有任何可用采样点。可能原因：① 该指标从未被记录过"
                    + "（例如 rocketmq_consumer_lag_messages 只在有消费活动之后才存在）；"
                    + "② 窗口早于进程启动，或 5m 窗口还没填满（此时 P99 会整条是 NaN）；"
                    + "③ 指标名在本项目里不存在（见启动日志的目录自检）。"
                    + "**它不等于该指标为 0**。");
            if (allNanSeries > 0) {
                notes.add("其中有 " + allNanSeries + " 条序列在窗口内全是空值（NaN）——"
                        + "那通常意味着这段时间确实没有请求经过，而不是「延迟为零」。");
            }
            data.put("series", List.of());
            data.put("summary", Map.of("seriesCount", 0));
            return ToolResult.ok(name(), data, notes);
        }

        // 按「窗口内最大值」降序排列后截断：出问题的那条序列最该出现在最前面。
        // 排序而不是随机截断，是因为按 uri 分组的序列可能上百条，而模型的注意力在头部。
        withData.sort(Comparator.comparingDouble((PromSeries s) -> stats(s).max()).reversed());
        boolean seriesTruncated = withData.size() > MAX_SERIES;
        if (seriesTruncated) {
            notes.add("序列条数 " + withData.size() + " 超过上限 " + MAX_SERIES
                    + "，已按窗口内最大值降序保留前 " + MAX_SERIES + " 条（最可能相关的那几条）。");
            withData = new ArrayList<>(withData.subList(0, MAX_SERIES));
        }
        if (nonFinite > 0) {
            notes.add("窗口内有 " + nonFinite + " 个采样点为空值（NaN），已在统计中剔除；"
                    + "它们表示「那一刻没有数据」，不是 0。");
        }

        List<Map<String, Object>> seriesOut = new ArrayList<>(withData.size());
        double worstMax = Double.NEGATIVE_INFINITY;
        double avgSum = 0;
        int avgCount = 0;
        for (PromSeries series : withData) {
            Stats stats = stats(series);
            Map<String, Object> item = new LinkedHashMap<>(8);
            item.put("labels", series.labels());
            item.put("avg", round(stats.avg()));
            item.put("min", round(stats.min()));
            item.put("max", round(stats.max()));
            item.put("last", round(stats.last()));
            item.put("p99", round(stats.p99()));
            item.put("samples", stats.count());
            item.put("trend", stats.trend());
            seriesOut.add(item);
            if (Double.isFinite(stats.max())) {
                worstMax = Math.max(worstMax, stats.max());
            }
            if (Double.isFinite(stats.avg())) {
                avgSum += stats.avg();
                avgCount++;
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>(6);
        summary.put("seriesCount", seriesOut.size());
        summary.put("worstMax", Double.isFinite(worstMax) ? round(worstMax) : null);
        summary.put("avgOfAvg", avgCount == 0 ? null : round(avgSum / avgCount));
        data.put("series", seriesOut);
        data.put("summary", summary);
        notes.add("p99 是「窗口内采样点的第 99 百分位」，与「指标本身就是 P99」是两件事："
                + "前者回答「这个 P99 一路最高到过多少」，后者是每条采样点的含义。");
        return ToolResult.ok(name(), data, notes);
    }

    // ================================================================ 参数解析

    /**
     * 解析 service 参数。
     * <p>不传时取配置里的默认服务名（与 {@code prometheus.yml} 的 job 标签同源）——
     * 而不是「不过滤」。理由：本项目只有一个业务服务，而 Prometheus 自己、
     * Alertmanager、Broker 都是<b>独立的目标</b>（各自带 service 标签）。
     * 不过滤时 {@code up} 这类指标会混进 4 个目标的曲线，
     * 让「本服务是否被抓到」这个问题变得难以回答。
     */
    private String resolveService(ToolArguments args) {
        String service = args.optionalString("service");
        if (service == null) {
            return logProperties.serviceName();
        }
        if ("*".equals(service)) {
            return null;
        }
        return service;
    }

    /**
     * 步长：取「配置步长」与「窗口 / 最大点数」中的较大者。
     * <p>取较大者而不是固定值，是为了同时满足两个约束：
     * 步长不能小于采集周期（否则拿到重复点，白白放大响应体积），
     * 点数不能超过上限（否则一个 24 小时窗口会返回 5760 个点，整形阶段全被砍掉）。
     */
    private long resolveStep(ToolArguments args, long start, long end) {
        long configured = properties.prometheus().queryStepSeconds();
        int maxPoints = properties.prometheus().maxPoints();
        long windowSeconds = Math.max(1, (end - start) / 1000);
        long minimum = Math.max(1, (windowSeconds + maxPoints - 1) / maxPoints);
        long step = Math.max(configured, minimum);
        return args.longOrDefault("step_seconds", step, 5, 3600);
    }

    private static long parseTime(ToolArguments args, String name, long fallback, long now) {
        return TimeParsing.parseOrThrow(args.optionalString(name), fallback, now, name);
    }

    // ================================================================ 统计

    private Map<String, Object> window(long start, long end, long stepSeconds) {
        Map<String, Object> window = new LinkedHashMap<>(6);
        window.put("startMillis", start);
        window.put("endMillis", end);
        window.put("startText", format(start));
        window.put("endText", format(end));
        window.put("stepSeconds", stepSeconds);
        window.put("durationSeconds", (end - start) / 1000);
        return window;
    }

    private static String format(long millis) {
        return TimeParsing.format(millis);
    }

    /**
     * 一个序列在窗口内的统计量。
     *
     * <p>【为什么 NaN 一律剔除而不是当 0】见 {@link PromPoint} 的注释。
     * 这里再补一句代价：若把 NaN 当 0，一条「整个窗口都没有数据」的 P99 会得到
     * avg = 0、max = 0，模型于是得到「延迟为零，非常健康」——
     * 一个与事实完全相反的结论。
     */
    private static Stats stats(PromSeries series) {
        List<Double> values = new ArrayList<>(series.points().size());
        for (PromPoint point : series.points()) {
            if (point.finite()) {
                values.add(point.value());
            }
        }
        if (values.isEmpty()) {
            return new Stats(Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0);
        }
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        double sum = 0;
        for (double value : values) {
            min = Math.min(min, value);
            max = Math.max(max, value);
            sum += value;
        }
        double avg = sum / values.size();
        double p99 = percentile(values, 0.99);
        double last = values.get(values.size() - 1);
        double first = values.get(0);
        String trend;
        if (first == 0 || !Double.isFinite(first) || !Double.isFinite(last)) {
            trend = "unknown";
        } else if (last > first * 1.2) {
            trend = "worsening";
        } else if (last < first * 0.8) {
            trend = "recovering";
        } else {
            trend = "stable";
        }
        return new Stats(avg, min, max, last, p99, values.size(), trend);
    }

    /**
     * 最近秩（nearest-rank）百分位。
     * <p>【为什么不用插值】采样点只有几十个，插值会给出一个「看起来更精确」的值
     * （例如 2.1037s），而它其实是两个真实采样点之间的估计。
     * 排障决策不会因为这点精度而改变，但「这个值是算出来的还是量到的」会影响可信度判断。
     * 最近秩返回的一定是<b>真实出现过的</b>采样点。
     */
    static double percentile(List<Double> values, double quantile) {
        if (values.isEmpty()) {
            return Double.NaN;
        }
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compareTo);
        int rank = (int) Math.ceil(quantile * sorted.size());
        int index = Math.min(sorted.size() - 1, Math.max(0, rank - 1));
        return sorted.get(index);
    }

    /** 统一保留 6 位小数：足够表达秒级延迟与比例，又不会把 0.30000000000000004 这类噪音带进上下文 */
    private static Object round(double value) {
        if (!Double.isFinite(value)) {
            return null;
        }
        return Math.round(value * 1_000_000d) / 1_000_000d;
    }

    private record Stats(double avg, double min, double max, double last, double p99, int count,
                         String trend) {

        Stats(double avg, double min, double max, double last, double p99, int count) {
            this(avg, min, max, last, p99, count, "unknown");
        }
    }

    // ================================================================ 启动自检

    /**
     * 目录自检：把目录里声明的每个底层序列名，与 Prometheus 的<b>指标名索引</b>对照一次。
     *
     * <h2>为什么不是「逐条查一次看有没有数据」</h2>
     * <p>
     * 初版就是这么写的（对每条目录项做一次即时查询，数结果条数），它在真实环境里
     * <b>把 39 条全部报成了「无序列」</b>，而那是一个假警报。原因是即时查询只返回
     * 「此刻仍然活跃」的序列：应用没在跑、或刚启动还没被采集到，`seckill_*` 全部查不到 ——
     * 于是这个自检分不清下面两件性质相反的事：
     * <pre>
     *   指标名写错了        → 要改代码（它就是本自检要抓的东西）
     *   指标名对但此刻没数据 → 完全正常（应用没在跑、窗口没填满、MQ 没活动）
     * </pre>
     * 一个长期挂着一串「查不到」的启动警告，读它的人很快就不看了 ——
     * 真正写错的那一个也就淹没在里面。这与本项目反复记录的教训同源：
     * <b>一个总是响的告警等于没有告警</b>。
     *
     * <p>指标名索引（{@code /api/v1/label/__name__/values}）回答的正是「这个名字存不存在」，
     * 与「此刻有没有数据」无关，而且只需要<b>一次</b> HTTP 请求。
     *
     * <p>【它不会因为 Prometheus 没起而拖慢启动】先做一次可达性检查，
     * 不可达时直接跳过并只打一条 warn。
     */
    public void probeCatalog() {
        if (!querier.reachable()) {
            log.warn("[MetricsTool] Prometheus（{}）当前不可达，跳过指标目录自检。"
                    + "Agent 的指标类证据将不可用，但日志/数据库/业务工具仍可用。", querier.baseUrl());
            return;
        }
        Set<String> known = querier.metricNames();
        if (known.isEmpty()) {
            // 拿不到索引时不能报「全部缺失」—— 那会把一次读取失败伪装成 39 个配置错误。
            log.warn("[MetricsTool] 读取不到 Prometheus 的指标名索引，本次跳过目录自检"
                    + "（这不代表目录里的指标名有问题）。baseUrl={}", querier.baseUrl());
            return;
        }
        List<String> referenced = catalog.referencedMetricNames();
        List<String> missing = new ArrayList<>();
        for (String name : referenced) {
            if (!known.contains(name)) {
                missing.add(name);
            }
        }
        // 【为什么把 mq_* 单独分成一类】Broker 的 OTel 导出器**不输出「从未被记录过」的仪表**，
        // 因此 rocketmq_* 的名字在「还没有发生过对应活动」时本来就不存在 ——
        // 那是文档化的正常现象（见 docs/批次1_健康基线快照.md §3.3），不是配置错误。
        // 把它与「写错了」混在一起报，会让这行警告变成噪音，
        // 而噪音的代价是真问题被淹没（这正是初版 probeCatalog 的毛病）。
        List<String> mqOnly = new ArrayList<>();
        List<String> realProblems = new ArrayList<>();
        for (String name : missing) {
            (name.startsWith("rocketmq_") ? mqOnly : realProblems).add(name);
        }

        if (!realProblems.isEmpty()) {
            log.error("[MetricsTool] 指标目录自检失败：{} 个序列名在 Prometheus 的指标名索引里不存在，"
                            + "这些口径将永远查不到数据（请核对 MetricCatalog 里的名字）。缺失={}；"
                            + "索引里已有 {} 个指标名。",
                    realProblems.size(), realProblems, known.size());
        }
        if (!mqOnly.isEmpty()) {
            log.warn("[MetricsTool] 以下 rocketmq_* 序列名当前不存在：{}。"
                    + "按 RocketMQ 5.x 的行为，Broker 的 OTel 导出器不输出「从未被记录过」的仪表 —— "
                    + "Broker 启动后还没有消费活动（或从未产生过死信）时这是<b>正常</b>的，"
                    + "不要据此改动指标名。", mqOnly);
        }
        if (realProblems.isEmpty()) {
            // 【即使只有 mq_* 缺失，也要给一条明确的结论】否则启动日志里只有一段警告、
            // 没有「其余都对」这句，读的人无法判断自检到底跑没跑、结论是什么。
            log.info("[MetricsTool] 指标目录自检通过：{} 条口径引用的 {} 个序列名中，{} 个在 Prometheus "
                            + "索引里存在；{} 个是允许缺失的 rocketmq_*（尚无对应 Broker 活动）。"
                            + "注意这只证明名字对，不证明此刻有数据 —— 应用没在跑时指标会过期。",
                    catalog.entries().size(), referenced.size(),
                    referenced.size() - mqOnly.size(), mqOnly.size());
        }
    }
}
