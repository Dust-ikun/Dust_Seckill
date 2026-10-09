package com.dustikun.seckill.monitor.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 构造 OpenAI / DeepSeek function-calling 的 {@code parameters} JSON Schema。
 *
 * <h2>为什么把 schema 与校验器分开放</h2>
 * <p>
 * 这两件事看起来是一份信息的两种写法（「参数是 1~200 的整数」既是 schema 也是校验），
 * 但它们<b>服务于不同的对象，而且会各自演化</b>：
 * <pre>
 *   schema  写给模型看 —— 措辞影响的是「它会不会用对」，写得越清楚命中率越高
 *   validate 写给运行时  —— 影响的是「用错了会不会造成损失」，必须严格
 * </pre>
 * 典型场景：schema 里把 {@code limit} 描述为「1~200」，模型仍可能给 1000。
 * 此时 schema 帮不上忙（它只是一个声明），必须靠 {@link ToolArguments} 拒绝。
 * 反过来，如果只在代码里 clamp 而不在 schema 里说明，模型会以为 1000 被接受了，
 * 于是它会基于「拿到了 1000 条」这个错误前提继续推理。
 *
 * <p>因此本类只做<b>声明</b>，一处校验都不做。
 *
 * <h2>为什么只用手写的 Map 而不用 JSON Schema 生成库</h2>
 * <p>
 * 本项目要用的只有 {@code object / string / integer / boolean / array / enum / required}
 * 这七种形态（SPEC 第 9 节 5 个 Tool 的全部参数）。引入一个 schema 库为了这七种形态
 * 换一次版本冲突风险（本项目对 Spring AI 已经有过一次同样的裁定，见
 * {@code application-docker.yaml} 里「为什么不用 Spring AI」），不划算。
 */
public final class ToolSchema {

    private ToolSchema() {
    }

    /** 一个对象类型的 schema。{@code required} 为空的参数一律可选 */
    public static Map<String, Object> object(Map<String, Object> properties, String... required) {
        Map<String, Object> schema = new LinkedHashMap<>(4);
        schema.put("type", "object");
        schema.put("properties", properties);
        // 【为什么要显式写 required=[]】
        // 省略 required 与写成空数组对模型的提示强度不同：前者它可能猜「是不是都要传」，
        // 后者明确告诉它「全都可以不传，只传你需要的」。本项目的 Tool 参数设计
        // 就是「不传即取默认」，因此必须显式表达出来。
        schema.put("required", List.of(required == null ? new String[0] : required));
        // 明确禁止模型塞不在 schema 里的字段。实测中模型偶尔会带上
        // `{"reason":"..."}` 这类自造字段；允许它会让轨迹里出现无法解释的参数，
        // ToolArguments 会忽略它们并记一条 note。
        schema.put("additionalProperties", false);
        return schema;
    }

    public static Map<String, Object> string(String description) {
        return type("string", description);
    }

    /**
     * 字符串参数，并在描述里带上长度上限。
     * <p>上限同时由 {@link ToolArguments} 强制 —— 描述是给模型的提示，校验才是闸门。
     */
    public static Map<String, Object> string(String description, int maxChars) {
        Map<String, Object> schema = type("string", description);
        schema.put("maxLength", maxChars);
        return schema;
    }

    public static Map<String, Object> integer(String description, long min, long max) {
        Map<String, Object> schema = type("integer", description);
        schema.put("minimum", min);
        schema.put("maximum", max);
        return schema;
    }

    /**
     * 小数参数（阈值类）。
     * <p>与 {@link #integer} 分开是必要的：把 {@code min_seconds} 声明成 integer，
     * 模型就只能给 0 或 1，而「平均耗时超过 0.5 秒」这种门槛是这个参数最常见的用法。
     */
    public static Map<String, Object> number(String description, double min, double max) {
        Map<String, Object> schema = type("number", description);
        schema.put("minimum", min);
        schema.put("maximum", max);
        return schema;
    }

    public static Map<String, Object> bool(String description) {
        return type("boolean", description);
    }

    public static Map<String, Object> enumeration(String description, List<String> values) {
        Map<String, Object> schema = type("string", description);
        schema.put("enum", new ArrayList<>(values));
        return schema;
    }

    /** 字符串数组。{@code maxItems} 是给模型的提示，真正的闸门在 {@link ToolArguments} */
    public static Map<String, Object> stringArray(String description, int maxItems) {
        Map<String, Object> schema = new LinkedHashMap<>(4);
        schema.put("type", "array");
        schema.put("description", description);
        schema.put("items", Map.of("type", "string"));
        schema.put("maxItems", maxItems);
        return schema;
    }

    private static Map<String, Object> type(String type, String description) {
        Map<String, Object> schema = new LinkedHashMap<>(2);
        schema.put("type", type);
        schema.put("description", description);
        return schema;
    }
}
