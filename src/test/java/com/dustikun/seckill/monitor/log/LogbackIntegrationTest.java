package com.dustikun.seckill.monitor.log;

import ch.qos.logback.classic.LoggerContext;
import com.dustikun.seckill.monitor.core.MaskProperties;
import com.dustikun.seckill.monitor.core.Masker;
import com.dustikun.seckill.monitor.core.TraceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端验证「Logback → LogRingBuffer → LogSink」这条链路真的通。
 *
 * <h2>为什么必须有这一层测试，而不是只测 {@link LogRingBuffer}</h2>
 * <p>
 * 单元测试能证明缓冲本身正确，却证明不了<b>日志有没有进去</b>。这条链路上有三个
 * 只在装配时才会暴露、且都不会报错的风险点：
 * <ol>
 *   <li><b>MDC 取值时机</b>：appender 必须在日志事件的现场读 MDC。若在读完之后取，
 *       拿到的会是空值 —— 于是每条日志的 traceId 都是 null，而系统看起来一切正常；</li>
 *   <li><b>appender 名字对不上</b>：{@code logback-spring.xml} 里叫
 *       {@code RING_BUFFER}，代码里找的是同一个名；改名只改一处会静默失效；</li>
 *   <li><b>MDC 不清理</b>：线程池复用线程时上一条请求的 traceId 会残留，
 *       这比没有 traceId 更容易误导人。</li>
 * </ol>
 * <p>这里的做法是<b>自己建一个 {@link LoggerContext}</b>，而不是用全局的 ——
 * 全局上下文会让本测试的日志污染其它测试（以及 IDEA 的控制台），
 * 而独立上下文让本测试完全自包含。这是第 4 条要验证的行为。
 *
 * <p><b>不依赖 Spring / Redis / MySQL / RocketMQ</b>，因此它永远会跑，不会被跳过。
 */
@DisplayName("日志底座：Logback appender 与 MDC 串接")
class LogbackIntegrationTest {

    private LoggerContext context;

    private LogRingBufferAppender appender;

    private Logger logger;

    /**
     * 本测试专用的 logger。
     * <p><b>它必须来自本测试自己的 {@link LoggerContext}</b>，不能用 Lombok {@code @Slf4j}
     * 生成的那个 —— 后者绑定在<b>全局</b> SLF4J 上下文上，写进去的日志会跑到生产配置的
     * appender 里，本测试的缓冲永远收不到东西，而失败信息只会说「查到 0 条」，
     * 完全看不出真正的原因。
     */
    private ch.qos.logback.classic.Logger contextLogger() {
        return (ch.qos.logback.classic.Logger) logger;
    }

    private ch.qos.logback.classic.Logger rootLogger() {
        return context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    }

    private static java.util.List<String> appenderNames(ch.qos.logback.classic.Logger l) {
        java.util.List<String> names = new java.util.ArrayList<>();
        l.iteratorForAppenders().forEachRemaining(a -> names.add(a.getName()));
        return names;
    }

    @BeforeEach
    void setUp() {
        // 独立上下文：不碰全局的那一个，测试之间互不干扰
        context = new LoggerContext();
        context.setName("test-context-" + System.nanoTime());

        appender = new LogRingBufferAppender();
        appender.setContext(context);
        appender.setCapacity(500);
        appender.setLevel("INFO");
        appender.start();

        ch.qos.logback.classic.Logger root =
                context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        root.setLevel(ch.qos.logback.classic.Level.INFO);
        root.addAppender(appender);

        logger = context.getLogger("com.dustikun.seckill.monitor.log.LogbackIntegrationTest");
    }

    @AfterEach
    void tearDown() {
        // 顺序有讲究：先清 MDC 再停 appender/context。
        // 反过来时，关闭过程中的日志会带着上一条用例的 traceId 进入缓冲，
        // 而那些「临终日志」会让后面读 bufferStats 的断言看到一个意外的条数。
        TraceContext.clear();
        appender.stop();
        context.stop();
    }

    // ================================================================ 基本通路

