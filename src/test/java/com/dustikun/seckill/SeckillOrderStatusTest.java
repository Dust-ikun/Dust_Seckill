package com.dustikun.seckill;

import com.dustikun.seckill.Mapper.OrderMapper;
import com.dustikun.seckill.Mapper.OutboxMessageMapper;
import com.dustikun.seckill.Service.SeckillPersistenceService;
import com.dustikun.seckill.Service.SeckillPersistenceService.CancelOutcome;
import com.dustikun.seckill.Service.SeckillPersistenceService.ConfirmOutcome;
import com.dustikun.seckill.Service.SeckillPersistenceService.PersistOutcome;
import com.dustikun.seckill.entity.Order;
import com.dustikun.seckill.entity.OutboxMessage;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 迁移第二步的验收测试：订单状态机 + 两阶段落库（预订单 / 确认）。
 * <p>
 * 本类锁住五件事：
 * <ol>
 *   <li>{@code createPending()} 写出的订单必须是 PENDING，且<b>与待投递凭据同事务</b>
 *       —— 凭据写失败时订单必须一起消失，这是「订单与消息记录同一事务」的全部意义；</li>
 *   <li>{@code confirm()} 的许可证语义：第一次确认才扣库存，重投必须返回 0 行、不再扣；</li>
 *   <li>{@code confirm()} 在没有订单时不得扣库存（少卖方向）；</li>
 *   <li>{@code cancelPending()} 必须区分「刚取消 / 此前已取消 / 已确认 / 订单不存在」——
 *       调用方据此决定敢不敢归还 Redis 预扣，归还错方向就是超卖；</li>
 *   <li>{@code persist()}（降级链路）仍写 CONFIRMED，行为与引入状态机之前等价。</li>
 * </ol>
 * 【为什么不依赖 Redis / RocketMQ】被测对象全是数据库原语与事务边界，用 review profile
 * 关掉 MQ 与后台调度即可；不需要像 SeckillReviewFixTest 那样探测 Redis。
 * <p>
 * 【为什么用两个商品 ID】{@code STOCK_ID} 供 Mapper 原语用例使用（与 SeckillOutboxTest 同号，
 * 这是第一步就有的约定）；{@code SERVICE_STOCK_ID} 供服务层用例使用，
 * 它的 {@code seckill_outbox} 行归本类自己清理，避免与其它测试类的投递器扫描互相干扰。
 */
@Slf4j
@SpringBootTest
@ActiveProfiles("review")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("迁移第二步：订单状态机与两阶段落库")
class SeckillOrderStatusTest {

    private static final long STOCK_ID = 990001L;
    private static final long SERVICE_STOCK_ID = 990002L;
    private static final int INIT_STOCK = 100;

    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private OutboxMessageMapper outboxMapper;
    @Autowired
    private SeckillPersistenceService persistenceService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void reset() {
        prepare(SERVICE_STOCK_ID);
        prepare(STOCK_ID);
    }

    private void prepare(long stockId) {
        jdbcTemplate.update("INSERT INTO stock(id, name, count) VALUES(?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE count = ?",
                stockId, "订单状态机测试商品", INIT_STOCK, INIT_STOCK);
        jdbcTemplate.update("DELETE FROM orders WHERE stock_id = ?", stockId);
        jdbcTemplate.update("DELETE FROM seckill_outbox WHERE stock_id = ?", stockId);
    }

    @AfterAll
    void cleanUp() {
        clean(SERVICE_STOCK_ID);
        clean(STOCK_ID);
    }

    private void clean(long stockId) {
        jdbcTemplate.update("DELETE FROM orders WHERE stock_id = ?", stockId);
        jdbcTemplate.update("DELETE FROM seckill_outbox WHERE stock_id = ?", stockId);
        jdbcTemplate.update("DELETE FROM stock WHERE id = ?", stockId);
    }

    // ================================================================ 第一步既有用例

