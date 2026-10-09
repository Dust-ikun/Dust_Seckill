package com.dustikun.seckill.monitor.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ToolArguments} 的参数校验（SPEC 第 24 节安全项「Tool 参数全部经过校验」）。
 *
 * <h2>这个测试锁的是「模型会犯的错」，不是「代码会犯的错」</h2>
 * <p>
 * 每条用例都对应一种真实出现过的模型输出形态（见 {@link ToolArguments} 的类注释）。
 * 因此用例名都写成「模型给了什么」而不是「方法做了什么」——
 * 将来加参数校验时，看这些名字就知道哪些形态已经被覆盖。
 */
class ToolArgumentsTest {

    private static ToolArguments args(Map<String, Object> raw) {
        return ToolArguments.of("test_tool", raw);
    }

    private static Map<String, Object> map(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return map;
    }

    @Test
    @DisplayName("模型把数字写成字符串 / 实数时都应当被接受（\"20\" 与 20.0 等价于 20）")
    void acceptsNumericStringsAndIntegralReals() {
        assertEquals(20, args(map("limit", "20")).intOrDefault("limit", 5, 1, 200));
        assertEquals(20, args(map("limit", 20L)).intOrDefault("limit", 5, 1, 200));
        assertEquals(20, args(map("limit", 20.0)).intOrDefault("limit", 5, 1, 200));
        assertEquals(20, args(map("limit", " 20 ")).intOrDefault("limit", 5, 1, 200));
    }

    @Test
    @DisplayName("带小数的值被拒绝而不是静默截断（20.7 不能变成 20）")
    void rejectsFractionalValuesInsteadOfTruncating() {
        ToolArgumentException e = assertThrows(ToolArgumentException.class,
                () -> args(map("limit", 20.7)).intOrDefault("limit", 5, 1, 200));
        assertEquals("limit", e.argumentName());
        assertTrue(e.getMessage().contains("不是整数"), e.getMessage());
    }

    @Test
    @DisplayName("超出范围的值被拒绝，且错误消息里带上合法范围（模型据此能自己改对）")
    void rejectsOutOfRangeWithRangeInMessage() {
        ToolArgumentException tooBig = assertThrows(ToolArgumentException.class,
                () -> args(map("limit", 100000)).intOrDefault("limit", 20, 1, 200));
        assertTrue(tooBig.getMessage().contains("[1, 200]"), tooBig.getMessage());

        // 负值是 LLM 表示「不限」的常见约定 —— 但 SPEC 第 23.3 节要求限制条数，
        // 因此这里必须拒绝而不是当成 0 或最大值（LogQuery 的注释把这一点写成了约定）
        assertThrows(ToolArgumentException.class,
                () -> args(map("limit", -1)).intOrDefault("limit", 20, 1, 200));
    }

    @Test
    @DisplayName("空串与空白等于「没传」，不产生关键字（否则会匹配到全部日志）")
    void blankStringBecomesNull() {
        assertNull(args(map("keyword", "")).optionalString("keyword"));
        assertNull(args(map("keyword", "   ")).optionalString("keyword"));
        assertNull(args(map("keyword", null)).optionalString("keyword"));
        assertNull(args(Map.of()).optionalString("keyword"));
        assertEquals("SQLException", args(map("keyword", " SQLException ")).optionalString("keyword"));
    }

    @Test
    @DisplayName("超长字符串被拒绝：它会被送进日志匹配，1MB 的关键字能让一次查询从微秒变秒级")
    void rejectsOverlongString() {
        String huge = "x".repeat(ToolArguments.MAX_STRING_CHARS + 1);
        ToolArgumentException e = assertThrows(ToolArgumentException.class,
                () -> args(map("keyword", huge)).optionalString("keyword"));
        assertTrue(e.getMessage().contains("超过上限"), e.getMessage());
    }

    @Test
    @DisplayName("必填参数缺失时给出「必填」而不是 NPE")
    void requireStringExplainsMissing() {
        ToolArgumentException e = assertThrows(ToolArgumentException.class,
                () -> args(Map.of()).requireString("metric", 64));
        assertTrue(e.getMessage().contains("必填"), e.getMessage());
    }

