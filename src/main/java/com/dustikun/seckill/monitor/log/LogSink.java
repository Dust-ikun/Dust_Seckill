package com.dustikun.seckill.monitor.log;

import com.dustikun.seckill.monitor.core.Masker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Logs Tool 的<b>产出侧</b>门面：查询 → 脱敏 → 截断 → 结构化返回。
 *
 * <h2>它和 {@link LogRingBuffer} 的分工</h2>
 * <pre>
 *   LogRingBuffer   存什么：忠实、完整、快速。不做任何改写（脱敏也不做）
 *   LogSink         给什么：按配置脱敏、按上限截断、按限额取样
 * </pre>
 * <p>把两件事分开的理由只有一个但很硬：<b>人看的与人给 LLM 看的必须能不同</b>。
 * 排障时人需要原始值（按 userId 追一位用户的下单链路），而 SPEC 第 17.2 节
 * 明确禁止把完整账户信息送进 LLM。若在存储层就脱敏，人就永远看不到原值；
 * 若在存储层不脱敏而输出层也忘了脱，就是合规事故。
 * 分开之后，「有没有脱敏」只需检查这一个类。
 *
 * <h2>为什么脱敏之后还要再截断一次</h2>
 * <p>
 * 写侧（{@code maxMessageChars=4000}）管的是<b>内存</b>。
 * 读侧（{@code maxSampleChars=500}）管的是<b>LLM 上下文成本</b>。
 * 一条 4000 字符的异常堆栈在缓冲里应当完整（人排查要用），
 * 但塞进上下文里 20 条就是 8 万字符 —— 那既贵，又会把真正的关键行淹没
 * （SPEC 第 23.3 节「不要直接把大量原始日志全部传给 LLM」说的就是这件事）。
 */
public class LogSink {

    private static final Logger log = LoggerFactory.getLogger(LogSink.class);

    private final LogRingBuffer buffer;

    private final MonitorLogProperties props;

    private final Masker masker;

    /**
     * 构造器只做装配，<b>不做能力探测</b>。
     * <p>探测放在 {@link #probeMasking()} 里、由 {@link MonitorLogConfiguration}
     * 显式调用一次 —— 构造器里打日志会让「创建对象」这个动作有副作用，
     * 而那个副作用发生在日志系统自身的装配期，很容易变成循环依赖。
     */
    public LogSink(LogRingBuffer buffer, MonitorLogProperties rawProps, Masker masker) {
        this.buffer = buffer;
        this.props = (rawProps == null ? new MonitorLogProperties(null, null, null, null) : rawProps)
                .normalized();
        this.masker = masker;
    }

    // ================================================================ 查询

    /**
     * 一次完整的日志检索。
     *
     * @param query 查询条件；{@code null} 时退化为「最近若干条」
     * @return 已脱敏、已截断的结果。任何情况下都<b>不抛异常</b>：
     *         一次日志查询失败不该让 Agent 的整次诊断崩掉（SPEC 第 25 节
     *         「Agent 异常不能阻塞交易请求」的对偶要求）
     */
    public LogPage search(LogQuery query) {
        LogQuery effective = effectiveQuery(query);
        try {
            LogPage page = buffer.search(effective);
            return shape(page, effective);
        } catch (RuntimeException e) {
            log.warn("[LogsTool] 日志检索失败，返回空结果。条件={}，原因={}",
                    effective.describe(), e.toString());
            return LogPage.empty("日志检索内部异常（" + e.getClass().getSimpleName()
                    + "），不代表这段时间没有日志。");
        }
    }

    /** 缓冲区现状。不需要脱敏，读的就是计数 */
    public LogPage.BufferStats stats() {
        return buffer.stats();
    }

    /** 缓冲区容量（条）。供健康检查断言「上界存在」 */
    public int capacity() {
        return buffer.capacity();
    }

    /**
     * 把调用方的条件夹到本组件允许的范围。
     * <p>两个上限各有理由：{@code limit} 由配置给（默认 20，对齐
     * {@code tool.logs.max-samples}），{@code MAX_LIMIT} 是硬闸门 ——
     * 入参来自 LLM，它一定会偶尔给出 {@code limit=100000}。
     */
    private LogQuery effectiveQuery(LogQuery query) {
        LogQuery base = (query == null
                ? new LogQuery(null, null, null, null, null, null, props.queryLimit())
                : query).normalized();
        int limit = base.limit() <= 0 ? props.queryLimit() : Math.min(base.limit(), props.queryLimit());
        return new LogQuery(base.keyword(), base.traceId(), base.level(), base.serviceName(),
                base.fromMillis(), base.toMillis(), limit);
    }

