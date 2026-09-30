package com.dustikun.seckill.Controller;

import com.dustikun.seckill.Common.Exception.ErrorCode;
import com.dustikun.seckill.Common.result.ReconcileReport;
import com.dustikun.seckill.Common.result.Result;
import com.dustikun.seckill.Common.result.SeckillOrderResponse;
import com.dustikun.seckill.Mq.SeckillMessageProducer;
import com.dustikun.seckill.Mq.SeckillOrderConsumer;
import com.dustikun.seckill.Service.CompensateTaskService;
import com.dustikun.seckill.Service.OutboxService;
import com.dustikun.seckill.Service.SeckillService;
import com.dustikun.seckill.Service.StockReconcileService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/seckill")
public class SeckillController {

    @Autowired
    SeckillService seckillService;

    @Autowired
    StockReconcileService stockReconcileService;

    @Autowired
    CompensateTaskService compensateTaskService;

    @Autowired
    OutboxService outboxService;

    /**
     * MQ 关闭时该 Bean 不存在，用 ObjectProvider 容忍。
     * <p>消费者同理：它同样带 {@code @ConditionalOnProperty}，直接注入会导致
     * {@code seckill.mq.enabled=false} 时应用起不来——而那正是「本机没起 Broker 时调试其它功能」的模式。
     */
    @Autowired
    ObjectProvider<SeckillMessageProducer> producerProvider;

    @Autowired
    ObjectProvider<SeckillOrderConsumer> consumerProvider;

    /**
     * 秒杀下单。
     * <p>
     * 返回语义是「已受理」，含义很具体：接口返回时数据库里已经有一条
     * <b>PENDING 预订单</b>和一条待投递凭据（两者同一事务），库存扣减与订单确认由消费线程接力。
     * 客户端需要拿 {@code orderNo} 轮询 {@code /seckill/order/status} 才能知道最终结果。
     * <p>
     * 这是把数据库热点写从请求路径上摘掉的必然代价——用一次额外的查询换请求 RT 与数据库压力的解耦。
     */
    @PostMapping
    public Result<?> seckill(@RequestParam Long userId, @RequestParam Long stockId) {
        SeckillOrderResponse response = seckillService.seckill(userId, stockId);
        return Result.success(response.message(), response);
    }

    /**
     * 查询下单的最终结果。
     * <p>
     * 判据以订单状态为准（PENDING → 处理中、CONFIRMED → 成功、CANCELLED → 失败）；
     * 只有「订单还没写进库」时才退回用 Redis 预扣标记判断，
     * 因此仍需同时传 userId 与 stockId —— 标记本身是按 stockId 分组的。
     */
    @GetMapping("/order/status")
    public Result<?> orderStatus(@RequestParam String orderNo,
                                 @RequestParam Long userId,
                                 @RequestParam Long stockId) {
        SeckillOrderResponse response = seckillService.queryOrder(userId, stockId, orderNo);
        return Result.success(response.message(), response);
    }

    /**
     * 库存对账：比对 Redis 与数据库的真实状态。
     * <p>
     * 这是补偿逻辑<b>照不到</b>的两条路径的唯一兜底手段：
     * Redis 故障期间的降级写库、以及 Redis 响应超时造成的假阴性。
     * 两者都无法靠事后补偿修复，只能靠比对真实状态发现。
     *
     * @param expectedInFlight 在途预扣数（已投递未落库的消息数）。
     *                         <b>默认 -1 = 自动模式</b>：按「数据库库存 − PENDING 预订单数」
     *                         从数据库直接算出，不需要人工判断（订单前置之后在途有了数据库事实）。
     *                         传 >= 0 可显式覆盖（MANUAL 模式），用于「我明知有 N 条在途」的场景。
     * @param repair           true 时执行修复（把 Redis 校准到「数据库 − 在途」、补齐缺失的已购标记）。
     *                         默认 false：只报告不动数据，避免在不知情的情况下改写线上状态。
     */
    @GetMapping("/reconcile")
    public Result<?> reconcile(@RequestParam Long stockId,
                               @RequestParam(defaultValue = "-1") long expectedInFlight,
                               @RequestParam(defaultValue = "false") boolean repair) {
        ReconcileReport report = stockReconcileService.reconcile(stockId, expectedInFlight, repair);
        return Result.success(report.conclusion(), report);
    }

    /**
     * 库存预热：活动开始前把数据库库存前置到 Redis。必须预热，否则秒杀会直接返回 1003。
     * <p>
     * 预热也是「降级之后重新对齐两边」最直接的手段：它直接取数据库当前库存，
     * 会把 Redis 在故障期间落后的部分一次性追平。
     */
    @PostMapping("/preheat")
    public Result<?> preheat(@RequestParam Long stockId) {
        long preheated = seckillService.preheat(stockId);
        return Result.success("预热完成", preheated);
    }

