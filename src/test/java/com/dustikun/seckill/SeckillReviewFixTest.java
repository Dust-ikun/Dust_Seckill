package com.dustikun.seckill;

import com.dustikun.seckill.Common.constant.CompensateType;
import com.dustikun.seckill.Common.constant.SeckillRedisKeys;
import com.dustikun.seckill.Common.result.ReconcileReport;
import com.dustikun.seckill.Common.result.RollbackResult;
import com.dustikun.seckill.Mapper.CompensateTaskMapper;
import com.dustikun.seckill.Mapper.OrderMapper;
import com.dustikun.seckill.Service.CompensateTaskService;
import com.dustikun.seckill.Service.SeckillPersistenceService;
import com.dustikun.seckill.Service.SeckillPersistenceService.PersistOutcome;
import com.dustikun.seckill.Service.StockCacheService;
import com.dustikun.seckill.Service.StockReconcileService;
import com.dustikun.seckill.entity.Order;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 阶段 4 评审问题修复的验收测试。
 * <p>
 * 覆盖评审里被判定为「真实存在」的四条：两种重复的区分、仅回补库存的幂等与标记保留、
 * 补偿失败的持久化重试、以及降级漂移的对账发现与修复。
 * <p>
 * 【为什么不依赖 RocketMQ】这些逻辑都发生在「落库之后」或「补偿 / 对账」环节，与消息中间件无关。
 * 关掉 MQ 只留 MySQL + Redis，测试更快、更稳，也不会因为本机没起 Broker 就整类失败。
 * <p>
 * 【前置条件】需要本机 MySQL 与 Redis 可用；Redis 不可用时整类跳过（而不是假失败）。
 */
