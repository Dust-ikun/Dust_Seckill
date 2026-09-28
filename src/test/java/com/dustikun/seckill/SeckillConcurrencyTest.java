package com.dustikun.seckill;

import com.dustikun.seckill.Common.Exception.BizException;
import com.dustikun.seckill.Common.Exception.ErrorCode;
import com.dustikun.seckill.Common.result.SeckillOrderResponse;
import com.dustikun.seckill.Common.util.OrderNoGenerator;
import com.dustikun.seckill.Service.SeckillService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 阶段 4 验收测试：Redis 预扣 + RocketMQ 异步落库（迁移第二步后为「预订单 + 后台确认」）。
 * <p>
 * 与阶段 3 测试最大的区别在于<b>断言必须等待</b>：{@code seckill()} 返回「已受理」时订单只是
 * PENDING 预订单、库存还没扣，直接断言数据库会拿到一个不完整的状态。
 * 因此每个用例都先等<b>订单被确认（CONFIRMED）</b>再校验——
 * 这也正是异步化给测试带来的真实变化。
 * <p>
 * 【为什么等待的是数据库而不是消费端计数器】
 * 计数器是「本上下文内那个消费者实例」的私有数据。一旦容器里存在多个消费者实例（同一个消费组），
 * Broker 会把队列分给多个实例，任何实例的计数器都只是局部视图，用它做断言会得到假失败。
 * 而「数据库最终是否收敛到预期值」是外部可观测的事实，与有几个消费者无关，也更接近验收标准。
 * <p>
 * 【前置条件】本测试需要本机 RocketMQ 处于运行状态（NameServer 9876 + Broker 10911）。
 * 否则 Spring 容器会在启动生产者时直接失败——这是刻意设计的快速失败：
 * 宁可启动不起来，也不能在上游以为「消息已发出」的情况下静默丢单。
 * <p>
 * 【隔离手段】每个用例使用独立的测试商品 ID。因为消费是异步的，
 * 上一个用例残留的消息可能在下一个用例执行期间才被消费，共用商品 ID 会造成相互污染。
 */
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SeckillConcurrencyTest {

    private static final long STOCK_BASE = 900000L;
    private static final int INIT_STOCK = 100;
    private static final int CONCURRENT_USERS = 300;
    private static final long ASYNC_TIMEOUT_MS = 60_000;

    @Autowired
    private SeckillService seckillService;
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private OrderNoGenerator orderNoGenerator;

    /** 每个用例固定一个商品 ID：用例序号即偏移量 */
    private long stockIdOf(int offset) {
        return STOCK_BASE + offset;
    }

    @BeforeAll
    void requireRedis() {
        try {
            var connection = redisTemplate.getConnectionFactory().getConnection();
            boolean alive = "PONG".equalsIgnoreCase(connection.ping());
            connection.close();
            Assumptions.assumeTrue(alive, "Redis 未就绪，跳过阶段 4 并发验收测试");
        } catch (Exception e) {
            Assumptions.assumeTrue(false, "Redis 不可用（" + e.getMessage() + "），跳过阶段 4 并发验收测试");
        }
    }

    @AfterAll
    void cleanUp() {
        for (int i = 1; i <= 9; i++) {
            long stockId = stockIdOf(i);
            jdbcTemplate.update("DELETE FROM orders WHERE stock_id = ?", stockId);
            jdbcTemplate.update("DELETE FROM seckill_outbox WHERE stock_id = ?", stockId);
            jdbcTemplate.update("DELETE FROM stock WHERE id = ?", stockId);
            seckillService.clear(stockId);
        }
    }

    /**
     * 重置测试商品：库内库存归位、清空历史订单与待投递凭据、清 Redis、再预热。
     * <p>
     * 必须同时清 {@code seckill_outbox}：迁移第二步之后每个被受理的请求都会留下一条凭据，
     * 残留的 PENDING 凭据会被投递器扫到并投出去，而上一个用例的订单行已经被删除，
     * 消费端只能报「订单缺失」——那是测试污染，不是被测逻辑的问题。
     */
    private void prepare(long stockId, int initStock) {
        jdbcTemplate.update("INSERT INTO stock(id, name, count) VALUES(?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE count = ?",
                stockId, "并发验收测试商品", initStock, initStock);
        jdbcTemplate.update("DELETE FROM orders WHERE stock_id = ?", stockId);
        jdbcTemplate.update("DELETE FROM seckill_outbox WHERE stock_id = ?", stockId);
        seckillService.clear(stockId);

        long preheated = seckillService.preheat(stockId);
        assertEquals(initStock, preheated, "预热后 Redis 库存应等于数据库库存");
    }

    // ================================================================= 用例

    @Test
    @Order(1)
    @DisplayName("防超卖：300 个用户并发抢 100 件，受理数与最终订单数都必须精确等于 100")
    void shouldNotOversellUnderConcurrency() throws Exception {
        long stockId = stockIdOf(1);
        prepare(stockId, INIT_STOCK);

        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger stockNotEnough = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();
        List<String> orderNos = Collections.synchronizedList(new ArrayList<>());

        runConcurrently(CONCURRENT_USERS, i -> {
            try {
                SeckillOrderResponse response = seckillService.seckill(10_000L + i, stockId);
                assertEquals(SeckillOrderResponse.Status.QUEUED, response.status(),
                        "异步链路必须返回「已受理」而不是「已成功」");
                orderNos.add(response.orderNo());
                accepted.incrementAndGet();
            } catch (BizException e) {
                if (ErrorCode.STOCK_NOT_ENOUGH.getCode().equals(e.getCode())) {
                    stockNotEnough.incrementAndGet();
                } else {
                    unexpected.incrementAndGet();
                }
            } catch (Exception e) {
                unexpected.incrementAndGet();
            }
        });

        // ---- 请求侧：同步就能确定的结论 ----
        assertEquals(INIT_STOCK, accepted.get(), "受理数必须精确等于初始库存");
        assertEquals(CONCURRENT_USERS - INIT_STOCK, stockNotEnough.get(), "其余请求应全部返回库存不足");
        assertEquals(0, unexpected.get(), "不应出现库存不足/重复下单之外的异常");
        assertEquals(INIT_STOCK, new HashSet<>(orderNos).size(), "单号不能重复");
        assertEquals(0, seckillService.remain(stockId), "Redis 剩余库存必须为 0");

        // ---- 迁移第二步的标志：受理的预订单在请求线程就已经落库了 ----
        // 这一条同时也是「订单表不会被打爆」的证据：落库量级 = 库存量级（100），而不是请求量级（300）。
        assertEquals(INIT_STOCK, queryInt("SELECT COUNT(*) FROM orders WHERE stock_id = ?", stockId),
                "每个被受理的请求都应在请求线程写出一条 PENDING 预订单");

        // ---- 消费侧：等待后台确认收敛后再校验最终一致性 ----
        // 注意等的是 CONFIRMED 而不是「订单出现」：预订单是同步写的，订单一出现就断言库存
        // 会把「还没扣库存」误判成失败。
        awaitConfirmedCount(stockId, INIT_STOCK, ASYNC_TIMEOUT_MS);

        assertEquals(0, queryInt("SELECT count FROM stock WHERE id = ?", stockId),
                "数据库库存必须扣到 0，不能出现负数");
        assertEquals(INIT_STOCK, queryInt("SELECT COUNT(*) FROM orders WHERE stock_id = ?", stockId),
                "最终订单数必须等于受理数");
        assertEquals(0, queryInt("SELECT COUNT(*) FROM orders WHERE stock_id = ? AND status = 'PENDING'",
                        stockId),
                "全部确认之后不应再有在途（PENDING）订单 —— 它就是在途的数据库事实来源");
        // 再等一小会儿确认没有多余的重复订单冒出来（消费幂等）
        Thread.sleep(500);
        assertEquals(INIT_STOCK, queryInt("SELECT COUNT(*) FROM orders WHERE stock_id = ?", stockId),
                "重复投递不得产生额外订单");

        System.out.printf("[验收] 并发=%d 受理=%d 库存不足=%d -> 最终 DB库存=%d 订单数=%d Redis余量=%d%n",
                CONCURRENT_USERS, accepted.get(), stockNotEnough.get(),
                queryInt("SELECT count FROM stock WHERE id = ?", stockId),
                queryInt("SELECT COUNT(*) FROM orders WHERE stock_id = ?", stockId),
                seckillService.remain(stockId));
    }

    @Test
    @Order(2)
    @DisplayName("一人一单：同一用户 50 个并发请求只允许受理 1 次，最终只落 1 单、只扣 1 件")
    void sameUserShouldOnlySucceedOnce() throws Exception {
        long stockId = stockIdOf(2);
        prepare(stockId, INIT_STOCK);

        long userId = 77_777L;
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger repeat = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();

        runConcurrently(50, i -> {
            try {
                seckillService.seckill(userId, stockId);
                accepted.incrementAndGet();
            } catch (BizException e) {
                if (ErrorCode.REPEAT_ORDER.getCode().equals(e.getCode())) {
                    repeat.incrementAndGet();
                } else {
                    unexpected.incrementAndGet();
                }
            } catch (Exception e) {
                unexpected.incrementAndGet();
            }
        });

        assertEquals(1, accepted.get(), "同一用户只能被受理一次");
        assertEquals(49, repeat.get(), "其余请求应全部被识别为重复下单");
        assertEquals(0, unexpected.get());
        assertEquals(INIT_STOCK - 1, seckillService.remain(stockId), "只能扣减 1 件库存");

        awaitConfirmedCount(stockId, 1, ASYNC_TIMEOUT_MS);

        assertEquals(1, queryInt("SELECT COUNT(*) FROM orders WHERE stock_id = ? AND user_id = ?",
                stockId, userId), "数据库里也只能有 1 单");
        assertEquals(INIT_STOCK - 1, queryInt("SELECT count FROM stock WHERE id = ?", stockId));

        System.out.printf("[验收] 同一用户并发 50 次 -> 受理=%d 拦截重复=%d 最终订单=%d 剩余库存=%d%n",
                accepted.get(), repeat.get(),
                queryInt("SELECT COUNT(*) FROM orders WHERE stock_id = ? AND user_id = ?", stockId, userId),
                seckillService.remain(stockId));
    }

    @Test
    @Order(3)
    @DisplayName("未预热保护：库存未前置到 Redis 时直接拒绝，不产生订单也不投递消息")
    void shouldRejectWhenNotPreheated() {
        long stockId = stockIdOf(3);
        prepare(stockId, INIT_STOCK);
        seckillService.clear(stockId);
        assertNull(seckillService.remain(stockId), "清理后 Redis 应查询不到库存");

        BizException ex = assertThrows(BizException.class,
                () -> seckillService.seckill(1L, stockId));
        assertEquals(ErrorCode.STOCK_NOT_READY.getCode(), ex.getCode());
        assertEquals(0, queryInt("SELECT COUNT(*) FROM orders WHERE stock_id = ?", stockId),
                "未预热时必须快速失败，不能产生订单");
    }

    @Test
    @Order(4)
    @DisplayName("异步状态流转：接口返回 QUEUED，落库完成后查询变为 SUCCESS")
    void orderStatusShouldBecomeSuccess() throws Exception {
        long stockId = stockIdOf(4);
        prepare(stockId, INIT_STOCK);

        long userId = 66_666L;
        SeckillOrderResponse accepted = seckillService.seckill(userId, stockId);
        assertEquals(SeckillOrderResponse.Status.QUEUED, accepted.status(),
                "接口返回时订单尚未落库，状态必须是 QUEUED");

        SeckillOrderResponse finalStatus =
                awaitStatus(userId, stockId, accepted.orderNo(), SeckillOrderResponse.Status.SUCCESS,
                        ASYNC_TIMEOUT_MS);
        assertEquals(accepted.orderNo(), finalStatus.orderNo());

        System.out.printf("[验收] 异步状态流转：QUEUED -> %s（单号 %s）%n",
                finalStatus.status(), accepted.orderNo());
    }

    @Test
    @Order(5)
    @DisplayName("单号选型：Snowflake 单号时间有序且不重复")
    void orderNoShouldBeTimeOrderedAndUnique() {
        List<String> generated = new ArrayList<>();
        for (int i = 0; i < 5000; i++) {
            generated.add(orderNoGenerator.next());
        }

        assertEquals(5000, new HashSet<>(generated).size(), "单号不能重复");

        List<String> sorted = new ArrayList<>(generated);
        Collections.sort(sorted);
        assertEquals(sorted, generated, "单号必须时间有序（字典序等于生成顺序），否则索引页仍会分裂");

        assertTrue(generated.get(0).length() >= 18, "单号长度异常：" + generated.get(0));
    }

    // ------------------------------------------------------------------ helpers

    /**
     * 轮询数据库，等待<b>已确认（CONFIRMED）</b>的订单数收敛到期望值。
     * <p>
     * 【为什么必须等 CONFIRMED 而不是「订单数」】迁移第二步之后订单是在请求线程同步写入的
     * （状态 PENDING），「订单出现」不再代表后台已经处理完 —— 直接用它当收敛信号，
     * 会在库存还没扣的时候就断言库存，得到一个随机器快慢而定的假失败。
     * 状态翻到 CONFIRMED 才是「确认 + 扣库存」这个事务提交完成的标志。
     */
    private void awaitConfirmedCount(long stockId, int expected, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        int count = -1;
        while (System.currentTimeMillis() < deadline) {
            count = queryInt("SELECT COUNT(*) FROM orders WHERE stock_id = ? AND status = 'CONFIRMED'",
                    stockId);
            if (count >= expected) {
                return;
            }
            Thread.sleep(50);
        }
        fail("等待后台确认超时：期望 CONFIRMED 订单数达到 " + expected + "，实际为 " + count);
    }

    /** 轮询订单状态，等待其翻转到期望值 */
    private SeckillOrderResponse awaitStatus(long userId, long stockId, String orderNo,
                                             SeckillOrderResponse.Status expected,
                                             long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        SeckillOrderResponse current = null;
        while (System.currentTimeMillis() < deadline) {
            current = seckillService.queryOrder(userId, stockId, orderNo);
            if (current.status() == expected) {
                return current;
            }
            Thread.sleep(50);
        }
        fail("等待订单状态变为 " + expected + " 超时，当前状态为 "
                + (current == null ? "null" : current.status()));
        return current;
    }

    private void runConcurrently(int threads, IntConsumer task) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        try {
            for (int i = 0; i < threads; i++) {
                final int index = i;
                pool.execute(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        task.accept(index);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            // 所有线程就绪后同时放行，制造真实的最大并发压力
            assertTrue(ready.await(60, TimeUnit.SECONDS), "并发线程未全部就绪");
            start.countDown();
            assertTrue(done.await(180, TimeUnit.SECONDS), "并发任务超时未完成");
        } finally {
            pool.shutdownNow();
        }
    }

    private int queryInt(String sql, Object... args) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return value == null ? -1 : value;
    }
}
