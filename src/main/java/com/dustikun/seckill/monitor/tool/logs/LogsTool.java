package com.dustikun.seckill.monitor.tool.logs;

import com.dustikun.seckill.monitor.log.LogPage;
import com.dustikun.seckill.monitor.log.LogQuery;
import com.dustikun.seckill.monitor.log.LogRecord;
import com.dustikun.seckill.monitor.log.LogSink;
import com.dustikun.seckill.monitor.log.MonitorLogProperties;
import com.dustikun.seckill.monitor.tool.MonitorTool;
import com.dustikun.seckill.monitor.tool.TimeParsing;
import com.dustikun.seckill.monitor.tool.ToolArguments;
import com.dustikun.seckill.monitor.tool.ToolResult;
import com.dustikun.seckill.monitor.tool.ToolSchema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Logs Tool（SPEC 第 9.2 节）：{@code search_logs(service, keyword, start_time, end_time, limit)}。
 *
 * <h2>这个类有多薄，就说明批次 1 的地基打得有多对</h2>
 * <p>
 * 查询引擎（环形缓冲）、脱敏、去重、截断、按 traceId 串联，全部在
 * {@code monitor.log} 里完成并已有 46 项单测覆盖。本类只做三件事：
 * <ol>
 *   <li>把 LLM 的入参翻译成 {@link LogQuery}（含时间格式容错）；</li>
 *   <li>调用 {@link LogSink#search} —— <b>不直接碰 {@code LogRingBuffer}</b>。
 *       直接碰它会绕过脱敏（那是一条合规事故路径），而这里是与 LLM 之间唯一的接缝，
 *       接缝越窄越安全；</li>
 *   <li>把 {@link LogPage} 摊平成「模型好读 + 人好核对」的结构。</li>
 * </ol>
 *
 * <h2>为什么比 SPEC 多两个参数（level 与 trace_id）</h2>
 * <p>
 * {@link LogQuery} 的类注释已经论证过：SPEC 给的五个参数做不到两个真实的取证动作 ——
 * 只看 ERROR（否则 INFO 噪音会把样本位占满），以及按 traceId 取全链。
 * 两个参数都是<b>可选</b>的，不传时行为与 SPEC 完全一致。
 *
 * <h2>{@code samples} 为什么是对象而不是字符串</h2>
 * <p>
 * SPEC 的示例是 {@code "samples": ["MySQL query timeout", ...]}。
 * 这里改成对象，每个对象带 {@code text}（等价于 SPEC 的字符串）与结构化字段。
 * 理由是诊断里最常用的三个判断都依赖结构：
 * <pre>
 *   occurrences=321 vs 1      → 是「风暴」还是「偶发」（LogPage 里最关键的一个数）
 *   level=ERROR vs WARN       → 是「已经坏了」还是「可能要坏」
 *   orderNo / traceId 末几位   → 同类错误是对所有人都发生，还是集中在同一单/同一用户
 * </pre>
 * 这些信息若只以字符串形式给出，模型必须自己在文本里做正则 —— 而它会做错。
 *
 * <h2>Prompt Injection</h2>
 * <p>
 * 日志内容是<b>最容易被注入的</b>数据源：任何能下单的人都能让 {@code userId} 出现在日志里
 * （SPEC 第 17.3 节的例子正是这种字符串）。本类不做任何转义（那会破坏日志原貌），
 * 防护统一由 {@code ResultShaper} 的 {@code <untrusted_data>} 包裹承担 ——
 * 因此<b>不要</b>在任何地方绕过注册表直接取用本类的返回值。
 */
public final class LogsTool implements MonitorTool {

    /** 工具名。SPEC 第 9.2 节给的就是这个名字 */
    public static final String NAME = "search_logs";

    /** 时间窗上限（小时）。环形缓冲只有 10000 条，更长的窗口只会白扫 */
    private static final long MAX_WINDOW_HOURS = 12;

    private final LogSink sink;

    private final MonitorLogProperties logProperties;

    public LogsTool(LogSink sink, MonitorLogProperties logProperties) {
        this.sink = sink;
        this.logProperties = logProperties == null
                ? new MonitorLogProperties(null, null, null, null) : logProperties.normalized();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "检索应用的结构化日志（内存环形缓冲，最近 " + sink.capacity() + " 条 INFO 及以上）。"
                + "回答「这段时间报了什么错」「这一单到底怎么了」。"
                + "排障顺序建议：先用 keyword 或 level=ERROR 找线索，"
                + "拿到 traceId 或单号后再查一次取全链。"
                + "返回的 count 是命中<b>原始</b>条数，samples 是<b>按签名去重后</b>的样本，"
                + "两者相差很大说明同一个错误在反复刷（典型是连接池耗尽或重试风暴）。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        Map<String, Object> properties = new LinkedHashMap<>(10);
        properties.put("keyword", ToolSchema.string(
                "关键字，大小写不敏感。命中消息/堆栈/traceId/错误码/单号/类名任一即可。"
                        + "不要整段贴日志，用最能定位问题的片段（例如 SQLException、1003）", 200));
        properties.put("trace_id", ToolSchema.string(
                "精确取某条调用链的全部日志。规则：请求侧是 16 位十六进制，"
                        + "消费侧是 mq-<单号>。传它就等于「把这一次下单的前后经过都调出来」", 64));
        properties.put("level", ToolSchema.enumeration(
                "最低级别过滤：传 WARN 表示只要 WARN 与 ERROR（INFO 噪音通常是 ERROR 的几十倍）",
                List.of("INFO", "WARN", "ERROR")));
        properties.put("service", ToolSchema.string(
                "按链路角色过滤，默认不传 = 不过滤。**建议不要传**：serviceName 只在有 tracing "
                        + "作用域的代码路径上被写入，而启动日志、定时任务、对账这些路径没有它 —— "
                        + "带上过滤会漏掉最常见的那些证据（配置里约定的角色名是 "
                        + logProperties.serviceName() + "）", 64));
        properties.put("start_time", ToolSchema.string(
                "窗口下界。" + TimeParsing.FORMAT_HINT + "。不传则不限下界", 40));
        properties.put("end_time", ToolSchema.string(
                "窗口上界。" + TimeParsing.FORMAT_HINT + "。不传则不限上界", 40));
        properties.put("limit", ToolSchema.integer(
                "最多返回多少条（去重后）。1~" + LogQuery.MAX_LIMIT + "，默认 "
                        + logProperties.queryLimit(), 1, LogQuery.MAX_LIMIT));
        // 【参数名为什么是 order_no 而不是塞进 keyword】单号是两侧唯一的公共字段
        // （见 TraceContext 的注释：MDC 是 ThreadLocal，不会跨线程传播）。
        // 给它一个独立参数，模型就更容易想到「用单号把请求侧和消费侧串起来」这个动作，
        // 而不用去猜 keyword 能不能匹配单号 —— 虽然能，但它不知道。
        properties.put("order_no", ToolSchema.string(
                "按下单号过滤（请求侧与消费侧的日志都带它，是跨线程关联的唯一手段）", 64));
        return ToolSchema.object(properties);
    }

    @Override
    public ToolResult execute(ToolArguments args) {
        List<String> notes = new ArrayList<>(4);
        long now = System.currentTimeMillis();

        String keyword = mergeKeyword(args, notes);
        String traceId = args.optionalString("trace_id");
        String level = args.optionalString("level");
        // 【刻意不给 service 一个默认值】见下面 schema 里的说明与 LogRingBuffer#passes 的注释：
        // serviceName 只在 TraceContext 作用域内被写入，因此启动日志、定时任务日志都没有它。
        // 给一个默认过滤值会让「查启动阶段的报错」这类最常见的取证动作安静地返回 0 条。
        String service = args.optionalString("service");
        int limit = args.intOrDefault("limit", logProperties.queryLimit(), 1, LogQuery.MAX_LIMIT);

        Long start = parseBound(args, "start_time", now);
        Long end = parseBound(args, "end_time", now);
        if (start != null && end != null && start > end) {
            Long tmp = start;
            start = end;
            end = tmp;
            notes.add("start_time 晚于 end_time，已自动交换（否则会返回空结果，"
                    + "而空结果会被误读为「这段时间没有日志」）。");
        }
        if (start != null && end != null && end - start > MAX_WINDOW_HOURS * 3_600_000L) {
            start = end - MAX_WINDOW_HOURS * 3_600_000L;
            notes.add("时间窗超过 " + MAX_WINDOW_HOURS + " 小时，下界已收窄；"
                    + "环形缓冲只保留最近 " + sink.capacity() + " 条日志，更早的内容本来也已滚出。");
        }
        if (level != null) {
            String upper = level.toUpperCase(java.util.Locale.ROOT);
            if (!List.of("INFO", "WARN", "ERROR").contains(upper)) {
                throw new com.dustikun.seckill.monitor.tool.ToolArgumentException("level",
                        "值 \"" + level + "\" 不是合法级别。合法取值：INFO / WARN / ERROR。"
                                + "注意 DEBUG 与 TRACE 不进环形缓冲，因此查不到。");
            }
            level = upper;
        }

        LogQuery query = new LogQuery(keyword, traceId, level, service, start, end, limit);
        LogPage page = sink.search(query);

        Map<String, Object> data = new LinkedHashMap<>(10);
        data.put("count", page.count());
        data.put("distinctCount", page.distinctCount());
        data.put("samples", samples(page));
        // 【字段名刻意不叫 truncated】外层信封的 truncated 由 ResultShaper 设置，
        // 含义是「结果因体积上限被裁剪」。这里的含义是「命中条数多于返回条数」——
        // 两者是不同的事实，共用一个字段名会让「到底哪个被截断了」永远说不清。
        data.put("queryTruncated", page.truncated());
        data.put("query", page.query());
        if (page.truncated()) {
            notes.add("命中数超过 limit，返回的只是其中一部分（按时间倒序取最新的）。"
                    + "要缩小范围请加 level 或更短的时间窗。");
        }

        if (page.bufferStats() != null) {
            Map<String, Object> stats = new LinkedHashMap<>(6);
            stats.put("capacity", page.bufferStats().capacity());
            stats.put("size", page.bufferStats().size());
            stats.put("oldestText", page.bufferStats().oldestMillis() == 0
                    ? null : TimeParsing.format(page.bufferStats().oldestMillis()));
            stats.put("newestText", page.bufferStats().newestMillis() == 0
                    ? null : TimeParsing.format(page.bufferStats().newestMillis()));
            stats.put("dropped", page.bufferStats().dropped());
            stats.put("totalStored", page.bufferStats().totalStored());
            data.put("buffer", stats);
            // 【为什么要把「丢过日志」单独提醒】dropped > 0 时「没查到」有可能是
            // 「写缓冲时被丢了」，而不是「没发生」。不说明的话，Agent 会把
            // 一个观测缺口当成一个否证。
            if (page.bufferStats().dropped() > 0) {
                notes.add("环形缓冲累计丢弃过 " + page.bufferStats().dropped()
                        + " 条日志（高并发下写锁竞争所致）。因此「没查到」不等于「没发生」。");
            }
        }

        if (page.count() == 0) {
            notes.add("没有命中任何日志。请先确认三件事：① 时间窗是否落在应用启动之后；"
                    + "② 关键字是否是日志里真的会出现的片段（类名、异常类型、错误码）；"
                    + "③ 缓冲只保留最近 " + sink.capacity() + " 条且最低级别是 INFO —— "
                    + "更早的与 DEBUG 级别的日志本来就查不到。");
        } else if (page.count() > page.distinctCount() * 3 && page.count() - page.distinctCount() > 5) {
            // 这一条是 LogPage 设计的直接兑现：count 远大于「种类数」意味着风暴。
            // 把判断写进 note 而不是留给模型，是因为这是本系统里最确定的一类结论。
            notes.add("命中 " + page.count() + " 条但只有 " + page.distinctCount()
                    + " 种不同的错误 —— 属于「同一个错误反复刷」（典型成因：连接池耗尽、"
                    + "下游超时重试、锁等待），排查方向应当是那个根因，而不是" + page.distinctCount()
                    + " 个独立问题。");
        }
        if (!page.note().isBlank()) {
            notes.add(page.note());
        }
        return ToolResult.ok(name(), data, notes);
    }

    /**
     * 合并 {@code keyword} 与 {@code order_no} 两个参数。
     *
     * <p>【为什么单号不能直接丢给 keyword】{@code LogRingBuffer} 的关键字匹配是
     * <b>子串</b>匹配，而单号是 18 位数字。传 keyword=单号 时它也能命中，
     * 但同一时刻可能有别的字段（例如某个毫秒时间戳）恰好包含这串数字 —— 概率极低但存在。
     * 更实际的理由是：把单号作为独立参数传下去，可以让「按单号取全链」这个动作
     * 在轨迹里明确可见（复盘时一眼能看出 Agent 用的是哪种关联方式）。
     * 两者同时给出时按 OR 语义处理需要引擎支持，而这里引擎只有单关键字匹配 ——
     * 因此显式拒绝，而不是悄悄丢掉一个（丢掉哪个都会让结果与调用者预期不符）。
     */
    private static String mergeKeyword(ToolArguments args, List<String> notes) {
        String keyword = args.optionalString("keyword");
        String orderNo = args.optionalString("order_no");
        if (keyword != null && orderNo != null && !keyword.equals(orderNo)) {
            throw new com.dustikun.seckill.monitor.tool.ToolArgumentException("order_no",
                    "本工具一次只支持一个匹配条件，但 keyword=\"" + keyword
                            + "\" 与 order_no=\"" + orderNo + "\" 同时给出。"
                            + "请只用其中一个：要按单号取全链就只传 order_no。");
        }
        return orderNo != null ? orderNo : keyword;
    }

    private static Long parseBound(ToolArguments args, String name, long now) {
        String raw = args.optionalString(name);
        if (raw == null) {
            return null;
        }
        return TimeParsing.parseOrThrow(raw, 0L, now, name);
    }

    /** 把样本摊平成便于模型阅读的结构。见类注释「为什么 samples 是对象」 */
    private static List<Map<String, Object>> samples(LogPage page) {
        List<Map<String, Object>> samples = new ArrayList<>(page.samples().size());
        for (LogPage.LogSample sample : page.samples()) {
            LogRecord record = sample.record();
            Map<String, Object> item = new LinkedHashMap<>(12);
            // text 与 SPEC 第 9.2 节的 samples 字符串等价，一行就能读
            item.put("text", record.renderForText());
            item.put("occurrences", sample.occurrences());
            item.put("level", record.level());
            item.put("logger", shorten(record.loggerName()));
            item.put("thread", record.threadName());
            putIfPresent(item, "traceId", record.traceId());
            putIfPresent(item, "orderNo", record.orderNo());
            putIfPresent(item, "userId", record.userId());
            putIfPresent(item, "errorCode", record.errorCode());
            if (record.throwable() != null && !record.throwable().isBlank()) {
                item.put("throwable", record.throwable());
            }
            item.put("firstText", TimeParsing.format(sample.firstMillis()));
            item.put("lastText", TimeParsing.format(sample.lastMillis()));
            if (sample.masked()) {
                // 【脱敏必须显式标注】模型看到 userId=***8000 时会以为这是原始值，
                // 从而在结论里写出「用户 ***8000」这种奇怪的东西；标明之后它会知道
                // 「这是脱敏后的标识，只能用于判断是不是同一个人」。
                item.put("masked", true);
            }
            samples.add(item);
        }
        return samples;
    }

    private static void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    /** 日志里的类名是全限定名，缩到包名首字母形式（c.d.s.Mq.SeckillOrderConsumer），省上下文 */
    private static String shorten(String loggerName) {
        if (loggerName == null || loggerName.length() <= 40) {
            return loggerName;
        }
        String[] parts = loggerName.split("\\.");
        StringBuilder sb = new StringBuilder(loggerName.length());
        for (int i = 0; i < parts.length - 1; i++) {
            if (!parts[i].isEmpty()) {
                sb.append(parts[i].charAt(0)).append('.');
            }
        }
        return sb.append(parts[parts.length - 1]).toString();
    }
}
