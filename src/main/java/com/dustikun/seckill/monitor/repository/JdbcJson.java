package com.dustikun.seckill.monitor.repository;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * 三个 JDBC Store 共用的两件小事：时间的换算与 JSON 列的读写。
 *
 * <h2>时间为什么统一走这里</h2>
 * <p>
 * 项目里既有的实体（{@code Order} / {@code OutboxMessage} 等）在数据库里是
 * {@code DATETIME}，映射成 {@link LocalDateTime}。而监控侧的领域模型用
 * {@link Instant}（Incident 的窗口比较是「绝对时间轴上的先后」，用 LocalDateTime
 * 会在夏令时切换那天给出错误结果 —— 虽然本项目跑在固定时区，但把
 * 「哪个类型才是正确的」这件事写对，比依赖「反正我们是 +08:00」更省事）。
 *
 * <p>换算只在本类发生，且<b>写入与读取用同一对方法</b>：写 {@code LocalDateTime}、
 * 读 {@code getObject(..., LocalDateTime.class)}。混用
 * （例如写 {@code Timestamp} 而读 {@code LocalDateTime}）在
 * {@code connectionTimeZone} 不是 JVM 默认时区时会产生偏移 ——
 * 而那种偏移的表现是「时间差了 8 小时」，排查时第一反应一定是去怀疑业务逻辑。
 *
 * <h2>JSON 列读取失败为什么只记日志</h2>
 * <p>
 * 因为这一层是<b>展示与复盘</b>的路径：一列存坏了，正确的反应是
 * 「把这一列显示为空，并把损坏这件事说出来」，而不是让整个查询 API 500 ——
 * 那会把「一条轨迹损坏」放大成「诊断记录完全看不到」。
 */
final class JdbcJson {

    private static final Logger log = LoggerFactory.getLogger(JdbcJson.class);

    private JdbcJson() {
    }

    // ================================================================ 时间

    /** {@link Instant} → 数据库 {@code DATETIME} 的写入值 */
    static LocalDateTime toDb(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }

    /** 数据库 {@code DATETIME} → {@link Instant} */
    static Instant fromDb(LocalDateTime local) {
        return local == null ? null : local.atZone(ZoneId.systemDefault()).toInstant();
    }

    /** 从 ResultSet 读一列时间（列不存在或为 NULL 都返回 {@code null}） */
    static Instant readInstant(ResultSet rs, String column) throws SQLException {
        return fromDb(rs.getObject(column, LocalDateTime.class));
    }

    // ================================================================ JSON

    /** 把一个对象序列化成可以写进 JSON 列的字符串。失败时返回 {@code "{}"} 而不是抛异常 */
    static String write(ObjectMapper mapper, Object value) {
        if (value == null) {
            return "{}";
        }
        try {
            return mapper.writeValueAsString(value);
        } catch (JacksonException e) {
            log.warn("[AiRepository] JSON 序列化失败，已写空对象：{}", e.getMessage());
            return "{}";
        }
    }

    /** 读一个 JSON 对象列 */
    static Map<String, Object> readMap(ObjectMapper mapper, String column, String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = mapper.readValue(json,
                    new TypeReference<java.util.LinkedHashMap<String, Object>>() { });
            return parsed == null ? Map.of() : parsed;
        } catch (RuntimeException e) {
            // 【为什么只捕获 RuntimeException】Jackson 3 的 JacksonException 已继承它，
            // 因此 `catch (JacksonException | RuntimeException)` 是非法的多捕获（子父关系）。
            log.warn("[AiRepository] 列 {} 不是合法 JSON 对象，已按空对象处理：{}", column, e.getMessage());
            return Map.of();
        }
    }

    /** 读一个 JSON 数组列（元素按字符串返回；对象元素会保留为紧凑 JSON 串） */
    static List<String> readStringList(ObjectMapper mapper, String column, String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<Object> parsed = mapper.readValue(json, new TypeReference<List<Object>>() { });
            if (parsed == null) {
                return List.of();
            }
            return parsed.stream()
                    .filter(java.util.Objects::nonNull)
                    .map(value -> asText(mapper, value))
                    .toList();
        } catch (RuntimeException e) {
            log.warn("[AiRepository] 列 {} 不是合法 JSON 数组，已按空列表处理：{}", column, e.getMessage());
            return List.of();
        }
    }

    /**
     * 把一个 JSON 元素还原成字符串。
     * <p>正常路径是「写进去就是字符串」（SPEC 第 11 节的 {@code evidence} 是字符串数组）；
     * 这里的对象分支只在「数据被别的东西改过」时才会用到，
     * 而它给出的紧凑 JSON 至少比 {@code toString()} 更接近原意。
     */
    private static String asText(ObjectMapper mapper, Object value) {
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        try {
            return mapper.writeValueAsString(value);
        } catch (JacksonException e) {
            return String.valueOf(value);
        }
    }
}
