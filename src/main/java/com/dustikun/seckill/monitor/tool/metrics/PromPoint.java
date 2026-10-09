package com.dustikun.seckill.monitor.tool.metrics;

/**
 * 一个采样点。
 *
 * @param timestampMillis 采样时刻（毫秒）。Prometheus 的 API 返回的是<b>秒</b>（可带小数），
 *                        在解析时就统一换算成毫秒 —— 混着两种单位是本类最容易出错的地方，
 *                        因此换算只做一次（见 {@code HttpPrometheusQuerier}）
 * @param value            值。可能为 {@link Double#NaN}：Prometheus 对「窗口内没有数据」的
 *                         聚合（如 {@code histogram_quantile}）会返回 NaN。
 *                         <b>NaN 与 0 的语义完全不同</b>：前者是「没有数据」，
 *                         后者是「有数据且为零」。两者混淆会直接导致错误的诊断结论
 *                         （「P99 = 0，系统很快」vs「P99 无数据，无法判断」），
 *                         因此这里保留 NaN 原样，由上层显式处理
 */
public record PromPoint(long timestampMillis, double value) {

    public boolean finite() {
        return Double.isFinite(value);
    }
}