    @Test
    @DisplayName("persist() 落库即成功（降级链路）：订单必须显式写 CONFIRMED，不允许 PENDING 泄漏")
    void persistShouldWriteConfirmedStatus() {
        String orderNo = "STATUS-1-A";
        assertEquals(PersistOutcome.CREATED,
                persistenceService.persist(orderNo, 900001L, STOCK_ID, 1));

        Order loaded = orderMapper.selectByOrderNo(orderNo);
        assertNotNull(loaded, "订单应已落库");
        assertEquals(Order.STATUS_CONFIRMED, loaded.getStatus(),
                "降级链路没有后台确认环节，落库即成功；写成 PENDING 会让它在排空判定里永久卡住");
        assertEquals(INIT_STOCK - 1, dbStock(STOCK_ID), "DB 库存应被同步扣减");

        // 重投走的是既有幂等路径（uk_order_no），本步行为零变化，不应受状态字段影响
        assertEquals(PersistOutcome.DUPLICATE,
                persistenceService.persist(orderNo, 900001L, STOCK_ID, 1),
                "同一单号重投应被识别为重复投递");
        assertEquals(INIT_STOCK - 1, dbStock(STOCK_ID), "重投不得再扣库存");
    }

    @Test
    @DisplayName("confirmOrder 是许可证：同一订单只放行一次，第二次必须返回 0")
    void confirmOrderGrantsLicenseOnlyOnce() {
        String orderNo = insertPendingOrder("STATUS-2-A", 900002L, STOCK_ID);

        assertEquals(1, orderMapper.confirmOrder(orderNo),
                "PENDING 订单首次确认应影响 1 行 —— 这一行就是扣库存的许可证");
        assertEquals(Order.STATUS_CONFIRMED, orderMapper.selectByOrderNo(orderNo).getStatus());

        assertEquals(0, orderMapper.confirmOrder(orderNo),
                "重复确认必须返回 0（许可证已用掉）—— 这是重投不重复扣库存的全部依据");
        assertEquals(Order.STATUS_CONFIRMED, orderMapper.selectByOrderNo(orderNo).getStatus(),
                "返回 0 的同时不得改动任何状态");
    }

    @Test
    @DisplayName("cancelOrder 只能从 PENDING 出发：CONFIRMED 订单不可取消")
    void cancelOrderOnlyFromPending() {
        String pendingNo = insertPendingOrder("STATUS-3-A", 900003L, STOCK_ID);
        assertEquals(1, orderMapper.cancelOrder(pendingNo), "PENDING 订单应可取消");
        assertEquals(Order.STATUS_CANCELLED, orderMapper.selectByOrderNo(pendingNo).getStatus());

        // 已确认的订单（等于降级链路里每一条真实订单）必须拒绝取消
        String confirmedNo = insertOrder("STATUS-3-B", 900004L, STOCK_ID, Order.STATUS_CONFIRMED);
        assertEquals(0, orderMapper.cancelOrder(confirmedNo),
                "CONFIRMED 订单不可再取消 —— 库存已实扣，取消会造成「订单取消 + 库存不回」的错配");

        // 已取消的订单也不能被再取消/再确认（许可证同样只发一次）
        assertEquals(0, orderMapper.cancelOrder(pendingNo), "CANCELLED 不可重复取消");
        assertEquals(0, orderMapper.confirmOrder(pendingNo), "CANCELLED 不可再被确认");
    }

    @Test
    @DisplayName("countPendingByStockId 只数 PENDING：CONFIRMED / CANCELLED 都不算在途")
    void countPendingOnlyCountsPendingOrders() {
        insertPendingOrder("STATUS-4-A", 900005L, STOCK_ID);
        insertPendingOrder("STATUS-4-B", 900006L, STOCK_ID);
        insertOrder("STATUS-4-C", 900007L, STOCK_ID, Order.STATUS_CONFIRMED);

        assertEquals(2, orderMapper.countPendingByStockId(STOCK_ID),
                "只有 PENDING 才是在途 —— 这个口径将替代 JVM 计数器做多实例排空判定");

        orderMapper.confirmOrder("STATUS-4-A");
        assertEquals(1, orderMapper.countPendingByStockId(STOCK_ID),
                "确认一单后，在途应立即减一 —— 消费完成与在途归零必须由同一状态承载");

        assertEquals(0, orderMapper.countByStockId(-1L),
                "无关活动应为 0，防止计数口径把别人的订单算进来");
    }

