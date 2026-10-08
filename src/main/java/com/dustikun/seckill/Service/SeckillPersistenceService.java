package com.dustikun.seckill.Service;

import com.dustikun.seckill.Common.Exception.BizException;
import com.dustikun.seckill.Common.Exception.ErrorCode;
import com.dustikun.seckill.Mapper.OrderMapper;
import com.dustikun.seckill.Mapper.StockMapper;
import com.dustikun.seckill.entity.Order;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 秒杀落库服务。
 * <p>
 * 【形态：订单前置 + 后台确认】
 * <pre>
 * 请求线程   createPending()   INSERT orders(status=PENDING) + INSERT outbox   同事务
 * 消费线程   confirm()         UPDATE orders SET status=CONFIRMED（许可证）+ UPDATE stock  同事务
 * 消费耗尽   cancelPending()   UPDATE orders SET status=CANCELLED（独立事务）
 * 降级链路   persist()         INSERT orders(status=CONFIRMED) + UPDATE stock  同事务（Redis 不可用时的同步兜底）
 * </pre>
 * 单独抽成一个 Bean 而不是塞进 {@link SeckillService}，是为了让事务边界清晰：
 * <ul>
 *   <li>Redis 预扣减不能回滚，所以必须放在事务之外；</li>
 *   <li>「订单 + 待投递记录」与「状态流转 + 扣库存」各自必须同事务；</li>
 *   <li>独立 Bean 也规避了同类内部方法调用导致 {@code @Transactional} 失效的经典坑。</li>
 * </ul>
 *
 * <p><b>扣库存的幂等键</b>：消费者只做 UPDATE，没有「插入订单撞唯一索引」这个天然把关点
 * （插入撞了就不扣，天然成立；订单一旦在请求线程就落库，把关点就消失了）。
 * 因此改为<b>用订单状态流转的影响行数当许可证</b>：
 * {@code UPDATE ... WHERE status='PENDING'} 影响 1 行才有权扣一次库存。见 {@link #confirm}。
 *
 * <p><b>为什么不再需要 {@code uk_user_stock} 兜底</b>：请求线程建预订单时，
 * 「该用户此前已有订单」会当场撞上 {@code uk_user_stock}，当场回补、当场拒绝，
 * 不必再走「预扣 → 投递 → 消费 → 落库失败 → 回补」一整圈。见 {@link #createPending}。
 */
@Slf4j
@Service
public class SeckillPersistenceService {

    /**
     * 建单（{@link #createPending} / {@link #persist}）的结果。
     * <p>
     * 三个取值对应三种<b>必须分开处理</b>的结局：
     * <ul>
     *   <li>{@link #CREATED} —— 正常写入；</li>
     *   <li>{@link #DUPLICATE} —— 本单号已存在：单号生成器出了故障（同一条消息被重复投递时也会命中），
     *       本次 Redis 预扣对应的就是库里那条订单，<b>绝不能按「用户已购」处置</b>；</li>
     *   <li>{@link #USER_ALREADY_BOUGHT} —— 撞 {@code uk_user_stock}：库里是「另一笔旧订单」，
     *       本次 Redis 预扣是多余的，<b>应当回补库存但保留用户标记</b>。</li>
     * </ul>
     * 这两个来源绝不能合并成同一个取值：调用方无法区分的话，
     * 两种场景必然有一种被处理错——要么白白丢掉库存（少卖），要么把库存加多（超卖）。
     */
    public enum PersistOutcome {
        /** 本次调用真的把订单写进了数据库 */
        CREATED,
        /** 同一单号的订单已存在（消息重复投递 / 单号生成缺陷），本次未做任何扣减，Redis 预扣是正当的 */
        DUPLICATE,
        /** 该用户对这件商品此前已有订单，本次未做任何扣减，Redis 预扣是多余的 */
        USER_ALREADY_BOUGHT
    }

    /**
     * 确认（{@link #confirm}）的结果。
     * <p>
     * 这里是「许可证」语义的三条出路：拿到许可、无权可用、根本没有订单可确认。
     */
    public enum ConfirmOutcome {
        /** 拿到许可证并成功扣减库存 */
        CONFIRMED,
        /** 订单已处于 CONFIRMED / CANCELLED（重复投递），本次无权再扣，也无需任何动作 */
        ALREADY_SETTLED,
        /** 订单表里查不到该单号：无法确认、也无权扣减，按「少卖」处置并告警 */
        ORDER_MISSING
    }

    /**
     * 取消（{@link #cancelPending}）的结果。
     * <p>
     * 调用方要据此决定<b>敢不敢归还 Redis 预扣</b>，因此必须区分到「库存到底被消耗了没有」：
     * 归还错方向就是超卖（多放行），不归还最多是少卖（对账能发现）。
     */
    public enum CancelOutcome {
        /** 本次把 PENDING 置为 CANCELLED：库存尚未归还，调用方必须归还 */
        CANCELLED,
        /** 此前已经取消过：可能已归还、也可能归还前进程消失；调用方应再归还一次（归还动作本身幂等） */
        ALREADY_CANCELLED,
        /** 订单已确认：这笔预扣已被真实消耗（可能只是本线程没观测到），<b>绝不能归还</b> */
        CONFIRMED,
        /** 订单不存在：无法判断预扣是否被消耗，按「少卖」处置，不归还并告警 */
        MISSING
    }

    private final StockMapper stockMapper;
    private final OrderMapper orderMapper;
    private final OutboxService outboxService;

    public SeckillPersistenceService(StockMapper stockMapper, OrderMapper orderMapper,
                                     OutboxService outboxService) {
        this.stockMapper = stockMapper;
        this.orderMapper = orderMapper;
        this.outboxService = outboxService;
    }

    // ==================================================================== 请求线程：建预订单

    /**
     * 建「预订单 + 待投递凭据」，两者<b>同一个本地事务</b>。
     * <p>
     * 这是本项目的 Outbox 语义落点：它记的不是「订单已成立」，而是
     * 「这笔预扣已受理、且一定有人接着处理」。所以订单的初始状态是 PENDING，
     * 真正成立要等消费线程 {@link #confirm} 把状态推到 CONFIRMED。
     * <p>
     * 【为什么只有 {@code uk_user_stock} 可能撞】单号是刚生成的，全局唯一；
     * 命中的唯一现实来源是「该用户此前已下单而 Redis 标记丢了」。
     * 但为了不把单号生成缺陷误判成用户已购，这里仍然走一次 {@link #classifyDuplicate}
     * 反查确认（只在异常分支多一次等值查询，正常路径零开销）。
     *
     * @return {@link PersistOutcome#CREATED} 或 {@link PersistOutcome#USER_ALREADY_BOUGHT}
     *         （理论上还可能是 {@code DUPLICATE}，即单号生成缺陷）
     */
    @Transactional(rollbackFor = Exception.class)
    public PersistOutcome createPending(String orderNo, Long userId, Long stockId, long num) {
        PersistOutcome outcome = insertPendingOrder(orderNo, userId, stockId);
        if (outcome != PersistOutcome.CREATED) {
            return outcome;
        }
        // 与订单插入同事务：enqueue 内部只是一条 INSERT，不自己开事务，
        // 因此它会加入当前事务 —— 这正是「订单与消息记录一起成功或一起失败」的实现方式。
        outboxService.enqueue(orderNo, userId, stockId, num);
        return PersistOutcome.CREATED;
    }

    /**
     * 只建预订单、不写待投递记录。
     * <p>
     * 仅供「显式关闭 outbox」（{@code seckill.outbox.enabled=false}）的同步投递对照路径使用：
     * 那条链路在请求线程直接投 MQ，因此没有需要持久化的「待投递凭据」。
     * <p>
     * 代价必须说清：关掉 outbox 就自愿接受了「消息无持久化凭据」的窗口，
     * 并且多出一条对账<b>也照不到</b>的失败形态 —— 订单已落成 PENDING、消息却没投出去时，
     * 它会一直被认为「在途」：没有 outbox 记录，就没有任何数据库事实能证明「这条单已经被放弃」，
     * 于是对账的等式两边一起偏，结论仍是「一致」（区别于投递器放弃的那条路径，
     * 那条有 FAILED 作为凭据，对账会以 ABANDONED_PENDING 报出来）。
     * 代价就是这条链路自愿付出的，生产路径不应开启它。
     */
    @Transactional(rollbackFor = Exception.class)
    public PersistOutcome createPendingWithoutOutbox(String orderNo, Long userId, Long stockId) {
        return insertPendingOrder(orderNo, userId, stockId);
    }

    /**
     * 插入一条 PENDING 预订单。不自己带 {@code @Transactional}：
     * 由上面两个公开方法提供事务，它在事务内联执行。
     * <p>
     * 【顺序：先插订单、后写凭据】反过来（先写凭据再插订单）时，唯一索引冲突的处置更绕：
     * 凭据已经落了，还得再决定怎么回滚它。而按现顺序，碰撞只会发生在第一条语句上，
     * 事务里没有任何已生效的写操作，直接返回即可。
     */
    private PersistOutcome insertPendingOrder(String orderNo, Long userId, Long stockId) {
        Order order = buildOrder(orderNo, userId, stockId, Order.STATUS_PENDING);

        try {
            orderMapper.insert(order);
        } catch (DuplicateKeyException e) {
            // 两个唯一索引都会抛 DuplicateKeyException，但它们描述的完全是两件事，必须区分开。
            // 事务内实际生效的写操作一条都没有（第一条语句就失败了），提交一个空事务是安全的。
            return classifyDuplicate(orderNo, userId, stockId);
        }
        return PersistOutcome.CREATED;
    }

    // ==================================================================== 消费线程：确认

    /**
     * 确认订单并扣减库存，<b>同一事务</b>。
     * <p>
     * 【幂等键：订单状态流转的影响行数】
     * <pre>
     * UPDATE orders SET status='CONFIRMED' WHERE order_no=? AND status='PENDING'
     *   ├ 影响 1 行 = 本条消息拿到许可证，有权执行一次扣减
     *   └ 影响 0 行 = 已确认或已取消（重投），无权再扣，直接返回
     * </pre>
     * <b>顺序不能反</b>：必须「先改状态、后扣库存」。反过来（先扣后改）时，
     * 重投会先实扣一次库存、再发现状态改不动，而库存已经不回来了。
     * <p>
     * 【扣减失败为什么抛异常而不是就地取消】抛出后整个事务回滚，
     * 订单状态自动退回 PENDING —— 于是「重试」天然是安全的：下次投递会重新拿许可证。
     * 取消与归还库存由消费端的重试耗尽分支统一处置（见 {@code SeckillOrderConsumer}）。
     *
     * @throws BizException 数据库侧库存不足（Redis 与 DB 不一致，需重新预热）
     */
    @Transactional(rollbackFor = Exception.class)
    public ConfirmOutcome confirm(String orderNo, Long userId, Long stockId, long num) {
        int licensed = orderMapper.confirmOrder(orderNo);
        if (licensed == 0) {
            Order existing = orderMapper.selectByOrderNo(orderNo);
            if (existing == null) {
                // outbox 有记录、订单却不存在：只能是订单行被人工/归档清掉了。
                // 此时「这笔预扣是否已被消耗」无法判断，两个方向都可能错，取代价更小的那个：
                // 不归还库存（少卖，对账会以 REDIS_BEHIND 报出来），绝不动标记。
                log.error("[确认·订单缺失 → 需人工介入] 本单号在 orders 表中不存在，"
                                + "无法确认也无权扣减，按「少卖」处置（不归还库存）。"
                                + "orderNo={}, userId={}, stockId={}", orderNo, userId, stockId);
                return ConfirmOutcome.ORDER_MISSING;
            }
            log.info("[确认·幂等命中] 订单已处于 {}，本次不再扣减。orderNo={}, userId={}, stockId={}",
                    existing.getStatus(), orderNo, userId, stockId);
            return ConfirmOutcome.ALREADY_SETTLED;
        }

        int row = stockMapper.deduct(num, stockId);
        if (row == 0) {
            // Redis 已经拦住超卖，正常不会走到这里；一旦出现说明 Redis 与 DB 库存不一致
            // （例如预热之后又手工改过库）。抛异常让事务整体回滚（状态退回 PENDING），
            // 由消费端的重试与重试耗尽分支接管。
            log.error("[确认·库存不一致需人工介入] DB 侧库存不足，本事务回滚、订单退回 PENDING。"
                            + "orderNo={}, stockId={}, num={}", orderNo, stockId, num);
            throw new BizException(ErrorCode.STOCK_NOT_ENOUGH);
        }
        return ConfirmOutcome.CONFIRMED;
    }

    /**
     * 把预订单置为 CANCELLED（条件更新，只从 PENDING 出发）。
     * <p>
     * <b>独立事务</b>：它总是在「消费失败已经把事务搞坏」之后被调用，必须能自己提交。
     * <p>
     * 【为什么取消时只补库存、留标记】取消只发生在「DB 库存不足」这类系统自身不一致的路径上，
     * 用户没有过错。但标记必须保留：标记与「该用户在 orders 里有一行」是同一件事的两种表述，
     * 摘掉标记等于亲手制造一条 {@code MARK_MISSING} 不一致 —— 对账会发现它并把标记再加回去。
     * 代价是这位用户不能重抢（少卖），这与项目「宁可少卖，绝不超卖」的一贯取向一致。
     */
    @Transactional(rollbackFor = Exception.class)
    public CancelOutcome cancelPending(String orderNo) {
        if (orderMapper.cancelOrder(orderNo) == 1) {
            return CancelOutcome.CANCELLED;
        }

        Order existing = orderMapper.selectByOrderNo(orderNo);
        if (existing == null) {
            log.error("[取消·订单缺失 → 需人工介入] 本单号在 orders 表中不存在，无法取消。orderNo={}", orderNo);
            return CancelOutcome.MISSING;
        }
        if (Order.STATUS_CANCELLED.equals(existing.getStatus())) {
            log.warn("[取消·此前已取消] orderNo={} 已是 CANCELLED，归还动作将按其自身幂等再执行一次", orderNo);
            return CancelOutcome.ALREADY_CANCELLED;
        }
        // CONFIRMED：这笔预扣已被真实消耗，归还库存就是超卖。
        // 能走到这里通常意味着「重复投递的兄弟线程已确认成功，而本线程的观测失败」。
        log.error("[取消·订单已确认] orderNo={} 已是 {}，库存已被这次下单真实消耗，"
                + "本次不得归还库存。", orderNo, existing.getStatus());
        return CancelOutcome.CONFIRMED;
    }

    // ==================================================================== 降级链路：同步落库

    /**
     * 扣库存 + 写订单，同一事务内完成，订单直接落成 CONFIRMED。本方法<b>幂等</b>，可被重复调用。
     * <p>
     * 只用于「Redis 不可用」或「MQ 未启用」这两种无法走异步链路的降级场景（见
     * {@link SeckillService#persistSynchronously}）：此时没有 Redis 预扣作为前置过滤，
     * DB 的条件扣减就是唯一的防线，语义也仍是「当场成功」，因此不经过 PENDING。
     *
     * @param orderNo 业务单号，由调用方生成
     * @return 见 {@link PersistOutcome}
     * @throws BizException 数据库侧库存不足
     */
    @Transactional(rollbackFor = Exception.class)
    public PersistOutcome persist(String orderNo, Long userId, Long stockId, long num) {
        // 先写订单、再扣库存，这个顺序不能反，有两个原因：
        // 1) 幂等判断必须在任何写操作之前完成。两个唯一索引会把重复消息挡在第一步，
        //    此时事务里尚无任何变更，直接返回即可；
        // 2) 若先扣库存再插订单，重复消息会先扣掉库存、再因唯一索引冲突而回滚，
        //    白白制造一次「热点行加锁 → 回滚」的开销 —— 而这恰恰是秒杀场景里最贵的操作。
        Order order = buildOrder(orderNo, userId, stockId, Order.STATUS_CONFIRMED);

        try {
            orderMapper.insert(order);
        } catch (DuplicateKeyException e) {
            return classifyDuplicate(orderNo, userId, stockId);
        }

        int row = stockMapper.deduct(num, stockId);
        if (row == 0) {
            log.error("[落库·库存不一致需人工介入] DB 侧库存不足，请重新预热。orderNo={}, stockId={}, num={}",
                    orderNo, stockId, num);
            throw new BizException(ErrorCode.STOCK_NOT_ENOUGH);
        }

        return PersistOutcome.CREATED;
    }

    // ==================================================================== 内部

    /**
     * 组装一条待插入的订单。两个写入点（{@link #insertPendingOrder} 的 PENDING、
     * {@link #persist} 的 CONFIRMED）共用它 —— 差别只有 status 一个参数。
     * <p>
     * <b>为什么不再设置 {@code createTime}</b>：{@code OrderMapper.insert} 的 SQL 只写
     * {@code order_no / user_id / stock_id / status} 四列，而 {@code orders.create_time}
     * 列有 {@code DEFAULT CURRENT_TIMESTAMP}。此前两处都写了 {@code setCreateTime(LocalDateTime.now())}，
     * 但那行赋值<b>根本进不了数据库</b>（列不在 INSERT 列表里），只是让人误以为应用侧控制了时间。
     * 删掉它，时间来源就只剩一个真相：数据库默认值。
     * <p>
     * 若将来需要应用侧时间（例如要求多实例间时钟可比），必须同时把该列加进 INSERT 语句，
     * 否则又会回到「赋值了但没生效」的假象。
     */
    private Order buildOrder(String orderNo, Long userId, Long stockId, String status) {
        Order order = new Order();
        order.setOrderNo(orderNo);
        order.setUserId(userId);
        order.setStockId(stockId);
        order.setStatus(status);
        return order;
    }

    /**
     * 区分「同一单号重复」与「同一用户重复下单」。
     * <p>
     * 判据是<b>本单号在库里是否已经存在</b>：
     * <ul>
     *   <li>已存在 → 重复投递（或单号生成缺陷）；Redis 预扣是正当的，不需要回补；</li>
     *   <li>不存在 → 冲突来自 {@code uk_user_stock}，库里那笔是更早的订单；
     *       本次预扣多余，需要回补库存（但标记要保留）。</li>
     * </ul>
     * 只有落到异常分支时才会多一次等值查询（走 {@code uk_order_no} 索引），
     * 正常路径零额外开销。单号由调用方生成、全局唯一，因此这个判据是可靠的。
     */
    private PersistOutcome classifyDuplicate(String orderNo, Long userId, Long stockId) {
        if (orderMapper.selectByOrderNo(orderNo) != null) {
            log.info("[建单·单号已存在] 本单号在库中已有订单，本次未做任何扣减。orderNo={}, userId={}, stockId={}",
                    orderNo, userId, stockId);
            return PersistOutcome.DUPLICATE;
        }

        log.warn("[建单·用户已购] 撞 uk_user_stock，本次 Redis 预扣为多余，需回补库存且保留标记。"
                + "orderNo={}, userId={}, stockId={}", orderNo, userId, stockId);
        return PersistOutcome.USER_ALREADY_BOUGHT;
    }
}
