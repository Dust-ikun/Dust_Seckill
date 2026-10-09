package com.dustikun.seckill.monitor.tool;

import com.dustikun.seckill.monitor.tool.metrics.MetricsTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import java.util.List;

/**
 * Tool 层的启动自检。
 *
 * <h2>为什么是「应用已就绪」而不是「构造 Bean 时」</h2>
 * <p>
 * 两件事决定了时机：
 * <ol>
 *   <li><b>自检包含网络请求</b>（问 Prometheus 每条目录指标在不在）。
 *       放在构造期会让启动时间受外部依赖影响，而外部依赖慢不该拖慢应用的可用性；</li>
 *   <li><b>它要读的日志缓冲需要应用先起完</b>。{@code ApplicationReadyEvent} 之后
 *       Spring 自己的启动日志（含 Logs Tool 的两条自检行）都已经进了环形缓冲 ——
 *       这时数一次「缓冲里有多少条」，得到的是一个有意义的非零值；
 *       更早执行则可能数到 0，而「0 条」会被误读成「日志底座没工作」。</li>
 * </ol>
 *
 * <h2>它检查的四件事，以及每一件对应哪种静默失效</h2>
 * <table border="1">
 *   <caption>自检项与它防的失效</caption>
 *   <tr><th>检查</th><th>失效表现（若不检查）</th></tr>
 *   <tr>
 *     <td>白名单个数与名称</td>
 *     <td>少注册一个 Tool → Agent 反复调用一个不存在的工具，轨迹里全是 REJECTED</td>
 *   </tr>
 *   <tr>
 *     <td>指标目录里每条指标在 Prometheus 中是否真的有序列</td>
 *     <td>指标名写错 → 该指标永远返回空，而空结果被读成「没有异常」（本项目头号坑源）</td>
 *   </tr>
 *   <tr>
 *     <td>日志底座的容量与当前条数</td>
 *     <td>Logback 的 appender 没装载 / 缓冲被禁用 → Logs Tool 永远返回 0 条</td>
 *   </tr>
 *   <tr>
 *     <td>Prometheus 可达性</td>
 *     <td>整个指标类证据线不可用，而 Agent 仍以为自己在看指标</td>
 *   </tr>
 * </table>
 *
 * <p>【为什么用 INFO/WARN 而不是启动失败】除了「白名单少于 5 个」这一条
 * （那由 {@code MonitorToolConfiguration} 直接抛异常），其余都是
 * <b>「能力降级」而不是「应用不可用」</b>。SPEC 第 18 节的原则是
 * 「AI 失败 ≠ 监控失败」，同理：监控证据不完整 ≠ 秒杀系统不能用。
 * 把这类问题打成启动失败，会让一个可用的交易系统因为一个指标名写错而起不来 ——
 * 那是比缺陷本身更严重的事故。
 */
public class ToolSelfCheck {

    private static final Logger log = LoggerFactory.getLogger(ToolSelfCheck.class);

    private final ToolRegistry registry;

    private final MetricsTool metricsTool;

    private final ResultShaper shaper;

    private final com.dustikun.seckill.monitor.log.LogSink logSink;

    public ToolSelfCheck(ToolRegistry registry, MetricsTool metricsTool, ResultShaper shaper,
                         com.dustikun.seckill.monitor.log.LogSink logSink) {
        this.registry = registry;
        this.metricsTool = metricsTool;
        this.shaper = shaper;
        this.logSink = logSink;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void check() {
        log.info("[ToolRegistry] 白名单自检：{} 个工具 ——{}", registry.size(), registry.describe());

        // ---- 指标目录（唯一会发网络请求的一项，见 MetricsTool#probeCatalog）----
        try {
            metricsTool.probeCatalog();
        } catch (RuntimeException e) {
            // 自检本身绝不能把应用带崩：它只是「报告能力状态」。
            log.warn("[MetricsTool] 指标目录自检执行失败（不影响监控的其余部分）：{}", e.toString());
        }

        // ---- 日志底座 ----
        try {
            int capacity = logSink.capacity();
            int size = logSink.stats().size();
            if (size == 0) {
                // 走到这里说明「应用已经启动完成，但环形缓冲里一条日志都没有」——
                // 那只可能是 appender 没生效（例如 logback-spring.xml 被 logging.config 指走）。
                log.warn("[LogsTool] 日志底座容量 {} 条，但启动完成后缓冲里是 0 条 —— "
                        + "Logback 的 RING_BUFFER appender 可能没有生效，"
                        + "Logs Tool 将永远返回空结果。请检查 src/main/resources/logback-spring.xml。",
                        capacity);
            } else {
                log.info("[LogsTool] 日志底座可用：容量 {} 条，启动完成时已缓存 {} 条。",
                        capacity, size);
            }
        } catch (RuntimeException e) {
            log.warn("[LogsTool] 日志底座自检执行失败：{}", e.toString());
        }

        // ---- 结果整形器 ----
        log.info("[ToolRegistry] 结果整形器：单次结果上限 {} 字符（含 <{}> 包裹），"
                        + "超出时先收缩列表、再截断字符串、最后才返回预览。",
                shaper.maxResultChars(), ResultShaper.UNTRUSTED_TAG);

        List<String> names = registry.names();
        if (names.size() < 5) {
            // 正常不可达：MonitorToolConfiguration 在装配期已经断言过。留着是为了
            // 万一那条断言被误删时这里还能出声（两处检查的成本是几微秒）。
            log.error("[ToolRegistry] 白名单少于 SPEC 第 24 节要求的 5 个：{}", names);
        }
    }
}
