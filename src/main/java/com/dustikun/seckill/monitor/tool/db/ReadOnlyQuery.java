package com.dustikun.seckill.monitor.tool.db;

import java.util.List;

/**
 * DB Tool 能执行的<b>全部</b>语句（SPEC 第 9.3 节：默认只读，禁止自由 SQL）。
 *
 * <h2>这不是「一份 SQL 清单」，而是「唯一性保证」</h2>
 * <p>
 * SPEC 的验收判据原话是：<b>传入任何 UPDATE / DELETE 被「结构性」拒绝（不是字符串匹配）</b>。
 * 这两者的区别是本质的：
 * <pre>
 *   ✗ 字符串匹配：拿到一段 SQL，检查它有没有以 UPDATE 开头 / 是否含 "DELETE"。
 *       它永远列不全（注释、多语句、CTE、大小写、Unicode 空白都能绕），
 *       而且每漏一种就是一个可写通道。
 *   ✓ 结构性拒绝：接口<b>根本不接受 SQL</b>。Agent 只能给出一个枚举名，
 *       而枚举的取值在编译期就定死了。它无论传什么参数，
 *       能被执行的都是下面这 8 条常量里的某一条，且都是 SELECT / SHOW 类只读语句。
 * </pre>
 * 换句话说：这里的防护不依赖「检查得够不够严」，而依赖
 * 「<b>写操作在类型系统里没有表达方式</b>」—— 因此它不可能因为某个正则没写全面失效。
 *
 * <h2>为什么参数用占位符而不是拼进 SQL</h2>
 * <p>
 * 同样的理由。{@code LIMIT ?} 这样的占位符让「参数」永远是<b>值</b>而不是<b>语法</b>；
 * 一旦允许把 {@code stockId} 拼进字符串，就等于重新开了一个自由 SQL 的入口
 * （{@code stockId="1 UNION SELECT ..."}）。因此本枚举里的 SQL
 * <b>没有一个字符来自调用方</b>。
 *
 * <h2>每条语句为什么值得存在</h2>
 * <p>
 * SPEC 第 9.3 节点了四件事（慢 SQL / 连接数 / 事务状态 / 业务统计）。
 * 下面把它们落成 8 条语句，是因为「事务状态」这一个问题需要三条
 * （长事务、锁等待、锁等待的双方）才能回答 —— 只看长事务列表会在
 * 「一个短事务卡在锁上、后面排了一串」这种最常见的形态上给出错误结论。
 */
public enum ReadOnlyQuery {

    /**
     * 慢 SQL 排行。
     * <p>数据来自 {@code performance_schema.events_statements_summary_by_digest}：
     * 它按「语句摘要」（把常量替换成 {@code ?}）聚合，因此天生就是「有哪几种慢查询」
     * 而不是「哪几次慢查询」—— 后者才是排查时需要的形态。
     * <p>取 {@code AVG_TIMER_WAIT} 而不是 {@code SUM_TIMER_WAIT} 排序：
     * 一条执行了 10 万次的 10ms 查询，与一条执行了 3 次的 300ms 查询，
     * 谁更值得优化取决于目的；但「慢」的字面含义是单次耗时，因此默认按平均值排，
     * 同时把两个数都给出来（见 {@link #columns()}）。
     * <p>{@code ps} 前缀是 {@code performance_schema} 的缩写，只用于日志与文档。
     */
    SLOW_SQL("""
            SELECT DIGEST_TEXT                       AS digest_text,
                   COUNT_STAR                       AS exec_count,
                   AVG_TIMER_WAIT / 1000000000000   AS avg_seconds,
                   MAX_TIMER_WAIT / 1000000000000   AS max_seconds,
                   SUM_ROWS_EXAMINED                AS rows_examined,
                   SUM_ROWS_SENT                    AS rows_sent,
                   SUM_NO_INDEX_USED                AS no_index_used,
                   FIRST_SEEN                       AS first_seen,
                   LAST_SEEN                        AS last_seen
            FROM performance_schema.events_statements_summary_by_digest
            WHERE SCHEMA_NAME = 'seckill'
              AND DIGEST_TEXT IS NOT NULL
              AND AVG_TIMER_WAIT >= ?
            ORDER BY AVG_TIMER_WAIT DESC
            LIMIT ?
            """,
            List.of("avg_seconds_min", "limit"),
            "按语句摘要聚合的慢查询排行（performance_schema）。"
                    + "rows_examined 远大于 rows_sent 说明索引没走对；"
                    + "no_index_used > 0 是明确的「该加索引」信号"),

