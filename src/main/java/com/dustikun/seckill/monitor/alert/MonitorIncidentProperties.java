package com.dustikun.seckill.monitor.alert;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Incident 聚合配置（{@code seckill.monitor.incident.*}，SPEC 第 16 节）。
 *
 * <h2>为什么聚合窗口是 120 秒（而不是「随便给个 60」）</h2>
 * <p>
 * 它必须大于「一次故障引发多个告警」的传播延迟。本项目的实测链路是：
 * <pre>
 *   MySQL 慢查询出现
 *     → Prometheus 15s 采集周期
 *     → 规则 for: 30s 才进入 firing
 *     → Alertmanager group_wait: 0s 立即投递
 * </pre>
 * 最坏约 60~75 秒。窗口取小了会把同一个事故拆成两个任务
 * （各付一次 LLM 费用、各查一遍指标）；取大了会把前后两次独立故障合并，
 * 于是 Agent 拿到互相矛盾的证据。因此这个值应当随告警规则的 {@code for}
 * 一起调整，而不是单独拍。
 *
 * <h2>{@code aggregate-by} 为什么不是「可配置的聚合键」</h2>
 * <p>
 * 配置文件里写的是 {@code service,alertType}，而这个值在代码里是<b>固定的</b>。
 * 这里刻意<b>不</b>把它做成真正的动态聚合键，理由是 SPEC 第 16 节自己给的那张图：
 * <pre>
 *   P99 高 + Error Rate 高 + Timeout 高 → 可能是同一个数据库故障
 * </pre>
 * 「它们是不是同一件事」正是 Agent 该做的推理，不该在聚合层替它下结论 ——
 * 而一旦允许把 {@code alertType} 从键里去掉，聚合层就正好在做这个结论。
 *
 * <p>但「读进来却不用」是本项目记录过三次的坑（{@code management.metrics} 前缀、
 * {@code lettuce.command.completion}、{@code MonitorLogProperties} 的前缀）：
 * 配置被静默忽略，改了没有反应。因此这里的处理是
 * <b>校验后失败</b>：值不等于 {@code service,alertType} 时启动即报错并说明原因，
 * 让「我改了这个配置」立刻得到反馈，而不是等到某次诊断把两个无关事故合并了才发现。
 *
 * @param aggregateWindowSeconds 聚合窗口（秒）
 * @param aggregateBy            聚合键的文档化声明，只接受 {@code service,alertType}
 */
@ConfigurationProperties(prefix = "seckill.monitor.incident")
public record MonitorIncidentProperties(
        Integer aggregateWindowSeconds,
        String aggregateBy
) {

    private static final int DEFAULT_WINDOW_SECONDS = 120;

    /** 唯一受支持的聚合键，见类注释 */
    public static final String SUPPORTED_AGGREGATE_BY = "service,alertType";

    public MonitorIncidentProperties normalized() {
        return new MonitorIncidentProperties(
                clamp(aggregateWindowSeconds, 10, 3600, DEFAULT_WINDOW_SECONDS),
                aggregateBy == null ? SUPPORTED_AGGREGATE_BY : aggregateBy.trim());
    }

    /** 聚合窗口。窗口内、同服务、同异常类型的告警会并入同一个诊断任务 */
    public Duration window() {
        return Duration.ofSeconds(clamp(aggregateWindowSeconds, 10, 3600, DEFAULT_WINDOW_SECONDS));
    }

    /**
     * 装配期校验：把「配置被静默忽略」变成「启动即失败」。
     * <p>它故意把 {@code aggregateBy} 归一化后比较（去掉空格、忽略大小写），
     * 这样 {@code service, alertType} 这种带空格的写法不会被误报。
     */
    public void validate() {
        String actual = normalized().aggregateBy();
        String canonical = actual.replace(" ", "");
        if (!SUPPORTED_AGGREGATE_BY.equalsIgnoreCase(canonical)) {
            throw new IllegalStateException(
                    "seckill.monitor.incident.aggregate-by=" + actual + " 不受支持。"
                            + "本项目只实现「同服务 + 同异常类型」这一种聚合键（SPEC 第 16 节），"
                            + "因为「P99 高与错误率高是不是同一件事」属于 Agent 的推理，"
                            + "不该由聚合层替它下结论。若确实要跨类型合并，请改代码并 code review，"
                            + "而不是改配置 —— 一个读进来却不生效的配置比没有这个配置更糟。");
        }
    }

    private static int clamp(Integer value, int min, int max, int fallback) {
        if (value == null) {
            return fallback;
        }
        return Math.max(min, Math.min(max, value));
    }
}
