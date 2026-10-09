package com.dustikun.seckill.monitor.tool.db;

import com.dustikun.seckill.monitor.tool.MonitorTool;
import com.dustikun.seckill.monitor.tool.ToolArguments;
import com.dustikun.seckill.monitor.tool.ToolResult;
import com.dustikun.seckill.monitor.tool.ToolSchema;
import com.dustikun.seckill.monitor.tool.ToolProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DB Tool（SPEC 第 9.3 节）：{@code get_slow_sql} / {@code get_db_connections} /
 * {@code get_transaction_status} / {@code get_business_statistics}，<b>默认且只能只读</b>。
 *
 * <h2>「结构性只读」体现在哪一行代码上</h2>
 * <p>
 * 就在下面这个签名里：{@link #execute(ToolArguments)} 拿到的参数<b>没有任何一种能表达 SQL</b>。
 * Agent 只能给一个 {@link Operation} 枚举名和几个数字/ID，而枚举到 SQL 的映射
 * 写在 {@link ReadOnlyQuery} 里、是编译期常量。因此：
 * <pre>
 *   传给本工具 "UPDATE orders SET status='CONFIRMED'"  →  参数校验阶段就被拒绝
 *                                                        （operation 不是合法枚举值）
 *   传给本工具 stockId="1; DROP TABLE orders"          →  参数校验拒绝（不是数字）
 *   它<b>连一个能接收 SQL 字符串的参数都没有</b>          →  没有第二条路径
 * </pre>
 * 这正是 SPEC 第 24 节安全项「Agent 不拥有 DB 写权限」所要求的形态：
 * 权限不是靠「我们检查了它没有写」，而是靠「这条路上不存在写的能力」。
 *
 * <h2>三道额外闸门</h2>
 * <ol>
 *   <li><b>独立的 JdbcTemplate</b>：{@code monitorReadOnlyJdbcTemplate} 与业务用的那个不是同一个实例，
 *       它上面设了 {@code queryTimeout} 与 {@code maxRows}（见 {@code MonitorToolConfiguration}）。
 *       隔离的意义是：调监控的读超时不会影响业务查询；</li>
 *   <li><b>行数上限</b>：所有带列表的语句都有 {@code LIMIT ?}，值取配置里的 {@code db.max-rows}；</li>
 *   <li><b>不返回原始行</b>：{@code trx_query} 这类字段是 SQL 原文，含订单号等业务值，
 *       它会被 {@code ResultShaper} 统一脱敏（16~19 位连续数字按银行卡规则处理）——
 *       因此「Agent 看不到完整单号」是设计如此，不是缺陷。</li>
 * </ol>
 *
 * <h2>为什么 {@code performance_schema} 查不到时不算失败</h2>
 * <p>
 * {@code performance_schema} 与 {@code information_schema.INNODB_TRX} 都可能因为
 * 权限或配置而不可用（前者需要 {@code performance_schema=ON}，后者需要 PROCESS 权限）。
 * 这时返回 {@link ToolStatus#FAILED} 会让 Agent 以为「数据库出问题了」，
 * 而事实是「这条证据线在本环境下不可用」—— 两者对诊断方向的影响完全相反。
 * 因此查询失败时返回成功 + 明确的 note + {@code available=false}。
 * 唯一例外是连接本身不可用（那是真的数据库故障，值得 FAILED）。
 */
public final class DbTool implements MonitorTool {

    private static final Logger log = LoggerFactory.getLogger(DbTool.class);

    /** 工具名 */
    public static final String NAME = "query_db";

    /** SPEC 第 9.3 节列的四个只读动作 */
    public enum Operation {
        GET_SLOW_SQL,
        GET_DB_CONNECTIONS,
        GET_TRANSACTION_STATUS,
        GET_BUSINESS_STATISTICS
    }

    /** 慢 SQL 的门槛下限，防止模型传 0 把全部语句都捞出来 */
    private static final double MIN_SLOW_SECONDS = 0.01;

    private final JdbcTemplate jdbcTemplate;

    private final ToolProperties properties;

    public DbTool(JdbcTemplate jdbcTemplate, ToolProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties.normalized();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "只读查询数据库的运行状态与业务统计（SPEC 的 get_slow_sql / get_db_connections / "
                + "get_transaction_status / get_business_statistics）。"
                + "operation=GET_SLOW_SQL 看按语句摘要聚合的慢查询；"
                + "GET_DB_CONNECTIONS 看连接与线程；"
                + "GET_TRANSACTION_STATUS 看长事务、锁等待与「谁在等谁」（判断行锁争用）；"
                + "GET_BUSINESS_STATISTICS 看订单/Outbox/补偿任务的行数。"
                + "本工具没有自由 SQL 入口 —— 只能选 operation，且全部为只读语句。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        Map<String, Object> properties = new LinkedHashMap<>(8);
        properties.put("operation", ToolSchema.enumeration(
                "要执行的只读查询。GET_SLOW_SQL=慢查询排行；GET_DB_CONNECTIONS=连接与线程；"
                        + "GET_TRANSACTION_STATUS=长事务与锁等待；GET_BUSINESS_STATISTICS=业务表行数",
                java.util.Arrays.stream(Operation.values()).map(Enum::name).toList()));
        properties.put("stock_id", ToolSchema.integer(
                "活动 ID。只对 GET_BUSINESS_STATISTICS 有意义：给了它就只统计该活动，"
                        + "不给则统计全表（会把历史测试数据一起算进来）", 1, Long.MAX_VALUE));
        properties.put("min_seconds", ToolSchema.number(
                "只对 GET_SLOW_SQL 有意义：平均耗时门槛（秒），默认 "
                        + this.properties.db().slowSqlMinSeconds(), MIN_SLOW_SECONDS, 600d));
        properties.put("top_n", ToolSchema.integer(
                "只对 GET_SLOW_SQL 有意义：返回条数，默认 " + this.properties.db().slowSqlTopN(),
                1, 100));
        properties.put("limit", ToolSchema.integer(
                "只对 GET_TRANSACTION_STATUS 有意义：事务/锁等待明细最多返回多少条，默认 "
                        + this.properties.db().maxRows(), 1, 500));
        return ToolSchema.object(properties, "operation");
    }

    @Override
    public ToolResult execute(ToolArguments args) {
        Operation operation = args.enumOrDefault("operation", Operation.class,
                Operation.GET_TRANSACTION_STATUS, List.of(Operation.values()));
        List<String> notes = new ArrayList<>(4);
        Map<String, Object> data = new LinkedHashMap<>(10);
        data.put("operation", operation.name());
        int maxRows = properties.db().maxRows();

        try {
            switch (operation) {
                case GET_SLOW_SQL -> slowSql(args, data, notes);
                case GET_DB_CONNECTIONS -> connections(data, notes);
                case GET_TRANSACTION_STATUS -> transactions(args, data, notes, maxRows);
                case GET_BUSINESS_STATISTICS -> businessStatistics(args, data, notes);
            }
        } catch (DataAccessException e) {
            // 见类注释「为什么查不到不算失败」：区分「连接层不可用」与「这张系统表查不了」。
            String message = rootMessage(e);
            if (isConnectivityFailure(message)) {
                return ToolResult.failed(name(), "数据库连接不可用：" + message,
                        List.of("这是明确的数据库故障信号，请优先排查它，而不是继续收集指标证据。"));
            }
            notes.add("该查询在本环境下不可用（" + message + "）。这通常意味着 "
                    + "performance_schema 未启用，或当前账号缺少 PROCESS 权限 —— "
                    + "它**不代表数据库有异常**，请改用其它证据来源。");
            data.put("available", false);
        }
        return ToolResult.ok(name(), data, notes);
    }

    // ================================================================ 四个动作

    private void slowSql(ToolArguments args, Map<String, Object> data, List<String> notes) {
        double minSeconds = args.doubleOrDefault("min_seconds",
                properties.db().slowSqlMinSeconds(), MIN_SLOW_SECONDS, 600d);
        int topN = args.intOrDefault("top_n", properties.db().slowSqlTopN(), 1, 100);

        // AVG_TIMER_WAIT 的单位是皮秒（performance_schema 的通用规则），
        // 因此门槛要乘回皮秒再比较 —— 在 SQL 里做除法会让索引用不上。
        long thresholdPicoseconds = (long) (minSeconds * 1_000_000_000_000d);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                ReadOnlyQuery.SLOW_SQL.sql(), thresholdPicoseconds, topN);

        data.put("minSeconds", minSeconds);
        data.put("topN", topN);
        data.put("slowQueries", rows);
        data.put("count", rows.size());
        if (rows.isEmpty()) {
            notes.add("没有平均耗时超过 " + minSeconds + "s 的语句摘要。"
                    + "注意 performance_schema 只保留最近一段时间的聚合，"
                    + "且它在 MySQL 重启后会清空 —— 空结果不等于从来没有慢查询。");
        } else {
            notes.add("按平均耗时降序。rows_examined 远大于 rows_sent 说明索引没走对，"
                    + "比「加缓存」更值得先处理。");
        }
    }

    private void connections(Map<String, Object> data, List<String> notes) {
        List<Map<String, Object>> status = jdbcTemplate.queryForList(ReadOnlyQuery.DB_CONNECTIONS.sql());
        Map<String, Object> limits = jdbcTemplate.queryForMap(ReadOnlyQuery.MAX_CONNECTIONS.sql());

        // 把「名字 → 值」摊平：模型读一个 map 比读一个数组准确得多（它不必自己找哪一行是哪一项）
        Map<String, Object> metrics = new LinkedHashMap<>(status.size() * 2);
        for (Map<String, Object> row : status) {
            metrics.put(String.valueOf(row.get("metric")), row.get("value"));
        }
        data.put("serverStatus", metrics);
        data.put("serverLimits", limits);

        // 使用率：SPEC 第 6.1 节的 db_connection_pool 在服务端的对应量。
        // 服务端与 Hikari 池是两个层次，两者都要看：池满而服务端空闲说明只是配置保守，
        // 服务端吃满则是真的并发打满。
        Long maxConnections = toLong(limits.get("max_connections"));
        Long threadsConnected = toLong(metrics.get("Threads_connected"));
        if (maxConnections != null && maxConnections > 0 && threadsConnected != null) {
            data.put("connectionUsage", round((double) threadsConnected / maxConnections));
        }
        notes.add("这里的连接数是**服务端视角**（所有客户端之和）；"
                + "应用自身的连接池使用率请看 query_metric 的 db_pool_usage。"
                + "两者不一致时，通常是有别的客户端（例如手工 mysql 会话）连着。");
    }

    private void transactions(ToolArguments args, Map<String, Object> data, List<String> notes, int maxRows) {
        int limit = args.intOrDefault("limit", maxRows, 1, 500);
        Map<String, Object> summary = jdbcTemplate.queryForMap(ReadOnlyQuery.LOCK_WAIT_COUNT.sql());
        data.put("summary", summary);

        List<Map<String, Object>> transactions =
                jdbcTemplate.queryForList(ReadOnlyQuery.TRANSACTIONS.sql(), limit);
        data.put("transactions", transactions);
        data.put("transactionCount", transactions.size());

        List<Map<String, Object>> waits =
                jdbcTemplate.queryForList(ReadOnlyQuery.LOCK_WAITS.sql(), limit);
        data.put("lockWaits", waits);

        Long currentWaits = toLong(summary.get("current_waits"));
        Long over5s = toLong(summary.get("transactions_over_5s"));
        if (currentWaits != null && currentWaits > 0) {
            notes.add("当前有 " + currentWaits + " 个锁等待。lockWaits 里给出了等待方与被等待方的"
                    + "线程号与各自的 SQL —— 这是唯一能回答「谁堵住了谁」的证据。");
        }
        if (over5s != null && over5s > 0) {
            notes.add("有 " + over5s + " 个事务已运行超过 5 秒。本项目的 stock 行是热点"
                    + "（每单确认都要抢同一行的排他锁），因此长事务会直接表现为受理吞吐下降。");
        }
        if (transactions.isEmpty() && (currentWaits == null || currentWaits == 0)) {
            notes.add("当前没有活动事务，也没有锁等待 —— 这是健康的排空态。"
                    + "若接口仍然慢，瓶颈不在数据库的行锁上，请转查 Redis 与 GC（query_metric "
                    + "的 redis_command_latency_p99 / jvm_gc_pause_rate）。");
        }
        notes.add("trx_query 是 SQL 原文，其中的长数字（含单号）已被统一脱敏；"
                + "这是刻意的，不要把它当作数据缺失。");
    }

    private void businessStatistics(ToolArguments args, Map<String, Object> data, List<String> notes) {
        Long stockId = args.optionalLong("stock_id", 1L, Long.MAX_VALUE);
        List<Map<String, Object>> rows;
        if (stockId == null) {
            rows = jdbcTemplate.queryForList(ReadOnlyQuery.BUSINESS_STATISTICS.sql());
            notes.add("未指定 stock_id，统计的是**全表**——本机库里有历史测试数据，"
                    + "数字会偏大。要排查具体活动请传 stock_id。");
        } else {
            rows = jdbcTemplate.queryForList(ReadOnlyQuery.BUSINESS_STATISTICS_BY_STOCK.sql(),
                    stockId, stockId, stockId);
            data.put("stockId", stockId);
        }
        data.put("tables", rows);
        data.put("count", rows.size());

        // 把「在途预扣」这个关键数直接算出来，它是本项目的核心不变量之一
        for (Map<String, Object> row : rows) {
            if ("orders".equals(row.get("table_name")) && "PENDING".equals(row.get("status"))) {
                data.put("pendingOrders", row.get("row_count"));
                notes.add("PENDING 订单 " + row.get("row_count")
                        + " 条 = 数据库视角的在途预扣。它使 Redis 与数据库的库存差值成为**正常现象**，"
                        + "对账正是按「数据库库存 − 可信在途」来判定的。");
            }
        }
    }

    // ================================================================ 辅助

    /**
     * 判断异常是否属于「连接层面不可用」。
     * <p>刻意用字符串匹配 —— 这里判断的是<b>要不要报 FAILED</b>，
     * 而 {@code DataAccessException} 的层次在 Spring 6/7 之间有过调整，
     * 按类型判断会让这一处随框架升级而静默改变行为。
     * 匹配不上时落到「证据不可用」这一侧：那是更保守的选择
     * （把一个真故障报成「环境不支持」，比把一个环境限制报成故障的代价小）。
     */
    private static boolean isConnectivityFailure(String message) {
        String lower = message == null ? "" : message.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("communications link failure")
                || lower.contains("connection refused")
                || lower.contains("unable to acquire jdbc connection")
                || lower.contains("data source rejected establishment")
                || lower.contains("too many connections");
    }

    private static String rootMessage(Throwable e) {
        Throwable current = e;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null ? current.getClass().getSimpleName() : message;
    }

    private static Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double round(double value) {
        return Math.round(value * 1_000_000d) / 1_000_000d;
    }
}
