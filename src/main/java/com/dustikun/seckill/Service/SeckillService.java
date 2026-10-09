package com.dustikun.seckill.Service;

import com.dustikun.seckill.Common.Exception.BizException;
import com.dustikun.seckill.Common.Exception.ErrorCode;
import com.dustikun.seckill.Common.constant.CompensateType;
import com.dustikun.seckill.Common.result.RollbackResult;
import com.dustikun.seckill.Common.result.SeckillOrderResponse;
import com.dustikun.seckill.Common.util.OrderNoGenerator;
import com.dustikun.seckill.Config.OutboxProperties;
import com.dustikun.seckill.Mapper.OrderMapper;
import com.dustikun.seckill.Metrics.SeckillMetrics;
import com.dustikun.seckill.Mq.SeckillMessage;
import com.dustikun.seckill.Mq.SeckillMessageProducer;
import com.dustikun.seckill.Service.SeckillPersistenceService.PersistOutcome;
import com.dustikun.seckill.entity.Order;
import com.dustikun.seckill.entity.OutboxMessage;
import com.dustikun.seckill.monitor.core.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * 秒杀主链路（Redis 预扣 + 本地消息表 + MQ 异步落库）。
 *
 * <pre>
 * 请求线程（RT 里没有热点行写入：stock 的条件 UPDATE 已挪到消费线程）
 *  ├─ 1. Redis + Lua 原子预扣        ← 库存校验 / 扣减 / 一人一单去重，内存里一次完成
 *  │       └─ Redis 不可用 → 尽力探测是否已生效，再降级为同步落库（保证活动可用）
 *  ├─ 2. 建预订单 + 写待投递凭据      ← 同一个本地事务：orders(PENDING) + seckill_outbox
 *  └─ 3. 立即返回「已受理」
 *
 * 后台投递器（独立于请求线程，见 OutboxDispatchTask）
 *  └─ 4. 扫出 PENDING 记录 → 投 MQ → 标记 SENT；失败按指数退避重试。
 *        耗尽则同一事务里「标记 FAILED + 登记归还待办」，随后立刻尝试归还
 *        （归还由待补偿任务保证，进程消失也追得回来，见 OutboxAbandonService）
 *
 * 消费线程（独立于请求线程与投递器）
 * └─ 5. 确认订单并扣库存（同一事务）：以「状态流转的影响行数」为扣减的幂等键；
 *       失败重试，重试耗尽则取消订单 + 归还预扣
 * </pre>
 *
 * <p><b>为什么订单要先落库</b>：让「订单与消息记录同一事务」真正成立，
 * 同时把 {@code UPDATE stock} 这条热点行锁从请求线程彻底摘掉。
 * 代价是 outbox 记的仍然是<b>意图</b>（订单是 PENDING，不是已成立的结果），
 * 因此它是一个「预订单状态机 + 可靠命令队列」，而不是经典 Outbox 的「已完成结果的通知」。
 * 由于业务结果在请求那一刻还不存在，「用同一个事务记录业务结果」本就没有记录对象可谈。
 *
 * <p><b>相比「请求线程直接同步投递 MQ」的两点关键差异</b>：
 * <ol>
 *   <li>请求线程不再同步投递 MQ，改为写一条窄表的待投递记录。因此
 *       <b>Broker 抖动不再等于用户丢单</b>，也少了一次跨进程往返；</li>
 *   <li>「已受理但未投出」的规模变成可查询的数据库状态（PENDING 条数），
 *       不再依赖 JVM 内存计数器。</li>
 * </ol>
 *
 * <p><b>仍未关闭的窗口（如实说明）</b>：Redis 预扣与「建预订单」那次事务提交之间，
 * 进程消失依然会丢预扣。该窗口是一个本地事务（比单条 INSERT 略宽），语义上没有变化：
 * 它从「依赖外部中间件可达性」缩到了「一次本地事务提交」，但没有消失；
 * 彻底消除要求预扣本身可恢复，属于另一层取舍。兜底手段是
 * {@link StockReconcileService} 的对账。
 */
