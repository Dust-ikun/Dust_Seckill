package com.dustikun.seckill;

import com.dustikun.seckill.Config.OutboxProperties;
import com.dustikun.seckill.Config.RocketMqProperties;
import com.dustikun.seckill.Mapper.OutboxMessageMapper;
import com.dustikun.seckill.Metrics.SeckillMetrics;
import com.dustikun.seckill.Mq.SeckillMessageProducer;
import com.dustikun.seckill.Service.OutboxService;
import com.dustikun.seckill.Service.PreDeductCompensator;
import com.dustikun.seckill.Service.StockCacheService;
import com.dustikun.seckill.entity.OutboxMessage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 阶段 5 本地消息表（Outbox）验收。
 * <p>
 * 【为什么用 review profile（关掉 MQ）】本类要验证的核心是「投递失败之后会怎样」，
 * 需要人为让投递必然失败。若同时有真实的 {@code OutboxDispatchTask} 在按秒轮询，
 * 它会抢先把记录投出去并改成 SENT，测试就变成了与后台线程抢时序的竞态。
 * 关掉 MQ（生产者不存在 → 后台投递器直接返回 0）之后，投递完全由测试自己驱动，结果确定。
 * <p>
 * 【真实 MQ 下的端到端链路】由 {@link SeckillConcurrencyTest}（真实 Kafka/RocketMQ 消费）
 * 与 {@code .workbuddy/tools/smoke_stage4.py}（HTTP 端到端）覆盖，本类不重复。
 */