    @Test
    @DisplayName("写入的日志能按字段查回来：级别、类名、线程、消息")
    void logReachesBufferWithStructuredFields() {
        logger.info("第一条结构化日志");

        // 诊断信息：失败时能立刻看出是「appender 没挂上」还是「被过滤掉了」
        assertEquals(1, appender.getBuffer().stats().size(),
                "调试：size=" + appender.getBuffer().stats().size()
                        + " totalStored=" + appender.getBuffer().totalStored()
                        + " started=" + appender.isStarted()
                        + " threshold=" + appender.thresholdLevel()
                        + " loggerLevel=" + contextLogger().getEffectiveLevel()
                        + " attached=" + appenderNames(contextLogger())
                        + " rootAttached=" + appenderNames(rootLogger()));

        LogPage page = appender.getBuffer()
                .search(new LogQuery("结构化", null, null, null, null, null, 10));

        assertEquals(1, page.count(), "日志必须真的进了缓冲");
        LogRecord record = page.samples().get(0).record();
        assertEquals("INFO", record.level());
        assertEquals("com.dustikun.seckill.monitor.log.LogbackIntegrationTest", record.loggerName());
        assertTrue(record.message().contains("第一条结构化日志"));
        assertTrue(record.timestampMillis() > 0, "事件时间戳必须被记录");
        assertFalse(record.threadName().isEmpty(), "线程名必须被记录");
    }

    @Test
    @DisplayName("MDC 里的 traceId / operation / orderNo 被写进对应字段")
    void mdcFieldsAreCaptured() {
        try (TraceContext.Scope ignored = TraceContext.open(
                "a1b2c3d4e5f60718", "seckill",
                java.util.Map.of(TraceContext.ORDER_NO, "SN1759900000123456"))) {
            logger.info("带上下文的一行");
        }

        LogPage page = appender.getBuffer()
                .search(new LogQuery("带上下文", null, null, null, null, null, 10));
        LogRecord record = page.samples().get(0).record();

        // 【这三条断言就是批次 1 的验收判据】「日志里能 grep 到 traceId 并串起一次下单」
        assertEquals("a1b2c3d4e5f60718", record.traceId());
        assertEquals("seckill", record.operation());
        assertEquals("SN1759900000123456", record.orderNo());
    }
    @Test
    @DisplayName("同一 traceId 下的多行日志能被一次查全（串起一次下单）")
    void oneTraceIdTiesMultipleLinesTogether() {
        String traceId = "0123456789abcdef";
        try (TraceContext.Scope ignored = TraceContext.open(traceId, "seckill",
                java.util.Map.of(TraceContext.ORDER_NO, "SN1759900000999999"))) {
            logger.info("[建单] 预订单已落库");
            logger.info("[投递] 消息已进 Broker");
        }
        try (TraceContext.Scope ignored = TraceContext.open("ffffffffffffffff", "other")) {
            logger.info("[无关] 别的请求");
        }

        LogPage page = appender.getBuffer()
                .search(new LogQuery(null, traceId, null, null, null, null, 20));

        assertEquals(2, page.count(), "同一 traceId 只应命中它自己的那两行");
        assertTrue(page.samples().stream()
                .allMatch(s -> s.record().traceId().equals(traceId)));
    }

    @Test
    @DisplayName("异常堆栈被完整保留：Logs Tool 靠它区分故障类型")
    void throwableIsRenderedIntoItsOwnField() {
        try {
            throw new java.net.SocketTimeoutException("Read timed out");
        } catch (Exception e) {
            logger.error("[投递异常] orderNo=SN1759900000123456", e);
        }

        LogPage page = appender.getBuffer()
                .search(new LogQuery("timed out", null, "ERROR", null, null, null, 10));
        LogRecord record = page.samples().get(0).record();

        assertEquals("ERROR", record.level());
        assertTrue(record.throwable() != null, "堆栈必须被单独保留，而不是并进消息里");
        assertTrue(record.throwable().contains("java.net.SocketTimeoutException"),
                "堆栈要含异常类名，实际：" + record.throwable());
        assertTrue(record.throwable().contains("LogbackIntegrationTest"),
                "堆栈要含抛出位置，否则无法定位代码");
    }

    // ================================================================ 边界与自我保护