@Slf4j
@Service
public class SeckillService {

    private static final long DEDUCT_NUM = 1L;

    private final StockCacheService stockCacheService;
    private final SeckillPersistenceService persistenceService;
    private final CompensateTaskService compensateTaskService;
    private final PreDeductCompensator compensator;
    private final OutboxService outboxService;
    private final OutboxProperties outboxProperties;
    private final OrderNoGenerator orderNoGenerator;
    private final OrderMapper orderMapper;

    /**
     * 用 ObjectProvider 而不是直接注入：MQ 关闭时（seckill.mq.enabled=false）容器里根本不存在
     * 这个 Bean，直接注入会导致启动失败。这里按「有则用、无则退化为同步落库」处理。
     */
    private final ObjectProvider<SeckillMessageProducer> producerProvider;

    /**
     * 指标出口。
     * <p>
     * 本类原本自己持有 5 个 {@code AtomicInteger} 计数器，现已全部删除 ——
     * 计数的所有权统一交给 {@link SeckillMetrics}（原因见该类注释：重启归零、多实例各看各的、
     * 没有时间维度算不出 rate）。本类只负责「报告事件发生」，不再负责「保存数字」。
     */
    private final SeckillMetrics metrics;

    public SeckillService(StockCacheService stockCacheService,
                          SeckillPersistenceService persistenceService,
                          CompensateTaskService compensateTaskService,
                          PreDeductCompensator compensator,
                          OutboxService outboxService,
                          OutboxProperties outboxProperties,
                          OrderNoGenerator orderNoGenerator,
                          OrderMapper orderMapper,
                          ObjectProvider<SeckillMessageProducer> producerProvider,
                          SeckillMetrics metrics) {
        this.stockCacheService = stockCacheService;
        this.persistenceService = persistenceService;
        this.compensateTaskService = compensateTaskService;
        this.compensator = compensator;
        this.outboxService = outboxService;
        this.outboxProperties = outboxProperties;
        this.orderNoGenerator = orderNoGenerator;
        this.orderMapper = orderMapper;
        this.producerProvider = producerProvider;
        this.metrics = metrics;
    }

    /**
     * 秒杀下单。返回「已受理」表示这笔预扣已被持久化、等待后台投递与落库，
     * 客户端需凭 {@code orderNo} 调用 {@link #queryOrder} 获取最终结果。
     */
    public SeckillOrderResponse seckill(Long userId, Long stockId) {
        // ---------- 1. Redis 原子预扣 ----------
        boolean preDeducted = false;
        try {
            stockCacheService.tryDeduct(stockId, userId, DEDUCT_NUM);
            preDeducted = true;
        } catch (DataAccessException e) {
            // Redis 故障降级：宁可回落到数据库条件扣减，也不能让活动整体不可用。
            //
            // 【为什么不能直接按「未生效」处理】Redis 命令可能**已经执行成功**，只是响应在网络
            // 上超时——直接按 false 处理会让这次预扣在后续落库失败时永远得不到回补，
            // 变成无人认领的孤儿。因此先尽力探测一次，探测不出来才退回保守假设。
            boolean actuallyDeducted = probePreDeducted(stockId, userId);
            return persistSynchronously(userId, stockId, actuallyDeducted, e);
        }

        SeckillMessageProducer producer = producerProvider.getIfAvailable();
        if (producer == null) {
            // MQ 未启用，但库存已经在 Redis 里扣掉了，必须当场落库，
            // 否则这笔预扣就成了无人认领的孤儿（既没有消息，也没人补偿）
            return persistSynchronously(userId, stockId, preDeducted, null);
        }

        String orderNo = orderNoGenerator.next();

        // ------------------------------------------------------------------
        // 【把单号写进日志上下文，这是「一单到底怎么了」唯一可用的连接键】
        //
        // 请求线程此时才知道单号（它由 Snowflake 现场生成），因此只能在这里写；
        // 而消费线程处理同一条消息时会用它作为 traceId（见 SeckillOrderConsumer），
        // 于是两侧日志都带上了 orderNo=…，一行 grep 就能把两段拼起来。
        //
        // 不这样做的话，两侧的 traceId 是两套值（请求侧是随机 hex，消费侧是 mq-<单号>），
        // 排查一单问题要先用时间戳猜、再人工比对单号 —— 而「猜」正是这套日志要消灭的东西。
        // ------------------------------------------------------------------
        TraceContext.put(TraceContext.ORDER_NO, orderNo);
        TraceContext.put(TraceContext.STOCK_ID, String.valueOf(stockId));

        if (!outboxProperties.isEnabled()) {
            return acceptWithoutOutbox(orderNo, userId, stockId, preDeducted, producer);
        }
        return acceptWithOutbox(orderNo, userId, stockId, preDeducted);
    }