    // ================================================================ 第二步新增用例

    @Test
    @DisplayName("createPending：写 PENDING 预订单 + 待投递凭据，且不扣 DB 库存")
    void createPendingShouldWritePreOrderAndCredential() {
        long userId = 910001L;
        String orderNo = "STATUS-5-A";

        assertEquals(PersistOutcome.CREATED,
                persistenceService.createPending(orderNo, userId, SERVICE_STOCK_ID, 1));

        Order order = orderMapper.selectByOrderNo(orderNo);
        assertNotNull(order, "预订单应已落库");
        assertEquals(Order.STATUS_PENDING, order.getStatus(),
                "请求线程只受理、不确认 —— 确认要等消费线程拿到许可证");
        assertEquals(INIT_STOCK, dbStock(SERVICE_STOCK_ID),
                "建预订单不扣 DB 库存：热点行 UPDATE 已经不在请求线程上了");

        OutboxMessage credential = outboxMapper.selectByOrderNo(orderNo);
        assertNotNull(credential,
                "待投递凭据必须由同一次调用写出 —— 否则「订单与消息记录同一事务」就不成立");
        assertEquals(OutboxMessage.STATUS_PENDING, credential.getStatus());
        assertEquals(userId, credential.getUserId());
    }

    @Test
    @DisplayName("createPending 的事务边界：凭据写失败时预订单必须一起回滚")
    void createPendingShouldRollBackOrderWhenCredentialInsertFails() {
        long userId = 910002L;
        String orderNo = "STATUS-6-A";

        // 先占掉这个单号的凭据位：seckill_outbox 上有 uk_outbox_order_no 唯一索引，
        // 于是 createPending 的顺序插入必然在第二条语句上失败。
        assertEquals(1, outboxMapper.insert(pendingCredential(orderNo, userId)));

        assertThrows(DuplicateKeyException.class,
                () -> persistenceService.createPending(orderNo, userId, SERVICE_STOCK_ID, 1));

        assertNull(orderMapper.selectByOrderNo(orderNo),
                "凭据写失败时订单必须一起回滚 —— 这正是「订单与消息记录同事务」的全部意义。"
                        + "若这里查到订单，说明两个写操作各自独立提交，迁移目标就没有达成");
    }

    @Test
    @DisplayName("confirm：第一次确认扣一次库存，重投必须返回 ALREADY_SETTLED 且不再扣")
    void confirmShouldDeductOnceAndRejectRedelivery() {
        long userId = 910003L;
        String orderNo = "STATUS-7-A";
        persistenceService.createPending(orderNo, userId, SERVICE_STOCK_ID, 1);

        assertEquals(ConfirmOutcome.CONFIRMED,
                persistenceService.confirm(orderNo, userId, SERVICE_STOCK_ID, 1));
        assertEquals(Order.STATUS_CONFIRMED, orderMapper.selectByOrderNo(orderNo).getStatus());
        assertEquals(INIT_STOCK - 1, dbStock(SERVICE_STOCK_ID), "首次确认必须扣一次库存");

        // 重投：MQ 只保证「至少一次」，同一条消息被消费两次是常态而不是异常
        assertEquals(ConfirmOutcome.ALREADY_SETTLED,
                persistenceService.confirm(orderNo, userId, SERVICE_STOCK_ID, 1),
                "许可证已用掉，第二次必须被识别为「已结案」而不是「又拿到一次许可」");
        assertEquals(INIT_STOCK - 1, dbStock(SERVICE_STOCK_ID),
                "重投绝不能重复扣库存 —— 这是「用状态流转的影响行数当许可证」的全部意义");
    }

    @Test
    @DisplayName("confirm：订单不存在时无权扣库存，返回 ORDER_MISSING（少卖方向）")
    void confirmShouldNotDeductWhenOrderMissing() {
        assertEquals(ConfirmOutcome.ORDER_MISSING,
                persistenceService.confirm("STATUS-8-NOPE", 910004L, SERVICE_STOCK_ID, 1));
        assertEquals(INIT_STOCK, dbStock(SERVICE_STOCK_ID),
                "没有订单就没有许可证 —— 此时扣库存等于凭空多卖一件");
    }