    // ================================================================ 产出整形

    private LogPage shape(LogPage page, LogQuery query) {
        List<LogPage.LogSample> shaped = new ArrayList<>(page.samples().size());
        boolean anyMasked = false;
        boolean anyTruncated = false;

        for (LogPage.LogSample sample : page.samples()) {
            Shaped s = shapeRecord(sample.record());
            anyMasked |= s.masked;
            anyTruncated |= s.truncated;
            shaped.add(new LogPage.LogSample(sample.occurrences(), sample.firstMillis(),
                    sample.lastMillis(), s.record, s.masked));
        }

        // 去重在这里再做一次：脱敏会把不同的原文折叠成同一个字符串
        // （两个不同的手机号都变成 ***）。若不去重，limit=20 可能被 20 条一模一样的
        // `userId=***` 占满，而 Agent 会以为「这里有 20 种不同的问题」。
        List<LogPage.LogSample> deduped = props.dedupe() ? mergeDuplicates(shaped) : shaped;
        boolean dedupeTruncated = deduped.size() < shaped.size();
        if (deduped.size() > query.limit()) {
            deduped = deduped.subList(0, query.limit());
            dedupeTruncated = true;
        }

        List<String> notes = new ArrayList<>(4);
        notes.add(page.note());
        if (anyMasked) {
            notes.add("样本已脱敏：口令/token 等值替换为 " + Masker.MASK
                    + "，userId/orderNo/traceId 保留末 " + masker.keepTailChars() + " 位。");
        }
        if (dedupeTruncated) {
            notes.add("脱敏后出现内容相同的样本，已合并计数（occurrences 累加）。");
        }
        for (String warning : masker.patternWarnings()) {
            notes.add("【配置告警】" + warning);
        }

        return new LogPage(page.count(), deduped,
                page.truncated() || anyTruncated || dedupeTruncated,
                String.join(" ", notes),
                query.describe(),
                page.bufferStats());
    }

    /**
     * 合并脱敏后变得完全相同的样本。
     * <p>累加 {@code occurrences}、取时间窗的并集，并保留<b>先出现的那条</b>作为代表。
     * <p>用 {@link LinkedHashMap} 保序：输入的样本已按「最新的在前」排好，
     * 合并后若改用别的容器会打乱这个顺序，而顺序本身就是信息（越靠前越新）。
     */
    private static List<LogPage.LogSample> mergeDuplicates(List<LogPage.LogSample> samples) {
        Map<String, LogPage.LogSample> merged = new LinkedHashMap<>(samples.size() * 2);
        for (LogPage.LogSample s : samples) {
            String key = s.record().level() + '|' + s.record().message() + '|' + s.record().throwable();
            LogPage.LogSample prev = merged.get(key);
            if (prev == null) {
                merged.put(key, s);
            } else {
                merged.put(key, new LogPage.LogSample(
                        prev.occurrences() + s.occurrences(),
                        Math.min(prev.firstMillis(), s.firstMillis()),
                        Math.max(prev.lastMillis(), s.lastMillis()),
                        prev.record(), prev.masked()));
            }
        }
        return new ArrayList<>(merged.values());
    }

