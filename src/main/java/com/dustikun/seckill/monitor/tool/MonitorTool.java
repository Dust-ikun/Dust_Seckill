package com.dustikun.seckill.monitor.tool;

import java.util.List;
import java.util.Map;

/**
 * 一个只读的监控 Tool（SPEC 第 9 节）。
 *
 * <h2>「只读」如何被结构性保证，而不只靠约定</h2>
 * <p>
 * SPEC 第 17.1 节要求五个 Tool 全部只读、第 24 节要求「Agent 不拥有 DB 写权限」。
 * 只写一句「本接口应当只读」是没有约束力的 —— 后来者加一个 `repair` 参数就破坏了它。
 * 因此本接口的设计让写入<b>无处可写</b>：
 * <ul>
 *   <li>没有「执行任意语句」的方法。DB Tool 能执行的语句是一个枚举里的常量
 *       （见 {@code monitor.tool.db.ReadOnlyQuery}），Agent 无论传什么参数都只能选中
 *       其中一条，<b>没有任何路径能把它变成 UPDATE</b>；</li>
 *   <li>本接口不提供 JDBC / Redis / RocketMQ 的客户端句柄，只提供已经聚合好的读数；</li>
 *   <li>{@code StockReconcileService#reconcile} 这种「读的时候带一个 repair 开关」的
 *       现有能力，在 Business Tool 里被显式固定为 {@code repair=false}。</li>
 * </ul>
 *
 * <h2>三个方法为什么都要有</h2>
 * <p>
 * {@link #parametersSchema()} 与 {@link #description()} 是给 LLM 看的
 * （function-calling 的声明），{@link #execute} 是给人用的。声明与实现放在同一个类里，
 * 是为了让「改了参数却忘了改声明」这件事在 code review 时无法被忽略 ——
 * 而参数与声明不一致的后果很隐蔽：模型按声明传参、工具按实现拒绝，
 * 表现为「这个工具总是失败」，很难定位到声明本身。
 */
public interface MonitorTool {

    /**
     * 工具名（function-calling 的 {@code name} 字段）。
     * <p>取值必须是白名单里声明的名字（见 {@code MonitorToolConfiguration}）；
     * {@link ToolRegistry} 用它做唯一键，重名会在启动时直接报错。
     */
    String name();

    /**
     * 给模型看的一句话说明：这个工具回答什么问题、什么时候该用它。
     * <p>它直接影响模型的工具选择命中率，因此要写「在什么情况下用」，
     * 而不只是「它是什么」。
     */
    String description();

    /** JSON Schema 形态的参数声明（SPEC 第 9 节列出的那些参数） */
    Map<String, Object> parametersSchema();

    /**
     * 执行一次调用。
     *
     * @param args 已经过类型与范围校验的入参（见 {@link ToolArguments}）
     * @return 结果。**实现不应抛异常来报告业务失败** —— 例如「Prometheus 连不上」
     *         应当返回一个带 {@code note} 的成功结果（数据里标明不可达），
     *         因为「查不到」与「工具坏了」对 Agent 是两种不同的事实，
     *         而抛异常会把它压成后者。真正的实现缺陷（NPE 等）交给
     *         {@link ToolRegistry} 兜底转成 {@link ToolStatus#FAILED}。
     */
    ToolResult execute(ToolArguments args);

    /**
     * OpenAI / DeepSeek 的 tools 数组元素。
     * <p>默认实现直接由上面三个方法拼出，各 Tool 不需要覆写。
     */
    default Map<String, Object> definition() {
        Map<String, Object> function = new java.util.LinkedHashMap<>(4);
        function.put("name", name());
        function.put("description", description());
        function.put("parameters", parametersSchema());
        return Map.of("type", "function", "function", function);
    }

    /** 参数名清单。用于启动自检打印「每个工具接受哪些参数」 */
    @SuppressWarnings("unchecked")
    default List<String> parameterNames() {
        Object properties = parametersSchema().get("properties");
        if (properties instanceof Map<?, ?> map) {
            return map.keySet().stream().map(String::valueOf).toList();
        }
        return List.of();
    }
}
