package com.dustikun.seckill.monitor.log;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LogRingBuffer} 的行为回归。
 *
 * <p><b>刻意不依赖 Spring、Redis、MySQL 与 RocketMQ</b>：本类只关心「有界缓冲的语义」，
 * 而它恰恰是最需要频繁跑的那一类测试。任何外部依赖都会让它在「中间件没起」时被跳过，
 * 而跳过的测试等于没有测试。
 *
 * <p><b>重点覆盖三件事</b>：
 * <ol>
 *   <li><b>上界真的存在</b>：写超容量后 {@code size()} 不增长 —— 这是「日志不会把应用拖死」的底线；</li>
 *   <li><b>去重按签名而不是按原文</b>：变量不同、模板相同的日志必须合并成一条，
 *       否则故障刷屏时 {@code limit=20} 会被同一个错误占满；</li>
 *   <li><b>「查不到」与「没发生」的区分依据</b>：{@code bufferStats} 必须如实报告
 *       容量、丢弃数与时间范围，否则 Agent 会把「证据不全」误读成「没有问题」。</li>
 * </ol>
 */
@DisplayName("Logs Tool 底座：有界环形缓冲")
class LogRingBufferTest {

    private static final long T0 = 1_800_000_000_000L;

    private static LogRecord rec(long ts, String level, String msg) {
        return new LogRecord(ts, level, "com.dustikun.seckill.TestLogger", "http-nio-8081-exec-1",
                msg, null, "abcdef0123456789", "req-0001", "order-service", "http:POST",
                null, "1001", "SN1759900000123456", "1");
    }

    private static LogRecord rec(long ts, String level, String msg, String throwable) {
        LogRecord base = rec(ts, level, msg);
        return new LogRecord(base.timestampMillis(), base.level(), base.loggerName(),
                base.threadName(), base.message(), throwable, base.traceId(), base.requestId(),
                base.serviceName(), base.operation(), base.errorCode(), base.userId(),
                base.orderNo(), base.stockId());
    }

    // ================================================================ 有界性

    @Test
    @DisplayName("容量是硬上界：写超容量后 size 不再增长，且最老的被覆盖")
    void capacityIsHardBound() {
        LogRingBuffer buffer = new LogRingBuffer(10, 4_000, 50);

        for (int i = 0; i < 25; i++) {
            buffer.offer(rec(T0 + i, "INFO", "第 " + i + " 条"));
        }

        assertEquals(10, buffer.capacity(), "容量由构造参数决定");
        assertEquals(10, buffer.size(), "写超容量后 size 必须停在容量上");
        assertEquals(25, buffer.totalStored(), "总写入量如实累计");

        // 保留的应当是最后 10 条（第 15~24 条）
        LogPage page = buffer.search(new LogQuery(null, null, null, null, null, null, 100));
        assertEquals(10, page.count(), "只有最近 10 条还在");
        List<String> messages = page.samples().stream().map(s -> s.record().message()).toList();
        assertTrue(messages.contains("第 24 条"), "最新一条必须在");
        assertFalse(messages.contains("第 14 条"), "被顶掉的那条不该还在");
    }

    @Test
    @DisplayName("approxBytes 不单调增长：覆盖时旧记录占用的字节会被扣掉")
    void approximateBytesDoesNotGrowForever() {
        LogRingBuffer buffer = new LogRingBuffer(16, 4_000, 50);
        String filler = "x".repeat(500);

        for (int i = 0; i < 16; i++) {
            buffer.offer(rec(T0 + i, "INFO", filler));
        }
        long afterFill = buffer.stats().approxBytes();

        // 再写 200 轮：若覆盖时不扣减，这个数会一直涨
        for (int i = 0; i < 200; i++) {
            buffer.offer(rec(T0 + i, "INFO", filler));
        }
        long afterChurn = buffer.stats().approxBytes();

        assertTrue(afterFill > 0, "应该有占用");
        // 允许小幅波动（估算本身是按 2 倍保守估的），但绝不允许量级增长
        assertTrue(afterChurn < afterFill * 2,
                "近似占用不该随写入次数增长：fill=" + afterFill + " churn=" + afterChurn);
    }

    @Test
    @DisplayName("单条超长时被截断并留下标记：一次异常不能吃掉几 MB")
    void longMessageIsTruncated() {
        LogRingBuffer buffer = new LogRingBuffer(4, 100, 50);
        buffer.offer(rec(T0, "ERROR", "y".repeat(5_000)));

        LogRecord stored = buffer.search(new LogQuery(null, null, null, null, null, null, 1))
                .samples().get(0).record();
        assertTrue(stored.message().length() <= 100 + LogRecord.TRUNCATED_MARK.length(),
                "截断后长度受控，实际 " + stored.message().length());
        assertTrue(stored.message().endsWith(LogRecord.TRUNCATED_MARK), "必须留下截断标记");
    }