@Slf4j
@SpringBootTest
@ActiveProfiles("review")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SeckillOutboxTest {

    private static final long STOCK_ID = 990001L;
    private static final int INIT_STOCK = 100;

    @Autowired
    private OutboxService outboxService;
    @Autowired
    private OutboxMessageMapper outboxMapper;
    @Autowired
    private PreDeductCompensator compensator;
    @Autowired
    private StockCacheService stockCacheService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private boolean redisAvailable;

    @BeforeAll
    void requireRedis() {
        try {
            var connection = jdbcTemplate.getDataSource().getConnection();
            connection.close();
            redisAvailable = true;
        } catch (Exception e) {
            redisAvailable = false;
        }
        Assumptions.assumeTrue(redisAvailable, "数据源不可用，跳过 Outbox 验收测试");
    }

    @BeforeEach
    void reset() {
        clean();
        jdbcTemplate.update("INSERT INTO stock(id, name, count) VALUES(?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE count = ?",
                STOCK_ID, "Outbox 测试商品", INIT_STOCK, INIT_STOCK);
        stockCacheService.clear(STOCK_ID);
    }

    @AfterAll
    void cleanUp() {
        clean();
        jdbcTemplate.update("DELETE FROM stock WHERE id = ?", STOCK_ID);
        stockCacheService.clear(STOCK_ID);
    }

    private void clean() {
        jdbcTemplate.update("DELETE FROM seckill_outbox WHERE stock_id = ?", STOCK_ID);
        jdbcTemplate.update("DELETE FROM compensate_task WHERE stock_id = ?", STOCK_ID);
    }

    // ============================================================ 写入与查询

    @Test
    @DisplayName("登记后应可从库里查回，且字段映射完整（下单凭据必须可还原）")
    void enqueuedMessageShouldBeReadableAndFullyMapped() {
        String orderNo = "OUTBOX-A-" + System.nanoTime();
        outboxService.enqueue(orderNo, 600001L, STOCK_ID, 1);

        OutboxMessage loaded = outboxService.findByOrderNo(orderNo);
        assertNotNull(loaded, "刚登记的记录必须能按单号查回来，否则订单状态接口无法判断这笔下单走到哪一步");
        assertEquals(orderNo, loaded.getOrderNo());
        assertEquals(600001L, loaded.getUserId());
        assertEquals(STOCK_ID, loaded.getStockId());
        assertEquals(1, loaded.getNum());
        assertEquals(OutboxMessage.STATUS_PENDING, loaded.getStatus());
        assertEquals(0, loaded.getRetryCount());
        assertNotNull(loaded.getNextRetryTime());
        assertTrue(loaded.getNextRetryTime().isBefore(LocalDateTime.now().plusSeconds(1)),
                "首次投递时间必须落在当下附近，否则记录会白白躺着不投");
    }

    // ============================================================ 投递失败

    @Test
    @DisplayName("投递失败应退避重试，且记录保持 PENDING 不被丢弃")
    void failedDispatchShouldRescheduleInsteadOfLosingTheMessage() {
        String orderNo = "OUTBOX-B-" + System.nanoTime();
        OutboxService failing = failingOutboxWithMaxRetry(5);
        outboxService.enqueue(orderNo, 600002L, STOCK_ID, 1);

        int sent = failing.dispatchDue(10);

        assertEquals(0, sent, "投递必然失败，不应报告成功");
        OutboxMessage after = outboxService.findByOrderNo(orderNo);
        assertEquals(OutboxMessage.STATUS_PENDING, after.getStatus(),
                "投递失败后记录必须留在 PENDING —— 它还在待投递队列里，只是要等退避时间");
        assertEquals(1, after.getRetryCount(), "重试次数应累加");
        assertNotNull(after.getLastError(), "失败原因必须落库，否则事后无法判断当初为什么没投出去");
        assertTrue(after.getNextRetryTime().isAfter(LocalDateTime.now()),
                "应被推到未来重试，而不是立刻又打一次");
    }

    @Test
    @DisplayName("重试耗尽应放弃并回补 Redis 预扣：宁可让用户重抢，也不能让库存凭空消失")
    void exhaustedRetryShouldAbandonAndRestoreRedis() {
        String orderNo = "OUTBOX-C-" + System.nanoTime();
        OutboxService failing = failingOutboxWithMaxRetry(2);

        stockCacheService.preheat(STOCK_ID);
        stockCacheService.tryDeduct(STOCK_ID, 600003L, 1);
        assertEquals(INIT_STOCK - 1, stockCacheService.remain(STOCK_ID), "预扣应已生效");

        outboxService.enqueue(orderNo, 600003L, STOCK_ID, 1);

        // 第 1 次：失败 → 退避
        failing.dispatchDue(10);
        assertEquals(OutboxMessage.STATUS_PENDING, outboxService.findByOrderNo(orderNo).getStatus());

        // 第 2 次：达到 max-retry → 放弃 + 回补
        makeDue(orderNo);
        failing.dispatchDue(10);

        OutboxMessage after = outboxService.findByOrderNo(orderNo);
        assertEquals(OutboxMessage.STATUS_FAILED, after.getStatus(), "重试耗尽应标记为 FAILED");
        assertEquals(INIT_STOCK, stockCacheService.remain(STOCK_ID),
                "放弃投递后必须归还 Redis 预扣，否则这件库存就永久少卖了");
        assertFalse(stockCacheService.isBought(STOCK_ID, 600003L),
                "完整回滚应摘掉用户标记，用户必须能重新抢");
        assertTrue(outboxService.countFailed() >= 1, "FAILED 规模应当可被查询到（用于告警）");
    }

    @Test
    @DisplayName("MQ 未启用时投递器不得擅自处理记录（避免在不知情的情况下改写状态）")
    void dispatcherShouldNoOpWhenMqDisabled() {
        String orderNo = "OUTBOX-D-" + System.nanoTime();
        outboxService.enqueue(orderNo, 600004L, STOCK_ID, 1);

        // 本 profile 下容器里没有生产者，注入的 outboxService 拿到的就是 null
        assertEquals(0, outboxService.dispatchDue(10), "没有生产者时不应投递任何消息");
        assertEquals(OutboxMessage.STATUS_PENDING, outboxService.findByOrderNo(orderNo).getStatus(),
                "更不该顺手改成 SENT 或 FAILED —— 状态被误改会掩盖真实问题");
    }

    @Test
    @DisplayName("批量标记已投出必须一次覆盖整批，且不误伤批外记录")
    void markSentBatchShouldCoverExactlyTheGivenIds() {
        String first = "OUTBOX-G-" + System.nanoTime();
        String second = "OUTBOX-H-" + System.nanoTime();
        String outsider = "OUTBOX-I-" + System.nanoTime();
        outboxService.enqueue(first, 600007L, STOCK_ID, 1);
        outboxService.enqueue(second, 600008L, STOCK_ID, 1);
        outboxService.enqueue(outsider, 600009L, STOCK_ID, 1);

        List<Long> ids = List.of(outboxService.findByOrderNo(first).getId(),
                outboxService.findByOrderNo(second).getId());
        int affected = outboxMapper.markSentBatch(ids);

        assertEquals(2, affected, "批量更新应恰好命中传入的 2 行");
        assertEquals(OutboxMessage.STATUS_SENT, outboxService.findByOrderNo(first).getStatus());
        assertEquals(OutboxMessage.STATUS_SENT, outboxService.findByOrderNo(second).getStatus());
        assertEquals(1, outboxService.findByOrderNo(first).getRetryCount(),
                "标记已投出时应与单条路径一样累加 retry_count，避免两条路径的语义分叉");
        assertNull(outboxService.findByOrderNo(first).getLastError());
        assertEquals(OutboxMessage.STATUS_PENDING, outboxService.findByOrderNo(outsider).getStatus(),
                "批外的记录绝不能被顺带改成 SENT —— 那会让它永远不再被投递");
    }

    @Test
    @DisplayName("批量投递失败时整批逐条退避，不能只处置第一条")
    void batchFailureShouldRescheduleEveryMessageInTheBatch() {
        String orderA = "OUTBOX-J-" + System.nanoTime();
        String orderB = "OUTBOX-K-" + System.nanoTime();
        String orderC = "OUTBOX-L-" + System.nanoTime();
        OutboxService failing = failingOutboxWithMaxRetry(5);
        outboxService.enqueue(orderA, 600010L, STOCK_ID, 1);
        outboxService.enqueue(orderB, 600011L, STOCK_ID, 1);
        outboxService.enqueue(orderC, 600012L, STOCK_ID, 1);

        assertEquals(0, failing.dispatchDue(50), "投递必然失败，不应报告成功");

        for (String orderNo : List.of(orderA, orderB, orderC)) {
            OutboxMessage after = outboxService.findByOrderNo(orderNo);
            assertEquals(OutboxMessage.STATUS_PENDING, after.getStatus(),
                    "批量路径失败后退回逐条，每一条都应留在 PENDING 等待退避：" + orderNo);
            assertEquals(1, after.getRetryCount(), "每一条的重试次数都应累加：" + orderNo);
            assertNotNull(after.getLastError(), "每一条都必须记下失败原因：" + orderNo);
        }
    }

    // ============================================================ 归档清理

    @Test
    @DisplayName("归档只清过期的已投递记录，绝不碰仍待投递的记录")
    void purgeShouldOnlyRemoveOldSentRecords() {
        String sentOrderNo = "OUTBOX-E-" + System.nanoTime();
        String pendingOrderNo = "OUTBOX-F-" + System.nanoTime();
        outboxService.enqueue(sentOrderNo, 600005L, STOCK_ID, 1);
        outboxService.enqueue(pendingOrderNo, 600006L, STOCK_ID, 1);

        // 把第一条改成「已投递且久远」：显式赋值 update_time 会覆盖 ON UPDATE 的自动刷新
        jdbcTemplate.update("UPDATE seckill_outbox SET status = 'SENT', "
                + "update_time = DATE_SUB(NOW(), INTERVAL 3 DAY) WHERE order_no = ?", sentOrderNo);
        // 第二条是 PENDING 且同样久远 —— 它还没投出去，绝不能删
        jdbcTemplate.update("UPDATE seckill_outbox SET update_time = DATE_SUB(NOW(), INTERVAL 3 DAY) "
                + "WHERE order_no = ?", pendingOrderNo);

        int purged = outboxService.purgeExpired();

        assertTrue(purged >= 1, "过期已投递记录应被清理（Outbox 表会持续增长，必须有人负责清）");
        assertNull(outboxService.findByOrderNo(sentOrderNo), "过期的已投递记录应被删除");
        assertNotNull(outboxService.findByOrderNo(pendingOrderNo),
                "待投递记录无论多老都不能删 —— 删掉就等于把这笔下单弄丢了");
    }

    // ============================================================ helpers

    /**
     * 构造一个「投递必然失败」的 OutboxService。
     * <p>
     * 让投递失败最简单的办法是给一个<b>没有 start()</b> 的 {@link DefaultMQProducer}：
     * RocketMQ 在执行 send 之前会先校验服务状态，未启动即抛 {@code MQClientException}。
     * 比停掉 Broker 更可控——不依赖外部环境，也不会影响同 JVM 内其它测试。
     */
    private OutboxService failingOutboxWithMaxRetry(int maxRetry) {
        RocketMqProperties mqProperties = new RocketMqProperties();
        mqProperties.setTopic("unused-topic");
        mqProperties.setTag("unused-tag");
        DefaultMQProducer neverStarted = new DefaultMQProducer("outbox-test-never-started");
        SeckillMessageProducer broken =
                new SeckillMessageProducer(neverStarted, new ObjectMapper(), mqProperties, newMetrics());

        OutboxProperties properties = new OutboxProperties();
        properties.setMaxRetry(maxRetry);
        properties.setFirstRetryDelaySeconds(1);
        properties.setBatchSize(50);

        return new OutboxService(outboxMapper, fixedProvider(broken), compensator, properties, newMetrics());
    }

    /**
     * 每次构造一个挂在<b>独立</b> SimpleMeterRegistry 上的指标出口。
     * <p>
     * 不复用同一个实例是有意的：同一个 registry 上重复注册同名 meter 会返回同一个 Counter，
     * 于是多个用例的计数会累到一起。这里每个被测对象配一份干净的注册表，互不干扰。
     */
    private SeckillMetrics newMetrics() {
        return new SeckillMetrics(new SimpleMeterRegistry());
    }

    /**
     * 只实现 {@code getObject()}：{@code getIfAvailable()} 的默认实现会调它并兜住异常，
     * 这正是被测代码用到的那个方法。
     */
    private ObjectProvider<SeckillMessageProducer> fixedProvider(SeckillMessageProducer producer) {
        return new ObjectProvider<>() {
            @Override
            public SeckillMessageProducer getObject() {
                return producer;
            }
        };
    }

    /**
     * 把记录置为「已到期」。
     * <p>
     * {@code next_retry_time} 是 DATETIME(0)，MySQL 对小数秒向上取整，
     * 因此刚退避完的记录可能还差不到 1 秒才到期；测试里显式置为过去，避免依赖亚秒级时序。
     */
    private void makeDue(String orderNo) {
        jdbcTemplate.update("UPDATE seckill_outbox SET next_retry_time = DATE_SUB(NOW(), INTERVAL 1 SECOND) "
                + "WHERE order_no = ?", orderNo);
    }
}
