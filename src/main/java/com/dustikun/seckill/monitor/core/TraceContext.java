package com.dustikun.seckill.monitor.core;

import org.slf4j.MDC;

import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 日志的追踪上下文：一次业务调用链的标识，以及它在 MDC 里的读写帮手。
 *
 * <p><b>为什么需要它，而不是直接依赖 Actuator / Micrometer Tracing</b>
 * <p>
 * Spring Boot 3+ 自带的 Micrometer Tracing 能给出 traceId，但它要求引入
 * {@code micrometer-tracing-bridge-brave} 或 {@code -otel}，还要一个导出器才有意义。
 * 而本项目对 traceId 的<b>唯一用途</b>是「把一次下单在日志里串起来」，
 * 付费与复杂度都花在了用不上的链路上（本方案不引入 Jaeger / Tempo）。
 * 这里用 MDC + 一个过滤器就得到同样的能力，且 traceId 会出现在日志行里 ——
 * 那正是 SPEC 第 6.2 节与批次 1 验收判据要求的形态。
 *
 * <p><b>MDC 的线程语义（这一条决定了所有用法）</b>
 * <p>
 * MDC 是 {@link ThreadLocal} 的实现。它<b>不会</b>自动传播到线程池的任务里，
 * 也不会从请求线程跟随消息进入消费线程。因此本类的用法有两种，别混：
 * <ul>
 *   <li><b>HTTP 请求线程</b>：由 {@code RequestTraceFilter} 在 entry 写入、finally 清理，
 *       全程不需要业务代码关心。</li>
 *   <li><b>消费线程 / 定时任务线程</b>：没有过滤器，必须用
 *       {@link #open(String, String, Map)} 的 try-with-resources 显式包住，
 *       否则日志里会是上一条消息残留的（或为空的）上下文 ——
 *       那是比没有 traceId 更糟的状态，因为它会把人引向错误的单号。</li>
 * </ul>
 *
 * <p><b>字段名为什么是这些</b>：它们与 SPEC 第 6.2 节的字段名逐字对齐，
 * 也与 SLF4J 生态里 Bravelog / Logstash 的常见键名一致，
 * 将来若真的接入 Micrometer Tracing，无需改日志格式与 Logs Tool 的查询参数。
 */
public final class TraceContext {

    // ================================================================ MDC 键名（唯一出口）

    /** 一次业务调用链的标识。HTTP 请求与「由它派生的 MQ 消费」共享同一个值 */
    public static final String TRACE_ID = "traceId";

    /** 一次 HTTP 请求的标识。仅 HTTP 路径存在（含降级路径里的同步投递） */
    public static final String REQUEST_ID = "requestId";

    /** 链路角色名，与 Prometheus 的 {@code service} 标签同源，供 Logs Tool 按 service 过滤 */
    public static final String SERVICE_NAME = "serviceName";

    /** 业务动作名（建单 / 投递 / 消费确认 / 对账 …） */
    public static final String OPERATION = "operation";

    /** 错误码（{@code ErrorCode} 枚举的 code） */
    public static final String ERROR_CODE = "errorCode";

    public static final String USER_ID = "userId";
    public static final String ORDER_NO = "orderNo";
    public static final String STOCK_ID = "stockId";

    /**
     * 本类会写入 MDC 的全部键。
     * <p>
     * 清理时<b>必须逐键 remove</b>而不是 {@link MDC#clear()}：
     * 线程池里的线程可能还带着别人的 MDC（例如 Tomcat 请求线程复用，
     * 或日志框架自己放的键），clear() 会把不属于我们的键一起抹掉，
     * 而那种副作用不会当场报错，只会在别处表现为「某个字段莫名消失」。
     */
    public static final String[] MDC_KEYS = {
            TRACE_ID, REQUEST_ID, SERVICE_NAME, OPERATION, ERROR_CODE, USER_ID, ORDER_NO, STOCK_ID
    };

    /** 缺省 serviceName。与 prometheus.yml 里 job 的 {@code service} 标签保持同一个值 */
    public static final String DEFAULT_SERVICE_NAME = "order-service";

    /**
     * traceId 的长度。
     * <p>
     * 取 16 个 hex 字符（64 bit）而不是 W3C TraceContext 的 32 字符：
     * 本方案的 traceId <b>只在本机单实例的日志里做串联</b>，不参与跨进程传播协议。
     * 16 字符在「一天百万级请求」的量级下碰撞概率仍可忽略，
     * 而它比 32 字符短一半 —— 日志行短一半、LLM 上下文里的重复字符少一半。
     */
    private static final int TRACE_ID_HEX_LEN = 16;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private TraceContext() {
    }

    // ================================================================ 生成

    /** 生成一个新的 traceId（16 个 hex 字符） */
    public static String newTraceId() {
        byte[] bytes = new byte[TRACE_ID_HEX_LEN / 2];
        RANDOM.nextBytes(bytes);
        char[] out = new char[TRACE_ID_HEX_LEN];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }

    // ================================================================ 读写

    /** 写入一个键。值为 {@code null} 时<b>移除</b>该键（而不是写入字符串 "null"） */
    public static void put(String key, String value) {
        if (value == null || value.isEmpty()) {
            MDC.remove(key);
        } else {
            MDC.put(key, value);
        }
    }

    /** 读一个键，不存在时为 {@code null} */
    public static String get(String key) {
        return MDC.get(key);
    }

    /** 清掉本类写入的全部键。返回前不抛异常 —— 清理失败不该盖住业务异常 */
    public static void clear() {
        for (String key : MDC_KEYS) {
            MDC.remove(key);
        }
    }

    /**
     * 打开一段带追踪上下文的代码块（try-with-resources）。
     *
     * <p><b>它会保存并恢复原上下文</b>，而不是简单地「进入时写、退出时清」。
     * 这一点在线程池里是必须的：消费线程在处理消息 A 时若又调用了某个也会开上下文的方法，
     * 「退出时清空」会把 A 的上下文一起抹掉，于是 A 剩下的日志全部丢失 traceId。
     * 保存/恢复让嵌套调用退化成栈式语义，与人的直觉一致。
     *
     * <pre>{@code
     * try (TraceContext.Scope ignored = TraceContext.open(traceId, "消费确认", Map.of("orderNo", no))) {
     *     ...   // 这段里所有日志自动带上 traceId / operation / orderNo
     * }
     * }</pre>
     *
     * @param traceId   调用链标识。传 {@code null} 时自动生成一个新的
     * @param operation 业务动作名，可为 {@code null}
     * @param fields    额外字段（如 orderNo / stockId），键必须是本类定义的 MDC 键之一
     */
    public static Scope open(String traceId, String operation, Map<String, String> fields) {
        Map<String, String> saved = new LinkedHashMap<>();
        for (String key : MDC_KEYS) {
            saved.put(key, MDC.get(key));
        }

        put(TRACE_ID, traceId == null ? newTraceId() : traceId);
        put(OPERATION, operation);
        if (fields != null) {
            fields.forEach(TraceContext::put);
        }
        if (MDC.get(SERVICE_NAME) == null) {
            put(SERVICE_NAME, DEFAULT_SERVICE_NAME);
        }
        return new Scope(saved);
    }

    /** @see #open(String, String, Map) */
    public static Scope open(String traceId, String operation) {
        return open(traceId, operation, null);
    }

    /**
     * {@link #open} 的返回值，只负责「还原快照」。
     * <p>实现 {@link AutoCloseable} 且 {@link #close()} 不抛受检异常，
     * 因此可以直接用在 try-with-resources 里而不需要在业务代码里写 catch。
     */
    public static final class Scope implements AutoCloseable {

        private final Map<String, String> saved;

        private Scope(Map<String, String> saved) {
            this.saved = saved;
        }

        @Override
        public void close() {
            saved.forEach(TraceContext::put);
        }
    }
}