    /** 数据库服务端的连接与线程现状。用 performance_schema 而不是 {@code SHOW STATUS}，避免依赖输出格式 */
    DB_CONNECTIONS("""
            SELECT VARIABLE_NAME  AS metric,
                   VARIABLE_VALUE AS value
            FROM performance_schema.global_status
            WHERE VARIABLE_NAME IN ('Threads_connected',
                                    'Threads_running',
                                    'Threads_created',
                                    'Max_used_connections',
                                    'Aborted_connects',
                                    'Aborted_clients',
                                    'Connections',
                                    'Innodb_row_lock_current_waits',
                                    'Innodb_row_lock_waits',
                                    'Innodb_row_lock_time_avg')
            ORDER BY VARIABLE_NAME
            """,
            List.of(),
            "连接与线程现状。Threads_running 持续接近上限说明并发已经打满；"
                    + "Innodb_row_lock_waits 的增长速率对应本项目已知的 stock 行锁瓶颈"),

    /** 连接数上限。与 {@link #DB_CONNECTIONS} 一起才能算出使用率 */
    MAX_CONNECTIONS("""
            SELECT @@max_connections          AS max_connections,
                   @@innodb_lock_wait_timeout AS innodb_lock_wait_timeout,
                   @@transaction_isolation    AS transaction_isolation,
                   @@long_query_time          AS long_query_time,
                   @@slow_query_log           AS slow_query_log
            """,
            List.of(),
            "服务端上限与超时参数。innodb_lock_wait_timeout 是「等锁多久就放弃」——"
                    + "它远小于应用侧的超时时间，因此锁等待的表现是数据库报错而不是慢慢变慢"),

    /** 当前事务与长事务。按开始时间升序 → 最老的事务排最前 */
    TRANSACTIONS("""
            SELECT trx_id                                        AS trx_id,
                   trx_state                                     AS state,
                   trx_started                                   AS started,
                   TIMESTAMPDIFF(SECOND, trx_started, NOW())     AS age_seconds,
                   trx_mysql_thread_id                           AS thread_id,
                   trx_rows_locked                               AS rows_locked,
                   trx_rows_modified                             AS rows_modified,
                   trx_tables_locked                             AS tables_locked,
                   trx_isolation_level                           AS isolation_level,
                   trx_query                                     AS current_query
            FROM information_schema.INNODB_TRX
            ORDER BY trx_started ASC
            LIMIT ?
            """,
            List.of("limit"),
            "当前活动事务。age_seconds 大且 rows_locked 多 = 长事务持有锁，"
                    + "它会拖慢所有碰同一行的请求"),

    /**
     * 锁等待的双方。
     * <p>【为什么必须有这一条】「有锁等待」与「谁在等谁」是两个问题，
     * 而只有后者能给出可行动的建议（该杀哪个事务 / 该优化哪条语句）。
     * MySQL 8 的表名是 {@code performance_schema.data_lock_waits}
     * （5.7 的 {@code INNODB_LOCK_WAITS} 已被移除），这一点写在这里避免将来照抄旧资料。
     */
    LOCK_WAITS("""
            SELECT w.REQUESTING_ENGINE_TRANSACTION_ID  AS waiting_trx_id,
                   w.BLOCKING_ENGINE_TRANSACTION_ID    AS blocking_trx_id,
                   w.REQUESTING_THREAD_ID              AS waiting_thread,
                   w.BLOCKING_THREAD_ID                AS blocking_thread,
                   t.trx_started                       AS blocking_started,
                   TIMESTAMPDIFF(SECOND, t.trx_started, NOW()) AS blocking_age_seconds,
                   t.trx_query                         AS blocking_query,
                   r.trx_query                         AS waiting_query
            FROM performance_schema.data_lock_waits w
                     LEFT JOIN information_schema.INNODB_TRX t
                               ON t.trx_id = w.BLOCKING_ENGINE_TRANSACTION_ID
                     LEFT JOIN information_schema.INNODB_TRX r
                               ON r.trx_id = w.REQUESTING_ENGINE_TRANSACTION_ID
            LIMIT ?
            """,
            List.of("limit"),
            "锁等待的等待方与被等待方。waiting_thread 与 blocking_thread 可直接用于人工处置"),

