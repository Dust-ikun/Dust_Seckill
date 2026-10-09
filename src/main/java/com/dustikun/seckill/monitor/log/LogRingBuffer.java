package com.dustikun.seckill.monitor.log;

import com.dustikun.seckill.monitor.core.TraceContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Logs Tool 的数据底座：<b>有界</b>的进程内日志环形缓冲。
 *
 * <h2>为什么是「环形缓冲」而不是上 Loki / ELK</h2>
 * <p>
 * SPEC 第 9.2 节要求 {@code search_logs(service, keyword, start_time, end_time, limit)}。
 * 集中式日志方案（Loki / ELK）当然能实现它，但代价是一条新的采集链路、一个新容器、
 * 一份新的存储，而它们的收益是「跨实例、可长期检索」—— 本项目是单实例、跑在本机、
 * 证据只需要覆盖最近几分钟到几小时。可行性报告 §3.2 因此裁定：
 * <b>先用进程内有界环形缓冲，接口形状与 SPEC 一致，将来换 Loki 只改实现</b>。
 * 于是本类的公开方法刻意只暴露「查询语义」，不暴露「内存数组」这件事。
 *
 * <h2>三道边界（SPEC 第 23.3 节的 Token 限制与第 25 节的可靠性要求）</h2>
 * <ol>
 *   <li><b>容量有界</b>：固定 {@code capacity} 条，写满即覆盖最老的一条。
 *       内存占用因此有一个与运行时长无关的上界 —— 这是「日志不能把应用拖死」的底线。</li>
 *   <li><b>单条有界</b>：单条消息超过 {@code maxMessageChars} 即截断。
 *       没有这一条时，一次 ORM 异常打印出的超长 SQL 就能吃掉几 MB。</li>
 *   <li><b>写入不阻塞业务</b>：写锁只在 {@code writeLockTimeoutMillis} 内尝试获取，
 *       拿不到就<b>丢弃并计数</b>。理由是日志是旁路：
 *       让秒杀请求线程在日志锁上排队，是把「监控」变成了「故障源」，
 *       而丢掉的日志条数会在 {@link LogPage.BufferStats#dropped()} 里如实报告 ——
 *       「少了几条日志」是可接受的，「拖慢交易」不是。</li>
 * </ol>
 *
 * <h2>去重为什么按「签名」而不是按原文</h2>
 * <p>
 * 一次数据库故障会刷出几千行<b>措辞相同、只有单号/耗时不同</b>的日志。
 * 按原文去重，这 3000 行是 3000 个不同的样本，于是 {@code limit=20} 会被它们占满 ——
 * 而 Agent 真正需要的是「有哪几种不同的错误」，那可能是 2 种。
 * 所以去重键用<b>把变量替换成占位符之后的模板</b>，但返回的样本是<b>原文</b>
 * （见 {@link Signature#representative}），于是既不丢信息，也不被重复淹没。
 *
 * <h2>线程安全</h2>
 * <p>
 * 单锁保护。写入在锁内只做几次字段赋值与数组下标运算（微秒级），
 * 查询在锁内完成扫描后立即释放 —— 不在锁内做脱敏或序列化，
 * 那两件事的耗时不可控（脱敏要走正则），放进锁里会让写侧被迫丢日志。
 */
public class LogRingBuffer {

    // ================================================================ 签名归一化
    //
    // 归一的规则与理由见 normalize() 的注释。这里只留一个占位符常量 ——
    // 早期版本用四条正则（数字 / 长 hex / 字母数字混排 / 单号）串联替换，
    // 那条路在「数字与单位黏在一起」时会留下半个 token，且替换顺序会影响结果
    // （先跑数字规则会把单号劈成 SN#，后续规则再也匹配不到完整单号）。
    // 现在改为「按 token 判断、一趟扫描」，行为与顺序无关 —— 见 normalize()。

    /** 归一化后的占位符 */
    private static final String PH = "#";

    /**
     * 视为「变量」的最短数字串长度。
     * <p>取 2 而不是 1：单位数几乎总是语义的一部分（{@code HTTP 4xx / 5xx}、{@code v2}、
     * {@code num=1}），抹掉它们会把本来不同的故障合并成一条。见 {@link #normalize}。
     */
    static final int MIN_VARIABLE_DIGITS = 2;

    /**
     * 单条日志保留的近似字节上界（含 message 与 throwable）。
     * <p>超出部分丢弃并追加 {@link LogRecord#TRUNCATED_MARK}。
     */
    private final int maxMessageChars;

    /** 环形数组。长度即容量，创建后不再变化 */
    private final LogRecord[] ring;

    /** 写锁。用 tryLock 带超时，拿不到即丢日志 */
    private final ReentrantLock lock = new ReentrantLock();

    private final long writeLockTimeoutMillis;

    /** 下一条要写入的下标 */
    private int head;

    /** 当前有效条数（&le; capacity） */
    private int size;

    /** 进程启动以来成功写入的总条数 */
    private long totalStored;

    /**
     * 因锁竞争或容量之外的任何原因被丢弃的条数。
     * <p>写锁内自增（受锁保护）；中断路径用 {@link #interruptedDrops} 单独累加。
     * 分成两个字段是因为<b>两个数都是被读取的</b>：一个丢日志的系统必须能说清丢了多少，
     * 而「读改写一个非 volatile 的 long」在 Java 内存模型下会丢更新 ——
     * 丢掉的恰好是「我们丢了日志」这个警告本身。
     */
    private long dropped;

    /** 中断路径的丢弃计数（无法在写锁内累加，因为此时根本没拿到锁） */
    private final java.util.concurrent.atomic.AtomicLong interruptedDrops =
            new java.util.concurrent.atomic.AtomicLong();

    /** 当前占用的近似字节数（随写入与覆盖滚动更新） */
    private long approxBytes;

    /** 被覆盖掉的那一条占用的近似字节数，覆盖时从 approxBytes 里扣掉 */
    private final long[] entryBytes;

    public LogRingBuffer(int capacity, int maxMessageChars, long writeLockTimeoutMillis) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("LogRingBuffer capacity must be > 0, got " + capacity);
        }
        this.ring = new LogRecord[capacity];
        this.entryBytes = new long[capacity];
        this.maxMessageChars = Math.max(64, maxMessageChars);
        this.writeLockTimeoutMillis = Math.max(0, writeLockTimeoutMillis);
    }

    /**
     * 默认档：10000 条 / 单条 4000 字符 / 写锁等待 5ms。
     * <p>【配置在哪】采集侧参数（容量、单条长度、最低级别、写锁超时）全部写在
     * {@code logback-spring.xml} 的 {@code RING_BUFFER} appender 里，<b>不在 yaml 里</b> ——
     * 它们必须在 Spring 容器起来之前生效。本方法只是「XML 缺失时」的兜底默认值，
     * 与 XML 里的取值保持一致。
     */
    public static LogRingBuffer withDefaults() {
        return new LogRingBuffer(10_000, 4_000, 5);
    }

    // ================================================================ 写入

    /**
     * 写入一条日志。<b>永不抛异常、永不阻塞超过写锁超时</b>。
     *
     * <p>这个签名是刻意「不抛异常」的：它由 Logback appender 在业务线程上调用，
     * 任何逃逸的异常都会变成一条日志错误（甚至递归），而日志的问题不该影响交易。
     *
     * @param record 已结构化的日志；{@code null} 直接忽略
     * @return true 表示已存入；false 表示被丢弃（锁竞争）或入参非法
     */
    public boolean offer(LogRecord record) {
        if (record == null) {
            return false;
        }
        boolean acquired;
        try {
            acquired = lock.tryLock(writeLockTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            // 恢复中断标记：丢掉中断标记会让上层再也感知不到关闭信号
            Thread.currentThread().interrupt();
            interruptedDrops.incrementAndGet();
            return false;
        }
        if (!acquired) {
            countDropWithoutLock();
            return false;
        }
        try {
            LogRecord trunc = truncate(record);
            long bytes = estimateBytes(trunc);

            int slot = head;
            // 【覆盖时先扣后加】被顶掉的旧记录占用的字节要从计数里减掉，
            // 否则 approxBytes 会单调增长成一个没有意义的数 —— 一个永远只增不减的
            // "占用" 指标会让看它的人以为存在泄漏，从而去查一个不存在的问题。
            long evicted = entryBytes[slot];
            if (evicted > 0) {
                approxBytes -= evicted;
                if (approxBytes < 0) {
                    approxBytes = 0;
                }
            }

            ring[slot] = trunc;
            entryBytes[slot] = bytes;
            approxBytes += bytes;

            head = (head + 1) % ring.length;
            if (size < ring.length) {
                size++;
            }
            totalStored++;
            return true;
        } catch (RuntimeException e) {
            // 兜底：任何意料之外的运行时异常都在这里终止，不向上传播到业务线程。
            // 【为什么刻意不在这里打日志】本方法在<b>日志线程</b>上被调用，
            // 用日志去报告日志的故障会再次进入 appender —— 轻则递归、重则栈溢出。
            // 正确的出口是返回值：调用方（appender）把 false 转成一条 Logback 状态消息。
            dropped++;
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 累加「拿不到锁」造成的丢弃。
     * <p>这里刻意用一个独立的 {@link java.util.concurrent.atomic.AtomicLong}：
     * 走到这一行时我们<b>确定</b>没有持有写锁（tryLock 失败了），
     * 对一个普通 long 做 {@code ++} 会与写锁内的 {@code dropped++} 并发，
     * 产生丢失更新。原子变量让这条路径无需加锁也正确。
     */
    private void countDropWithoutLock() {
        interruptedDrops.incrementAndGet();
    }

    private LogRecord truncate(LogRecord r) {
        String msg = r.message() == null ? "" : r.message();
        String thr = r.throwable();
        boolean cut = false;
        if (msg.length() > maxMessageChars) {
            msg = msg.substring(0, maxMessageChars) + LogRecord.TRUNCATED_MARK;
            cut = true;
        }
        if (thr != null && thr.length() > maxMessageChars) {
            thr = thr.substring(0, maxMessageChars) + LogRecord.TRUNCATED_MARK;
            cut = true;
        }
        if (!cut) {
            return r;
        }
        return new LogRecord(r.timestampMillis(), r.level(), r.loggerName(), r.threadName(),
                msg, thr, r.traceId(), r.requestId(), r.serviceName(), r.operation(),
                r.errorCode(), r.userId(), r.orderNo(), r.stockId());
    }

    private static long estimateBytes(LogRecord r) {
        long n = 64; // 对象头 + 字段本身
        n += len(r.message()) + len(r.throwable()) + len(r.loggerName()) + len(r.threadName())
                + len(r.traceId()) + len(r.requestId()) + len(r.serviceName()) + len(r.operation())
                + len(r.errorCode()) + len(r.userId()) + len(r.orderNo()) + len(r.stockId());
        // String 的实际占用约为字符数的 2 倍（压缩指针下 ASCII 可减半），这里按 2 倍保守估算
        return n * 2;
    }

    private static int len(String s) {
        return s == null ? 0 : s.length();
    }

    // ================================================================ 查询

    /**
     * 按条件检索。<b>去重、计数、排序都在这里完成</b>，产出层只需要脱敏与截断。
     *
     * <p>扫描方向是「从最新往最老」，理由有两个：
     * <ol>
     *   <li>排障关心的几乎总是最近发生了什么；</li>
     *   <li>可以先撞到 {@link #MAX_SCAN} 的上界就停手，而不必每次都扫满整个缓冲 ——
     *       满容量 10000 条 × 每条十几个字段匹配，一次查询约 10~30ms，
     *       对「Tool 单次调用 &lt; 1s」的目标（SPEC 第 25 节）是宽裕的，
     *       但没必要在证据已经足够时把整个缓冲翻一遍。</li>
     * </ol>
     */
    public LogPage search(LogQuery rawQuery) {
        LogQuery query = rawQuery == null
                ? new LogQuery(null, null, null, null, null, null, LogQuery.DEFAULT_LIMIT).normalized()
                : rawQuery.normalized();

        String lowerKeyword = query.keyword() == null ? null : query.keyword().toLowerCase(Locale.ROOT);
        int minLevelRank = levelRank(query.level());
        if (minLevelRank < 0) {
            // 无法识别的级别名：当成「不过滤」而不是「查不到」。
            // 返回空结果会被 Agent 读成「这段时间没有日志」，那是个错误结论。
            minLevelRank = 0;
        }

        // 去重表：签名 → 聚合结果。LinkedHashMap 保留插入顺序（即「更新的在前」，
        // 因为扫描是从最新往最老），于是最后无需再排一次序。
        Map<String, Acc> accs = new LinkedHashMap<>();
        long matched = 0;
        long scanned = 0;
        boolean scanCapped = false;
        LogPage.BufferStats stats;

        lock.lock();
        try {
            int cap = size;
            for (int i = 0; i < cap; i++) {
                if (scanned >= MAX_SCAN) {
                    scanCapped = true;
                    break;
                }
                LogRecord r = ring[(head - 1 - i + ring.length * 2) % ring.length];
                if (r == null) {
                    continue;
                }
                scanned++;
                if (!passes(r, query, lowerKeyword, minLevelRank)) {
                    continue;
                }
                matched++;

                String sig = signature(r);
                Acc acc = accs.get(sig);
                if (acc == null) {
                    if (accs.size() < MAX_DISTINCT) {
                        acc = new Acc(sig, r);
                        accs.put(sig, acc);
                    } else {
                        // 不同签名的种类已到上限。仍然计数（matched 已经加过），
                        // 只是不再新增样本 —— 由 truncated 把这个事实告诉调用方。
                        scanCapped = true;
                    }
                } else {
                    acc.add(r);
                }
            }
            stats = new LogPage.BufferStats(ring.length, size, totalStored, droppedTotalLocked(),
                    oldestMillisLocked(), newestMillisLocked(), approxBytes);
        } finally {
            lock.unlock();
        }

        List<LogPage.LogSample> samples = new ArrayList<>(Math.min(query.limit(), accs.size()));
        for (Acc acc : accs.values()) {
            if (samples.size() >= query.limit()) {
                break;
            }
            samples.add(acc.toSample());
        }

        boolean truncated = scanCapped || accs.size() > samples.size();
        String note = "count 是去重前的命中条数；samples 已按『变量归一化后的签名』去重，"
                + "occurrences 是同一签名的真实出现次数，record 是该签名最新一条的原文。"
                + (scanCapped ? "本次扫描到达上限，count 与 samples 可能不完整。" : "")
                + (stats.dropped() > 0 ? "注意：缓冲区已丢弃 " + stats.dropped()
                        + " 条（写入竞争），『查不到』不等于『没发生』。" : "");

        return new LogPage(matched, samples, truncated, note, query.describe(), stats);
    }

    /** 单次查询最多扫描的原始条数。它给出的是查询耗时的上界，而不是结果的上界 */
    static final int MAX_SCAN = 5_000;

    /** 单次查询最多保留的不同签名数。防止「每条日志都不一样」时把内存吃满 */
    static final int MAX_DISTINCT = 500;

    private boolean passes(LogRecord r, LogQuery q, String lowerKeyword, int minLevelRank) {
        if (r.timestampMillis() < q.fromMillisOrMin() || r.timestampMillis() > q.toMillisOrMax()) {
            return false;
        }
        if (levelRank(r.level()) < minLevelRank) {
            return false;
        }
        if (q.traceId() != null && !q.traceId().equals(r.traceId())) {
            return false;
        }
        // 【serviceName 为空的记录不参与服务过滤】
        // serviceName 来自 MDC，而它只在 {@link TraceContext#open} 的作用域内被写入。
        // 于是<b>所有不在作用域里的日志都没有这个字段</b>：Spring 自己的启动日志、
        // 定时任务、以及任何漏开作用域的代码路径 —— 而它们恰恰是启动失败、
        // 对账异常这类问题唯一的证据来源。
        // 若把「没有 serviceName」判为「不匹配」，一次带 service 的查询会安静地
        // 返回 0 条，而 Agent 会把它读成「这个服务没有相关日志」——
        // 这与 SPEC 第 10 节「不允许在没有证据的情况下下结论」是同一条纪律：
        // 宁可多给几条（本进程只有一个服务角色），也不要少给一条。
        if (q.serviceName() != null && r.serviceName() != null && !r.serviceName().isBlank()
                && !q.serviceName().equals(r.serviceName())) {
            return false;
        }
        return lowerKeyword == null || r.matchesKeyword(lowerKeyword);
    }

    /** 该签名在缓冲区里的真实次数。给「我只想知道这个错出现多少次」的场景用 */
    public long countBySignature(String keyword) {
        return search(new LogQuery(keyword, null, null, null, null, null, LogQuery.MAX_LIMIT)).count();
    }

    // ================================================================ 统计与维护

    /** 缓冲区现状快照（供健康检查与 Logs Tool 的自述） */
    public LogPage.BufferStats stats() {
        lock.lock();
        try {
            return new LogPage.BufferStats(ring.length, size, totalStored, droppedTotalLocked(),
                    oldestMillisLocked(), newestMillisLocked(), approxBytes);
        } finally {
            lock.unlock();
        }
    }

    public int capacity() {
        return ring.length;
    }

    public int size() {
        lock.lock();
        try {
            return size;
        } finally {
            lock.unlock();
        }
    }

    public long droppedCount() {
        lock.lock();
        try {
            return droppedTotalLocked();
        } finally {
            lock.unlock();
        }
    }

    /** 丢弃总数 = 写锁内的兜底丢弃 + 拿不到锁的中断丢弃。必须在持锁时调用 */
    private long droppedTotalLocked() {
        return dropped + interruptedDrops.get();
    }

    public long totalStored() {
        lock.lock();
        try {
            return totalStored;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 清空缓冲。
     * <p><b>只给测试用</b>：生产路径上没有任何理由清日志，
     * 而一个能被业务调用的「清日志」入口会顺手把故障现场抹掉。
     * 因此它不在任何 Tool 的白名单里（批次 2 的 ToolRegistry 只注册查询方法）。
     */
    public void clearForTest() {
        lock.lock();
        try {
            java.util.Arrays.fill(ring, null);
            java.util.Arrays.fill(entryBytes, 0L);
            head = 0;
            size = 0;
            approxBytes = 0;
            // totalStored / dropped 刻意不重置：它们是「进程启动以来」的累计量，
            // 重置会让依赖它们的测试掩盖真实行为。
        } finally {
            lock.unlock();
        }
    }

    private long oldestMillisLocked() {
        if (size == 0) {
            return 0;
        }
        // 最老的一条：写入顺序上最靠前的那个下标
        int oldest = size == ring.length ? head : 0;
        LogRecord r = ring[oldest];
        return r == null ? 0 : r.timestampMillis();
    }

    private long newestMillisLocked() {
        if (size == 0) {
            return 0;
        }
        LogRecord r = ring[(head - 1 + ring.length) % ring.length];
        return r == null ? 0 : r.timestampMillis();
    }

    // ================================================================ 去重签名

    /**
     * 把一个 LogRecord 归一成去重签名。
     *
     * <p><b>为什么把 throwable 也算进签名</b>：两行「[消费·重试耗尽] 放弃本条消息」，
     * 一行是 NullPointerException、一行是 SocketTimeoutException，
     * 它们是完全不同的两个故障，不该被归成一条。异常类名是签名的一部分，
     * 而异常的堆栈行号不是（同一个故障在不同版本里行号会变，那不该算两种故障）。
     */
    static String signature(LogRecord r) {
        StringBuilder sb = new StringBuilder(128);
        sb.append(r.level()).append('|');
        // 类名参与签名：同一句话由不同组件打出来，含义不同
        sb.append(shortLogger(r.loggerName())).append('|');
        sb.append(r.errorCode() == null ? "" : r.errorCode()).append('|');
        if (r.throwable() != null) {
            sb.append(exceptionClass(r.throwable())).append('|');
        }
        sb.append(normalize(r.message()));
        return sb.toString();
    }

    /** 去掉包名前缀，只留类名。包名在签名里是噪音，且会让签名长度翻倍 */
    private static String shortLogger(String loggerName) {
        int idx = loggerName.lastIndexOf('.');
        return idx < 0 ? loggerName : loggerName.substring(idx + 1);
    }

    /**
     * 从渲染好的堆栈文本里取最外层异常类名。
     * <p>只取第一行：它是「这个错误是什么」，而后面几十行是「它发生在哪」。
     */
    private static String exceptionClass(String throwable) {
        int nl = throwable.indexOf('\n');
        String first = nl < 0 ? throwable : throwable.substring(0, nl);
        int colon = first.indexOf(':');
        String cls = colon < 0 ? first : first.substring(0, colon);
        return cls.trim();
    }

    /**
     * 把消息里的变量替换成占位符，得到去重签名用的模板。
     *
     * <h2>规则：连续 {@value #MIN_VARIABLE_DIGITS} 位及以上的数字串替换成 {@code #}</h2>
     * <p>只有这一条，没有别的。它同时满足四个互相拉扯的要求：
     * <pre>
     *   orderNo=SN1759900000123456 耗时=123ms  →  orderNo=SN# 耗时=#ms
     *   traceId=a1b2c3d4e5f60718                →  traceId=a#b#c#d#e#f#d#
     *   expect 100 but got 99                   →  expect # but got #
     *   v2.0 / status=5 / num=1                 →  原样    （单位数不是变量）
     *   [消费·重试耗尽] 放弃本条消息               →  原样    （中文是故障类型的判据）
     * </pre>
     *
     * <h2>为什么不是「整个 token 替换」</h2>
     * <p>那是第一版的做法，它在 {@code 耗时=123ms} 上失败了：{@code 123ms} 是一个 token
     * （数字与单位之间没有分隔符），整个替换掉之后 {@code ms} 也一起消失，
     * 于是 {@code 耗时=123ms} 与 {@code 耗时=0.5s} 看起来一模一样 ——
     * 「慢」与「卡死」在诊断上是两件事。
     * 按数字串替换则保留了单位，也保留了 {@code SN} 这样的前缀
     * （{@code SN#} 仍然一眼能看出这是一批单号）。
     *
     * <h2>为什么阈值是 {@value #MIN_VARIABLE_DIGITS} 位而不是 1 位</h2>
     * <p>单位数几乎总是语义的一部分：{@code HTTP 4xx / 5xx}、{@code v2}、
     * {@code num=1}、{@code status=0}。把它们抹成 {@code #} 会把本来不同的故障合并，
     * 而去重一旦<em>过度</em>合并，Agent 就会把两种故障看成一种 ——
     * 那比不去重更危险。两位及以上则几乎总是标识符或度量值。
     *
     * <p><b>「中文不动」是刻意的</b>：本项目的日志大量使用中文前缀
     * （{@code [消费·重试耗尽]}），那正是「这是哪种故障」的唯一判据。
     * 任何会改写中文的归一化都会把所有故障合并成一条，让去重从有用变成有害。
     */
    static String normalize(String message) {
        if (message == null || message.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(message.length());
        int i = 0;
        int n = message.length();
        while (i < n) {
            char c = message.charAt(i);
            if (!isDigit(c)) {
                out.append(c);
                i++;
                continue;
            }
            int start = i;
            while (i < n && isDigit(message.charAt(i))) {
                i++;
            }
            // 只有「足够长」的数字串才视为变量；短数字原样保留（见方法注释）
            if (i - start >= MIN_VARIABLE_DIGITS) {
                out.append(PH);
            } else {
                // 【不能写成 out.append(PH, start, i)】那个三元表达式里的两个分支
                // 类型不同（String / CharSequence），Java 会把它推断成
                // StringBuilder.append(CharSequence, int, int) —— 于是 start 与 i
                // 被当成「在 PH 里的下标」而不是「在 message 里的下标」。
                // 编译通过、运行到 normalize 才抛 IndexOutOfBoundsException，
                // 而 PH 长度是 1，报错信息（Range [10, 26) out of bounds for length 1）
                // 完全指不到真正的原因上。
                out.append(message, start, i);
            }
        }
        return out.toString();
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    // ================================================================ 辅助

    /**
     * 级别的可比较次序。
     * <p>{@code Level} 枚举本身的自然序是 DEBUG &lt; ERROR &lt; INFO &lt; TRACE &lt; WARN
     * （按声明顺序），拿它比大小会得到「ERROR 比 WARN 严重度低」这种荒谬结论，
     * 而那个 bug 不会报错，只会让 {@code level=WARN} 的查询返回 INFO 与 ERROR 的混合体。
     * 因此这里显式给出次序。
     *
     * @return 未识别的级别名返回 -1，由调用方决定如何处理
     */
    static int levelRank(String level) {
        if (level == null) {
            return 0;
        }
        return switch (level.trim().toUpperCase(Locale.ROOT)) {
            case "TRACE" -> 0;
            case "DEBUG" -> 1;
            case "INFO" -> 2;
            case "WARN", "WARNING" -> 3;
            case "ERROR" -> 4;
            case "OFF" -> 5;
            default -> -1;
        };
    }

    static int levelRankOfRecord(String level) {
        int r = levelRank(level);
        return r < 0 ? 2 : r;
    }

    /** 单个签名的聚合。只在锁内使用，不对外暴露 */
    private static final class Acc {
        private final String signature;
        private final LogRecord representative;
        private long occurrences;
        private long firstMillis;
        private long lastMillis;

        private Acc(String signature, LogRecord first) {
            this.signature = signature;
            this.representative = first;
            this.occurrences = 1;
            this.firstMillis = first.timestampMillis();
            this.lastMillis = first.timestampMillis();
        }

        private void add(LogRecord r) {
            occurrences++;
            long ts = r.timestampMillis();
            if (ts < firstMillis) {
                firstMillis = ts;
            }
            if (ts > lastMillis) {
                lastMillis = ts;
            }
        }

        private LogPage.LogSample toSample() {
            return new LogPage.LogSample(occurrences, firstMillis, lastMillis, representative, false);
        }
    }

    /**
     * 从 MDC 取上下文字段，构造一条待写入的记录。
     * <p>放在这里而不是 appender 里，是为了让「非 Logback 来源」（例如测试直接注入、
     * 或将来从消息里补写消费者日志）也能产出同构的记录。
     */
    public static LogRecord fromMdc(long timestampMillis, String level, String loggerName,
                                    String threadName, String message, String throwable) {
        return new LogRecord(timestampMillis, level, loggerName, threadName, message, throwable,
                TraceContext.get(TraceContext.TRACE_ID),
                TraceContext.get(TraceContext.REQUEST_ID),
                TraceContext.get(TraceContext.SERVICE_NAME),
                TraceContext.get(TraceContext.OPERATION),
                TraceContext.get(TraceContext.ERROR_CODE),
                TraceContext.get(TraceContext.USER_ID),
                TraceContext.get(TraceContext.ORDER_NO),
                TraceContext.get(TraceContext.STOCK_ID));
    }
}