@Slf4j
@SpringBootTest
@ActiveProfiles("review")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SeckillReviewFixTest {

    private static final long STOCK_ID = 970001L;
    private static final int INIT_STOCK = 100;

    @Autowired
    private StockCacheService stockCacheService;
    @Autowired
    private SeckillPersistenceService persistenceService;
    @Autowired
    private CompensateTaskService compensateTaskService;
    @Autowired
    private StockReconcileService stockReconcileService;
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private CompensateTaskMapper compensateTaskMapper;
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Redis 是否就绪。@BeforeAll 判定为不可用时，本类全部跳过，此时也不该再动数据库 */
    private boolean redisAvailable;

    @BeforeAll
    void requireRedis() {
        try {
            var connection = redisTemplate.getConnectionFactory().getConnection();
            redisAvailable = "PONG".equalsIgnoreCase(connection.ping());
            connection.close();
        } catch (Exception e) {
            redisAvailable = false;
            log.warn("Redis 不可用（{}），跳过评审修复验收测试", e.getMessage());
        }
        // JUnit 在 @BeforeAll 抛出「假设失败」后仍会执行 @AfterAll，
        // 因此重置/清理动作必须自己判断 Redis 是否可用，否则会因为一次跳过而报出一堆无关错误。
        Assumptions.assumeTrue(redisAvailable, "Redis 未就绪，跳过评审修复验收测试");
    }

    @BeforeEach
    void reset() {
        if (!redisAvailable) {
            return;
        }
        jdbcTemplate.update("INSERT INTO stock(id, name, count) VALUES(?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE count = ?",
                STOCK_ID, "评审修复测试商品", INIT_STOCK, INIT_STOCK);
        jdbcTemplate.update("DELETE FROM orders WHERE stock_id = ?", STOCK_ID);
        jdbcTemplate.update("DELETE FROM compensate_task WHERE stock_id = ?", STOCK_ID);
        stockCacheService.clear(STOCK_ID);
    }

    @AfterAll
    void cleanUp() {
        if (!redisAvailable) {
            return;
        }
        jdbcTemplate.update("DELETE FROM orders WHERE stock_id = ?", STOCK_ID);
        jdbcTemplate.update("DELETE FROM compensate_task WHERE stock_id = ?", STOCK_ID);
        jdbcTemplate.update("DELETE FROM stock WHERE id = ?", STOCK_ID);
        stockCacheService.clear(STOCK_ID);
    }

    // ================================================================ 评审问题 6

    @Test
    @DisplayName("评审#6：落库必须区分「同单号重投」与「同用户重复下单」——两者处置相反")
    void persistShouldDistinguishTwoKindsOfDuplicate() {
        long userId = 800001L;
        String orderNo = "RVT-6-A";

        // 用户正常下单一次：库存 100 -> 99
        assertEquals(PersistOutcome.CREATED,
                persistenceService.persist(orderNo, userId, STOCK_ID, 1));
        assertEquals(INIT_STOCK - 1, dbStock());

        // 情况一：同一条消息被重复投递（MQ 只保证至少一次，这是常态，不是异常）。
        // 库里那条订单就是它，本次 Redis 预扣是【正当】的 —— 绝不能回补，否则库存虚增变超卖。
        assertEquals(PersistOutcome.DUPLICATE,
                persistenceService.persist(orderNo, userId, STOCK_ID, 1),
                "同单号重投必须被识别为 DUPLICATE");
        assertEquals(INIT_STOCK - 1, dbStock(), "重复投递不得再扣一次库存");

        // 情况二：同一用户换了个新单号再来（Redis 已购标记因故丢失才会走到这里）。
        // 库里那笔是【旧订单】，本次 Redis 预扣是多余的 —— 应当回补库存，但标记必须保留。
        // 修复前这两种情况共用一个 DUPLICATE 返回值，调用方必然把其中一种处理错。
        assertEquals(PersistOutcome.USER_ALREADY_BOUGHT,
                persistenceService.persist("RVT-6-B", userId, STOCK_ID, 1),
                "同一用户不同单号必须被识别为 USER_ALREADY_BOUGHT");
        assertEquals(INIT_STOCK - 1, dbStock(), "用户已购也不得再扣库存");
    }

    // ================================================================ 评审问题 6（续）

    @Test
    @DisplayName("评审#6：仅回补库存保留标记，且重复调用不会把库存补多（幂等）")
    void restoreStockOnlyShouldKeepMarkAndBeIdempotent() {
        long userId = 800002L;
        // token 必须每次都不同：它是回补去重键的一部分，而该键带 24 小时 TTL（刻意设计成
        // 「长于任何一次重试窗口」，因此不能靠 clear() 清掉）。若这里写死一个固定单号，
        // 第二次运行测试时去重键还在，首次调用就会直接返回 ALREADY_ROLLED_BACK 而失败——
        // 那是测试没做好隔离，不是功能有问题。
        String orderNo = "RVT-6-C-" + System.nanoTime();

        stockCacheService.preheat(STOCK_ID);
        stockCacheService.tryDeduct(STOCK_ID, userId, 1);
        assertEquals(INIT_STOCK - 1, stockCacheService.remain(STOCK_ID), "预扣应已生效");
        assertTrue(stockCacheService.isBought(STOCK_ID, userId), "预扣同时应打上已购标记");

        // 第一次回补：库存还回去，标记必须留下 —— 用户确实持有订单，不该被重新放行
        RollbackResult first = stockCacheService.restoreStockOnly(STOCK_ID, userId, orderNo, 1);
        assertEquals(RollbackResult.Status.SUCCESS, first.status());
        assertEquals(INIT_STOCK, first.remainStock());
        assertTrue(stockCacheService.isBought(STOCK_ID, userId),
                "仅回补库存绝不能摘掉标记 —— 摘掉会让用户陷入「放行→DB拦截→回补→再放行」的死循环");

        // 第二次（重试 / 人工重复执行）：必须被一次性去重键挡住
        RollbackResult second = stockCacheService.restoreStockOnly(STOCK_ID, userId, orderNo, 1);
        assertEquals(RollbackResult.Status.ALREADY_ROLLED_BACK, second.status());
        assertNull(second.remainStock());
        assertEquals(INIT_STOCK, stockCacheService.remain(STOCK_ID),
                "重复回补绝不能把库存补成 101 —— 那正是「无条件 INCRBY」的经典缺陷");
        assertTrue(stockCacheService.isBought(STOCK_ID, userId), "重复回补也不该动标记");
    }

    // ================================================================ 评审问题 4

    @Test
    @DisplayName("评审#4：补偿失败落表后能被重试任务追回，而不是只留一行日志")
    void failedCompensationShouldBeRecoveredByRetryTask() {
        long userId = 800003L;

        stockCacheService.preheat(STOCK_ID);
        stockCacheService.tryDeduct(STOCK_ID, userId, 1);
        assertEquals(INIT_STOCK - 1, stockCacheService.remain(STOCK_ID));

        // 登记一条待补偿任务，等价于「回补时 Redis 不可用，compensate 失败」的处置。
        // 修复前这一步只写一行 ERROR 日志：没人看日志的时候，这 1 件库存就永久消失了。
        compensateTaskService.enqueue(STOCK_ID, userId, null, 1,
                CompensateType.ROLLBACK_ALL, "单测模拟：回补时 Redis 不可用");
        assertEquals(1, compensateTaskService.countPending(), "待补偿任务应已落表");

        // 必须先把任务「置为已到期」再触发重试，原因见 makeDue() 的注释
        makeDue();

        // 定时任务到点后做的事（这里手动触发一轮，避免依赖真实调度）
        int recovered = compensateTaskService.retryDue(100);

        assertEquals(1, recovered, "重试任务应当把这笔补偿追回来");
        assertEquals(0, compensateTaskService.countPending(), "补偿成功后任务应结案");
        assertEquals(INIT_STOCK, stockCacheService.remain(STOCK_ID), "库存必须被追回，不能永久丢失");
        assertFalse(stockCacheService.isBought(STOCK_ID, userId),
                "完整回滚应摘掉标记，用户必须能重新抢");
    }

    @Test
    @DisplayName("评审#4：待补偿重试是幂等的，不会把库存补第二次")
    void compensateRetryShouldBeIdempotent() {
        long userId = 800004L;
        stockCacheService.preheat(STOCK_ID);
        stockCacheService.tryDeduct(STOCK_ID, userId, 1);

        compensateTaskService.enqueue(STOCK_ID, userId, null, 1,
                CompensateType.ROLLBACK_ALL, "单测模拟：回补失败");
        makeDue();
        compensateTaskService.retryDue(100);
        assertEquals(INIT_STOCK, stockCacheService.remain(STOCK_ID));

        // 再手动补一条同样的任务（模拟重复登记），重试后库存仍必须是 100 而不是 101
        compensateTaskService.enqueue(STOCK_ID, userId, null, 1,
                CompensateType.ROLLBACK_ALL, "单测模拟：重复登记同一笔补偿");
        makeDue();
        compensateTaskService.retryDue(100);

        assertEquals(INIT_STOCK, stockCacheService.remain(STOCK_ID),
                "幂等命中必须被识别为「无需处理」，而不是当成失败继续补");
    }

    // ================================================================ 评审问题 1 / 2

    @Test
    @DisplayName("评审#1：对账能发现「Redis 比数据库多」的降级漂移，并在允许时修复")
    void reconcileShouldDetectAndRepairDegradedDrift() {
        stockCacheService.preheat(STOCK_ID);          // Redis = 100

        // 模拟 Redis 故障期间走降级路径：绕过 Redis，直接扣数据库。
        // 这正是评审第 1 条描述的场景——Redis 完全不知情，补偿逻辑也不会触发（preDeducted=false）。
        jdbcTemplate.update("UPDATE stock SET count = count - 1 WHERE id = ?", STOCK_ID);

        ReconcileReport detected = stockReconcileService.reconcile(STOCK_ID, 0L, false);
        assertEquals(ReconcileReport.Status.REDIS_AHEAD, detected.status(),
                "Redis 库存大于数据库，必然说明有扣减绕过了 Redis");
        assertFalse(detected.repaired(), "repair=false 时只报告、不改数据");

        ReconcileReport repaired = stockReconcileService.reconcile(STOCK_ID, 0L, true);
        assertTrue(repaired.repaired(), "repair=true 时应执行修复");
        assertEquals(INIT_STOCK - 1, stockCacheService.remain(STOCK_ID),
                "修复后 Redis 库存应校准为数据库值，用户不会再看到「抢到了却下单失败」");
    }

    @Test
    @DisplayName("评审#2：对账能发现 Redis 比数据库少（预扣泄漏/少卖）")
    void reconcileShouldDetectRedisBehind() {
        stockCacheService.preheat(STOCK_ID);
        long userId = 800007L;

        // 模拟「命令执行成功但响应超时」的假阴性：Redis 扣了，调用方以为没扣，
        // 于是这笔预扣没人回补也没人落库 —— 库存永久少卖 1 件。
        stockCacheService.tryDeduct(STOCK_ID, userId, 1);

        ReconcileReport report = stockReconcileService.reconcile(STOCK_ID, 0L, false);
        assertEquals(ReconcileReport.Status.REDIS_BEHIND, report.status(),
                "调用方已声明无在途消息，此时 Redis 落后就是预扣泄漏");
        assertNotNull(report.conclusion());
    }

    @Test
    @DisplayName("评审#1：对账能补齐因 Redis 数据丢失而缺失的已购标记")
    void reconcileShouldRestoreMissingBoughtMarks() {
        long buyer = 800005L;
        stockCacheService.preheat(STOCK_ID);

        // 构造「库里有订单、Redis 没有标记」——例如 Redis 被清过、或主从切换丢写
        Order order = new Order();
        order.setOrderNo("RVT-1-D");
        order.setUserId(buyer);
        order.setStockId(STOCK_ID);
        order.setStatus(Order.STATUS_CONFIRMED);
        order.setCreateTime(LocalDateTime.now());
        orderMapper.insert(order);
        jdbcTemplate.update("UPDATE stock SET count = count - 1 WHERE id = ?", STOCK_ID);
        redisTemplate.opsForValue().set(SeckillRedisKeys.stock(STOCK_ID), String.valueOf(INIT_STOCK - 1));

        ReconcileReport report = stockReconcileService.reconcile(STOCK_ID, 0L, true);

        assertEquals(ReconcileReport.Status.MARK_MISSING, report.status());
        assertTrue(report.repaired(), "缺失的标记应当被补齐");
        assertTrue(stockCacheService.isBought(STOCK_ID, buyer),
                "已买到却没留下标记的用户，必须被补回标记，否则他会被 Redis 重新放行");
        assertFalse(stockCacheService.isBought(STOCK_ID, 800006L), "没有订单的用户不该被误标");
    }

    // ================================================================ 对账改进：在途数自动计算

    @Test
    @DisplayName("对账改进：在途数可由 PENDING 预订单自动计算，不再依赖人工声明")
    void reconcileShouldAutoComputeInFlightFromPendingOrders() {
        stockCacheService.preheat(STOCK_ID);          // Redis = 100, stock.count = 100

        // 模拟一笔正常受理的中间态：Redis 已预扣 1 件，orders 里留下一条 PENDING 预订单
        long userId = 800010L;
        stockCacheService.tryDeduct(STOCK_ID, userId, 1);   // Redis = 99
        Order pending = new Order();
        pending.setOrderNo("RVT-AUTO-1");
        pending.setUserId(userId);
        pending.setStockId(STOCK_ID);
        pending.setStatus(Order.STATUS_PENDING);
        pending.setCreateTime(LocalDateTime.now());
        orderMapper.insert(pending);

        // 自动模式：期望值 = 数据库库存(100) − PENDING(1) = 99，与 Redis 相等 → 一致。
        // 在途不再需要人工声明，正常中间态不会被误报。
        ReconcileReport auto = stockReconcileService.reconcile(STOCK_ID, false);
        assertEquals(ReconcileReport.Status.CONSISTENT, auto.status(),
                "在途按数据库 PENDING 自动计算后，受理中的订单不应被误报为不一致");
        assertEquals(ReconcileReport.InFlightSource.AUTO, auto.inFlightSource());
        assertEquals(1, auto.pendingOrders());

        // 对照：同一状态若仍按「无在途」人工声明（0），会被误报成 REDIS_BEHIND——
        // 这正是自动模式要消除的人工判断负担
        ReconcileReport manual = stockReconcileService.reconcile(STOCK_ID, 0L, false);
        assertEquals(ReconcileReport.Status.REDIS_BEHIND, manual.status(),
                "忽略在途时，正常中间态必然被误报——证明 AUTO 判据确实用上了 PENDING");
        assertEquals(ReconcileReport.InFlightSource.MANUAL, manual.inFlightSource());

        // 真泄漏在自动模式下依然可判：Redis 再凭空少 1（没有对应的订单记录）。
        // 期望值 99 已经放宽到「算上全部在途」，仍差 1 → 泄漏，不再是「正常的中间状态」。
        redisTemplate.opsForValue().set(SeckillRedisKeys.stock(STOCK_ID), "98");
        ReconcileReport leak = stockReconcileService.reconcile(STOCK_ID, false);
        assertEquals(ReconcileReport.Status.REDIS_BEHIND, leak.status(),
                "算上全部 PENDING 预订单仍对不上，说明存在绕过订单记录的预扣泄漏");
        assertTrue(leak.conclusion().contains("泄漏"),
                "AUTO 模式的结论应直接指认泄漏，而不是模棱两可的「中间状态」");
    }

    // ================================================================ 评审问题 5 / 7

    @Test
    @DisplayName("评审#5/#7：tryDeduct 契约无歧义，补偿异常会带出独立错误码")
    void contractsShouldBeUnambiguousAfterFix() {
        stockCacheService.preheat(STOCK_ID);

        // #5：tryDeduct 收敛为 void。业务拒绝一律抛 BizException，
        // 不存在「返回 false 之后还能继续往下走」的歧义路径。
        // 这里用「库存不足」这个业务拒绝来验证：它必须抛异常，而不是安静地返回。
        jdbcTemplate.update("UPDATE stock SET count = 0 WHERE id = ?", STOCK_ID);
        redisTemplate.opsForValue().set(SeckillRedisKeys.stock(STOCK_ID), "0");

        var rejected = assertThrows(com.dustikun.seckill.Common.Exception.BizException.class,
                () -> stockCacheService.tryDeduct(STOCK_ID, 800009L, 1),
                "业务拒绝必须抛异常，不能靠返回值表达");
        assertEquals("1000", rejected.getCode(), "库存不足应返回业务错误码而非继续落库");

        // #7：补偿失败必须能被上游识别（COMPENSATE_FAILED=5002），且原始异常作为 cause 保留。
        // 这里只验证错误码定义存在且与业务错误码不同——真实触发需要 Redis 在回补中途失效。
        assertNotEquals(
                com.dustikun.seckill.Common.Exception.ErrorCode.REPEAT_ORDER.getCode(),
                com.dustikun.seckill.Common.Exception.ErrorCode.COMPENSATE_FAILED.getCode());
    }

    // ------------------------------------------------------------------ helpers

    /**
     * 把本测试商品的待补偿任务全部置为「已到期」。
     * <p>
     * 【为什么必须有这一步】{@code next_retry_time} 的列类型是 {@code DATETIME(0)}（没有小数秒），
     * 而 MySQL 对小数秒是<b>四舍五入</b>而不是截断：
     * <pre>
     *   CAST('22:18:11.922' AS DATETIME) → '22:18:12'   ← 向上进位到下一秒
     *   CAST('22:18:11.400' AS DATETIME) → '22:18:11'
     * </pre>
     * {@code enqueue()} 写入的是「当下时刻 + first-retry-delay-seconds」，因此即使把延迟配成 0，
     * 落库后也可能被进位到<b>下一秒</b>；紧接着同一秒内调用 {@code retryDue()}
     * （判据 {@code next_retry_time <= now}）就会一条都捞不到，测试随即表现为
     * 「补偿没有被追回」这种极具误导性的假失败。
     * <p>
     * 生产配置下这不是问题（延迟 10s、扫描间隔 30s，最多 1s 的进位无害，且「向上」正好符合
     * 「不早于」的重试语义），所以修的是测试的<b>前置条件表述</b>：
     * 直接声明「这批任务已经到期」，不再依赖亚秒级的时序运气。
     */
    private void makeDue() {
        jdbcTemplate.update("UPDATE compensate_task SET next_retry_time = DATE_SUB(NOW(), INTERVAL 1 SECOND) "
                + "WHERE stock_id = ?", STOCK_ID);
    }

    private int dbStock() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count FROM stock WHERE id = ?", Integer.class, STOCK_ID);
        return count == null ? -1 : count;
    }
}