    /** 锁等待的规模。它是「现在有多堵」的一个数，比逐条看更早暴露问题 */
    LOCK_WAIT_COUNT("""
            SELECT (SELECT COUNT(*) FROM performance_schema.data_lock_waits)   AS current_waits,
                   (SELECT COUNT(*) FROM information_schema.INNODB_TRX)        AS active_transactions,
                   (SELECT COUNT(*) FROM information_schema.INNODB_TRX
                     WHERE TIMESTAMPDIFF(SECOND, trx_started, NOW()) > 5)      AS transactions_over_5s
            """,
            List.of(),
            "锁等待规模与长事务个数。这三个数一起看才能区分"
                    + "「很多短事务排队」与「一个长事务堵住所有人」"),

    /**
     * 业务表统计（SPEC 第 9.3 节的 {@code get_business_statistics}）。
     * <p>【它与 Business Tool 的区别】这里回答的是「<b>数据库里的事实</b>」，
     * 而 Business Tool 回答的是「<b>业务链路的状态</b>」（含 Redis 侧库存与对账结论）。
     * 前者是后者的输入之一，因此两者都要有：只看 Redis 会漏掉「降级路径直接写了库」，
     * 只看库会漏掉「Redis 预扣了但订单还没落库」（那是正常的在途）。
     */
    BUSINESS_STATISTICS("""
            SELECT 'orders' AS table_name, status, COUNT(*) AS row_count
            FROM orders
            GROUP BY status
            UNION ALL
            SELECT 'seckill_outbox', status, COUNT(*)
            FROM seckill_outbox
            GROUP BY status
            UNION ALL
            SELECT 'compensate_task', status, COUNT(*)
            FROM compensate_task
            GROUP BY status
            ORDER BY table_name, status
            """,
            List.of(),
            "订单 / Outbox / 补偿任务按状态的行数。PENDING 的 orders 行就是「在途预扣」的数据库事实"),

    /**
     * 业务表统计，限定到某个活动（stockId）。
     * <p>【为什么值得单独一条】SPEC 第 19 节的 Case 5（Redis/MySQL 库存不一致）
     * 与 Case 4（Outbox 积压）都是<b>针对某个活动</b>的问题，
     * 而全表统计会把别的活动（含压测影子数据）混进来，让「有多少单卡住」这个数失去意义。
     * 本项目的库里有历史测试数据（见批次 1 健康基线 §5），这一点尤其重要。
     */
    BUSINESS_STATISTICS_BY_STOCK("""
            SELECT 'orders' AS table_name, status, COUNT(*) AS row_count
            FROM orders
            WHERE stock_id = ?
            GROUP BY status
            UNION ALL
            SELECT 'seckill_outbox', status, COUNT(*)
            FROM seckill_outbox
            WHERE stock_id = ?
            GROUP BY status
            UNION ALL
            SELECT 'compensate_task', status, COUNT(*)
            FROM compensate_task
            WHERE stock_id = ?
            GROUP BY status
            ORDER BY table_name, status
            """,
            List.of("stock_id", "stock_id", "stock_id"),
            "限定到某个活动的业务表统计。排查单个活动时应优先用它，"
                    + "全表统计会把历史测试数据一起算进来");

    private final String sql;

    private final List<String> parameters;

    private final String description;

    ReadOnlyQuery(String sql, List<String> parameters, String description) {
        this.sql = sql;
        this.parameters = parameters;
        this.description = description;
    }

    /** 固定的 SQL 文本。**永远不包含调用方提供的任何字符** */
    public String sql() {
        return sql;
    }

    /** 参数名清单（按占位符顺序），用于说明与断言 */
    public List<String> parameters() {
        return parameters;
    }

    /** 给人看的说明：这条语句回答什么问题 */
    public String description() {
        return description;
    }
}