    @Test
    @DisplayName("低于阈值的级别不进缓冲：DEBUG 不占容量（默认 INFO）")
    void belowThresholdIsFilteredBeforeFormatting() {
        ch.qos.logback.classic.Logger target =
                context.getLogger("com.dustikun.seckill.monitor.log.LowLevelProbe");
        target.setLevel(ch.qos.logback.classic.Level.DEBUG);

        // 【两条消息的字面必须互不包含】否则按关键字检索时两条会互相命中，
        // 于是「DEBUG 被过滤了」这个结论就测不出来了。
        target.debug("DEBUG-LEVEL-SHOULD-NOT-BE-STORED");
        target.info("INFO-LEVEL-SHOULD-BE-STORED");

        assertEquals(1, appender.getBuffer()
                        .search(new LogQuery("SHOULD-BE-STORED", null, "INFO", null, null, null, 10))
                        .count(),
                "只有 INFO 及以上应当入选");
        assertEquals(0, appender.getBuffer()
                        .search(new LogQuery("SHOULD-NOT-BE-STORED", null, null, null, null, null, 10))
                        .count(),
                "DEBUG 不该进缓冲");
    }

    @Test
    @DisplayName("级别阈值可配置：调成 DEBUG 后 DEBUG 也入选")
    void thresholdIsConfigurable() {
        LogRingBufferAppender debugAppender = new LogRingBufferAppender();
        debugAppender.setContext(context);
        debugAppender.setCapacity(100);
        debugAppender.setLevel("DEBUG");
        debugAppender.start();

        ch.qos.logback.classic.Logger target = context.getLogger("...ThresholdProbe");
        target.setLevel(ch.qos.logback.classic.Level.DEBUG);
        target.addAppender(debugAppender);

        target.debug("调试细节");

        assertEquals(1, debugAppender.getBuffer()
                .search(new LogQuery("调试细节", null, null, null, null, null, 10)).count());
        debugAppender.stop();
    }

    @Test
    @DisplayName("非法级别名被发现，而不是静默用默认值")
    void invalidLevelNameIsReported() {
        LogRingBufferAppender bad = new LogRingBufferAppender();
        bad.setContext(context);
        bad.setLevel("VERBOSE");
        bad.start();

        // 拼错的级别名会让过滤形同虚设（或完全关掉），必须留下可见痕迹
        boolean reported = context.getStatusManager().getCopyOfStatusList().stream()
                .anyMatch(s -> String.valueOf(s.getMessage()).contains("VERBOSE"));
        assertTrue(reported, "无法识别的级别名必须被报告出来");
        bad.stop();
    }

    @Test
    @DisplayName("MDC 在作用域退出后被还原：线程池复用时不会把 traceId 泄漏给下一个请求")
    void mdcIsRestoredAfterScope() {
        // 【这条防的是最难查的一类问题】Tomcat 与 RocketMQ 都会复用线程。
        // 若 MDC 没清，下一个请求的日志会带上一个不属于它的 traceId，
        // 于是排障时按 traceId 捞出来的日志里混着别人的行 —— 比没有 traceId 更误导。
        try (TraceContext.Scope ignored = TraceContext.open("1111111111111111", "第一段")) {
            logger.info("第一段的日志");
        }
        logger.info("作用域之外的日志");

        var first = appender.getBuffer()
                .search(new LogQuery("第一段的日志", null, null, null, null, null, 5))
                .samples().get(0).record();
        var outside = appender.getBuffer()
                .search(new LogQuery("作用域之外的日志", null, null, null, null, null, 5))
                .samples().get(0).record();

        assertEquals("1111111111111111", first.traceId());
        assertEquals(null, outside.traceId(), "作用域之外不该带着上一个 traceId");
        assertEquals(null, outside.operation());
    }

    @Test
    @DisplayName("嵌套作用域退出后恢复外层上下文（而不是清空）")
    void nestedScopesRestoreOuterContext() {
        try (TraceContext.Scope outer = TraceContext.open("aaaaaaaaaaaaaaaa", "外层")) {
            logger.info("外层的第一行");
            try (TraceContext.Scope inner = TraceContext.open("bbbbbbbbbbbbbbbb", "内层")) {
                logger.info("内层的一行");
            }
            logger.info("外层的第二行");
        }

        LogPage outerPage = appender.getBuffer()
                .search(new LogQuery("外层的", null, null, null, null, null, 10));
        LogPage innerPage = appender.getBuffer()
                .search(new LogQuery("内层的", null, null, null, null, null, 10));

        assertEquals(2, outerPage.count(), "外层两行都该带外层的 traceId");
        assertTrue(outerPage.samples().stream()
                        .allMatch(s -> "aaaaaaaaaaaaaaaa".equals(s.record().traceId())),
                "内层退出后必须恢复外层的 traceId，而不是清空");
        assertTrue(innerPage.samples().stream()
                .allMatch(s -> "bbbbbbbbbbbbbbbb".equals(s.record().traceId())));
    }