    /**
     * 正常链路：一个事务里写入「PENDING 预订单 + 待投递凭据」，然后立即返回。
     * <p>
     * 请求线程到此为止，此后投递与扣库存都不再依赖它存活。
     * 唯一的数据库写是这个双表事务：没有热点行 UPDATE、没有业务失败分支
     * （库存是否够已经由 Redis 在前面判过了），因此它可以稳定收敛。
     */
    private SeckillOrderResponse acceptWithOutbox(String orderNo, Long userId, Long stockId,
                                                  boolean preDeducted) {
        PersistOutcome outcome;
        try {
            outcome = persistenceService.createPending(orderNo, userId, stockId, DEDUCT_NUM);
        } catch (Exception e) {
            // 订单与凭据都没落成，这笔预扣又回到了「无人认领」的状态，必须当场归还
            requireCompensated(stockId, userId, preDeducted, "写入预订单与待投递记录失败", e);
            throw e;
        }
        rejectIfRepeated(outcome, orderNo, userId, stockId, preDeducted, "建预订单撞 uk_user_stock");

        metrics.onQueued();
        return SeckillOrderResponse.queued(orderNo);
    }

    /**
     * 同步投递对照链路（{@code seckill.outbox.enabled=false}）：关掉 outbox，
     * 请求线程直接投 MQ，用同一份消费端代码对比两种方案的差异。
     * <p>
     * 【为什么这里也要先建预订单】消费者的确认动作要求订单已存在（它只做状态流转）。
     * 若沿用「先投消息、订单由消费端创建」的做法，取消这一步就无处落脚 ——
     * 投递失败时已扣的库存无人归还。所以这里先建 PENDING 订单、再投递，
     * 投递失败则取消订单 + 归还库存。
     * <p>
     * 【这条链路自愿放弃的东西】没有持久化凭据：进程若在「订单已建、消息未投」之间消失，
     * 这笔预订单会永久停在 PENDING 被当作在途。注意这<b>不是</b>对账能报出来的问题：
     * 没有 outbox 记录就没有「已放弃」这个事实，对账的等式两边一起偏，结论仍是「一致」
     * （有凭据的投递器放弃路径才会被报成 ABANDONED_PENDING）。生产路径不应开启这个开关。
     */
    private SeckillOrderResponse acceptWithoutOutbox(String orderNo, Long userId, Long stockId,
                                                     boolean preDeducted, SeckillMessageProducer producer) {
        PersistOutcome outcome;
        try {
            outcome = persistenceService.createPendingWithoutOutbox(orderNo, userId, stockId);
        } catch (Exception e) {
            requireCompensated(stockId, userId, preDeducted, "写入预订单失败", e);
            throw e;
        }
        rejectIfRepeated(outcome, orderNo, userId, stockId, preDeducted, "建预订单撞 uk_user_stock");

        try {
            producer.sendOrderCreated(SeckillMessage.of(orderNo, userId, stockId, DEDUCT_NUM));
        } catch (Exception e) {
            // 投递没成功，但预订单已经落库：必须先把它取消掉，否则它会一直被认为「在途」，
            // 而库存归还之后这个状态就再也没人来纠正了（没有凭据可以重投）。
            cancelThenCompensate(orderNo, userId, stockId, preDeducted, e);
            throw e;
        }

        metrics.onQueued();
        return SeckillOrderResponse.queued(orderNo);
    }