    // ================================================================ 去重

    @Test
    @DisplayName("去重按签名：变量不同、模板相同的日志合并成一条并累加 occurrences")
    void dedupeCollapsesVariableOnlyDifferences() {
        // 容量刻意设为 100：既验证去重，又顺带验证「容量是硬上界」——
        // 写 301 条之后只剩下最后 100 条，那个只出现一次的错误必须还在。
        LogRingBuffer buffer = new LogRingBuffer(100, 4_000, 50);
        for (int i = 0; i < 300; i++) {
            buffer.offer(rec(T0 + i, "ERROR",
                    "[消费·重试耗尽] 放弃本条消息 orderNo=SN1759900000123" + String.format("%03d", i)
                            + " 耗时=" + (100 + i) + "ms"));
        }
        // 另一种完全不同的错误，只出现 1 次，且是最后写入的
        buffer.offer(rec(T0 + 400, "ERROR", "[投递失败] Broker 未确认。sendStatus=SEND_ERROR"));

        LogPage page = buffer.search(new LogQuery(null, null, "ERROR", null, null, null, 20));

        assertEquals(100, page.count(), "count 是去重前的命中条数，受容量上界约束");
        assertEquals(2, page.distinctCount(), "应当只有两种不同的错误");

        // 【按内容取样本，不按位置】顺序是「最新的在前」，而两种错误的时间戳只差 1ms，
        // 断言 samples().get(0) 具体是哪一种会让用例依赖实现细节。
        LogPage.LogSample storm = sampleContaining(page, "重试耗尽");
        LogPage.LogSample single = sampleContaining(page, "Broker 未确认");
        assertEquals(99, storm.occurrences(), "刷屏的那一种被累加（最后 100 条里有 99 条是它）");
        assertEquals(1, single.occurrences());

        // 【关键】代表性记录必须是原文，而不是被模板替换过的文本 ——
        // 否则排障时看不到真实的单号与耗时
        assertTrue(storm.record().message().contains("orderNo=SN1759900000123"),
                "返回的样本应当是原文，实际：" + storm.record().message());
        assertTrue(storm.record().message().contains("耗时="), "耗时也要保留在原文里");
    }

    /** 取出消息里含指定片段的样本。找不到时直接失败，并列出实际有哪些 */
    private static LogPage.LogSample sampleContaining(LogPage page, String fragment) {
        return page.samples().stream()
                .filter(s -> s.record().message().contains(fragment))
                .findFirst()
                .orElseThrow(() -> new AssertionError("没有找到含「" + fragment + "」的样本，实际样本="
                        + page.samples().stream().map(s -> s.record().message()).toList()));
    }

    @Test
    @DisplayName("同一句话但异常类型不同时不算同一个签名")
    void differentExceptionTypeIsDifferentSignature() {
        LogRingBuffer buffer = new LogRingBuffer(10, 4_000, 50);
        buffer.offer(rec(T0, "ERROR", "[消费·重试耗尽] 放弃本条消息",
                "java.net.SocketTimeoutException: Read timed out\n\tat x.y.Z(1)"));
        buffer.offer(rec(T0 + 1, "ERROR", "[消费·重试耗尽] 放弃本条消息",
                "java.lang.NullPointerException: null\n\tat x.y.Z(2)"));

        LogPage page = buffer.search(new LogQuery(null, null, "ERROR", null, null, null, 20));
        assertEquals(2, page.distinctCount(),
                "两个异常是两种故障，不该被合成一条 —— 否则 Agent 会把超时与空指针看成同一个问题");
    }

    @Test
    @DisplayName("级别与错误码参与签名：同一句话由不同级别打出时是两条")
    void levelAndErrorCodeArePartOfSignature() {
        LogRingBuffer buffer = new LogRingBuffer(10, 4_000, 50);
        buffer.offer(rec(T0, "WARN", "库存不足"));
        buffer.offer(rec(T0 + 1, "ERROR", "库存不足"));

        assertEquals(2, buffer.search(new LogQuery(null, null, null, null, null, null, 20))
                .distinctCount());
    }

    // ================================================================ 过滤

