package com.dustikun.seckill.monitor.tool.metrics;

import java.util.List;

/**
 * 查询 Prometheus 的 HTTP API（SPEC 第 9.1 节 Metrics Tool 的数据来源）。
 *
 * <h2>为什么要抽成接口，而不是让 Metrics Tool 直接持有 HttpClient</h2>
 * <p>
 * 两个具体收益：
 * <ol>
 *   <li><b>可测</b>：Metrics Tool 的聚合与取值逻辑（范围校验、NaN 处理、百分位）
 *       是本项目自己的代码，必须能脱离「Prometheus 是否在跑」来验证。
 *       用真实 HTTP 的测试在 CI 上必然不稳定，而用假实现可以让它逐点确定；</li>
 *   <li><b>可换</b>：将来若改用 VictoriaMetrics / Thanos 或加一层缓存，
 *       改动收敛在这一个实现类里。</li>
 * </ol>
 *
 * <h2>契约里最重要的一条：不抛异常，用空列表表达「查不到」</h2>
 * <p>
 * 数据源不可用与「这个指标确实没有数据」在 Prometheus 里都会表现为空结果，
 * 因此调用方<b>必须</b>再通过 {@link #reachable()} 区分二者，并在结果里写明。
 * 这正是 SPEC 第 23.3 节与「不得伪造不存在的指标」那条规则的落地方式：
 * 把「无数据」原样传递，而不是补一个 0 —— 补 0 会让 Agent 得出
 * 「系统一切正常」这个与事实相反的结论。
 */
public interface PrometheusQuerier {

    /**
     * 区间查询（{@code /api/v1/query_range}）。
     *
     * @param stepSeconds 步长（秒）。调用方负责按「窗口 / 最大点数」算出合适的值
     * @return 序列列表；查询失败或无数据时返回<b>空列表</b>
     */
    List<PromSeries> queryRange(String expr, long startMillis, long endMillis, long stepSeconds);

    /**
     * 瞬时查询（{@code /api/v1/query}），即「此刻的值」。
     *
     * @return 序列列表；查询失败或无数据时返回空列表
     */
    List<PromSeries> queryInstant(String expr);

    /**
     * 数据源是否可达（{@code /-/healthy}）。
     * <p>它与空结果的区别是诊断结论的分水岭：
     * 「Prometheus 不可达」意味着<b>任何</b>指标结论都不成立，
     * 而「某条指标为空」只意味着这一条没数据。
     */
    boolean reachable();

    /**
     * Prometheus 见过的<b>全部指标名</b>（{@code /api/v1/label/__name__/values}）。
     *
     * <h2>为什么需要它，而不是用「查一次有没有数据」来判断指标名对不对</h2>
     * <p>
     * 因为「指标名写错了」与「这条指标最近没有数据」在查询结果上完全一样（都是空），
     * 而它们的处置方式相反：前者要改代码，后者是正常现象。
     * 用一次即时查询来验证名字，会把第二种情况报成第一种 ——
     * 于是启动日志里会长期挂着一串「查不到」的指标名，读到它的人很快就不看了，
     * 而真正写错的那一个也淹没在里面。
     *
     * <p>指标名清单是 Prometheus 的<b>索引</b>：只要这个名字曾经出现过，
     * 它就在里面 —— 与应用此刻在不在跑、窗口里有没有数据无关。
     * 因此它正好回答「这个名字存在吗」这一个问题，且只需要<b>一次</b> HTTP 请求
     * （对比逐条即时查询的 39 次）。
     *
     * @return 指标名集合；查询失败时返回空集合（调用方据此跳过自检，而不是报「全部缺失」）
     */
    java.util.Set<String> metricNames();

    /** 供 note 与启动日志使用的地址，便于确认「连的是哪一个 Prometheus」 */
    String baseUrl();
}