    /**
     * 「投递失败」的收尾动作：先取消预订单，再归还预扣。
     * <p>
     * 【为什么要单独成一个方法】原先这段是嵌在 {@code catch} 里的两层 {@code try}，
     * 主流程因此缩进到第四层，而它承担的却是一个很清晰的语义：
     * 「订单要先不成立，库存才能还回去」。
     * <p>
     * 【为什么取消失败不能让它冒出去】一旦它覆盖原始异常，下面的补偿就不会执行，
     * 库存会直接消失且无人知晓。所以取消失败只记日志，继续走补偿 ——
     * 残留是一条永远停在 PENDING 的订单：它会被算作在途，因此对账也报不出来
     * （这条链路自愿放弃了 outbox，也就放弃了「证明它已被放弃」的凭据，生产路径不应开启它）。
     */
    private void cancelThenCompensate(String orderNo, Long userId, Long stockId,
                                      boolean preDeducted, Exception cause) {
        try {
            persistenceService.cancelPending(orderNo);
        } catch (Exception cancelFailure) {
            log.error("[降级·取消失败 → 需人工介入] MQ 投递失败后取消预订单也失败了，"
                            + "该订单会一直停在 PENDING。orderNo={}, stockId={}, userId={}",
                    orderNo, stockId, userId, cancelFailure);
        }
        requireCompensated(stockId, userId, preDeducted, "MQ 投递失败", cause);
    }

    /**
     * 建单时撞上唯一索引：两种来源的处置<b>相反</b>，绝不能合并。
     * <ul>
     *   <li>{@code USER_ALREADY_BOUGHT} —— 用户此前已买过（Redis 标记因故丢失才会走到这里）。
     *       本次预扣多余：归还库存但<b>保留标记</b>，然后拒绝这次请求。</li>
     *   <li>{@code DUPLICATE} —— 刚生成的单号在库里已存在，属单号生成缺陷：必须告警，
     *       并且这次预扣对应的订单并没有落成，所以要完整回滚。</li>
     * </ul>
     * 这个判定放在请求线程完成：命中时当场回补、当场拒绝，
     * 不再需要走完「预扣 → 投递 → 消费 → 落库失败 → 回补」一整圈。
     */
    private void rejectIfRepeated(PersistOutcome outcome, String orderNo, Long userId, Long stockId,
                                  boolean preDeducted, String reason) {
        switch (outcome) {
            case CREATED -> {
                // 正常建单，继续往下
            }
            case USER_ALREADY_BOUGHT -> {
                // 只回补库存、保留标记：他确实持有订单，不该被重新放行。
                // 回补必须在事务之外做 —— Redis 不是数据库事务的成员，
                // 在 @Transactional 方法内调用会在事务提交前就改掉 Redis。
                if (!restoreStockOnly(stockId, userId, orderNo, reason)) {
                    throw new BizException(ErrorCode.COMPENSATE_FAILED);
                }
                throw new BizException(ErrorCode.REPEAT_ORDER);
            }
            case DUPLICATE -> {
                log.error("[建单·单号冲突] 新生成的单号在库中已存在，属单号生成缺陷。orderNo={}", orderNo);
                requireCompensated(stockId, userId, preDeducted, "新生成单号与库中已有订单冲突", null);
                throw new BizException(ErrorCode.SYSTEM_BUSY);
            }
        }
    }

