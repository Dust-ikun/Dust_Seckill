package com.dustikun.seckill.monitor.log;

import java.util.List;

/**
 * 一次日志查询的结果。
 *
 * <p><b>为什么 count 与 samples.size() 是两个不同的数</b>
 * <p>
 * SPEC 第 9.2 节的返回体是 {@code {"count": 321, "samples": [...]}} —— 这两个数
 * 在<b>去重之后本来就不相等</b>，而它们的差值携带了最重要的诊断信息：
 * <pre>
 *   count=321, samples.size()=2   →  同一个错误刷了 321 遍（典型的连接池耗尽 / 重试风暴）
 *   count=321, samples.size()=20  →  20 种不同的错误（典型的多点故障）
 * </pre>
 * 只返回其中一个，Agent 就无法区分这两件性质完全不同的事。
 * 因此这里保留两种计数，并在 {@link LogSample} 上按组带出出现次数。
 *
 * @param count       命中的<b>原始</b>日志条数（去重前）
 * @param samples     去重后的样本，按时间倒序（最新的在前）
 * @param truncated   是否因为 {@code limit} / 单条长度上限而被截断
 * @param note        给人和 LLM 看的口径说明（例如「按签名去重，occurrences 是出现次数」）
 * @param query       回显的查询条件（{@link LogQuery#describe()}）
 * @param bufferStats 缓冲区的现状快照。为 {@code null} 表示本次未读取统计
 */
public record LogPage(
        long count,
        List<LogSample> samples,
        boolean truncated,
        String note,
        List<String> query,
        BufferStats bufferStats
) {

    /** 空结果。用于「缓冲被禁用」等不该抛异常的降级路径 */
    public static LogPage empty(String note) {
        return new LogPage(0, List.of(), false, note, List.of(), null);
    }

    /** 去重后的样本种类数 */
    public int distinctCount() {
        return samples.size();
    }

    /**
     * 一条（去重后的）日志样本。
     *
     * @param occurrences 该签名在命中集合里出现了多少次。
     *                    <b>它是本结构里信息量最大的字段</b>：1 次是偶发，3000 次是风暴
     * @param firstMillis 该签名最早一次出现的时间
     * @param lastMillis  该签名最晚一次出现的时间
     * @param record      代表性记录原文（取该签名<b>最新</b>的一条，变量值是真实的、没有被模板替换）
     * @param masked      代表性记录是否已被脱敏（{@code seckill.monitor.mask.*} 生效）
     */
    public record LogSample(
            long occurrences,
            long firstMillis,
            long lastMillis,
            LogRecord record,
            boolean masked
    ) {
    }

    /**
     * 环形缓冲区现状。放进查询结果是为了让 Agent 能自己发现「证据可能不完整」：
     * 例如 {@code dropped > 0} 说明高并发下丢过日志，
     * {@code oldestMillis} 说明时间窗已经滑出了缓冲范围 ——
     * 这两种情况下「没查到」不等于「没发生」。
     *
     * @param capacity     容量（条）
     * @param size         当前条数
     * @param totalStored  进程启动以来写入的总条数（含已被覆盖的）
     * @param dropped      因竞争或容量被丢弃的条数
     * @param oldestMillis 缓冲里最老一条的时间，缓冲为空时为 0
     * @param newestMillis 缓冲里最新一条的时间，缓冲为空时为 0
     * @param approxBytes  当前占用的近似字节数
     */
    public record BufferStats(
            int capacity,
            int size,
            long totalStored,
            long dropped,
            long oldestMillis,
            long newestMillis,
            long approxBytes
    ) {
    }
}
