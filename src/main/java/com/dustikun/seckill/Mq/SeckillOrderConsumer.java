package com.dustikun.seckill.Mq;

import com.dustikun.seckill.Common.constant.CompensateType;
import com.dustikun.seckill.Common.result.RollbackResult;
import com.dustikun.seckill.Config.RocketMqProperties;
import com.dustikun.seckill.Metrics.SeckillMetrics;
import com.dustikun.seckill.Service.CompensateTaskService;
import com.dustikun.seckill.Service.SeckillPersistenceService;
import com.dustikun.seckill.Service.SeckillPersistenceService.CancelOutcome;
import com.dustikun.seckill.Service.SeckillPersistenceService.ConfirmOutcome;
import com.dustikun.seckill.Service.StockCacheService;
import com.dustikun.seckill.monitor.core.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.message.MessageConst;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * 秒杀订单消费者：把「确认订单 + 扣库存」从请求线程搬到独立线程池。
 * <p>
 * 请求线程只做到「写入预订单与待投递凭据」为止，数据库热点行写入
 * 由这里的消费线程异步完成，于是请求的 RT 不再被数据库拖住，数据库连接池也不会再被大促流量占满。
 * <p>
 * 消费端要处理四件事：
 * <ol>
 *   <li><b>幂等</b>：MQ 保证的是「至少一次」投递，重复消费一定会发生。
 *       幂等键不是「插入撞唯一索引」（订单已在请求线程落地），而是
 *       {@code UPDATE orders ... WHERE status='PENDING'} 的<b>影响行数</b>：
 *       1 行才有权扣一次库存，0 行说明已被确认或已取消。见 {@link ConfirmOutcome}。</li>
 *   <li><b>重试</b>：确认失败（数据库瞬时故障、DB 库存不足等）返回 RECONSUME_LATER 让 RocketMQ 重投。
 *       事务回滚会把订单退回 PENDING，因此重投是安全的。</li>
 *   <li><b>补偿</b>：重试次数耗尽仍失败时，<b>先取消订单、再归还 Redis 预扣</b>（顺序不可颠倒，
 *       见 {@link #handleFailure}）；取消或归还本身失败则落待补偿任务表，
 *       由定时任务持续重试——而不是只打一行日志了事。</li>
 *   <li><b>不越权</b>：只有在订单确实处于 CANCELLED 时才归还库存。
 *       若订单其实已被确认（重复投递的兄弟线程成功、而本线程观测失败），
 *       归还库存就等于超卖——宁可少卖。</li>
 * </ol>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "seckill.mq", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SeckillOrderConsumer implements MessageListenerConcurrently {

    private final ObjectMapper objectMapper;
    private final SeckillPersistenceService persistenceService;
    private final StockCacheService stockCacheService;
    private final CompensateTaskService compensateTaskService;
    private final RocketMqProperties properties;

    /**
     * 指标出口。计数不再由本类自己维护。
     * <p>
     * 【为什么这一组迁移收益最大】消费侧是本项目真正的瓶颈段（4/8/16 线程排空速率恒为
     * ~152~157 单/秒，见 docs/压测报告-B轮消费线程调参.md）。原先是 JVM 计数器时，
     * 只能看到「累计确认了多少」，看不到「现在的确认速率是多少」——
     * 而判断瓶颈是否被推开，靠的正是 {@code rate(seckill_consume_confirmed_total[1m])}。
     */
    private final SeckillMetrics metrics;

    public SeckillOrderConsumer(ObjectMapper objectMapper,
                                SeckillPersistenceService persistenceService,
                                StockCacheService stockCacheService,
                                CompensateTaskService compensateTaskService,
                                RocketMqProperties properties,
                                SeckillMetrics metrics) {
        this.objectMapper = objectMapper;
        this.persistenceService = persistenceService;
        this.stockCacheService = stockCacheService;
        this.compensateTaskService = compensateTaskService;
        this.properties = properties;
        this.metrics = metrics;
    }

    @Override
    public ConsumeConcurrentlyStatus consumeMessage(List<MessageExt> messages, ConsumeConcurrentlyContext context) {
        for (MessageExt messageExt : messages) {
            ConsumeConcurrentlyStatus status = consumeOne(messageExt);
            if (status == ConsumeConcurrentlyStatus.RECONSUME_LATER) {
                return status;
            }
        }
        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
    }

    private ConsumeConcurrentlyStatus consumeOne(MessageExt messageExt) {
        SeckillMessage message = parse(messageExt);
        if (message == null) {
            // 消息体都解析不出来，重试多少次结果都一样，只能丢弃并靠日志告警
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        }

        // ------------------------------------------------------------------
        // 【为什么必须在这里开一个 tracing 作用域】
        //
        // MDC 底层是 ThreadLocal，它<b>不会</b>从请求线程传播到消费线程。没有这一段时：
        //   · 消费侧所有日志的 traceId 为空（或更糟：残留着上一条消息的值，
        //     因为 ConsumeMessageConcurrentlyService 的线程池会复用线程）；
        //   · 于是「一次下单的建单日志」与「它被消费的日志」无法用同一个值串起来，
        //     而 SPEC 第 12 节的诊断流程恰恰要求跨这两段取证。
        //
        // 作用域用 try-with-resources 包住<b>整条消费路径</b>（含 handleFailure），
        // 因为失败路径的日志才是最需要 traceId 的那些。
        // ------------------------------------------------------------------
        try (TraceContext.Scope ignored = TraceContext.open(
                resolveTraceId(messageExt, message), "consumeOne",
                Map.of(TraceContext.ORDER_NO, String.valueOf(message.orderNo()),
                        TraceContext.STOCK_ID, String.valueOf(message.stockId())))) {
            return doConsume(messageExt, message);
        }
    }

    /**
     * 解析这条消息的追踪标识。
     *
     * <p><b>三级来源，按可靠性排序</b>：
     * <ol>
     *   <li><b>RocketMQ 的 {@code KEYS} 属性</b>（生产者用 orderNo 作为消息 key 写下，
     *       见 {@code SeckillMessageProducer#toMqMessage}）。这是 Broker 侧也能看到的字段，
     *       因此它让「日志里的 traceId」与「Broker 控制台里的消息」对得上 ——
     *       这是本方案能给出的最强关联。</li>
     *   <li><b>消息体里的 orderNo</b>。它一定存在，且同样能唯一定位一次下单。</li>
     *   <li>兜底新生成一个。走到这里说明消息体与属性都不可靠，
     *       此时仍要给一个值 —— 空 traceId 会让后续排查误以为「这条日志不属于任何请求」。</li>
     * </ol>
     *
     * <p><b>为什么加前缀而不是直接用 orderNo</b>：日志里 {@code traceId=SN175...}
     * 会被读成「单号」而不是「追踪标识」，而两者在语义上不同（一个标识订单、
     * 一个标识调用链）。前缀让它们一眼可辨。
     */
    private static String resolveTraceId(MessageExt messageExt, SeckillMessage message) {
        String keys = messageExt.getProperty(MessageConst.PROPERTY_KEYS);
        if (keys != null && !keys.isBlank()) {
            return "mq-" + keys.trim();
        }
        return "mq-" + message.orderNo();
    }

    /** 真正的消费动作。抽出来是为了让 tracing 作用域只出现在一个地方，不容易被误删 */
    private ConsumeConcurrentlyStatus doConsume(MessageExt messageExt, SeckillMessage message) {
        try {
            ConfirmOutcome outcome = persistenceService.confirm(
                    message.orderNo(), message.userId(), message.stockId(), message.num());

            switch (outcome) {
                case CONFIRMED -> {
                    metrics.onConsumeConfirmed();
                    log.info("[消费·确认成功] orderNo={}, userId={}, stockId={}, 投递到确认耗时={}ms",
                            message.orderNo(), message.userId(), message.stockId(),
                            System.currentTimeMillis() - message.createTime());
                }
                case ALREADY_SETTLED -> {
                    // 同一条消息被重复投递，而订单此前已经确认或取消：本次没有许可证，也无需任何动作。
                    // 绝不能顺手归还库存 —— 那正是「把幂等命中当成失败」的做法，会直接把库存补多。
                    metrics.onConsumeDuplicate();
                    log.info("[消费·重复投递] 订单已结案（无许可证），不做任何扣减。orderNo={}, reconsumeTimes={}",
                            message.orderNo(), messageExt.getReconsumeTimes());
                }
                case ORDER_MISSING -> {
                    // outbox 有记录、orders 里却没有这条订单：只能是订单行被人工/归档清掉了。
                    // 此时「这笔预扣是否已被消耗」无法判断，不归还库存（少卖方向），并让它显式可见。
                    metrics.onConsumeOrderMissing();
                    log.error("[消费·订单缺失 → 需人工介入] 待投递凭据对应的订单不存在。"
                                    + "orderNo={}, userId={}, stockId={}",
                            message.orderNo(), message.userId(), message.stockId());
                }
            }
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        } catch (Exception e) {
            return handleFailure(messageExt, message, e);
        }
    }

    /**
     * 确认失败：能重试就重试，重试次数用尽则取消订单并归还库存。
     */
    private ConsumeConcurrentlyStatus handleFailure(MessageExt messageExt, SeckillMessage message, Exception cause) {
        int reconsumeTimes = messageExt.getReconsumeTimes();
        boolean lastChance = reconsumeTimes + 1 >= properties.getMaxReconsumeTimes();

        if (!lastChance) {
            log.warn("[消费失败·将重试] orderNo={}, reconsumeTimes={}/{}",
                    message.orderNo(), reconsumeTimes, properties.getMaxReconsumeTimes(), cause);
            return ConsumeConcurrentlyStatus.RECONSUME_LATER;
        }

        metrics.onConsumeFailed();
        log.error("[消费·重试耗尽] 放弃本条消息：先取消订单，确认取消成功后才归还 Redis 预扣。"
                        + "orderNo={}, userId={}, stockId={}, reconsumeTimes={}",
                message.orderNo(), message.userId(), message.stockId(), reconsumeTimes, cause);

        // 【顺序不可颠倒：先取消订单，再归还库存】
        // 反过来的话，若在两步之间进程消失，订单仍是 PENDING，重投会让 confirmOrder 再次拿到许可证
        // 并真实扣减库存 —— 于是「库存已归还」与「订单成立」同时发生，就是超卖（不可修复）。
        // 按现顺序最坏情况是「订单已取消、库存未归还」= 少卖，对账能发现；两者代价不对称，
        // 因此顺序选择明确偏向「宁可少卖，绝不超卖」。
        CancelOutcome cancel;
        try {
            cancel = persistenceService.cancelPending(message.orderNo());
        } catch (Exception cancelFailure) {
            // 连取消都没成功：此时【绝不能】归还库存 —— 订单仍是 PENDING，
            // 重投依然可能拿到许可证并成功扣减。登记待补偿任务，让后台把「取消 + 归还」做完。
            log.error("[消费·重试耗尽·取消失败 → 已登记待补偿] orderNo={}, userId={}, stockId={}",
                    message.orderNo(), message.userId(), message.stockId(), cancelFailure);
            enqueueCompensate(message, CompensateType.CANCEL_ORDER,
                    "重试耗尽后取消失败：" + cancelFailure.getClass().getSimpleName());
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        }

        switch (cancel) {
            case CANCELLED, ALREADY_CANCELLED -> {
                // 订单已经（或被之前某次尝试）取消，可以安全归还预扣。
                // ALREADY_CANCELLED 也不能跳过归还：上一次可能正是在「已取消、未归还」之间崩掉的，
                // 而归还是幂等的（脚本里的一次性去重键），多做一次没有代价。
                metrics.onConsumeCancelled();
                restoreStockOnly(message);
            }
            case CONFIRMED -> {
                // 订单其实已经确认成功，只是本线程的观测失败了（例如重复投递的兄弟线程先提交）。
                // 库存已被这次下单真实消耗，归还就是超卖。这里什么都不做才是正确的。
                log.error("[消费·重试耗尽] 订单其实已确认成功，本次异常属观测失败，不归还库存。"
                        + "orderNo={}, userId={}", message.orderNo(), message.userId());
            }
            case MISSING -> {
                // 订单不存在：无法判断预扣是否被消耗，不归还（少卖方向），并让它在待补偿表里可见
                log.error("[消费·重试耗尽·订单缺失 → 已登记待补偿] orderNo={}, userId={}, stockId={}",
                        message.orderNo(), message.userId(), message.stockId());
                enqueueCompensate(message, CompensateType.CANCEL_ORDER,
                        "重试耗尽后取消时订单不存在");
            }
        }

        // 返回 CONSUME_SUCCESS（而不让它进死信队列）是刻意为之：
        // 订单已取消、库存已归还（或已登记待补偿），这条消息代表的「预扣」在系统里已不复存在；
        // 若把它留在 DLQ 被人工重放，就会在库存已归还的前提下重新确认订单，反而制造出超卖。
        // 所以此处确认掉消息，靠 ERROR 日志 + 待补偿任务留痕。
        // 生产环境更稳妥的做法是把这类消息转投到一个「仅供人工审阅、不可自动重放」的死信 Topic，
        // 而不是直接塞进可一键重放的 DLQ。
        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
    }

    /**
     * 只归还库存、<b>保留</b>标记：订单已被取消，但这位用户在 orders 里仍有一行记录。
     * <p>
     * 标记必须留着 —— 它和「该用户有一行订单」是同一件事的两种表述。
     * 摘掉标记等于亲手制造一条 {@code MARK_MISSING} 不一致：对账会发现它，并把标记再加回来。
     * 代价是这位用户不能重抢（少卖），与项目「宁可少卖，绝不超卖」的一贯取向一致。
     * <p>
     * 幂等由脚本里的「活动 + 单号」一次性去重键保证，所以这里的重试与待补偿重试都不会补第二次。
     */
    private void restoreStockOnly(SeckillMessage message) {
        try {
            RollbackResult result = stockCacheService.restoreStockOnly(
                    message.stockId(), message.userId(), message.orderNo(), message.num());
            if (result.rolledBack()) {
                metrics.onConsumeStockRestored();
                log.warn("[消费·已取消] 预扣已归还，剩余库存={}，已购标记保留。orderNo={}",
                        result.remainStock(), message.orderNo());
            } else if (result.benign()) {
                log.info("[消费·已取消] 无需归还（{}）。orderNo={}", result.status(), message.orderNo());
            } else {
                log.error("[消费·已取消·归还失败] status={}, orderNo={}", result.status(), message.orderNo());
                enqueueCompensate(message, CompensateType.RESTORE_STOCK_ONLY,
                        "订单已取消，预扣归还失败：" + result.status());
            }
        } catch (Exception e) {
            log.error("[消费·已取消·归还异常] orderNo={}", message.orderNo(), e);
            enqueueCompensate(message, CompensateType.RESTORE_STOCK_ONLY,
                    "订单已取消，预扣归还异常：" + e.getClass().getSimpleName());
        }
    }

    private void enqueueCompensate(SeckillMessage message, CompensateType type, String reason) {
        compensateTaskService.enqueue(message.stockId(), message.userId(),
                message.orderNo(), message.num(), type, reason);
    }

    private SeckillMessage parse(MessageExt messageExt) {
        try {
            return objectMapper.readValue(messageExt.getBody(), SeckillMessage.class);
        } catch (Exception e) {
            log.error("[消费·消息不可解析] 丢弃。msgId={}, topic={}, body={}",
                    messageExt.getMsgId(), messageExt.getTopic(),
                    new String(messageExt.getBody()), e);
            return null;
        }
    }

    // 本类原先在这里暴露 7 个 getXxxCount() 读 JVM 计数器，现已删除：
    // 读数请走 SeckillMetrics（含 resolvedCount()，即原来的 getResolvedCount() 口径）。
}