    private Shaped shapeRecord(LogRecord r) {
        String message = r.message();
        boolean truncated = false;
        if (message != null && message.length() > props.maxSampleChars()) {
            message = message.substring(0, props.maxSampleChars()) + LogRecord.TRUNCATED_MARK;
            truncated = true;
        }
        String throwable = r.throwable();
        if (throwable != null && throwable.length() > props.maxSampleChars()) {
            throwable = throwable.substring(0, props.maxSampleChars()) + LogRecord.TRUNCATED_MARK;
            truncated = true;
        }

        String maskedMessage = masker.mask(message);
        String maskedThrowable = masker.mask(throwable);
        // 【traceId 也要脱敏，但必须是「确定性的」】
        // 它与 userId 不是一类东西 —— 它不标识人，它标识一次调用。
        // 之所以仍然保留尾部而不是整体替换：Agent 可能先用一次查询拿到 traceId，
        // 再用它做第二次查询（"把这一单的全链日志取出来"）。
        // 只要同一个 traceId 映射到同一个掩码值，这条链路就仍然可用；
        // 而保留尾部让它对人也是可核对的。
        String maskedTraceId = r.traceId() == null ? null : masker.maskIdentifier(r.traceId());

        boolean masked = !Objects.equals(maskedMessage, message)
                || !Objects.equals(maskedThrowable, throwable);

        LogRecord out = new LogRecord(
                r.timestampMillis(), r.level(), r.loggerName(), r.threadName(),
                maskedMessage, maskedThrowable,
                maskedTraceId,
                r.requestId(),
                r.serviceName(), r.operation(), r.errorCode(),
                masker.maskIdentifier(r.userId()),
                masker.maskIdentifier(r.orderNo()),
                masker.maskIdentifier(r.stockId()));
        return new Shaped(out, masked, truncated);
    }

    private record Shaped(LogRecord record, boolean masked, boolean truncated) {
    }

    // ================================================================ 启动自检

    /**
     * 用一组人造输入验证脱敏规则确实生效，并把结果写进启动日志。
     *
     * <p><b>为什么需要这一步</b>：脱敏是本项目里最容易「看起来配了、实际没生效」的地方。
     * 例如把 {@code mask.patterns} 写成 {@code (password=)(?i)([^&]+)} →
     * Java 会以 "Flags should be at the start" 拒绝编译；又例如正则写对了但没写分组，
     * 于是替换掉的是整行而不是值。这两种情况都不会让应用起不来，
     * 只会在某一天被 LLM 看到一份完整的连接串。
     *
     * <p>因此这里的做法是：<b>启动时用真实规则跑一遍，把「输入 → 输出」打进日志</b>。
     * 任何配错都会在启动日志里以一行的形式当场暴露，而不是等到事故之后。
     * 代价是启动时多几十微秒。
     */
    public void probeMasking() {
        String sample = "连接失败 url=jdbc:mysql://localhost:3306/seckill?user=root&password=example-value-a1b2c3"
                + "&useSSL=false Authorization: Bearer sk-abcdef123456"
                + " phone=13800138000";
        String masked = masker.mask(sample);

        List<String> problems = new ArrayList<>(masker.patternWarnings());
        // 逐条检查「必须被脱掉」的片段是否真的消失了。
        // 用「原文里的敏感值是否仍出现在输出里」作为判据，而不是检查某个具体占位符 ——
        // 后者会把断言绑死在实现细节上。
        if (masked.contains("example-value-a1b2c3")) {
            problems.add("JDBC 口令未被脱敏");
        }
        if (masked.contains("sk-abcdef123456")) {
            problems.add("Bearer Token 未被脱敏");
        }
        if (masked.contains("13800138000")) {
            problems.add("手机号未被脱敏");
        }
        if (!masked.contains("useSSL=false")) {
            // 反向断言：脱敏不能把不该动的内容一起吃掉。
            // 「脱得太多」同样是缺陷 —— 它会让排障时看不出连的是哪个库。
            problems.add("脱敏越界：连接串的其它参数被一并抹掉");
        }

        if (problems.isEmpty()) {
            log.info("[LogsTool] 脱敏自检通过。生效规则：Bearer {} 条 / 关键字 {} 条 / 自定义正则 {} 条；"
                            + "示例：{}",
                    masker.bearerRuleCount(), masker.keywordRuleCount(),
                    masker.customRuleCount(), abbreviate(masked));
        } else {
            // 用 error 而不是 warn：这是合规问题，不是性能提示。
            // 而且它是「静默失效」型的缺陷，只有这里会主动喊出来。
            log.error("[LogsTool] 脱敏自检未通过，进入 LLM 的内容可能包含敏感信息！问题={}；"
                    + "实际输出={}", problems, abbreviate(masked));
        }
    }

    /** 供启动日志与测试使用：脱敏一段文本（不经过环形缓冲） */
    public String maskText(String text) {
        return masker.mask(text);
    }

    private static String abbreviate(String s) {
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }
}
