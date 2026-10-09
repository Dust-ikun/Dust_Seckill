package com.dustikun.seckill.monitor.tool.metrics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 测试替身：按<b>精确的表达式字符串</b>匹配预置结果。
 *
 * <h2>为什么用「精确匹配」而不是「包含某个指标名就返回」</h2>
 * <p>
 * 因为本项目的 Tool 会动态渲染 PromQL（{@code $SERVICE_SELECTOR$} 展开、
 * {@code is_retry} 过滤等），而渲染错误（例如把 service 选择器丢进了错误的位置）
 * 恰恰是要测的东西之一。用「包含即命中」的宽松匹配，一个渲染错位的表达式
 * 仍然会拿到数据，测试就永远发现不了它。
 *
 * <p>精确匹配的代价是测试里要写出完整表达式。这是刻意的：那条表达式是
 * 「Tool 到底向 Prometheus 问了什么」的显式断言，而它正是可解释性的一部分
 * （与 {@code LogPage.query} 回显查询条件同源的理由）。
 *
 * <h2>{@code queryInstant} 与 {@code queryRange} 分开预置</h2>
 * <p>
 * 两者在真实 Prometheus 上返回的结构不同（vector vs matrix），
 * 混在一起会让「瞬时值只取最后一个点」这类错误测不出来。
 */
public final class FakePrometheusQuerier implements PrometheusQuerier {

    private final Map<String, List<PromSeries>> instant = new LinkedHashMap<>();

    private final Map<String, List<PromSeries>> range = new LinkedHashMap<>();

    /** 收到过的全部查询表达式（按顺序），用于断言「问对了没有」 */
    private final List<String> queries = new ArrayList<>();

    private boolean reachable = true;

    private Set<String> metricNames = Set.of();

    public FakePrometheusQuerier reachable(boolean value) {
        this.reachable = value;
        return this;
    }

    /** 预置 Prometheus 的指标名索引（{@code /api/v1/label/__name__/values}） */
    public FakePrometheusQuerier metricNames(String... names) {
        this.metricNames = Set.of(names);
        return this;
    }

    @Override
    public Set<String> metricNames() {
        return metricNames;
    }

    /** 预置一条瞬时查询的结果 */
    public FakePrometheusQuerier onInstant(String expr, PromSeries... series) {
        instant.put(expr, List.of(series));
        return this;
    }

    /** 预置一条区间查询的结果 */
    public FakePrometheusQuerier onRange(String expr, PromSeries... series) {
        range.put(expr, List.of(series));
        return this;
    }

    public List<String> queries() {
        return List.copyOf(queries);
    }

    /** 已预置的瞬时查询键。用于「为什么没命中」这类问题的定位 */
    public List<String> registeredInstantKeys() {
        return List.copyOf(instant.keySet());
    }

    public boolean asked(String expr) {
        return queries.contains(expr);
    }

    @Override
    public List<PromSeries> queryRange(String expr, long startMillis, long endMillis, long stepSeconds) {
        queries.add(expr);
        return range.getOrDefault(expr, List.of());
    }

    @Override
    public List<PromSeries> queryInstant(String expr) {
        queries.add(expr);
        return instant.getOrDefault(expr, List.of());
    }

    @Override
    public boolean reachable() {
        return reachable;
    }

    @Override
    public String baseUrl() {
        return "http://fake-prometheus:9090";
    }

    // ================================================================ 构造辅助

    /** 一条多点的序列（区间查询用） */
    public static PromSeries series(Map<String, String> labels, double... values) {
        List<PromPoint> points = new ArrayList<>(values.length);
        long base = 1_760_000_000_000L;
        for (int i = 0; i < values.length; i++) {
            points.add(new PromPoint(base + i * 15_000L, values[i]));
        }
        return new PromSeries(labels, points);
    }

    /** 一条单点的序列（瞬时查询用）。值为 NaN 表示「窗口内无数据」 */
    public static PromSeries point(Map<String, String> labels, double value) {
        return new PromSeries(labels, List.of(new PromPoint(1_760_000_000_000L, value)));
    }

    public static PromSeries value(double value) {
        return point(Map.of(), value);
    }

    public static Map<String, String> labels(String k1, String v1) {
        return Map.of(k1, v1);
    }

    public static Map<String, String> labels(String k1, String v1, String k2, String v2) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put(k1, v1);
        map.put(k2, v2);
        return map;
    }
}