    @Test
    @DisplayName("枚举参数大小写与分隔符容错：GET-SLOW-SQL / get_slow_sql 都指向同一个操作")
    void enumIsCaseAndSeparatorTolerant() {
        assertEquals(Sample.GET_SLOW_SQL, args(map("operation", "get_slow_sql"))
                .enumOrDefault("operation", Sample.class, Sample.OTHER, List.of(Sample.values())));
        assertEquals(Sample.GET_SLOW_SQL, args(map("operation", "Get-Slow-Sql"))
                .enumOrDefault("operation", Sample.class, Sample.OTHER, List.of(Sample.values())));
        assertEquals(Sample.GET_SLOW_SQL, args(map("operation", "GET_SLOW_SQL"))
                .enumOrDefault("operation", Sample.class, Sample.OTHER, List.of(Sample.values())));
    }

    @Test
    @DisplayName("拼错的枚举值被拒绝，并且错误消息列出全部合法取值")
    void unknownEnumIsRejectedWithAllowedValues() {
        ToolArgumentException e = assertThrows(ToolArgumentException.class,
                () -> args(map("operation", "get_all_orders"))
                        .enumOrDefault("operation", Sample.class, Sample.OTHER, List.of(Sample.values())));
        assertTrue(e.getMessage().contains("GET_SLOW_SQL"), e.getMessage());
        assertTrue(e.getMessage().contains("OTHER"), e.getMessage());
    }

    @Test
    @DisplayName("枚举缺失时取默认值（LLM 常省略「显然」的参数）")
    void enumFallsBackToDefault() {
        assertEquals(Sample.OTHER, args(Map.of())
                .enumOrDefault("operation", Sample.class, Sample.OTHER, List.of(Sample.values())));
    }

    @Test
    @DisplayName("数组参数：单个字符串按「只有一个元素」处理，空数组归一为 null")
    void arrayParameterIsForgiving() {
        assertEquals(List.of("a"), args(map("keywords", "a")).optionalStringList("keywords", 4));
        assertEquals(List.of("a", "b"), args(map("keywords", List.of("a", "b")))
                .optionalStringList("keywords", 4));
        assertNull(args(map("keywords", List.of())).optionalStringList("keywords", 4));
        assertThrows(ToolArgumentException.class,
                () -> args(map("keywords", List.of("a", "b", "c"))).optionalStringList("keywords", 2));
    }

    @Test
    @DisplayName("被接受的参数留痕（写进 ai_tool_execution.arguments 的是校验后的实际值）")
    void acceptedValuesAreRecorded() {
        ToolArguments arguments = args(map("limit", "20", "keyword", "boom"));
        arguments.intOrDefault("limit", 5, 1, 200);
        arguments.optionalString("keyword");
        assertEquals(20L, arguments.accepted().get("limit"),
                "写进轨迹的应当是校验后的实际值（Long），不是原始串");
        assertEquals("boom", arguments.accepted().get("keyword"));
    }

    @Test
    @DisplayName("未定义字段被登记（但不拒绝整次调用）—— 空值与空数组不算未定义")
    void unrecognizedFieldsAreRecordedButBlankOnesAreNot() {
        ToolArguments arguments = args(map("limit", 20, "reason", "因为看起来慢", "keyword", ""));
        arguments.intOrDefault("limit", 5, 1, 200);
        arguments.optionalString("keyword");
        arguments.markUnrecognized();

        assertTrue(arguments.ignored().contains("reason"));
        assertFalse(arguments.ignored().contains("keyword"),
                "空串是「没传」，不该被报成未定义字段：" + arguments.ignored());
        assertFalse(arguments.ignored().contains("limit"));
    }

    @Test
    @DisplayName("无法解析的数字给出可读原因，而不是 NumberFormatException 的堆栈")
    void unparsableNumberExplainsItself() {
        ToolArgumentException e = assertThrows(ToolArgumentException.class,
                () -> args(map("limit", "很多")).intOrDefault("limit", 5, 1, 200));
        assertTrue(e.getMessage().contains("无法解析为数字"), e.getMessage());
    }

    @Test
    @DisplayName("超长的数字串在解析前就被挡住（BigDecimal 解析超长串是 CPU 放大面）")
    void overlongNumberIsRejectedBeforeParsing() {
        assertThrows(ToolArgumentException.class,
                () -> args(map("limit", "1".repeat(200))).intOrDefault("limit", 5, 1, 200));
    }

    /** 用于枚举容错测试的最小枚举 */
    private enum Sample {
        GET_SLOW_SQL,
        OTHER
    }
}
