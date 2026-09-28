package com.dustikun.seckill;

import com.dustikun.seckill.Common.Exception.BizException;
import com.dustikun.seckill.Common.Exception.ErrorCode;
import com.dustikun.seckill.Common.result.RollbackResult;
import com.dustikun.seckill.Common.result.SeckillOrderResponse;
import com.dustikun.seckill.Service.SeckillService;
import com.dustikun.seckill.Service.StockCacheService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 补偿链路验收：消费端重试耗尽后必须取消订单并归还 Redis 预扣，且归还本身必须幂等。
 * <p>
 * 【迁移第二步之后的语义变化】订单现在在请求线程就已经落库，因此「落库失败」不再等于
 * 「没有订单」：重试耗尽时先把订单置为 CANCELLED，确认取消成功之后才归还预扣，
 * 且归还时<b>保留</b>已购标记（标记与那条订单行是同一件事的两种表述）。
 * 见 {@code shouldCancelOrderAndRestoreRedisWhenConfirmNeverSucceeds} 的断言与注释。
 * <p>
 * 【为什么用一个独立的 profile 把 max-reconsume-times 覆盖成 1】
 * RocketMQ 的重试是带退避的（首次重试要等 10 秒，之后 30 秒、1 分钟……），
 * 若沿用默认的 3 次，跑完一条「重试耗尽」的链路需要上百秒，无法放进单元测试。
 * 覆盖成 1 之后，第一次消费失败即视为重试耗尽，走的是<b>完全相同</b>的那段回补代码，
 * 只是省掉了等待时间。
 * <p>
 * 【重要】profile 必须与其它测试类保持一致（都用 test）。Spring 的上下文缓存以配置组合为键，
 * 只要有一个测试类用了不同配置就会多出一份上下文，而每个上下文都会启动一个 RocketMQ 消费者 ——
 * 同一个消费组里出现两个消费者实例时 Broker 会把队列对半分，任何依赖消费进度的断言都会假失败。
 * <p>
 * 【断言依据】这里的等待条件是「Redis 库存与用户标记是否复原」这类外部可观测状态，
 * 而不是消费端的内部计数器——后者只是某个消费者实例的局部视图。
 */
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SeckillCompensationTest {

    private static final long STOCK_ID = 950001L;
    private static final int INIT_STOCK = 100;
    private static final long ASYNC_TIMEOUT_MS = 60_000;

    @Autowired
    private SeckillService seckillService;
    @Autowired
    private StockCacheService stockCacheService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private void resetStock() {
        jdbcTemplate.update("INSERT INTO stock(id, name, count) VALUES(?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE count = ?",
                STOCK_ID, "补偿链路测试商品", INIT_STOCK, INIT_STOCK);
        jdbcTemplate.update("DELETE FROM orders WHERE stock_id = ?", STOCK_ID);
        jdbcTemplate.update("DELETE FROM seckill_outbox WHERE stock_id = ?", STOCK_ID);
        seckillService.clear(STOCK_ID);
        seckillService.preheat(STOCK_ID);
    }

    @AfterAll
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM orders WHERE stock_id = ?", STOCK_ID);
        jdbcTemplate.update("DELETE FROM seckill_outbox WHERE stock_id = ?", STOCK_ID);
        jdbcTemplate.update("DELETE FROM stock WHERE id = ?", STOCK_ID);
        seckillService.clear(STOCK_ID);
    }

    @Test
    @Order(1)
    @DisplayName("重试耗尽：先取消订单、再归还预扣；订单必须留下 CANCELLED 痕迹且标记保留")
    void shouldCancelOrderAndRestoreRedisWhenConfirmNeverSucceeds() throws Exception {
        // 先按正常库存预热（Redis = 100），再把库内库存改成 0，
        // 人为制造「Redis 有货、DB 无货」的不一致，让消费端的条件扣减必然失败
        resetStock();
        jdbcTemplate.update("UPDATE stock SET count = 0 WHERE id = ?", STOCK_ID);

        long userId = 88_888L;
        SeckillOrderResponse accepted = seckillService.seckill(userId, STOCK_ID);
        assertEquals(SeckillOrderResponse.Status.QUEUED, accepted.status());
        assertEquals(INIT_STOCK - 1, seckillService.remain(STOCK_ID), "Redis 预扣应当已生效");
        // 迁移第二步：接口返回时预订单已经落库（PENDING），库存仍未被扣
        assertEquals(1, queryInt("SELECT COUNT(*) FROM orders WHERE stock_id = ? AND status = 'PENDING'",
                STOCK_ID), "受理即写预订单");

        // 等待消费端重试耗尽并完成「取消订单 + 归还预扣」：Redis 余量必须回到 100
        awaitRemain(INIT_STOCK, ASYNC_TIMEOUT_MS);

        // 订单不会被删除，而是留下 CANCELLED 痕迹 —— 归档与事后追溯都需要它
        assertEquals(1, queryInt("SELECT COUNT(*) FROM orders WHERE stock_id = ? AND status = 'CANCELLED'",
                        STOCK_ID),
                "确认最终失败时必须把订单置为 CANCELLED，而不是删除或留在 PENDING");

        // 【与阶段 4 的差异，刻意为之】归还预扣时【保留】标记。
        // 标记与「该用户在 orders 里有一行（哪怕是 CANCELLED）」是同一件事的两种表述：
        // 摘掉标记等于亲手制造一条 MARK_MISSING 不一致，对账随后会把标记再加回来。
        // 代价是该用户不能重抢（少卖），与项目「宁可少卖，绝不超卖」的一贯取向一致。
        assertTrue(hasBoughtMark(userId),
                "标记必须保留：它对应一条真实存在的订单行，摘掉会造成 redis/DB 语义分裂");
        assertEquals(SeckillOrderResponse.Status.FAILED,
                seckillService.queryOrder(userId, STOCK_ID, accepted.orderNo()).status(),
                "订单已 CANCELLED，查询必须返回 FAILED 而不是一直 QUEUED");

        System.out.printf("[验收] 重试耗尽 -> 订单 CANCELLED、Redis 余量从 %d 回到 %d、标记保留%n",
                INIT_STOCK - 1, seckillService.remain(STOCK_ID));
    }

    @Test
    @Order(3)
    @DisplayName("请求线程拦截用户已购：撞 uk_user_stock 当场归还预扣并拒绝，不再绕整圈")
    void requestThreadShouldRejectAlreadyBoughtUser() {
        resetStock();   // Redis = 100，且已购标记集合被清空
        long userId = 12_345L;

        // 构造「库里有订单、Redis 没有标记」：预热清空了已购集合 / 主从切换丢写 / Redis 被清过。
        // 迁移第二步之后，这种请求在【请求线程】就会撞上 uk_user_stock，不必再走完
        // 「预扣 → 投递 → 消费 → 落库失败 → 回补」一整圈。
        jdbcTemplate.update("INSERT INTO orders(order_no, user_id, stock_id, status) VALUES(?, ?, ?, 'CONFIRMED')",
                "COMP-3-A", userId, STOCK_ID);

        BizException ex = assertThrows(BizException.class,
                () -> seckillService.seckill(userId, STOCK_ID));
        assertEquals(ErrorCode.REPEAT_ORDER.getCode(), ex.getCode(),
                "必须在请求线程就被识别为重复下单");

        assertEquals(INIT_STOCK, seckillService.remain(STOCK_ID),
                "多余的预扣必须当场归还 —— 这是把判定前移到请求线程买到的收益");
        assertEquals(1, queryInt("SELECT COUNT(*) FROM orders WHERE stock_id = ?", STOCK_ID),
                "不得插入第二条订单");
        assertEquals(0, queryInt("SELECT COUNT(*) FROM seckill_outbox WHERE stock_id = ?", STOCK_ID),
                "被拒绝的请求不该留下任何待投递凭据（凭据与订单同事务）");

        System.out.printf("[验收] 请求线程拦截已购用户 -> 库存回到 %d，无新增订单、无新增凭据%n",
                seckillService.remain(STOCK_ID));
    }

    @Test
    @Order(2)
    @DisplayName("回补幂等：重复回补不会把库存越补越多，且四种结局可区分")
    void rollbackShouldBeIdempotentAndDistinguishable() {
        resetStock();
        long userId = 99_999L;
        long otherUser = 99_998L;

        // 制造一次真实的预扣：Redis 库存 100 -> 99，同时打上用户标记
        // （tryDeduct 已收敛为 void：业务失败一律抛 BizException，不存在「返回 false 可以继续往下走」的歧义）
        stockCacheService.tryDeduct(STOCK_ID, userId, 1);
        assertEquals(INIT_STOCK - 1, seckillService.remain(STOCK_ID));

        // 第一次回补：应当真的把库存还回去
        RollbackResult first = stockCacheService.rollback(STOCK_ID, userId, 1);
        assertEquals(RollbackResult.Status.SUCCESS, first.status());
        assertEquals(INIT_STOCK, first.remainStock());
        assertTrue(first.rolledBack());
        assertEquals(INIT_STOCK, seckillService.remain(STOCK_ID));

        // 第二次回补同一笔：必须被幂等保护挡住，库存不能变成 101
        RollbackResult second = stockCacheService.rollback(STOCK_ID, userId, 1);
        assertEquals(RollbackResult.Status.ALREADY_ROLLED_BACK, second.status(),
                "重复回补必须返回独立的「已回补过」状态，而不是和别的错误共用 -1");
        assertNull(second.remainStock());
        assertTrue(second.benign(), "幂等命中属于正常结局，不应触发告警");
        assertEquals(INIT_STOCK, seckillService.remain(STOCK_ID),
                "重复回补绝不能把库存补成 101 —— 这正是旧版脚本的缺陷");

        // 入参非法：与上面两种情况必须能区分开
        assertEquals(RollbackResult.Status.ILLEGAL_ARGUMENT,
                stockCacheService.rollback(STOCK_ID, otherUser, 0).status());

        // 活动已结束（缓存被清）：同样是独立状态，且不修改任何数据
        seckillService.clear(STOCK_ID);
        RollbackResult closed = stockCacheService.rollback(STOCK_ID, otherUser, 1);
        assertEquals(RollbackResult.Status.ACTIVITY_CLOSED, closed.status());
        assertTrue(closed.benign());

        System.out.printf("[验收] 回补四种结局可区分：%s / %s / %s / %s%n",
                first.status(), second.status(),
                RollbackResult.Status.ILLEGAL_ARGUMENT, closed.status());
    }

    // ------------------------------------------------------------------ helpers

    private boolean hasBoughtMark(long userId) {
        return stockCacheService.isBought(STOCK_ID, userId);
    }

    /** 轮询 Redis 余量，等待其回到期望值 */
    private void awaitRemain(int expected, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        Integer remain = null;
        while (System.currentTimeMillis() < deadline) {
            remain = seckillService.remain(STOCK_ID);
            if (remain != null && remain == expected) {
                return;
            }
            Thread.sleep(50);
        }
        fail("等待 Redis 余量回到 " + expected + " 超时，当前为 " + remain);
    }

    private int queryInt(String sql, Object... args) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return value == null ? -1 : value;
    }
}
