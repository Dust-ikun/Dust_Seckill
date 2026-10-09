package com.dustikun.seckill.monitor.tool;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 一次 Tool 调用的入参，<b>唯一</b>的读取入口（SPEC 第 24 节安全项「Tool 参数全部经过校验」）。
 *
 * <h2>为什么必须有这一层，而不是每个 Tool 自己读 Map</h2>
 * <p>
 * 入参来自 LLM，它的错误分布是<b>可预测的</b>，而且每一种都真实发生过：
 * <pre>
 *   类型错      {"limit": "20"}          数字被写成字符串（模型按文本生成 JSON 时很常见）
 *   范围错      {"limit": 100000}        想要「全都给我」—— 这正是 SPEC 第 23.3 节要防的
 *   负值        {"limit": -1}            把「不限」当成 -1 的约定
 *   空串        {"keyword": ""}          以为空串等于「不过滤」
 *   大小写错    {"operation": "get_slow_sql"}  枚举值与声明的大小写不一致
 *   自造字段    {"reason": "..."}        模型顺手加的解释性字段
 * </pre>
 * 如果五个 Tool 各自读 Map，上面六种情况的处理会分成五种，而其中至少一种必然写错 ——
 * 典型是「空串当关键字」：它会变成一个匹配所有日志的关键字，于是 Agent 拿到 20 条
 * 无意义的噪音却以为自己在做定向取证。
 *
 * <h2>本类的两条铁律</h2>
 * <ol>
 *   <li><b>类型和格式问题一律修正，取值范围问题一律拒绝</b>。
 *       前者（{@code "20"} → {@code 20}）修正后语义没有歧义；后者（{@code limit=100000}）
 *       静默夹紧会让模型基于错误的假设继续推理，因此必须让它知道被拒了
 *       （见 {@link LogQuery#normalized()} 里对「谁来拒绝」的约定）。</li>
 *   <li><b>被接受的参数都要留痕</b>：{@link #accepted()} 里是校验后的<b>实际值</b>，
 *       它正是 {@code ai_tool_execution.arguments} 那一列要求的东西
 *       （SPEC 第 14.3 节：不是原始串）。这样轨迹里不会出现「模型传了什么」与
 *       「工具实际用了什么」不一致而无法解释的情况。</li>
 * </ol>
 *
 * <h2>线程安全</h2>
 * <p>一次调用一个实例，不共享、不发布。刻意不做成不可变对象 —— 它本来就是一次性的。
 */
public final class ToolArguments {

    /**
     * 字符串参数的硬上限（字符）。
     * <p>它防的不是「模型话多」，而是<b>把一个大字符串送进下游的正则或 LIKE</b>：
     * 一个 1 MB 的 keyword 在日志环形缓冲里做不区分大小写的匹配，
     * 会让一次 Tool 调用从微秒级变成秒级，而 SPEC 第 25 节要求 Tool 单次调用 &lt; 1s。
     * 200 字符足够表达任何真实的排查条件（单号 18 位、类名、错误码）。
     */
    public static final int MAX_STRING_CHARS = 200;

    /** 单个数组参数的硬上限（元素个数）。防止模型把整个服务清单塞进来 */
    public static final int MAX_ARRAY_ITEMS = 16;

    private final String toolName;

    private final Map<String, Object> raw;

    /** 已接受的参数：名 → 校验后的实际值 */
    private final Map<String, Object> accepted = new LinkedHashMap<>();

    /** 出现在入参里但本 Tool 不认识的字段名（按出现顺序） */
    private final Set<String> ignored = new LinkedHashSet<>();

    private ToolArguments(String toolName, Map<String, Object> raw) {
        this.toolName = toolName;
        this.raw = raw;
    }

    /**
     * @param toolName 仅用于错误消息（「哪个工具的参数错了」）
     * @param raw      模型给出的原始参数；{@code null} 视作空 map（无参调用）
     */
    public static ToolArguments of(String toolName, Map<String, Object> raw) {
        return new ToolArguments(toolName, raw == null ? Map.of() : raw);
    }

    // ================================================================ 元信息

    public String toolName() {
        return toolName;
    }

    /** 原始入参（只读）。用于需要「原文」的场景；写入轨迹请用 {@link #accepted()} */
    public Map<String, Object> raw() {
        return Collections.unmodifiableMap(raw);
    }

    /** 校验后的实际值。写入 {@code ai_tool_execution.arguments} */
    public Map<String, Object> accepted() {
        return Collections.unmodifiableMap(accepted);
    }

    /** 被忽略的未定义字段。非空时由 {@link ToolRegistry} 记一条 note */
    public Set<String> ignored() {
        return Collections.unmodifiableSet(ignored);
    }

    /** 入参里是否存在这个键（且值非 null）。用于「区分没传与传了 null」 */
    public boolean has(String name) {
        return raw.get(name) != null;
    }

    // ================================================================ 字符串

    /**
     * 可选字符串。空串 / 全空白 → {@code null}。
     * <p>「空串等于没传」是刻意的：模型经常用 {@code ""} 表达「这一项我不填」，
     * 而下游若把空串当关键字，会匹配到所有日志（见类注释）。
     */
    public String optionalString(String name) {
        Object value = raw.get(name);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        if (text.length() > MAX_STRING_CHARS) {
            throw new ToolArgumentException(name, "长度 " + text.length() + " 超过上限 "
                    + MAX_STRING_CHARS + " 字符。请把条件写得更精确（单号、类名、错误码），"
                    + "不要整段贴日志。");
        }
        accepted.put(name, text);
        return text;
    }

    /**
     * 必填字符串。缺失或空白时拒绝。
     *
     * @param maxChars 该参数自己的上限，必须 &le; {@link #MAX_STRING_CHARS}
     */
    public String requireString(String name, int maxChars) {
        String text = optionalString(name);
        if (text == null) {
            throw new ToolArgumentException(name, "是必填参数，但缺失或为空。");
        }
        if (text.length() > maxChars) {
            throw new ToolArgumentException(name, "长度 " + text.length() + " 超过上限 "
                    + maxChars + " 字符。");
        }
        return text;
    }

    // ================================================================ 数值

    /**
     * 可选长整数，缺失时取 {@code defaultValue}；<b>给了值就必须落在 [min,max] 内</b>。
     *
     * @throws ToolArgumentException 值超出范围或无法解析为整数
     */
    public long longOrDefault(String name, long defaultValue, long min, long max) {
        Long value = optionalLong(name, min, max);
        return value == null ? defaultValue : value;
    }

    /** 可选长整数；缺失返回 {@code null}，给了值就必须在范围内 */
    public Long optionalLong(String name, long min, long max) {
        Object value = raw.get(name);
        if (value == null || (value instanceof String s && s.isBlank())) {
            return null;
        }
        long parsed = parseLong(name, value);
        if (parsed < min || parsed > max) {
            throw new ToolArgumentException(name, "值 " + parsed + " 超出允许范围 ["
                    + min + ", " + max + "]。");
        }
        accepted.put(name, parsed);
        return parsed;
    }

    public int intOrDefault(String name, int defaultValue, int min, int max) {
        Long value = optionalLong(name, min, max);
        return value == null ? defaultValue : value.intValue();
    }

    public int requireInt(String name, int min, int max) {
        Long value = optionalLong(name, min, max);
        if (value == null) {
            throw new ToolArgumentException(name, "是必填参数，但缺失。允许范围 ["
                    + min + ", " + max + "]。");
        }
        return value.intValue();
    }

    /**
     * 可选小数（例如秒数阈值）。
     * <p>单独一个方法而不是复用整数解析：{@code slow-sql-min-seconds} 这类阈值
     * 用小数表达才自然，而模型会给 {@code 0.5} 这种写法。
     */
    public double doubleOrDefault(String name, double defaultValue, double min, double max) {
        Object value = raw.get(name);
        if (value == null || (value instanceof String s && s.isBlank())) {
            return defaultValue;
        }
        double parsed = parseDouble(name, value);
        if (Double.isNaN(parsed) || parsed < min || parsed > max) {
            throw new ToolArgumentException(name, "值 " + value + " 超出允许范围 ["
                    + min + ", " + max + "]。");
        }
        accepted.put(name, parsed);
        return parsed;
    }

    /**
     * 把模型给的值解析成长整数。
     * <p>先过 {@link BigDecimal} 再取整，是为了同时接受 {@code 20}、{@code "20"}
     * 与 {@code "20.0"} —— 最后这种在模型把整数写成实数时会出现，
     * 而 {@code Long.parseLong("20.0")} 会抛异常，把一次本可成功的调用变成
     * 一次 {@code REJECTED}。
     * <p>{@code longValueExact()} 保证「{@code 20.7} 不会被静默变成 20」：
     * 带小数的值会被拒绝而不是截断 —— 一个 limit 从 20.7 变成 20 是小事，
     * 但同一套逻辑用在「秒数阈值」上就会静默改变判定口径。
     */
    private static long parseLong(String name, Object value) {
        BigDecimal decimal = toBigDecimal(name, value);
        try {
            return decimal.longValueExact();
        } catch (ArithmeticException e) {
            throw new ToolArgumentException(name, "值 " + value + " 不是整数（含小数或超出长整型范围）。");
        }
    }

    private static double parseDouble(String name, Object value) {
        return toBigDecimal(name, value).doubleValue();
    }

    private static BigDecimal toBigDecimal(String name, Object value) {
        if (value instanceof Number number) {
            if (number instanceof Double d && (d.isNaN() || d.isInfinite())) {
                throw new ToolArgumentException(name, "值 " + value + " 不是有限数。");
            }
            return new BigDecimal(number.toString());
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            throw new ToolArgumentException(name, "值为空字符串，无法解析为数字。");
        }
        // 【先限长再解析】BigDecimal 解析超长数字串是 O(n^2) 级别的，
        // 而入参来自模型 —— 一个 10 万位的数字能让解析本身成为一次 CPU 放大攻击。
        // 32 字符足够表达任何本文本会用到的数（工具参数里最大的是毫秒时间戳）。
        if (text.length() > 32) {
            throw new ToolArgumentException(name, "值过长（" + text.length()
                    + " 字符），无法作为数字解析。");
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException e) {
            throw new ToolArgumentException(name, "值 \"" + text + "\" 无法解析为数字。");
        }
    }

    // ================================================================ 枚举

    /**
     * 枚举参数，按名称匹配（大小写不敏感，{@code -} 与 {@code _} 等价）。
     *
     * <p>【为什么容错到这一步】枚举值最终由人写进 prompt 给模型看，而模型回填时
     * 的大小写与分隔符是随机的：{@code GET_SLOW_SQL} / {@code get-slow-sql} /
     * {@code GetSlowSql} 都指向同一个操作。把它们判为非法，等于让一次本可成功的
     * 取证失败 —— 而失败的信息量只有「它写错了大小写」，远小于拿不到证据的代价。
     * 反过来，<b>拼错的枚举值仍然会被拒绝</b>（下面那句错误消息会列出全部合法值），
     * 因为那通常意味着模型在编造一个不存在的操作。
     *
     * @param values 合法值清单（顺序即错误消息里的展示顺序）
     */
    public <E extends Enum<E>> E enumOrDefault(String name, Class<E> type, E defaultValue,
                                               List<E> values) {
        String text = optionalString(name);
        if (text == null) {
            return defaultValue;
        }
        String normalized = text.toUpperCase(Locale.ROOT).replace('-', '_');
        for (E candidate : values) {
            if (candidate.name().equals(normalized)) {
                accepted.put(name, candidate.name());
                return candidate;
            }
        }
        List<String> allowed = new ArrayList<>(values.size());
        for (E candidate : values) {
            allowed.add(candidate.name());
        }
        throw new ToolArgumentException(name, "值 \"" + text + "\" 不是合法取值。合法取值："
                + String.join(" / ", allowed) + "（大小写不敏感）。");
    }

    // ================================================================ 数组

    /**
     * 字符串数组。单个元素长度受 {@link #MAX_STRING_CHARS} 约束，
     * 元素个数受 {@code maxItems} 约束，空数组归一为 {@code null}（与空串同理）。
     */
    public List<String> optionalStringList(String name, int maxItems) {
        Object value = raw.get(name);
        if (value == null) {
            return null;
        }
        List<?> items;
        if (value instanceof List<?> list) {
            items = list;
        } else {
            // 单个字符串当作「只有一个元素」而不是报错：模型把数组写成标量是常见手误，
            // 而这里的语义没有歧义（一个过滤条件）。
            items = List.of(value);
        }
        if (items.size() > maxItems) {
            throw new ToolArgumentException(name, "元素个数 " + items.size() + " 超过上限 "
                    + maxItems + "。");
        }
        List<String> result = new ArrayList<>(items.size());
        for (Object item : items) {
            if (item == null) {
                continue;
            }
            String text = String.valueOf(item).trim();
            if (text.isEmpty()) {
                continue;
            }
            if (text.length() > MAX_STRING_CHARS) {
                throw new ToolArgumentException(name, "其中一项长度 " + text.length()
                        + " 超过上限 " + MAX_STRING_CHARS + " 字符。");
            }
            result.add(text);
        }
        if (result.isEmpty()) {
            return null;
        }
        accepted.put(name, List.copyOf(result));
        return result;
    }

    // ================================================================ 未定义字段

    /**
     * 记录一个不在 schema 里的字段。由各 Tool 在读完自己认识的参数之后调用。
     *
     * <p>为什么不直接拒绝：{@code additionalProperties:false} 只对严格实现
     * function-calling 的模型生效，而实测中模型会顺手带上 {@code {"reason":"..."}}
     * 这类字段。拒掉整次调用，代价是拿不到证据；忽略它并留一条 note，
     * 代价只是轨迹里多一行 —— 而那一行恰好能回答「模型当时在想什么」。
     */
    public void recordIgnored(String name) {
        if (name != null && !name.isBlank()) {
            ignored.add(name);
        }
    }

    /**
     * 把入参里所有未被任何 getter 接受过的键登记为「未定义字段」。
     * <p>由 {@link ToolRegistry} 在工具执行前调用，因此各 Tool 不必自己维护
     * 「我认得哪些字段」这份清单 —— 那份清单与 getter 调用必然不同步。
     */
    public void markUnrecognized() {
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            String key = entry.getKey();
            if (accepted.containsKey(key)) {
                continue;
            }
            // 【空值不算「未定义字段」】getter 对 null / 空串 / 空数组一律返回默认值，
            // 因此它们不会进入 accepted。若把它们也记成 ignored，
            // 「模型传了 keyword=""」会被报成「传了未定义字段 keyword」——
            // 一条会误导排障方向的假告警。
            Object value = entry.getValue();
            if (value == null || (value instanceof String s && s.isBlank())
                    || (value instanceof List<?> list && list.isEmpty())) {
                continue;
            }
            ignored.add(key);
        }
    }
}
