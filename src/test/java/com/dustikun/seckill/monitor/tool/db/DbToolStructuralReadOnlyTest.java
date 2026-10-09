package com.dustikun.seckill.monitor.tool.db;

import com.dustikun.seckill.monitor.tool.MonitorTool;
import com.dustikun.seckill.monitor.tool.ToolArguments;
import com.dustikun.seckill.monitor.tool.ToolProperties;
import com.dustikun.seckill.monitor.tool.ToolResult;
import com.dustikun.seckill.monitor.tool.ToolStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DB Tool 的<b>结构性只读</b>保证（SPEC 第 9.3 / 17.1 / 24 节）。
 *
 * <h2>这个测试要证明的命题</h2>
 * <p>
 * 验收判据的原话是「传入任何 UPDATE / DELETE 被<b>结构性</b>拒绝（不是字符串匹配）」。
 * 「不是字符串匹配」意味着：不能靠「检查 SQL 里有没有 UPDATE 这个词」来实现防护，
 * 因为那种检查永远列不全（注释、多语句、CTE、大小写、Unicode 空白都能绕）。
 *
 * <p>因此下面用的是一个更强的判据：<b>记录真正被执行的 SQL，并断言它一定等于枚举里的常量</b>。
 * 无论调用方传什么，执行的语句都只能是那几条 SELECT。
 * 这不是「我们检查了它没写」，而是「这条路上不存在写的能力」。
 *
 * <h2>为什么要用一个记录型 JdbcTemplate 而不是跑真库</h2>
 * <p>
 * 因为要断言的是「执行的语句是什么」，而不是「查询结果对不对」。
 * 用真库时，一个被拒绝的 UPDATE 也会因为「表结构对得上」而执行成功，
 * 测试反而看不出来它被放行了。记录型替身让这一点变成确定性的断言。
 * 真库上的四类查询由 {@code MonitorToolIntegrationTest} 覆盖。
 */
class DbToolStructuralReadOnlyTest {

    /**
     * 任何写操作的动词。SQL 里出现它们即视为破坏只读保证。
     *
     * <p>【为什么用词边界而不是 {@code contains}】因为 {@code contains("CREATE")} 会命中
     * {@code Threads_created} —— 那是一条完全正常的只读状态指标，
     * 而一个会误报的守卫最终会被注释掉，于是真正的越界也就没人看了。
     * 正则里的 {@code \b} 在 {@code _} 与字母之间不成立，因此
     * {@code THREADS_CREATED} 不会命中 {@code \bCREATE\b}。
     */
    private static final java.util.regex.Pattern FORBIDDEN = java.util.regex.Pattern.compile(
            "(?i)\\b(INSERT|UPDATE|DELETE|DROP|ALTER|TRUNCATE|CREATE|GRANT|REVOKE|REPLACE"
                    + "|MERGE|CALL|EXECUTE|SET|LOCK|UNLOCK|RENAME|LOAD)\\b");

    @Test
    @DisplayName("枚举里的每一条语句都是只读的（无写动词，且以 SELECT 开头）")
    void everyStatementIsReadOnly() {
        for (ReadOnlyQuery query : ReadOnlyQuery.values()) {
            String sql = query.sql();
            assertTrue(sql.stripLeading().toUpperCase(Locale.ROOT).startsWith("SELECT"),
                    query.name() + " 不是以 SELECT 开头：" + sql);
            java.util.regex.Matcher matcher = FORBIDDEN.matcher(sql);
            assertFalse(matcher.find(),
                    query.name() + " 含有写操作关键字 " + (matcher.reset().find() ? matcher.group() : "")
                            + "：" + sql);
        }
    }

    @Test
    @DisplayName("占位符个数与声明的参数个数一致（不一致意味着有参数被拼进了 SQL）")
    void placeholderCountMatchesDeclaredParameters() {
        for (ReadOnlyQuery query : ReadOnlyQuery.values()) {
            assertEquals(query.parameters().size(), countOccurrences(query.sql(), "?"),
                    query.name() + " 的占位符与参数清单不匹配");
        }
    }

    @Test
    @DisplayName("参数声明里没有任何「能表达 SQL」的字段")
    void noParameterCanCarrySql() {
        List<String> sqlLike = List.of("sql", "query", "statement", "sqlText", "command", "script");
        for (ReadOnlyQuery query : ReadOnlyQuery.values()) {
            for (String parameter : query.parameters()) {
                assertFalse(sqlLike.contains(parameter),
                        query.name() + " 声明了一个可疑参数：" + parameter);
            }
        }
    }

    @Test
    @DisplayName("参数 schema 里也没有自由 SQL 入口（Agent 只能选 operation 与几个数字）")
    void parameterSchemaHasNoFreeSqlEntry() {
        Map<String, Object> schema = new DbTool(recordingTemplate().template,
                new ToolProperties(null, null, null)).parametersSchema();

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertNotNull(properties);
        assertEquals(List.of("operation", "stock_id", "min_seconds", "top_n", "limit"),
                new ArrayList<>(properties.keySet()));

        for (String name : properties.keySet()) {
            assertFalse(name.toLowerCase(Locale.ROOT).contains("sql"), "出现了 SQL 类参数：" + name);
            assertFalse(name.toLowerCase(Locale.ROOT).contains("query"), "出现了查询类参数：" + name);
        }
        // operation 是枚举（闭集），不是自由字符串 —— 这一点是结构性拒绝的关键
        @SuppressWarnings("unchecked")
        Map<String, Object> operation = (Map<String, Object>) properties.get("operation");
        assertEquals("string", operation.get("type"));
        assertTrue(operation.get("enum") instanceof List<?>);
        assertEquals(4, ((List<?>) operation.get("enum")).size());
    }

