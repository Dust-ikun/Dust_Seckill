package com.dustikun.seckill.Mapper;

import com.dustikun.seckill.entity.Order;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface OrderMapper {

    /**
     * 插入订单。status 为<b>必填</b>：列上有 NOT NULL 约束，忘记置状态会在这一步被直接拒绝，
     * 而不是静默落入某个默认语义 —— 与 {@code CompensateTaskService.apply()} 对 null 字段
     * 抛异常是同一取向：宁可失败在写入点，也不要带着歧义状态流向下游。
     */
    @Insert("INSERT INTO orders(order_no, user_id, stock_id, status) " +
            "VALUES(#{orderNo}, #{userId}, #{stockId}, #{status})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Order order);

    /**
     * 按业务单号查询订单。
     * <p>
     * 异步落库之后，下单接口只能返回「已受理」，
     * 用户需要凭单号回查这笔单子到底落库了没有，因此需要按单号查询。
     * 走的是 {@code uk_order_no} 唯一索引，等值查询，代价很低。
     */
    @Select("SELECT * FROM orders WHERE order_no = #{orderNo}")
    Order selectByOrderNo(@Param("orderNo") String orderNo);

    /**
     * 查询某用户对某商品是否已有订单。
     * <p>
     * 两个用途：
     * <ol>
     *   <li>Redis 降级时的「二次探测」——判断用户已购标记是本次预扣留下的、还是此前下单留下的
     *       （见 {@code SeckillService#probePreDeducted}）；</li>
     *   <li>落库撞 {@code uk_user_stock} 时复核。
     * </ol>
     * 走 {@code uk_user_stock} 唯一索引，等值查询。
     */
    @Select("SELECT * FROM orders WHERE user_id = #{userId} AND stock_id = #{stockId}")
    Order selectByUserAndStock(@Param("userId") Long userId, @Param("stockId") Long stockId);

    /** 某活动已落库的订单数（对账用） */
    @Select("SELECT COUNT(*) FROM orders WHERE stock_id = #{stockId}")
    int countByStockId(@Param("stockId") Long stockId);

    /** 某活动已落库订单的用户集合（对账修复时用来补齐 Redis 已购标记） */
    @Select("SELECT user_id FROM orders WHERE stock_id = #{stockId}")
    List<Long> selectUserIdsByStockId(@Param("stockId") Long stockId);

    /**
     * 把订单从 PENDING 置为 CONFIRMED（条件更新）。由消费线程调用。
     * <p>
     * 它承担「扣库存的许可证」职责：影响行数 1 = 本条消息拿到许可证、
     * 有权执行一次 DB 扣减；0 = 订单已被确认或取消（重投），<b>无权再扣</b>。
     * 由此取代唯一索引作为扣减动作的幂等键 —— 订单一旦提前落库，
     * 「插入撞索引」就不再能替扣减把关了。
     * <p>
     * 【顺序不能反】必须先改状态、后扣库存：反过来时重投会先扣掉库存、再发现改不动状态，
     * 而库存已实扣。两条 UPDATE 必须在同一事务里，保证「订单确认 ⟺ 库存扣减」。
     */
    @Update("UPDATE orders SET status = 'CONFIRMED' WHERE order_no = #{orderNo} AND status = 'PENDING'")
    int confirmOrder(@Param("orderNo") String orderNo);

    /**
     * 把订单从 PENDING 置为 CANCELLED（条件更新）。
     * <p>
     * 由两条取消路径调用：消费端重试耗尽（{@code SeckillOrderConsumer}）、
     * 关闭 outbox 的对照链路投递失败（{@code SeckillService}）、以及待补偿任务重试
     * （{@code CompensateTaskService}，归还库存前必须先确认订单不成立）。
     * 取消后按「补库存、留标记」处置 —— 与 {@code USER_ALREADY_BOUGHT} 同一取向
     * （宁可少卖，绝不超卖）：摘标记可能让用户被再放行又取消，形成循环。
     */
    @Update("UPDATE orders SET status = 'CANCELLED' WHERE order_no = #{orderNo} AND status = 'PENDING'")
    int cancelOrder(@Param("orderNo") String orderNo);

    /**
     * 某活动处于 PENDING（在途）的订单数。
     * <p>
     * 对账（AUTO 模式）用它当「在途预扣数」：期望 Redis 库存 = 数据库库存 − 本值。
     * 注意对账快照走 {@link #selectReconcileSnapshot}（四个数同一条 SQL 取同一时刻，
     * 且会把「已放弃但仍为 PENDING」的那些剔出去），本方法用于单独查询与测试。
     * 走 {@code idx_orders_stock_status} 索引。
     */
    @Select("SELECT COUNT(*) FROM orders WHERE stock_id = #{stockId} AND status = 'PENDING'")
    int countPendingByStockId(@Param("stockId") Long stockId);

    /**
     * 对账快照：一条 SQL 同时取回「数据库库存 / PENDING 预订单数 / 已放弃未了结数 / 订单总数」。
     * <p>
     * 对账的不变量是 {@code Redis 库存 == 数据库库存 − 在途预扣数}，等式两边的数据库侧
     * 必须来自同一时刻——分成两条查询时，间隙里落库的订单会自己制造假不一致。
     * 标量子查询在同一个语句里读到的是同一份一致性视图，天然满足这一点。
     * <p>
     * 【在途数为什么是 PENDING 减去「已放弃」】订单前置之后，每一笔受理都会留下一条
     * PENDING 预订单，消费确认（PENDING → CONFIRMED + 扣库存）在同一事务里完成，
     * 所以「Redis 已扣、数据库尚未扣」的差额等于 PENDING 行数 —— 但这条推理有一个前提：
     * <b>每条 PENDING 最终都会走向 CONFIRMED 或 CANCELLED</b>。投递重试耗尽的记录打破了这个前提：
     * 它的消息不会再被投出去（在 outbox 里是 FAILED 终态），订单却停在 PENDING。
     * 这种单既不会落库、也不会取消，把它算进在途就等于给等式两边各减 1 —— 差值被抵消，
     * 一笔真正丢失的预扣会被读成「一致」。所以它必须被单独数出来并从在途里剔掉：
     * 见 {@code ReconcileReport.Status#ABANDONED_PENDING}。
     * <p>
     * 快照里只有数据库：Redis 侧的库存在本方法之外单独读取
     * （它本来就不是数据库事务的一部分，且注意先取快照后读 Redis 的读偏斜窗口，见对账服务注释）。
     */
    @Select("SELECT"
            + " (SELECT count FROM stock WHERE id = #{stockId}) AS dbStock,"
            + " (SELECT COUNT(*) FROM orders WHERE stock_id = #{stockId} AND status = 'PENDING') AS pendingOrders,"
            + " (SELECT COUNT(*) FROM orders o JOIN seckill_outbox x ON x.order_no = o.order_no"
            + "     WHERE o.stock_id = #{stockId} AND o.status = 'PENDING' AND x.status = 'FAILED')"
            + "   AS abandonedPending,"
            + " (SELECT COUNT(*) FROM orders WHERE stock_id = #{stockId}) AS dbOrderCount")
    com.dustikun.seckill.Common.result.ReconcileSnapshot selectReconcileSnapshot(@Param("stockId") Long stockId);

    /**
     * 「投递已放弃、订单却仍停在 PENDING」的预订单明细（对账修复用）。
     * <p>
     * 判据与 {@link #selectReconcileSnapshot} 里的 {@code abandonedPending} 完全一致，
     * 只是这里要的是行本身：对账为它们补登记「取消订单 + 归还预扣」待办时需要
     * {@code orderNo / userId} 这两个还原现场所必需的字段。
     * <p>
     * 走 {@code uk_outbox_order_no} 等值连接，一次对账的调用频率（默认 5 分钟）下代价可忽略。
     */
    @Select("SELECT o.* FROM orders o JOIN seckill_outbox x ON x.order_no = o.order_no "
            + "WHERE o.stock_id = #{stockId} AND o.status = 'PENDING' AND x.status = 'FAILED' "
            + "ORDER BY o.id")
    List<Order> selectAbandonedPending(@Param("stockId") Long stockId);
}