    @Test
    @DisplayName("cancelPending：四种许可证状态必须可区分（决定敢不敢归还库存）")
    void cancelPendingShouldDistinguishLicenceStates() {
        // 1) PENDING → 本次取消
        String pending = "STATUS-9-A";
        persistenceService.createPending(pending, 910005L, SERVICE_STOCK_ID, 1);
        assertEquals(CancelOutcome.CANCELLED, persistenceService.cancelPending(pending));
        assertEquals(Order.STATUS_CANCELLED, orderMapper.selectByOrderNo(pending).getStatus());

        // 2) 已取消：必须单独返回，调用方仍需归还 —— 上一次可能正是在「取消成功、归还之前」崩掉的
        assertEquals(CancelOutcome.ALREADY_CANCELLED, persistenceService.cancelPending(pending),
                "「已取消」不能与「本次取消」混为一谈，也不能与「已确认」混为一谈");

        // 3) CONFIRMED：库存已被真实消耗，必须拒绝取消，否则会出现「订单取消 + 库存不回」的错配
        String confirmed = "STATUS-9-B";
        persistenceService.createPending(confirmed, 910006L, SERVICE_STOCK_ID, 1);
        persistenceService.confirm(confirmed, 910006L, SERVICE_STOCK_ID, 1);
        assertEquals(CancelOutcome.CONFIRMED, persistenceService.cancelPending(confirmed));
        assertEquals(Order.STATUS_CONFIRMED, orderMapper.selectByOrderNo(confirmed).getStatus(),
                "拒绝取消时不得改动订单状态");

        // 4) 订单不存在
        assertEquals(CancelOutcome.MISSING, persistenceService.cancelPending("STATUS-9-NOPE"));
    }

    @Test
    @DisplayName("createPending：撞 uk_user_stock 返回 USER_ALREADY_BOUGHT，且不留下任何凭据")
    void createPendingShouldReportUserAlreadyBought() {
        long userId = 910007L;
        insertOrder("STATUS-10-A", userId, SERVICE_STOCK_ID, Order.STATUS_CONFIRMED);

        assertEquals(PersistOutcome.USER_ALREADY_BOUGHT,
                persistenceService.createPending("STATUS-10-B", userId, SERVICE_STOCK_ID, 1),
                "同一用户已有订单时必须识别为「用户已购」，而不是笼统的重复");

        assertNull(outboxMapper.selectByOrderNo("STATUS-10-B"),
                "用户已购时凭据不该被写出来 —— 订单没成立，凭据也没有存在的理由");
        assertEquals(1, queryInt("SELECT COUNT(*) FROM orders WHERE stock_id = ?", SERVICE_STOCK_ID),
                "不得再插入第二条订单");
    }

    // ------------------------------------------------------------------ helpers

    private OutboxMessage pendingCredential(String orderNo, long userId) {
        OutboxMessage message = new OutboxMessage();
        message.setOrderNo(orderNo);
        message.setUserId(userId);
        message.setStockId(SERVICE_STOCK_ID);
        message.setNum(1);
        message.setNextRetryTime(LocalDateTime.now().minusSeconds(1));
        return message;
    }

    /** 插入一条 PENDING 订单 —— 模拟请求线程将写出的形态 */
    private String insertPendingOrder(String orderNo, long userId, long stockId) {
        return insertOrder(orderNo, userId, stockId, Order.STATUS_PENDING);
    }

    private String insertOrder(String orderNo, long userId, long stockId, String status) {
        Order order = new Order();
        order.setOrderNo(orderNo);
        order.setUserId(userId);
        order.setStockId(stockId);
        order.setStatus(status);
        order.setCreateTime(LocalDateTime.now());
        assertEquals(1, orderMapper.insert(order), "测试订单应写入成功：" + orderNo);
        return orderNo;
    }

    private int dbStock(long stockId) {
        return queryInt("SELECT count FROM stock WHERE id = ?", stockId);
    }

    private int queryInt(String sql, Object... args) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return value == null ? -1 : value;
    }
}