    @Test
    @DisplayName("传入 UPDATE 语句时，真正执行的仍然是枚举里那条只读 SELECT")
    void updateAttemptExecutesTheReadOnlyStatementInstead() {
        Recording template = recordingTemplate();
        DbTool tool = new DbTool(template.template, new ToolProperties(null, null, null));

        // 模型把「自由 SQL」当参数塞进来 —— 这正是要防的形态
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("operation", "GET_SLOW_SQL");
        arguments.put("sql", "UPDATE orders SET status='CONFIRMED' WHERE 1=1");
        arguments.put("query", "DROP TABLE orders");
        ToolArguments args = ToolArguments.of(DbTool.NAME, arguments);

        ToolResult result = tool.execute(args);
        args.markUnrecognized();

        assertEquals(ToolStatus.SUCCESS, result.status());
        assertEquals(1, template.executed.size(), "只应当执行一条语句：" + template.executed);
        assertEquals(ReadOnlyQuery.SLOW_SQL.sql(), template.executed.get(0));
        assertTrue(template.executed.get(0).stripLeading().toUpperCase(Locale.ROOT).startsWith("SELECT"));

        // 两个 SQL 类参数被登记为「未定义字段」—— 它们从未被解释，也从未到达数据库
        assertTrue(args.ignored().contains("sql"), args.ignored().toString());
        assertTrue(args.ignored().contains("query"), args.ignored().toString());
    }

    @Test
    @DisplayName("operation 不是合法枚举值时被拒绝，且没有任何语句被执行")
    void illegalOperationIsRejectedWithoutTouchingTheDatabase() {
        Recording template = recordingTemplate();
        DbTool tool = new DbTool(template.template, new ToolProperties(null, null, null));

        try {
            tool.execute(ToolArguments.of(DbTool.NAME,
                    Map.of("operation", "DELETE FROM orders")));
            // 走到这里说明没抛异常 —— 那是缺陷
            throw new AssertionError("非法 operation 应当被拒绝");
        } catch (com.dustikun.seckill.monitor.tool.ToolArgumentException expected) {
            assertEquals("operation", expected.argumentName());
            assertTrue(expected.getMessage().contains("GET_SLOW_SQL"), expected.getMessage());
        }
        assertTrue(template.executed.isEmpty(), "拒绝时不该执行任何语句：" + template.executed);
    }

    @Test
    @DisplayName("每个 operation 至少落到一条只读语句上（不存在「什么都不查」的操作）")
    void everyOperationMapsToAtLeastOneStatement() {
        for (DbTool.Operation operation : DbTool.Operation.values()) {
            Recording template = recordingTemplate();
            DbTool tool = new DbTool(template.template, new ToolProperties(null, null, null));

            Map<String, Object> arguments = new LinkedHashMap<>();
            arguments.put("operation", operation.name());
            arguments.put("stock_id", 1L);
            tool.execute(ToolArguments.of(DbTool.NAME, arguments));

            assertFalse(template.executed.isEmpty(), operation + " 没有执行任何语句");
            for (String sql : template.executed) {
                assertTrue(sql.stripLeading().toUpperCase(Locale.ROOT).startsWith("SELECT"),
                        operation + " 执行了非 SELECT 语句：" + sql);
            }
        }
    }

    @Test
    @DisplayName("工具描述里明确「没有自由 SQL 入口」—— 这句话是给模型看的边界声明")
    void descriptionStatesTheBoundary() {
        DbTool tool = new DbTool(recordingTemplate().template, new ToolProperties(null, null, null));
        assertTrue(tool.description().contains("只读"), tool.description());
        assertTrue(tool.description().contains("没有自由 SQL"), tool.description());
    }

    @Test
    @DisplayName("工具名与 SPEC 第 9.3 节的四个动作对齐")
    void toolNameIsStable() {
        MonitorTool tool = new DbTool(recordingTemplate().template, new ToolProperties(null, null, null));
        assertEquals("query_db", tool.name());
        assertEquals(List.of("operation", "stock_id", "min_seconds", "top_n", "limit"),
                tool.parameterNames());
    }

    // ================================================================ 记录型替身

    private static Recording recordingTemplate() {
        return new Recording();
    }

    /** 持有 JdbcTemplate 与它执行过的 SQL */
    private static final class Recording {

        private final List<String> executed = new ArrayList<>();

        private final JdbcTemplate template = new JdbcTemplate(new DriverManagerDataSource()) {
            // 【四个重载都要覆写】{@code queryForList(sql)} 与 {@code queryForList(sql, args...)}
            // 在 JdbcTemplate 上是两个不同的方法，只覆写可变参数那个会漏掉「不带参数」的调用 ——
            // 而 connection 类查询恰好都是不带参数的，于是「一条语句都没执行」这条断言
            // 会因为覆写不全而变成假阴性。这一点本身也是本测试想防的形态：
            // 守卫漏了一个分支，看起来还在工作。
            @Override
            public List<Map<String, Object>> queryForList(String sql) {
                executed.add(sql);
                return List.of();
            }

            @Override
            public List<Map<String, Object>> queryForList(String sql, Object... args) {
                executed.add(sql);
                return List.of();
            }

            @Override
            public Map<String, Object> queryForMap(String sql) {
                executed.add(sql);
                return Map.of();
            }

            @Override
            public Map<String, Object> queryForMap(String sql, Object... args) {
                executed.add(sql);
                return Map.of();
            }
        };
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            count++;
            index = text.indexOf(needle, index + needle.length());
        }
        return count;
    }
}