    /**
     * 查询订单最终状态。
     * <p>
     * 判据是「<b>订单状态优先</b>」——「处理中」有数据库证据：
     * <ol>
     *   <li>订单存在且 {@code CONFIRMED} → {@code SUCCESS}；
     *   <li>订单存在且 {@code CANCELLED} → {@code FAILED}（后台已放弃并归还预扣）；
     *   <li>订单存在且 {@code PENDING} → {@code QUEUED}，<b>不再依赖 Redis 标记兜底</b>；
     *   <li>订单不存在 → 退回旧判据：标记还在就是 {@code QUEUED}，否则 {@code FAILED}。</li>
     * </ol>
     * 第 4 条是为两类场景保留的兜底：Redis 故障期间的同步降级链路（订单本就不落这里），
     * 以及「Redis 已扣、预订单未写入」的未关闭窗口。
     * <p>
     * 【为什么 outbox 状态不作为判据，只用来补充原因】
     * <p>
     * outbox 的 {@code SENT} 只意味着「消息已成功交给 Broker」，<b>不等于消费成功</b>。
     * 消费端最终失败时会把订单置为 CANCELLED、把预扣归还，而 outbox 那条记录会永远停在
     * {@code SENT}——若拿它当判据，这类订单会显示成 {@code QUEUED} 永远不结束，
     * 用户既等不到结果、也不敢重抢。订单状态则天然承载了这条信息。
     * <p>
     * 这也解释了为什么回补脚本必须把「回补成功」与「幂等命中」区分开：
     * 区分不出来的话，这一层就无法判断用户是「还在等」还是「已经可以重试了」。
     */
    public SeckillOrderResponse queryOrder(Long userId, Long stockId, String orderNo) {
        // 1. 订单表是唯一权威：三种状态各自对应一个确定的结局
        Order order = orderMapper.selectByOrderNo(orderNo);
        if (order != null) {
            if (Order.STATUS_CONFIRMED.equals(order.getStatus())) {
                return SeckillOrderResponse.success(orderNo);
            }
            if (Order.STATUS_CANCELLED.equals(order.getStatus())) {
                logFailureReason(orderNo);
                return SeckillOrderResponse.failed(orderNo);
            }
            // PENDING：已受理、正在投递或消费的路上。这条证据在数据库里，因此比标记更可靠。
            return SeckillOrderResponse.queued(orderNo);
        }

        // 2. 订单不存在：降级链路，或「Redis 已扣、预订单未写成」的未关闭窗口。
        //    此时只能退回标记：标记在 = 预扣仍被占用，标记没了 = 预扣已归还。
        if (stockCacheService.isBought(stockId, userId)) {
            return SeckillOrderResponse.queued(orderNo);
        }

        logFailureReason(orderNo);
        return SeckillOrderResponse.failed(orderNo);
    }

    /**
     * 为「失败」的查询补一条日志说明原因。查一次 outbox 只为让日志说清「为什么失败」，不参与判定。
     */
    private void logFailureReason(String orderNo) {
        OutboxMessage outbox = outboxService.findByOrderNo(orderNo);
        if (outbox == null) {
            // 没记录：MQ 关闭时的同步链路，或记录已被归档清理，属正常情形。
            log.debug("[查单·失败] orderNo={} 无待投递记录，按最终失败返回", orderNo);
        } else if (OutboxMessage.STATUS_PENDING.equals(outbox.getStatus())) {
            // 还没投递却已失败：不是投递器放弃的路径（放弃会先改 FAILED），值得单独看见。
            log.warn("[查单·失败] orderNo={} 待投递记录仍为 PENDING 但已判定失败，"
                    + "请核对该活动的对账结果", orderNo);
        } else {
            log.warn("[查单·失败] orderNo={} outbox={}, lastError={}",
                    orderNo, outbox.getStatus(), outbox.getLastError());
        }
    }

