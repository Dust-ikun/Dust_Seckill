package com.dustikun.seckill.monitor.core;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 为每个 HTTP 请求建立追踪上下文：生成 traceId / requestId，写进 MDC，
 * 让该请求期间产生的<b>所有</b>日志自动带上它们。
 *
 * <h2>为什么用 Filter 而不是在 Controller 里手动埋点</h2>
 * <p>
 * 埋点式的写法（在每个方法开头 {@code MDC.put(...)}）有三个绕不过的问题：
 * <ol>
 *   <li><b>会漏</b>：新增一个接口就多一个漏点，而漏掉的那个接口恰好是出问题时最需要日志的；</li>
 *   <li><b>覆盖不到框架层</b>：参数绑定失败、序列化异常、拦截器抛错都发生在进方法之前，
 *       那些日志恰恰是排查「请求为什么没进来」的关键；</li>
 *   <li><b>清理容易忘</b>：Tomcat 复用请求线程，忘记清理会让<b>下一个</b>请求的日志
 *       带上上一个请求的 traceId —— 这比没有 traceId 更糟，它会把人引向错误的调用链。</li>
 * </ol>
 * Filter 天然解决这三件事，而且 {@link OncePerRequestFilter} 的 finally 保证清理必然发生。
 *
 * <h2>它在链路中的位置</h2>
 * <pre>
 *   HTTP 请求
 *      ↓  RequestTraceFilter        ← 生成 traceId，写 MDC
 *      ↓  Controller / Service / Mapper / Redis / MQ 投递
 *      ↓  （这些组件里的每一行日志都带 traceId）
 *      ↓  finally：清理 MDC
 *   响应
 * </pre>
 * <p><b>注意它的边界</b>：MDC 基于 {@link ThreadLocal}，<b>不会</b>传播到消费线程与定时任务线程。
 * MQ 消费侧要另用 {@link TraceContext#open} 显式包住 —— 见 {@code SeckillOrderConsumer}。
 * 这是本方案唯一需要人工照看的地方，因此在那里有显式注释指明。
 *
 * <h2>为什么顺序取最高优先级</h2>
 * <p>{@link Ordered#HIGHEST_PRECEDENCE} 让它排在所有业务过滤器之前（包括 Spring Security，
 * 若将来引入）。否则「在它之前的过滤器」打出的日志会没有 traceId，
 * 而那往往是鉴权失败、请求被拒这类最需要追溯的日志。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestTraceFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestTraceFilter.class);

    /**
     * 允许客户端传入 traceId 的请求头。
     * <p>这条通路的价值在于<b>压测与人工复现</b>：压测脚本可以先定一个 traceId 再发请求，
     * 于是「这一次压测产生的所有日志」可以被一次性捞出来。
     * 但它同时是一个<b>注入面</b>（外部可控的字符串会进日志），因此
     * {@link #SAFE_TRACE_ID} 严格限制字符集与长度。
     */
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    /**
     * 外部传入 traceId 的白名单格式。
     * <p>只允许 {@code [A-Za-z0-9._-]}、长度 8~64。
     * <p><b>为什么要限制</b>：traceId 会被写进日志行。若允许换行符，
     * 一次请求就能伪造出任意多行日志 —— 那等于给攻击者一个「向 Logs Tool 与 LLM
     * 注入内容」的通道，而这正是 SPEC 第 17.3 节要防的事。
     * 长度上限则是防「一个 1MB 的 header 把环形缓冲撑爆」。
     */
    private static final Pattern SAFE_TRACE_ID = Pattern.compile("^[A-Za-z0-9._-]{8,64}$");

    /**
     * 不建立追踪上下文的路径前缀。
     * <p>actuator 端点由 Prometheus 每 15 秒抓一次，给它建 traceId 只会往缓冲区灌噪音，
     * 而它对诊断没有价值（要查抓取是否正常应当看 {@code up} 指标）。
     */
    private static final String[] SKIP_PREFIXES = {"/actuator"};

    /** 会写进 MDC 的业务参数名。见 {@link #businessFields} */
    private static final String[] TRACKED_PARAMS = {"userId", "stockId", "orderNo"};

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String traceId = resolveTraceId(request);
        String requestId = TraceContext.newTraceId();

        Map<String, String> fields = new LinkedHashMap<>(6);
        // requestId 与 traceId 分开：traceId 在一次调用链上贯穿（将来若做跨进程传播，
        // 它是那个要透传的值），requestId 只标识这一次 HTTP 请求。
        // 当前实现里一次 HTTP 请求就是一条链，两者一一对应；
        // 分开是为了让「一个 traceId 下有多次请求」这种形态将来不必改字段语义。
        fields.put(TraceContext.REQUEST_ID, requestId);
        fields.put(TraceContext.OPERATION, "http:" + request.getMethod());
        fields.putAll(businessFields(request));

        // 【响应头也要回写 traceId】用户报障时能直接给出「出错的那次请求的 traceId」，
        // 排障从「查一下某时间段的所有日志」变成「查这一个值」，差别很大。
        // 用一个自定义头而不是改响应体：不动任何既有接口的返回契约。
        response.setHeader(TRACE_ID_HEADER, traceId);

        try (TraceContext.Scope ignored = TraceContext.open(traceId, null, fields)) {
            long startNanos = System.nanoTime();
            try {
                chain.doFilter(request, response);
            } finally {
                // 【为什么把耗时日志放在 finally 而不是正常路径】异常路径的耗时恰恰最需要知道：
                // 「这次请求是 20ms 就失败了，还是 30s 之后超时」是完全不同的两个问题。
                long costMs = (System.nanoTime() - startNanos) / 1_000_000L;
                int status = response.getStatus();
                String line = "[请求] {} {} → {} 耗时={}ms";
                if (status >= 500) {
                    log.warn(line + "（服务端错误）", request.getMethod(), path(request), status, costMs);
                } else if (costMs >= SLOW_REQUEST_MILLIS) {
                    log.warn(line + "（慢请求）", request.getMethod(), path(request), status, costMs);
                } else {
                    log.info(line, request.getMethod(), path(request), status, costMs);
                }
            }
        }
    }

    /**
     * 慢请求阈值。与 {@code application.yaml} 里 SLO 桶的 {@code 1s} 对齐 ——
     * 「日志里标为慢请求」与「Prometheus 里 {@code le="1.0"} 的慢请求计数」
     * 必须是同一个口径，否则两处同时看会得出矛盾的结论。
     */
    private static final long SLOW_REQUEST_MILLIS = 1_000L;

    /**
     * 优先采用客户端传入的、且格式安全的 traceId；否则生成一个。
     */
    private String resolveTraceId(HttpServletRequest request) {
        String incoming = request.getHeader(TRACE_ID_HEADER);
        if (incoming != null && SAFE_TRACE_ID.matcher(incoming).matches()) {
            return incoming;
        }
        return TraceContext.newTraceId();
    }

    /**
     * 从请求参数里抽出业务字段写进 MDC。
     * <p>这样做的直接收益：<b>不需要看响应体就能按 userId 或 orderNo 捞出整条链路的日志</b>
     * （{@code grep orderNo=SN175... app.log}），而这正是排障时最常用的入口。
     * <p>参数缺失时不写该键（{@link TraceContext#put} 对空值做的是 remove），
     * 于是日志里不会出现 {@code userId=null} 这种看着像值的东西。
     */
    private static Map<String, String> businessFields(HttpServletRequest request) {
        Map<String, String> fields = new LinkedHashMap<>(4);
        for (String name : TRACKED_PARAMS) {
            String value = request.getParameter(name);
            if (value == null || value.isEmpty() || value.length() > 64) {
                continue;
            }
            // 参数名与 MDC 键一一对应：不这样做的话「看日志的人」与「写日志的人」
            // 之间就多了一层需要记忆的映射，而那层映射迟早会写错。
            switch (name) {
                case "userId" -> fields.put(TraceContext.USER_ID, value);
                case "stockId" -> fields.put(TraceContext.STOCK_ID, value);
                case "orderNo" -> fields.put(TraceContext.ORDER_NO, value);
                default -> { /* TRACKED_PARAMS 里没有别的名字，这里不可达 */ }
            }
        }
        return fields;
    }

    private static String path(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri == null ? "-" : uri;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            return false;
        }
        for (String prefix : SKIP_PREFIXES) {
            if (uri.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
