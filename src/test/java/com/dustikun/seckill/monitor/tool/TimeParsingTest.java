package com.dustikun.seckill.monitor.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link TimeParsing}：时间参数的容错解析。
 *
 * <p>这个类的价值不在「解析对不对」，而在<b>口径是否唯一</b>：
 * {@code 30m} 必须到处都解释为「最近 30 分钟」。凡是两个 Tool 各自实现的地方，
 * 这条口径就有分叉的风险，而分叉的表现是「一个工具有数据、另一个没有」——
 * 一个会被读成「那段时间确实没异常」的假否证。
 */
class TimeParsingTest {

    private static final long NOW = 1_760_000_000_000L;

    private static long parse(String text) {
        Long value = TimeParsing.parse(text, NOW);
        assertNotNull(value, "应当能解析：" + text);
        return value;
    }

    @Test
    @DisplayName("now / now-30m / -30m / 30m 都指向相对时间，且 30m 与 now-30m 完全等价")
    void relativeFormsAreEquivalent() {
        assertEquals(NOW, parse("now"));
        assertEquals(NOW, parse("NOW"));
        assertEquals(NOW - 30 * 60_000L, parse("now-30m"));
        assertEquals(NOW - 30 * 60_000L, parse("-30m"));
        assertEquals(NOW - 30 * 60_000L, parse("30m"));
        assertEquals(NOW - 30 * 60_000L, parse("now - 30m"));
        assertEquals(NOW - 2 * 3_600_000L, parse("2h"));
        assertEquals(NOW - 86_400_000L, parse("1d"));
        assertEquals(NOW - 5_000L, parse("5s"));
        assertEquals(NOW - 1_500L, parse("1500ms"));
    }

    @Test
    @DisplayName("ISO-8601（带 Z / 带偏移 / 不带时区）与「数据库风格」的带空格格式都接受")
    void isoAndDatabaseFormsAreAccepted() {
        long expectedLocal = LocalDateTime.of(2026, 10, 9, 13, 0, 0)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();

        assertEquals(expectedLocal, parse("2026-10-09 13:00:00"));
        assertEquals(expectedLocal, parse("2026-10-09T13:00:00"));
        assertEquals(expectedLocal, parse("2026-10-09 13:00"));
        // 带时区的两个：结果应当是同一时刻，与系统时区无关
        assertEquals(parse("2026-10-09T13:00:00Z"), parse("2026-10-09T21:00:00+08:00"));
    }

    @Test
    @DisplayName("时间戳按位数区分秒与毫秒（10 位是秒、13 位是毫秒）")
    void epochTimestampsAreDisambiguatedByLength() {
        assertEquals(1_760_000_000_000L, parse("1760000000000"));
        assertEquals(1_760_000_000_000L, parse("1760000000"));
    }

    @Test
    @DisplayName("无法解析时返回 null（由调用方决定是拒绝还是取默认值）")
    void unparsableReturnsNull() {
        assertNull(TimeParsing.parse("昨天下午", NOW));
        assertNull(TimeParsing.parse("", NOW));
        assertNull(TimeParsing.parse("   ", NOW));
        assertNull(TimeParsing.parse(null, NOW));
        assertNull(TimeParsing.parse("30x", NOW));
        assertNull(TimeParsing.parse("abc123", NOW));
    }

    @Test
    @DisplayName("parseOrThrow：没传取默认值，传了但解析不了则抛参数异常（→ REJECTED）")
    void parseOrThrowDistinguishesMissingFromInvalid() {
        assertEquals(1234L, TimeParsing.parseOrThrow(null, 1234L, NOW, "start_time"));
        assertEquals(1234L, TimeParsing.parseOrThrow("  ", 1234L, NOW, "start_time"));

        ToolArgumentException e = assertThrows(ToolArgumentException.class,
                () -> TimeParsing.parseOrThrow("前天", 0L, NOW, "start_time"));
        assertEquals("start_time", e.argumentName());
        // 错误消息里必须带上可接受的格式：这是模型自我纠正的唯一依据
        assertEquals(true, e.getMessage().contains("now-30m"));
    }

    @Test
    @DisplayName("format 与 parse 对同一个时刻互逆（轨迹里回显的时间能被人核对）")
    void formatRoundTrips() {
        long millis = LocalDateTime.of(2026, 10, 9, 13, 45, 30)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        String text = TimeParsing.format(millis);
        assertEquals("2026-10-09 13:45:30", text);
        assertEquals(millis, parse(text));
    }
}
