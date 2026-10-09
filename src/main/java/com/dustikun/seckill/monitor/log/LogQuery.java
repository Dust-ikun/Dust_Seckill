package com.dustikun.seckill.monitor.log;

import java.util.List;

/**
 * 一次日志查询的请求条件。
 *
 * <p><b>它刻意与 SPEC 第 9.2 节的 {@code search_logs} 参数对齐，但更宽松一点</b>：
 * SPEC 给了 {@code (service, keyword, start_time, end_time, limit)} 五个参数，
 * 这里额外有 {@code level} 与 {@code traceId}。理由是两个真实的取证动作靠那五个参数做不到：
 * <ul>
 *   <li><b>只看 ERROR</b>：一次故障期间 INFO 噪音是 ERROR 的几十倍，
 *       不按级别过滤时 {@code max-samples=20} 会被「消费·确认成功」这类行占满；</li>
 *   <li><b>按 traceId 取全链</b>：Agent 从告警里拿到的是「某时刻 P99 高」，
 *       但人排障时更常见的入口是「这一单到底怎么了」——
 *       而 traceId 是把一次下单的建单、投递、消费三段日志串起来的<b>唯一</b>手段。</li>
 * </ul>
 * 两个参数都是可选的，不传时的行为与 SPEC 完全一致。
 *
 * @param keyword     关键字（大小写不敏感，命中消息/堆栈/traceId/错误码/单号/类名任一即可），可为 {@code null}
 * @param traceId     精确匹配某条调用链，可为 {@code null}
 * @param level       最低级别过滤（{@code WARN} 表示只要 WARN 与 ERROR），可为 {@code null}
 * @param serviceName 链路角色名，可为 {@code null}
 * @param fromMillis  时间窗下界（含），可为 {@code null}
 * @param toMillis    时间窗上界（含），可为 {@code null}
 * @param limit       最多返回多少条（去重后计数），必须为正
 */
public record LogQuery(
        String keyword,
        String traceId,
        String level,
        String serviceName,
        Long fromMillis,
        Long toMillis,
        int limit
) {

    /** 单次查询的硬上限。防止 LLM 传一个 {@code limit=100000} 把整条环形缓冲拉走 */
    public static final int MAX_LIMIT = 200;

    public static final int DEFAULT_LIMIT = 20;

    /**
     * 规整到可用状态。
     *
     * <p><b>为什么在这里夹紧而不是抛异常</b>：入参来自 LLM，它一定会偶尔给出
     * {@code limit=0} 或 {@code limit=100000} 这类值。抛异常会让整次 Tool 调用失败，
     * Agent 于是拿不到任何证据却要继续推理；而夹紧只损失「返回条数」这一个自由度，
     * 结果里会带 {@code truncated=true} 把它显式标出来。
     * 真正非法（例如 limit 为负）的情形由调用方（批次 2 的 Tool 参数校验）拒绝。
     */
    public LogQuery normalized() {
        int safeLimit = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        Long from = fromMillis;
        Long to = toMillis;
        // 时间窗给反了（LLM 常见错误）时自动交换，而不是返回空结果 ——
        // 空结果会被 Agent 解读成「这段时间没有日志」，那是一个错误的结论。
        if (from != null && to != null && from > to) {
            Long tmp = from;
            from = to;
            to = tmp;
        }
        return new LogQuery(blankToNull(keyword), blankToNull(traceId), blankToNull(level),
                blankToNull(serviceName), from, to, safeLimit);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** 结果里回显查询条件：让 Agent 与人看到的是「这些结论对应哪次查询」，而非孤立数字 */
    public List<String> describe() {
        List<String> parts = new java.util.ArrayList<>(4);
        if (keyword != null) {
            parts.add("keyword=" + keyword);
        }
        if (traceId != null) {
            parts.add("traceId=" + traceId);
        }
        if (level != null) {
            parts.add("level>=" + level);
        }
        if (serviceName != null) {
            parts.add("service=" + serviceName);
        }
        if (fromMillis != null || toMillis != null) {
            parts.add("window=[" + fromMillis + "," + toMillis + "]");
        }
        parts.add("limit=" + limit);
        return parts;
    }

    /**
     * 时间窗下界，未指定时为 {@link Long#MIN_VALUE}。
     * <p>用「哨兵值」而不是在扫描里写 {@code from == null || ts >= from}，
     * 是因为后者的短路写法在每一条日志上都要多一次判空 —— 扫描路径上的分支越少越好。
     */
    public long fromMillisOrMin() {
        return fromMillis == null ? Long.MIN_VALUE : fromMillis;
    }

    /** 时间窗上界，未指定时为 {@link Long#MAX_VALUE}。理由同 {@link #fromMillisOrMin()} */
    public long toMillisOrMax() {
        return toMillis == null ? Long.MAX_VALUE : toMillis;
    }
}