    @Test
    @DisplayName("级别过滤按严重度排序：level=WARN 时不返回 INFO")
    void levelFilterUsesSeverityOrder() {
        LogRingBuffer buffer = new LogRingBuffer(20, 4_000, 50);
        buffer.offer(rec(T0, "DEBUG", "调试信息"));
        buffer.offer(rec(T0 + 1, "INFO", "正常信息"));
        buffer.offer(rec(T0 + 2, "WARN", "警告信息"));
        buffer.offer(rec(T0 + 3, "ERROR", "错误信息"));

        // 【这条断言防的是一个不会报错的 bug】Level 枚举的自然序是
        // DEBUG < ERROR < INFO < TRACE < WARN，拿它比大小会得到
        // 「ERROR 比 WARN 轻」这种荒谬结论，而查询只是返回了错误的结果，不会抛异常。
        LogPage warnUp = buffer.search(new LogQuery(null, null, "WARN", null, null, null, 20));
        assertEquals(2, warnUp.count(), "WARN 及以上应有 2 条");
        List<String> levels = warnUp.samples().stream().map(s -> s.record().level()).toList();
        assertTrue(levels.contains("WARN") && levels.contains("ERROR"));
        assertFalse(levels.contains("INFO"), "INFO 比 WARN 轻，不该出现");

        assertEquals(1, buffer.search(new LogQuery(null, null, "ERROR", null, null, null, 20)).count());
        assertEquals(4, buffer.search(new LogQuery(null, null, "DEBUG", null, null, null, 20)).count());
    }

    @Test
    @DisplayName("时间窗过滤是闭区间，且 from > to 时自动交换而不是返回空")
    void timeWindowIsInclusiveAndOrderInsensitive() {
        LogRingBuffer buffer = new LogRingBuffer(20, 4_000, 50);
        for (int i = 0; i < 10; i++) {
            buffer.offer(rec(T0 + i * 1000, "INFO", "第 " + i + " 条"));
        }

        LogPage inWindow = buffer.search(
                new LogQuery(null, null, null, null, T0 + 3000, T0 + 5000, 20));
        assertEquals(3, inWindow.count(), "3000/4000/5000 三条（闭区间）");

        // 【为什么给反了也要能工作】入参来自 LLM，它一定会偶尔把两个时间参数写反。
        // 返回空结果会被 Agent 解读成「这段时间没有日志」—— 一个错误的结论。
        LogPage reversed = buffer.search(
                new LogQuery(null, null, null, null, T0 + 5000, T0 + 3000, 20));
        assertEquals(3, reversed.count(), "参数写反时自动交换，而不是返回空");
    }

    @Test
    @DisplayName("关键字命中消息、堆栈、单号与类名，且大小写不敏感")
    void keywordMatchesAcrossFields() {
        LogRingBuffer buffer = new LogRingBuffer(20, 4_000, 50);
        buffer.offer(rec(T0, "ERROR", "MySQL query timeout"));
        buffer.offer(rec(T0 + 1, "ERROR", "无关的一行", "java.sql.SQLException: Communications link failure"));

        assertEquals(1, buffer.search(
                new LogQuery("mysql", null, null, null, null, null, 20)).count(), "消息体，大小写不敏感");
        assertEquals(1, buffer.search(
                new LogQuery("communications", null, null, null, null, null, 20)).count(), "堆栈内容");
        // 归一化只影响「去重签名」，不改变存储的原文 —— 所以按完整单号仍能命中
        assertEquals(2, buffer.search(
                new LogQuery("SN1759900000123456", null, null, null, null, null, 20)).count(),
                "原文被完整保留，可以按完整单号检索");
        assertEquals(2, buffer.search(
                new LogQuery("TestLogger", null, null, null, null, null, 20)).count(), "类名");
    }

    @Test
    @DisplayName("按 traceId 精确取全链")
    void traceIdFilterIsExact() {
        LogRingBuffer buffer = new LogRingBuffer(20, 4_000, 50);
        buffer.offer(rec(T0, "INFO", "建单"));
        buffer.offer(new LogRecord(T0 + 1, "INFO", "L", "t", "消费确认", null,
                "ffffffffffffffff", null, "order-service", "consumeOne", null, null, null, null));

        LogPage page = buffer.search(
                new LogQuery(null, "ffffffffffffffff", null, null, null, null, 20));
        assertEquals(1, page.count());
        assertEquals("消费确认", page.samples().get(0).record().message());
    }