    @Test
    @DisplayName("getBuffer 在 start() 之前调用时报错，而不是返回 null 埋下 NPE")
    void bufferIsUnavailableBeforeStart() {
        LogRingBufferAppender notStarted = new LogRingBufferAppender();
        assertFalse(notStarted.isBufferReady());
        IllegalStateException e = assertThrows(IllegalStateException.class, notStarted::getBuffer);
        assertTrue(e.getMessage().contains("start"), "错误信息要指出真正的原因：" + e.getMessage());
    }

    // ================================================================ LogSink 产出侧

    @Test
    @DisplayName("LogSink 按配置脱敏：口令与单号不进入 LLM 上下文")
    void sinkMasksSensitiveContent() {
        LogSink sink = new LogSink(appender.getBuffer(),
                new MonitorLogProperties("order-service", 20, 500, true),
                new Masker(new MaskProperties(null, null, null)));

        logger.error("连接失败 url=jdbc:mysql://localhost:3306/seckill?user=root&password=example-value-a1b2c3"
                + "&useSSL=false");

        LogPage page = sink.search(new LogQuery("连接失败", null, null, null, null, null, 10));
        String text = page.samples().get(0).record().message();

        assertFalse(text.contains("example-value-a1b2c3"), "给 LLM 的文本不能含口令，实际：" + text);
        assertTrue(text.contains("password="), "字段名要保留，便于人理解被脱敏的是什么");
        assertTrue(page.samples().get(0).masked(), "必须标注 masked=true，让 Agent 知道内容已被改写");
    }

    @Test
    @DisplayName("LogSink 截断单条样本：写侧管内存、读侧管上下文成本")
    void sinkTruncatesLongSamples() {
        LogSink sink = new LogSink(appender.getBuffer(),
                new MonitorLogProperties("order-service", 20, 120, true),
                new Masker(new MaskProperties(null, null, null)));

        logger.error("很长的消息 " + "z".repeat(2_000));

        LogPage page = sink.search(new LogQuery("很长的消息", null, null, null, null, null, 10));
        String text = page.samples().get(0).record().message();

        assertTrue(text.length() <= 120 + LogRecord.TRUNCATED_MARK.length(),
                "读侧上限必须生效，实际长度 " + text.length());
        assertTrue(text.endsWith(LogRecord.TRUNCATED_MARK));
        assertTrue(page.truncated(), "截断要显式标注");
    }

    @Test
    @DisplayName("LogSink 的 limit 由配置决定，且不可被调用方放大")
    void sinkEnforcesConfiguredLimit() {
        LogSink sink = new LogSink(appender.getBuffer(),
                new MonitorLogProperties("order-service", 3, 500, true),
                new Masker(new MaskProperties(null, null, null)));

        for (int i = 0; i < 20; i++) {
            logger.info("第 " + i + " 种情况");
        }

        LogPage page = sink.search(new LogQuery(null, null, null, null, null, null, 1_000));
        assertTrue(page.distinctCount() <= 3,
                "配置的 queryLimit 是上界，LLM 传大数也不能突破，实际 " + page.distinctCount());
    }

    @Test
    @DisplayName("LogSink 在内部异常时返回空结果并说明原因，而不是把异常抛给 Agent")
    void sinkNeverThrows() {
        LogSink sink = new LogSink(appender.getBuffer(),
                new MonitorLogProperties(null, null, null, null),
                new Masker(new MaskProperties(null, null, null)));

        // null 查询应当被规整成「最近若干条」，而不是 NPE
        LogPage page = sink.search(null);
        assertTrue(page.note() != null && !page.note().isBlank(),
                "结果里必须有口径说明，避免空结果被读成「系统正常」");
    }

    @Test
    @DisplayName("bufferStats 经 LogSink 透出，供 Agent 判断证据完整性")
    void sinkExposesBufferStats() {
        LogSink sink = new LogSink(appender.getBuffer(),
                new MonitorLogProperties("order-service", 20, 500, true),
                new Masker(new MaskProperties(null, null, null)));

        logger.info("一条用于统计的日志");

        LogPage page = sink.search(new LogQuery("用于统计", null, null, null, null, null, 10));
        var stats = page.bufferStats();

        assertEquals(500, stats.capacity());
        assertTrue(stats.size() >= 1);
        assertTrue(stats.newestMillis() > 0);
    }
}
