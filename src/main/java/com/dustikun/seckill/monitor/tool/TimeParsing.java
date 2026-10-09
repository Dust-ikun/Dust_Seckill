package com.dustikun.seckill.monitor.tool;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * 时间参数的容错解析。<b>Metrics Tool 与 Logs Tool 共用同一份实现</b>。
 *
 * <h2>为什么必须容错，而不是「只接受 ISO-8601」</h2>
 * <p>
 * {@code start_time} / {@code end_time} 是模型最容易写错的参数（不是之一）。
 * 实测会出现的形态：
 * <pre>
 *   2026-10-09T13:00:00Z       ISO-8601 带 Z
 *   2026-10-09T13:00:00+08:00  带偏移
 *   2026-10-09 13:00:00        最常见的「数据库风格」，带空格
 *   2026-10-09T13:00:00        不带时区（按本机时区解释）
 *   now / now-30m / -30m       相对时间
 *   30m / 2h / 1d              裸时长（模型用「最近 30 分钟」表达窗口）
 *   1760000000000 / 1760000000 毫秒 / 秒级时间戳
 * </pre>
 * 这些形态的语义<b>都无歧义</b>，因此全部接受。反过来，把 {@code 30m} 判为非法会让
 * 一次本可完成的取证直接失败，而失败给出的信息只有「格式不对」——
 * 修复它的成本却是一整轮 LLM 往返。
 *
 * <h2>为什么两个 Tool 必须共用这一份</h2>
 * <p>
 * 若各自实现，最可能出现的分歧是 {@code 30m} 的解释：一个当作「最近 30 分钟」，
 * 另一个当作「从 1970 年起 30 分钟」。于是同一个提示词在两个工具上得到
 * 一个正常结果与一个空结果，而空结果会被 Agent 读成「那段时间没有异常」。
 * 口径分叉类缺陷的共同特征就是「两边都不报错」。
 *
 * <h2>时区</h2>
 * <p>不带时区的时间按<b>本机时区</b>解释（{@link ZoneId#systemDefault()}）。
 * 本项目是单机部署、日志时间也是本机时区，两者一致；若将来跨时区部署，
 * 这个选择必须与日志的时间基准一起重新审视 —— 因此它写在这里而不是散在各处。
 */
public final class TimeParsing {

    /** 对外展示与解析用的文本格式。与日志行里的时间格式一致，便于人工比对 */
    public static final DateTimeFormatter TEXT_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 给模型看的格式说明。放在这里，避免两个 Tool 各写一份（写岔了模型就只对一半） */
    public static final String FORMAT_HINT =
            "接受 2026-10-09T13:00:00Z / 2026-10-09 13:00:00 / now-30m / 30m（最近 30 分钟）/ "
                    + "毫秒或秒级时间戳";

    private TimeParsing() {
    }

    /**
     * 解析一个时间参数。
     *
     * @param raw      模型给出的原文
     * @param nowMillis 当前时刻（由调用方传入而不是在这里取 {@code System.currentTimeMillis()}：
     *                  同一个请求里的多个时间参数必须用<b>同一个</b> now，
     *                  否则 {@code start=now-30m&end=now} 会变成「31 分钟」的窗口）
     * @return 毫秒时间戳；无法解析时 {@code null}
     */
    public static Long parse(String raw, long nowMillis) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        if (text.isEmpty()) {
            return null;
        }
        // 去掉空格后判断相对时间：模型会写 "now - 30m" 这种带空格的形态
        String compact = text.toLowerCase(Locale.ROOT).replace(" ", "");

        if (compact.equals("now")) {
            return nowMillis;
        }
        String relative = compact.startsWith("now-") ? compact.substring(4)
                : compact.startsWith("-") ? compact.substring(1) : compact;
        if (relative.matches("\\d+(ms|s|m|h|d)")) {
            Long duration = durationMillis(relative);
            if (duration != null) {
                // 裸 "30m" 与 "now-30m" 都解释为「最近 30 分钟」，即 now - 30m。
                // 模型写 "30m" 时想表达的从来不是「1970 年之后 30 分钟」，这里没有歧义。
                return nowMillis - duration;
            }
            return null;
        }

        // 纯数字：时间戳。10~11 位按秒、12 位以上按毫秒 ——
        // 用长度而不是数值阈值判断，是因为「2001-09-09 之后毫秒值恒 > 秒值 × 1000」
        // 这条规律在 2286 年才失效，而长度规律永远成立。
        if (text.matches("\\d{9,14}")) {
            long value = Long.parseLong(text);
            return text.length() >= 12 ? value : value * 1000L;
        }

        try {
            return OffsetDateTime.parse(text).toInstant().toEpochMilli();
        } catch (DateTimeParseException ignored) {
            // 落到下一种格式
        }
        try {
            return Instant.parse(text).toEpochMilli();
        } catch (DateTimeParseException ignored) {
            // 落到下一种格式
        }
        try {
            return LocalDateTime.parse(text.replace(' ', 'T'))
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (DateTimeParseException ignored) {
            // 落到下一种格式
        }
        try {
            return LocalDateTime.parse(text, TEXT_FORMAT)
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    /**
     * 解析并要求成功，失败时抛出参数异常（即 {@code REJECTED}）。
     * <p>{@code null} / 空串视为「没传」，返回 {@code fallback}。
     */
    public static long parseOrThrow(String raw, long fallback, long nowMillis, String argName) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        Long parsed = parse(raw, nowMillis);
        if (parsed == null) {
            throw new ToolArgumentException(argName,
                    "值 \"" + raw + "\" 无法解析为时间。" + FORMAT_HINT + "。");
        }
        return parsed;
    }

    public static String format(long millis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())
                .format(TEXT_FORMAT);
    }

    private static Long durationMillis(String relative) {
        if (relative.endsWith("ms")) {
            return amount(relative.substring(0, relative.length() - 2), 1L);
        }
        long multiplier = switch (relative.charAt(relative.length() - 1)) {
            case 's' -> 1000L;
            case 'm' -> 60_000L;
            case 'h' -> 3_600_000L;
            case 'd' -> 86_400_000L;
            default -> 0L;
        };
        if (multiplier == 0) {
            return null;
        }
        return amount(relative.substring(0, relative.length() - 1), multiplier);
    }

    private static Long amount(String text, long multiplier) {
        try {
            return Long.parseLong(text) * multiplier;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