    @Test
    @DisplayName("limit 是去重后的条数上限，count 仍报告全部命中")
    void limitCapsSamplesNotCount() {
        LogRingBuffer buffer = new LogRingBuffer(100, 4_000, 50);
        // 【消息必须两两不同，且差异不能只是数字】否则归一化会把它们合并成一条，
        // 于是这条用例测的就不再是 limit，而是去重 —— 一个「测试通过但测错了东西」的陷阱。
        String[] kinds = {
                "连接池耗尽", "慢查询激增", "Redis 连接被拒", "消息投递超时", "消费线程饥饿",
                "库存不一致", "Outbox 积压", "补偿任务失败", "对账读偏斜", "连接泄漏"};
        for (int i = 0; i < 50; i++) {
            buffer.offer(rec(T0 + i, "ERROR", "故障类型：" + kinds[i % kinds.length] + "，序号 " + i));
        }

        LogPage page = buffer.search(new LogQuery(null, null, null, null, null, null, 5));
        assertEquals(50, page.count(), "count 不受 limit 影响");
        assertTrue(page.distinctCount() <= 5, "samples 受限，实际 " + page.distinctCount());
        assertTrue(page.truncated(), "被截断时必须显式标注");
    }

    // ================================================================ 统计与边界

    @Test
    @DisplayName("bufferStats 如实报告容量/条数/时间范围，供 Agent 判断证据是否完整")
    void bufferStatsIsHonest() {
        LogRingBuffer buffer = new LogRingBuffer(5, 4_000, 50);
        for (int i = 0; i < 8; i++) {
            buffer.offer(rec(T0 + i * 100, "INFO", "第 " + i + " 条"));
        }

        LogPage.BufferStats stats = buffer.stats();
        assertEquals(5, stats.capacity());
        assertEquals(5, stats.size());
        assertEquals(8, stats.totalStored());
        assertEquals(0, stats.dropped(), "单线程写入不该有丢弃");
        assertEquals(T0 + 300, stats.oldestMillis(), "最老的一条是第 3 条");
        assertEquals(T0 + 700, stats.newestMillis(), "最新的一条是第 7 条");
    }

    @Test
    @DisplayName("空缓冲的查询返回空结果而不是抛异常")
    void emptyBufferIsQueryable() {
        LogRingBuffer buffer = new LogRingBuffer(5, 4_000, 50);

        LogPage page = buffer.search(new LogQuery("任意", null, null, null, null, null, 20));
        assertEquals(0, page.count());
        assertTrue(page.samples().isEmpty());
        assertEquals(0, page.bufferStats().oldestMillis());
        assertNotNull(page.note(), "即使空结果也要给出说明，避免被读成「系统正常」");
    }

    @Test
    @DisplayName("null 查询与非法 limit 都被规整，不抛异常")
    void malformedQueryIsNormalized() {
        LogRingBuffer buffer = new LogRingBuffer(10, 4_000, 50);
        buffer.offer(rec(T0, "INFO", "一条日志"));

        assertEquals(1, buffer.search(null).count(), "null 查询退化为「最近若干条」");
        assertEquals(1, buffer.search(new LogQuery(null, null, null, null, null, null, -5)).count(),
                "负数 limit 回退到默认值");
        assertEquals(1, buffer.search(new LogQuery(null, null, null, null, null, null, 1_000_000)).count(),
                "超大 limit 被夹到硬上限，但仍能查到");
        assertEquals(1, buffer.search(new LogQuery("  ", null, null, null, null, null, 10)).count(),
                "空白关键字视为不过滤");
    }

    @Test
    @DisplayName("null 记录被忽略，不污染计数")
    void nullRecordIsIgnored() {
        LogRingBuffer buffer = new LogRingBuffer(5, 4_000, 50);
        assertFalse(buffer.offer(null));
        assertEquals(0, buffer.totalStored());
        assertEquals(0, buffer.size());
    }