    /**
     * 查询 Redis 侧剩余库存（真实可售余量，以它为准，而不是数据库）。
     */
    @GetMapping("/remain")
    public Result<?> remain(@RequestParam Long stockId) {
        Integer remain = seckillService.remain(stockId);
        if (remain == null) {
            return Result.fail(ErrorCode.STOCK_NOT_READY);
        }
        return Result.success("Redis 剩余库存", remain);
    }

    /**
     * 活动结束后清理 Redis 中的活动缓存。
     */
    @DeleteMapping("/cache")
    public Result<?> clearCache(@RequestParam Long stockId) {
        seckillService.clear(stockId);
        return Result.success("缓存已清理", null);
    }

    @GetMapping("/count")
    public Result<?> count() {
        return Result.success("已受理下单数", seckillService.getQueuedCount());
    }

    /**
     * 运行态观测。把「请求侧」「待投递侧」「投递侧」「消费侧」「补偿侧」的计数分开看，
     * 才能判断链路卡在哪一段：
     * {@code outboxPending} 长期不为零说明投递器追不上或 Broker 不可用；
     * {@code queued} 远大于 {@code consumed + consumedDuplicate + consumedOrderMissing}
     * 说明消费跟不上；{@code compensated / compensatePending} 不为零说明有订单落库失败过，
     * 而 {@code consumedOrderMissing / consumeCancelled} 不为零是真正需要人工过问的信号。
     */
    @GetMapping("/metrics")
    public Result<?> metrics() {
        Map<String, Object> metrics = new LinkedHashMap<>();

        // ---- 请求侧 ----
        metrics.put("queued", seckillService.getQueuedCount());
        metrics.put("degraded", seckillService.getDegradedCount());
        metrics.put("rollback", seckillService.getRollbackCount());
        metrics.put("restoreStockOnly", seckillService.getRestoreStockOnlyCount());
        metrics.put("syncSuccess", seckillService.getSuccessCount());

        // ---- 待投递侧 ----
        // outboxPending 是「已受理但消息还没进 Broker」的真实欠账规模，
        // 它查的是数据库，因此多实例部署下依然准确 —— 这一点优于下面的 JVM 计数器。
        metrics.put("outboxEnqueued", outboxService.getEnqueuedCount());
        metrics.put("outboxSent", outboxService.getSentCount());
        metrics.put("outboxRetried", outboxService.getRetriedCount());
        metrics.put("outboxAbandoned", outboxService.getAbandonedCount());
        metrics.put("outboxPurged", outboxService.getPurgedCount());
        metrics.put("outboxPending", outboxService.countPending());
        metrics.put("outboxFailed", outboxService.countFailed());

        // ---- MQ 投递侧 ----
        SeckillMessageProducer producer = producerProvider.getIfAvailable();
        metrics.put("mqEnabled", producer != null);
        metrics.put("mqSent", producer == null ? 0 : producer.getSentCount());
        metrics.put("mqSendFailed", producer == null ? 0 : producer.getFailedCount());

        // ---- 消费侧 ----
        SeckillOrderConsumer consumer = consumerProvider.getIfAvailable();
        metrics.put("consumed", consumer == null ? 0 : consumer.getConsumedCount());
        metrics.put("consumedDuplicate", consumer == null ? 0 : consumer.getDuplicateCount());
        metrics.put("consumedOrderMissing", consumer == null ? 0 : consumer.getOrderMissingCount());
        metrics.put("consumeCancelled", consumer == null ? 0 : consumer.getCancelledCount());
        metrics.put("consumeFailed", consumer == null ? 0 : consumer.getFailedCount());
        metrics.put("compensated", consumer == null ? 0 : consumer.getCompensatedCount());

        // ---- 在途估算：已投递 − 已处理完。仅单实例视角，多实例下只是粗略提示 ----
        int sent = producer == null ? 0 : producer.getSentCount();
        int resolved = consumer == null ? 0 : consumer.getResolvedCount();
        metrics.put("inFlightEstimate", Math.max(0, sent - resolved));

        // ---- 补偿侧 ----
        metrics.put("compensateEnqueued", compensateTaskService.getEnqueuedCount());
        metrics.put("compensateRecovered", compensateTaskService.getRecoveredCount());
        metrics.put("compensateAbandoned", compensateTaskService.getAbandonedCount());
        metrics.put("compensatePending", compensateTaskService.countPending());

        return Result.success("运行指标", metrics);
    }
}