    /**
     * Redis 预扣结果探测（尽力而为）。
     * <p>
     * 场景：{@code tryDeduct} 抛了 {@code DataAccessException}，但命令究竟执行了没有并不确定。
     * 直接假设「没执行」是安全的（不回补最多少卖，误回补却会超卖），但它会让
     * 「其实执行了」的那部分预扣永远没人管——所以先探测一次，能确认就确认。
     * <p>
     * 探测顺序很重要：<b>先查数据库订单，再查 Redis 标记</b>。
     * 因为已购标记可能来自该用户此前的一次成功下单，单看它会把「此前买过」误判成「本次扣过」，
     * 从而在后续失败时错误地把标记摘掉、把库存补多。
     *
     * @return true 表示可以确认本次预扣已生效；任何不确定的情况一律返回 false（保守）
     */
    private boolean probePreDeducted(Long stockId, Long userId) {
        try {
            if (orderMapper.selectByUserAndStock(userId, stockId) != null) {
                // 该用户此前已经下过单：标记可能来自那次下单，无法据此判断本次是否真的扣了。
                // 按「未生效」处理 —— 不回补最多少卖，误回补却会造成超卖。
                log.warn("[降级·探测] 用户此前已有订单，无法判定本次预扣是否生效，按未生效处理。stockId={}, userId={}",
                        stockId, userId);
                return false;
            }
            boolean bought = stockCacheService.isBought(stockId, userId);
            log.warn("[降级·探测] 预扣结果探测完成：{}。stockId={}, userId={}",
                    bought ? "命令已生效，后续落库失败将回补" : "命令未生效", stockId, userId);
            return bought;
        } catch (Exception probeFailure) {
            // 探测本身也失败（Redis 仍未恢复）：保守按「未生效」处理。
            // 这条路径没有补偿可用，剩下的只能交给对账任务去比对真实状态。
            log.warn("[降级·探测失败] Redis 仍不可用，按未生效处理，交由对账任务兜底。stockId={}, userId={}",
                    stockId, userId, probeFailure);
            return false;
        }
    }

    /**
     * 同步落库兜底：仅在「Redis 不可用」或「MQ 未启用」这两种无法走异步链路的情况下使用。
     *
     * @param preDeducted Redis 预扣是否确实已生效。生效了就必须在落库失败时回补
     * @param cause       Redis 故障原因；为 null 表示本次降级是因为 MQ 未启用
     */
    private SeckillOrderResponse persistSynchronously(Long userId, Long stockId,
                                                      boolean preDeducted, DataAccessException cause) {
        if (cause == null) {
            log.warn("[降级] MQ 未启用，预扣已生效，改为同步落库。stockId={}, userId={}", stockId, userId);
        } else {
            metrics.onDegraded();
            log.error("[降级] Redis 不可用，回落 MySQL 条件扣减。stockId={}, userId={}", stockId, userId, cause);
        }

        String orderNo = orderNoGenerator.next();
        PersistOutcome outcome;
        try {
            outcome = persistenceService.persist(orderNo, userId, stockId, DEDUCT_NUM);
        } catch (Exception e) {
            requireCompensated(stockId, userId, preDeducted, "降级同步落库失败", e);
            throw e;
        }

        // 降级路径与请求线程共用同一套「撞唯一索引」判定：两种来源的处置相反，不能各写一遍。
        rejectIfRepeated(outcome, orderNo, userId, stockId, preDeducted, "降级同步落库撞 uk_user_stock");

        metrics.onSyncSuccess();
        return SeckillOrderResponse.success(orderNo);
    }

    /**
     * 补偿之后必须检查补偿是否成功。
     * <p>
     * 若把 {@code compensate} 的异常吞掉，调用方只看到原始的 {@code persist} 异常，
     * 完全不知道「补偿也失败了」——而这才是真正需要人工介入的状态。这里把它显式化：
     * 补偿失败就抛一个独立错误码，让上游与告警能把它识别出来。
     */
    private void requireCompensated(Long stockId, Long userId, boolean preDeducted,
                                    String reason, Exception original) {
        if (compensate(stockId, userId, preDeducted, reason)) {
            return;
        }
        log.error("[补偿失败 → 需人工介入] reason={}, stockId={}, userId={}", reason, stockId, userId, original);
        throw new BizException(ErrorCode.COMPENSATE_FAILED, original);
    }