    @Test
    @DisplayName("容量必须为正：构造时就被拒绝，而不是运行到一半才出错")
    void capacityMustBePositive() {
        IllegalArgumentException e = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> new LogRingBuffer(0, 100, 5));
        assertTrue(e.getMessage().contains("capacity"));
    }

    @Test
    @DisplayName("多线程并发写入不丢结构：计数守恒，且无异常逃逸")
    void concurrentWritesKeepCountsConsistent() throws InterruptedException {
        LogRingBuffer buffer = new LogRingBuffer(2_000, 4_000, 200);
        int threads = 8;
        int perThread = 2_000;
        Thread[] workers = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            final int id = t;
            workers[t] = new Thread(() -> {
                for (int i = 0; i < perThread; i++) {
                    buffer.offer(rec(T0 + i, "INFO", "线程 " + id + " 的第 " + i + " 条"));
                }
            });
        }
        for (Thread w : workers) {
            w.start();
        }
        for (Thread w : workers) {
            w.join();
        }

        long total = buffer.totalStored();
        long dropped = buffer.droppedCount();
        // 【这条断言是并发正确性的核心】每条日志要么写入成功、要么被记为丢弃。
        // 若 dropped 忘了计数，这里会立刻暴露 —— 而「悄悄丢了日志却不报告」
        // 正是让 Agent 把「证据不全」误读成「没有问题」的那个缺陷。
        assertEquals((long) threads * perThread, total + dropped,
                "每条日志要么写入成功、要么被记为丢弃，不能凭空消失："
                        + "total=" + total + " dropped=" + dropped);
        assertTrue(buffer.size() <= buffer.capacity(), "size 不得越过容量");
        assertTrue(dropped >= 0, "丢弃计数不该为负");
    }

    @Test
    @DisplayName("关键字归一化：长数字串被替换为占位符，单位/中文/短数字原样保留")
    void normalizationReplacesVariablesOnly() {
        // 数字与单位黏在一起时，单位必须留下（否则「123ms」与「0.5s」永远合不到一起）
        assertEquals("orderNo=SN# 耗时=#ms",
                LogRingBuffer.normalize("orderNo=SN1759900000123456 耗时=123ms"));
        // 【中文绝不能被改写】它是「这是哪种故障」的唯一判据
        assertEquals("[消费·重试耗尽] 放弃本条消息 orderNo=SN#",
                LogRingBuffer.normalize("[消费·重试耗尽] 放弃本条消息 orderNo=SN1759900000123456"));
        // 纯文本原样：没有任何变量
        assertEquals("MySQL query timeout",
                LogRingBuffer.normalize("MySQL query timeout"));
        // 两位及以上的数字都算变量（单号/耗时/计数在模板里都不该区分故障类型）
        assertEquals("expect # but got #",
                LogRingBuffer.normalize("expect 100 but got 99"));
        // 【单位数刻意保留】{@code HTTP 4xx/5xx}、{@code v2}、{@code num=1} 这类
        // 单位数几乎总是语义的一部分，抹掉它们会把本来不同的故障合并成一条。
        assertEquals("v2.0", LogRingBuffer.normalize("v2.0"));
        assertEquals("status=5", LogRingBuffer.normalize("status=5"));
        assertEquals("num=1", LogRingBuffer.normalize("num=1"));
        // 长数字串无论夹在哪里都会被替换
        assertEquals("a#b", LogRingBuffer.normalize("a123b"));
        // traceId 这类 hex 会被逐段替换，但整体仍然可区分（不同 traceId 得到不同签名）
        assertFalse(LogRingBuffer.normalize("traceId=a1b2c3d4e5f60718")
                        .equals(LogRingBuffer.normalize("traceId=ffffffffffffffff")),
                "不同的 traceId 归一后仍应可区分，否则所有调用链会被合并");
        assertEquals("", LogRingBuffer.normalize(null));
    }

    @Test
    @DisplayName("数字串在行尾时也被替换（这曾是一个静默漏网的分支）")
    void trailingDigitRunIsAlsoNormalized() {
        // 【这条防的是一个具体的漏网】第一版按「整词」判断，需要 token 前后都有边界。
        // 行尾的数字串没有「后面」，条件写错时它会被跳过 ——
        // 于是 `orderNo=SN...` 被替换了、而 `耗时=123` 没有，
        // 结果同一个故障因为耗时不同而被算成多种，去重静默失效。
        assertEquals("耗时=#", LogRingBuffer.normalize("耗时=123"));
        assertEquals("count=#", LogRingBuffer.normalize("count=42"));
        // 短数字在行尾同样保留
        assertEquals("耗时=5", LogRingBuffer.normalize("耗时=5"));

        // 端到端复现：两条只有耗时不同的日志必须合并成一条
        LogRingBuffer buffer = new LogRingBuffer(10, 4_000, 50);
        buffer.offer(rec(T0, "ERROR", "[消费·重试耗尽] 放弃本条消息 耗时=123"));
        buffer.offer(rec(T0 + 1, "ERROR", "[消费·重试耗尽] 放弃本条消息 耗时=456"));

        LogPage page = buffer.search(new LogQuery(null, null, "ERROR", null, null, null, 10));
        assertEquals(1, page.distinctCount(),
                "只有耗时不同的同一故障应当合并；实际样本="
                        + page.samples().stream().map(s -> s.record().message()).toList());
        assertEquals(2, page.samples().get(0).occurrences());
    }
}
