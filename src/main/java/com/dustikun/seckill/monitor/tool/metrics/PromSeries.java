package com.dustikun.seckill.monitor.tool.metrics;

import java.util.List;
import java.util.Map;

/**
 * 一条时间序列（Prometheus 的 {@code result} 数组里的一项）。
 *
 * <h2>为什么标签要一起带出来，而不是只留值</h2>
 * <p>
 * 诊断的价值几乎全在标签里。同一个 {@code seckill:http_p99_latency:5m} 按
 * {@code uri} 分组后可能有 6 条序列，其中一条是 2.1s、其余是 10ms ——
 * 只返回值就会得出「P99 = 2.1s」然后去查整个系统；
 * 带上 {@code uri="/seckill"} 才能直接指向出问题的那一个接口。
 * 这正是 SPEC 第 12 节「Agent 不应看到一项异常就立即下结论」所依赖的信息。
 *
 * @param labels 标签集合（不含 {@code __name__}，指标名由调用方给出的表达式决定）
 * @param points 采样点，按时间升序。瞬时查询时长度为 1
 */
public record PromSeries(Map<String, String> labels, List<PromPoint> points) {

    /** 第一条也是唯一一条采样点的值；序列为空时返回 {@link Double#NaN} */
    public double lastValue() {
        return points.isEmpty() ? Double.NaN : points.get(points.size() - 1).value();
    }

    /** 标签拼成 {@code {k="v",...}} 形态，用于日志与 note（让「这条序列是哪一个」可读） */
    public String labelText() {
        if (labels.isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder(64).append('{');
        boolean first = true;
        for (Map.Entry<String, String> entry : labels.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            sb.append(entry.getKey()).append("=\"").append(entry.getValue()).append('"');
            first = false;
        }
        return sb.append('}').toString();
    }
}