    /**
     * 预扣减补偿：完整回滚，库存与用户标记一起还原。
     * <p>
     * 只有 Redis 确实扣过才需要回补；数据库侧的扣减由 {@code @Transactional} 自己回滚。
     *
     * @return true 表示库存已归还、或确认无需归还；false 表示补偿失败（已登记待补偿任务）
     */
    private boolean compensate(Long stockId, Long userId, boolean preDeducted, String reason) {
        if (!preDeducted) {
            return true;
        }
        // 实际动作抽到 PreDeductCompensator：后台投递器（Outbox 重试耗尽）走的是同一段逻辑。
        // 两处各写一遍最容易出现的偏差就是「一边登记了待补偿任务、另一边只打日志」——
        // 而后者正是库存静默丢失的来源。
        return switch (compensator.rollbackAll(stockId, userId, DEDUCT_NUM, reason)) {
            case ROLLED_BACK -> {
                metrics.onRollbackAll();
                yield true;
            }
            case BENIGN -> true;
            case FAILED -> false;
        };
    }

    /**
     * 只回补库存、保留用户标记。
     * <p>
     * 用于「用户其实已经买过」：库存要还回去，但标记必须留着，
     * 否则用户在 Redis 侧会被重新放行，陷入「放行 → DB 拦截 → 回补 → 再放行」的死循环。
     *
     * @return true 表示库存已归还、或确认无需归还；false 表示失败（已登记待补偿任务）
     */
    private boolean restoreStockOnly(Long stockId, Long userId, String orderNo, String reason) {
        try {
            RollbackResult result = stockCacheService.restoreStockOnly(stockId, userId, orderNo, DEDUCT_NUM);
            if (result.rolledBack()) {
                metrics.onRestoreStockOnly();
                log.warn("[回补·仅库存] 多余预扣已回补，已购标记保留。stockId={}, userId={}, orderNo={}, reason={}, remain={}",
                        stockId, userId, orderNo, reason, result.remainStock());
                return true;
            }
            if (result.benign()) {
                log.info("[回补·仅库存] 无需回补（{}）。stockId={}, userId={}, orderNo={}",
                        result.status(), stockId, userId, orderNo);
                return true;
            }
            log.error("[回补·仅库存失败·已登记待补偿] status={}, stockId={}, userId={}, orderNo={}",
                    result.status(), stockId, userId, orderNo);
            compensateTaskService.enqueue(stockId, userId, orderNo, DEDUCT_NUM,
                    CompensateType.RESTORE_STOCK_ONLY, reason + " | 回补失败：" + result.status());
            return false;
        } catch (Exception ex) {
            log.error("[回补·仅库存异常·已登记待补偿] stockId={}, userId={}, orderNo={}",
                    stockId, userId, orderNo, ex);
            compensateTaskService.enqueue(stockId, userId, orderNo, DEDUCT_NUM,
                    CompensateType.RESTORE_STOCK_ONLY,
                    reason + " | 回补异常：" + ex.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * 把数据库库存重新预热到 Redis。
     * <p>
     * 它同时是「降级之后重新对齐两边」的手段：预热直接取数据库当前库存，
     * 因此 Redis 因故障期间被绕过而落后的部分会在这里一次性追平。
     */
    public long preheat(Long stockId) {
        return stockCacheService.preheat(stockId);
    }

    /**
     * 查询 Redis 侧剩余库存，未预热返回 null。
     */
    public Integer remain(Long stockId) {
        return stockCacheService.remain(stockId);
    }

    /**
     * 清理活动缓存。
     */
    public void clear(Long stockId) {
        stockCacheService.clear(stockId);
    }

    // 本类原先在这里暴露 5 个 getXxxCount() 读 JVM 计数器。现已删除：
    // 数读 SeckillMetrics.xxxCount()，值本身仍存在（就在 MeterRegistry 里），
    // /actuator/prometheus 与 /seckill/metrics 读的是同一份，两个出口不可能不一致。
}
