package com.dustikun.seckill.monitor.agent;

import com.dustikun.seckill.monitor.tool.MonitorTool;
import com.dustikun.seckill.monitor.tool.ToolArguments;
import com.dustikun.seckill.monitor.tool.ToolResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一个只回固定数据的 Tool，用来把 {@code AgentExecutor} 的循环与真实数据源解耦。
 *
 * <h2>为什么它实现真的 {@code MonitorTool} 接口，而不是把 ToolRegistry Mock 掉</h2>
 * <p>
 * 因为循环要验证的事情里有三件<b>恰好发生在注册表内部</b>：
 * <pre>
 *   未注册的工具名 → REJECTED + 白名单提示      （ToolRegistry#invoke 的第一段）
 *   参数越界       → REJECTED                  （ToolArguments 的校验）
 *   结果整形       → <untrusted_data> 包裹      （ResultShaper）
 * </pre>
 * 把注册表 Mock 掉，这三条就变成「测试自己实现的行为」，等于没测。
 * 因此这里装一个假的<b>数据源</b>（这个 Tool），而注册表、参数校验、整形、
 * 超时全部用真的 —— 它们才是循环的边界条件所在。
 */
final class FakeMonitorTool implements MonitorTool {

    private final String name;

    private final Map<String, Object> data;

    private final boolean fail;

    private final long delayMillis;

    private final AtomicInteger invocations = new AtomicInteger();

    /** 最近一次收到的、经 ToolArguments 校验后的入参（用于断言「循环传了什么下去」） */
    private volatile Map<String, Object> lastAccepted = Map.of();

    FakeMonitorTool(String name) {
        this(name, Map.of("value", 42), false, 0L);
    }

    FakeMonitorTool(String name, Map<String, Object> data) {
        this(name, data, false, 0L);
    }

    FakeMonitorTool(String name, Map<String, Object> data, boolean fail, long delayMillis) {
        this.name = name;
        this.data = data;
        this.fail = fail;
        this.delayMillis = delayMillis;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return "测试用工具 " + name + "：在什么情况下用 —— 仅用于单元测试";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("metric", Map.of("type", "string"));
        properties.put("service", Map.of("type", "string"));
        return Map.of("type", "object", "properties", properties);
    }

    @Override
    public ToolResult execute(ToolArguments args) {
        invocations.incrementAndGet();
        // 显式读一遍参数：这样 accepted() 里才会有值（与真实工具的行为一致），
        // 而轨迹里落的 arguments 也才是有意义的那一份。
        lastAccepted = new LinkedHashMap<>();
        String metric = args.optionalString("metric");
        if (metric != null) {
            lastAccepted.put("metric", metric);
        }
        String service = args.optionalString("service");
        if (service != null) {
            lastAccepted.put("service", service);
        }
        lastAccepted = Map.copyOf(lastAccepted);

        if (delayMillis > 0) {
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (fail) {
            return ToolResult.failed(name, "测试构造的失败", List.of("这是测试注入口径说明"));
        }
        return ToolResult.ok(name, data, List.of("测试注入口径说明"));
    }

    int invocations() {
        return invocations.get();
    }

    Map<String, Object> lastAccepted() {
        return lastAccepted;
    }
}
