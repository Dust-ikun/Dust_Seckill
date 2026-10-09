package com.dustikun.seckill.monitor.log;

import com.dustikun.seckill.monitor.core.TraceContext;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import ch.qos.logback.core.status.ErrorStatus;
import ch.qos.logback.core.status.InfoStatus;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Logback appender：把每一条日志同时投进 {@link LogRingBuffer}，供 Logs Tool 查询。
 *
 * <h2>为什么必须在 logback 里注册，而不是靠一个「日志监听器」</h2>
 * <p>
 * 要让 Logs Tool 拿到日志，只有两条路：<b>读日志文件</b>（解析文本，脆弱且慢）
 * 或<b>在日志产生的现场截获</b>。后者才是 SPEC 第 6.2 节「结构化」的本意 ——
 * appender 拿到的 {@link ILoggingEvent} 已经带着 MDC 的<b>原始键值</b>，
 * 不需要从一行拼好的文本里再正则反解出 traceId。
 *
 * <h2>三条自我保护</h2>
 * <ol>
 *   <li><b>不抛异常</b>：{@link UnsynchronizedAppenderBase} 会把 {@link #append} 抛出的
 *       异常交给 StatusManager，但即便如此也不该依赖它兜底 ——
 *       本方法内部全部包在 try/catch 里。日志系统的故障不能变成业务的故障。</li>
 *   <li><b>防递归</b>：本 appender 自己写状态消息时可能触发日志 →
 *       再次进入 {@link #append}。用一个 {@link ThreadLocal} 按线程判定并直接返回。</li>
 *   <li><b>真正的开关在这里</b>：{@code level} 字段（默认 INFO）在构造 LogRecord
 *       <b>之前</b>就拦掉 DEBUG/TRACE。一个「先格式化再过滤」的实现会让生产环境
 *       白付格式化开销 —— 本项目在压测链路里有 DEBUG 日志，这个差别是可测的。</li>
 * </ol>
 *
 * <h2>为什么用 UnsynchronizedAppenderBase 而不是 AppenderBase</h2>
 * <p>
 * {@code AppenderBase} 会在 {@code doAppend} 上对整个 appender 加锁。
 * 本 appender 的下游（{@link LogRingBuffer}）<b>自己就有一把带超时的锁</b>，
 * 两层锁只会让「写锁拿不到就丢弃」这条设计失效：业务线程会先卡在 appender 的锁上，
 * 而那个锁没有超时。因此这里选择无同步基类，把并发控制权完整交给环形缓冲。
 *
 * <h2>生命周期：为什么缓冲在 start() 里创建</h2>
 * <p>
 * Logback 的行为是「先无参构造 → 再逐个调用 setter → 最后调 start()」。
 * 若在构造器里就把缓冲建好（容量固定），{@code capacity} 这个配置项就<b>永远不会生效</b>，
 * 而它不会报错、只会静默用默认值 —— 这正是本项目反复踩到的那类问题
 * （见 {@code application.yaml} 里 {@code management.metrics} 的注释）。
 * 因此缓冲延迟到 {@link #start()} 创建：那时所有 setter 都已调用完毕。
 */
public class LogRingBufferAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

    /** 按线程判定递归。别的线程的日志不该被连累 */
    private static final ThreadLocal<Boolean> IN_APPEND = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** 默认容量。与 {@code logback-spring.xml} 里 RING_BUFFER 的 {@code capacity} 保持一致 */
    public static final int DEFAULT_CAPACITY = 10_000;

    public static final int DEFAULT_MAX_MESSAGE_CHARS = 4_000;

    public static final long DEFAULT_WRITE_LOCK_TIMEOUT_MILLIS = 5L;

    // ================================================================ 可配置项（setter 在 start() 之前调用）

    private int capacity = DEFAULT_CAPACITY;

    private int maxMessageChars = DEFAULT_MAX_MESSAGE_CHARS;

    private long writeLockTimeoutMillis = DEFAULT_WRITE_LOCK_TIMEOUT_MILLIS;

    private Level threshold = Level.INFO;

    // ================================================================ 运行态

    private volatile LogRingBuffer buffer;

    private final AtomicBoolean dropReported = new AtomicBoolean(false);

    /** 环形缓冲容量（条）。logback-spring.xml 的 {@code capacity} 属性 */
    public void setCapacity(int capacity) {
        this.capacity = capacity;
    }

    /** 单条日志保留的最大字符数。{@code maxMessageChars} 属性 */
    public void setMaxMessageChars(int maxMessageChars) {
        this.maxMessageChars = maxMessageChars;
    }

    /** 写锁等待上限（毫秒），超过即丢弃该条日志。{@code writeLockTimeoutMillis} 属性 */
    public void setWriteLockTimeoutMillis(long writeLockTimeoutMillis) {
        this.writeLockTimeoutMillis = writeLockTimeoutMillis;
    }

    /** 最低入缓冲级别。默认 INFO —— DEBUG 不进缓冲（见类注释第 3 条） */
    public void setLevel(String level) {
        Level parsed = Level.toLevel(level, null);
        if (parsed == null) {
            // 拼错的级别名必须被看见：静默退回默认值会让人以为过滤生效了
            addError("无法识别的 level='" + level + "'，将使用 " + threshold);
            return;
        }
        this.threshold = parsed;
    }

    /** 供测试与运维读取：当前生效的最低入缓冲级别 */
    public String thresholdLevel() {
        return threshold.toString();
    }

    public long writeLockTimeoutMillis() {
        return writeLockTimeoutMillis;
    }

    /** 供测试构造已就绪的实例（跳过 Logback 的生命周期） */
    LogRingBufferAppender(int capacity, int maxMessageChars, long writeLockTimeoutMillis) {
        this.capacity = capacity;
        this.maxMessageChars = maxMessageChars;
        this.writeLockTimeoutMillis = writeLockTimeoutMillis;
    }

    public LogRingBufferAppender() {
        // 无参构造：Logback 反射实例化用。参数在 setter / start() 阶段补齐
    }

    // ================================================================ 生命周期

    @Override
    public void start() {
        if (capacity <= 0) {
            addStatus(new ErrorStatus("capacity 必须为正，收到 " + capacity
                    + "，回退为 " + DEFAULT_CAPACITY, this));
            capacity = DEFAULT_CAPACITY;
        }
        if (maxMessageChars < 64) {
            addStatus(new ErrorStatus("maxMessageChars 过小（" + maxMessageChars
                    + "），回退为 " + DEFAULT_MAX_MESSAGE_CHARS, this));
            maxMessageChars = DEFAULT_MAX_MESSAGE_CHARS;
        }
        this.buffer = new LogRingBuffer(capacity, maxMessageChars, writeLockTimeoutMillis);
        super.start();
        addStatus(new InfoStatus("LogRingBuffer 已启用：capacity=" + capacity
                + ", maxMessageChars=" + maxMessageChars
                + ", 最低入缓冲级别=" + threshold
                + ", 写锁超时=" + writeLockTimeoutMillis + "ms", this));
    }

    @Override
    public void stop() {
        super.stop();
    }

    /**
     * 暴露底层缓冲给 Spring。
     * <p>唯一的消费者是 {@code MonitorLogConfiguration} —— 它把这个实例注册成 Bean，
     * 让 Logs Tool 与 Logback 读写的是<b>同一个</b>缓冲。
     * 若让 Spring 自己 new 一个，就会出现「日志写进 A、工具查 B」这种
     * 不报错但永远查不到东西的状态。
     *
     * @throws IllegalStateException 在 {@link #start()} 之前调用时抛出。
     *         <b>刻意抛而不是返回 null</b>：返回 null 会让调用方在很久以后
     *         以一个无关的 NPE 暴露出来，而这里能立刻指出真正的原因。
     */
    public LogRingBuffer getBuffer() {
        LogRingBuffer b = buffer;
        if (b == null) {
            throw new IllegalStateException(
                    "LogRingBufferAppender 尚未 start()。这通常意味着 "
                            + "logback-spring.xml 里的 <appender> 没有被正确加载，"
                            + "或有人绕过 Logback 直接 new 了本类。");
        }
        return b;
    }

    /** 是否已完成 start()。供配置类在装配前判断，避免用异常做流程控制 */
    public boolean isBufferReady() {
        return buffer != null;
    }

    // ================================================================ 写入

    @Override
    protected void append(ILoggingEvent event) {
        LogRingBuffer target = buffer;
        if (event == null || target == null) {
            return;
        }
        if (Boolean.TRUE.equals(IN_APPEND.get())) {
            // 递归入口（本 appender 触发的日志不再入缓冲，否则会无限递归）
            return;
        }
        Level level = event.getLevel();
        if (level == null || level.levelInt < threshold.levelInt) {
            return;
        }

        IN_APPEND.set(Boolean.TRUE);
        try {
            if (!target.offer(toRecord(event))) {
                reportDropOnce();
            }
        } catch (Throwable t) {
            // 【为什么 catch Throwable 而不只是 Exception】
            // 走到这里的路径上任何异常都不该影响业务线程。Error（例如 OOM）
            // 在这里被吞掉确实有争议，但替代方案是「让一条日志把秒杀请求打挂」，
            // 两害相权取其轻 —— 而且此处不再分配大对象，触发 OOM 的可能性极低。
            reportDropOnce();
        } finally {
            // 必须清回 FALSE：ThreadLocal 泄漏到线程池的复用线程上，
            // 会让那条线程之后<b>所有</b>的日志静默消失。
            IN_APPEND.set(Boolean.FALSE);
        }
    }

    private LogRecord toRecord(ILoggingEvent event) {
        Map<String, String> mdc = mdcOf(event);
        return new LogRecord(
                event.getTimeStamp(),
                event.getLevel().toString(),
                event.getLoggerName(),
                event.getThreadName(),
                event.getFormattedMessage(),
                renderThrowable(event.getThrowableProxy()),
                mdc.get(TraceContext.TRACE_ID),
                mdc.get(TraceContext.REQUEST_ID),
                mdc.get(TraceContext.SERVICE_NAME),
                mdc.get(TraceContext.OPERATION),
                mdc.get(TraceContext.ERROR_CODE),
                mdc.get(TraceContext.USER_ID),
                mdc.get(TraceContext.ORDER_NO),
                mdc.get(TraceContext.STOCK_ID));
    }

    /**
     * 取日志事件携带的 MDC，<b>取不到时退回直接读 {@link MDC}</b>。
     *
     * <h3>为什么要这么小心</h3>
     * <p>{@code LoggingEvent.getMDCPropertyMap()} 内部会调用
     * {@code MDC.getMDCAdapter().getCopyOfContextMap()}。而 {@code MDC.getMDCAdapter()}
     * 是一个<b>静态字段</b>，只在 SLF4J 完成绑定时被赋值。于是有一类环境里它是 {@code null}，
     * 此时这个方法抛的是 {@link NullPointerException} —— 而它抛在<b>日志路径上</b>，
     * 被 {@link #append} 的兜底 catch 吞掉之后，表现是
     * 「应用一切正常，但一条日志都进不了缓冲」。
     * <p>这不是假设：集成测试里自建 {@link ch.qos.logback.classic.LoggerContext}
     * 时就精确复现了它，而当时的失败信息只是「查到 0 条」。
     *
     * <h3>退回方案为什么是等价的</h3>
     * <p>{@code MDC.get(...)} 读的是同一个 {@code MDCAdapter} 的 ThreadLocal。
     * 区别只在于「事件在创建时把 MDC 快照了吗」—— 快照更准确（异步 appender 下尤其如此），
     * 但两条路径读到的值在同步 appender 下完全一致。
     * 换句话说：这条兜底不牺牲正确性，只换来「MDC 适配器缺失时不至于整条链哑掉」。
     */
    private static Map<String, String> mdcOf(ILoggingEvent event) {
        try {
            Map<String, String> fromEvent = event.getMDCPropertyMap();
            if (fromEvent != null) {
                return fromEvent;
            }
        } catch (RuntimeException e) {
            // 见上文：MDCAdapter 未初始化时 getMDCPropertyMap() 会抛 NPE。
            // 刻意不在这里打日志 —— 那会再次进入 appender。
        }
        Map<String, String> fallback = new HashMap<>(8);
        for (String key : TraceContext.MDC_KEYS) {
            String value = TraceContext.get(key);
            if (value != null) {
                fallback.put(key, value);
            }
        }
        return fallback;
    }

    /**
     * 渲染异常链。
     * <p>用 Logback 自带的 {@link ThrowableProxyUtil#asString(IThrowableProxy)} 而不是
     * {@code event.getThrowableProxy().getMessage()}：前者输出<b>完整堆栈</b>，
     * 而真实排障需要知道「异常从哪一行抛出来的」。只留一行 message 会让 Logs Tool 对
     * {@code SocketTimeoutException} 这类「消息毫无信息量」的异常完全失效。
     */
    private static String renderThrowable(IThrowableProxy proxy) {
        if (proxy == null) {
            return null;
        }
        try {
            return ThrowableProxyUtil.asString(proxy);
        } catch (RuntimeException e) {
            // 渲染失败时退回单行形态：宁可信息少，也不能让日志写入失败
            return proxy.getClassName() + ": " + proxy.getMessage();
        }
    }

    /**
     * 首次丢弃时往 StatusManager 记一条。
     * <p>只记一次：丢弃发生在高并发场景，若每次都记，状态列表本身会成为内存与 CPU 的热点 ——
     * 「为了报告问题而制造更大的问题」。完整计数在
     * {@link LogPage.BufferStats#dropped()} 里，由 Logs Tool 读出。
     */
    private void reportDropOnce() {
        if (dropReported.compareAndSet(false, true)) {
            addStatus(new InfoStatus("LogRingBuffer 已开始丢弃日志（写锁竞争或容量上限）。"
                    + "丢弃总数可从 Logs Tool 的 bufferStats.dropped 查看，"
                    + "调大 logback-spring.xml 里 RING_BUFFER 的 capacity 或降低日志级别可缓解。", this));
        }
    }
}
