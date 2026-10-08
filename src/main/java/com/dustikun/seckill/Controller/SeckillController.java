package com.dustikun.seckill.Controller;

import com.dustikun.seckill.Common.Exception.ErrorCode;
import com.dustikun.seckill.Common.result.ReconcileReport;
import com.dustikun.seckill.Common.result.Result;
import com.dustikun.seckill.Common.result.SeckillOrderResponse;
import com.dustikun.seckill.Metrics.SeckillMetrics;
import com.dustikun.seckill.Metrics.SeckillMetricsRefresher;
import com.dustikun.seckill.Mq.SeckillMessageProducer;
import com.dustikun.seckill.Service.SeckillService;
import com.dustikun.seckill.Service.StockReconcileService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 秒杀业务接口。
 *
 * <p><b>本类只放「业务动作」与「给人看的诊断视图」，机器指标不在这里</b>
 * <p>
 * 引入 actuator 之后，指标有了两个出口，职责必须划清，否则就会出现「同一个语义数两遍」：
 * <ul>
 *   <li><b>{@code /actuator/prometheus}</b>（管理端口 9091，仅本机可达）——给机器：
 *       有历史、能算 {@code rate()} / {@code increase()}、多实例可聚合，用于告警规则与 Grafana。
 *       <b>指标的唯一真相在这里</b>。</li>
 *   <li><b>{@code GET /seckill/metrics}</b>——给人：零依赖、curl 即得、能表达非数值信息
 *       （如 {@code mqEnabled}）、能把「几个计数放在一起看」得出判断。
 *       它<b>不再自己维护任何计数器</b>，读的是 {@link SeckillMetrics} 里同一个 {@code MeterRegistry}，
 *       因此两个出口不可能给出互相矛盾的数字。</li>
 * </ul>
 * <p>
 * 唯一搬不走的是 {@link #reconcile}：它不是一个「读数」，而是一个<b>有副作用的诊断动作</b>
 * （{@code repair=true} 会直接改写 Redis 库存），返回的是结构化结论文本
 * （{@code status} 枚举 + 中文 {@code conclusion} + {@code actions} 列表）。
 * Prometheus 只能存数值，表达不了这种语义，因此它必须留在业务 Controller 上。
 */
@RestController
@RequestMapping("/seckill")
public class SeckillController {

    private final SeckillService seckillService;
    private final StockReconcileService stockReconcileService;
    private final SeckillMetrics metrics;
    private final SeckillMetricsRefresher metricsRefresher;

    /**
     * MQ 关闭时该 Bean 不存在，用 ObjectProvider 容忍。
     * <p>只用于回答「MQ 是否启用」这一个问题——原先它和消费者的 getter 一起提供各项计数，
     * 那些计数已全部迁到 {@link SeckillMetrics}，这里不再参与任何数值计算。
     */
    private final ObjectProvider<SeckillMessageProducer> producerProvider;

    /**
     * 构造器注入，与项目中所有 Service / Task / Metrics 类的写法保持一致
     * （原先这里用的是字段级 {@code @Autowired}，是全项目唯一的例外）。
     * 收益不止是风格统一：字段可以声明为 {@code final}，
     * 「依赖在构造完成后不再变化」这个事实由此变成编译期保证，而不是口头约定。
     */
    public SeckillController(SeckillService seckillService,
                             StockReconcileService stockReconcileService,
                             SeckillMetrics metrics,
                             SeckillMetricsRefresher metricsRefresher,
                             ObjectProvider<SeckillMessageProducer> producerProvider) {
        this.seckillService = seckillService;
        this.stockReconcileService = stockReconcileService;
        this.metrics = metrics;
        this.metricsRefresher = metricsRefresher;
        this.producerProvider = producerProvider;
    }

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
     * 这是补偿逻辑<b>照不到</b>的三条路径的唯一兜底手段：Redis 故障期间的降级写库、
     * Redis 响应超时造成的假阴性、以及「投递已放弃但订单仍停在 PENDING」的预订单。
     * 三者都无法靠事后补偿修复，只能靠比对真实状态发现。
     *
     * @param expectedInFlight 在途预扣数（已投递未落库的消息数）。
     *                         <b>默认 -1 = 自动模式</b>：按「数据库库存 − 可信在途」从数据库直接算出
     *                         （可信在途 = PENDING 预订单 − 已放弃但未了结的那些），
     *                         不需要人工判断（订单前置之后在途有了数据库事实）。
     *                         传 >= 0 可显式覆盖（MANUAL 模式），用于「我明知有 N 条在途」的场景；
     *                         此时已放弃的那些不会替调用方剔除，声明时须自己排除。
     * @param repair           true 时执行修复。修复分两类，都只做「可重复执行而不放大错误」的动作：
     *                         库存校准（把 Redis 校准到「数据库 − 可信在途」）与补齐缺失的已购标记；
     *                         已经存在悬空归还义务、或存在已放弃未了结的预订单时，
     *                         库存校准会被<b>跳过</b>并写进 {@code actions}（改了就是重复归还同一件库存），
     *                         改为为那些预订单补登记「取消订单 + 归还预扣」待办。
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

    /**
     * 已受理下单数。
     * <p>
     * 保留这个窄端点是因为压测脚手架按它取「受理侧计数」（{@code perf_seckill.py}）。
     * 数据来源与 {@code /metrics} 的 {@code queued} 是同一个 Counter，不会出现两个数。
     */
    @GetMapping("/count")
    public Result<?> count() {
        return Result.success("已受理下单数", metrics.queuedCount());
    }

    /**
     * 运行态观测（<b>给人看的</b>视图，不是指标源）。
     * <p>
     * 【怎么读它】把「请求侧」「待投递侧」「投递侧」「消费侧」「补偿侧」分开看，才能判断链路卡在哪一段：
     * <ul>
     *   <li>{@code outboxPending} 长期不为零 → 投递器追不上，或 Broker 不可用；</li>
     *   <li>{@code queued} 远大于 {@code consumed + consumedDuplicate + consumedOrderMissing}
     *       → 消费跟不上（本项目实测的 152~157 单/秒就是这一段的天花板）；</li>
     *   <li>{@code compensated / compensatePending} 不为零 → 有订单落库失败过；</li>
     *   <li>{@code consumedOrderMissing / consumeCancelled} 不为零 → 真正需要人工过问的信号。</li>
     * </ul>
     * <p>
     * 【和前端的区别】前两个判断是<b>速率</b>问题，靠这里的累计值是看不出来的——
     * 判断「现在是不是变慢了」要用 Prometheus 的
     * {@code rate(seckill_consume_confirmed_total[1m])}。本端点只回答「此刻的总账是多少」。
     * <p>
     * 【为什么这里主动刷新一次 Gauge】{@code outboxPending} / {@code outboxFailed} /
     * {@code compensatePending} 这三个是数据库事实，由定时任务缓存后供 Prometheus 抓取
     * （默认 30s 一轮，理由是避免「每次 scrape 都全表 COUNT」）。
     * 但本端点是给人排障用的，值必须是<b>此刻</b>的：所以先主动触发一次刷新——
     * 与定时任务共用同一段实现，而不是在这里另写一遍查询。
     * 这样两个出口对同一个量既不会各数一遍，也不会给出不同数字。
     */
    @GetMapping("/metrics")
    public Result<?> metrics() {
        // 主动刷新，保证下面三个 Gauge 读到的是当下这一刻的库内事实（失败时静默保留旧值）
        metricsRefresher.refreshGauges();

        Map<String, Object> view = new LinkedHashMap<>();

        // ---- 请求侧（Counter：进程内累计，重启归零；要时间维度请看 Prometheus）----
        view.put("queued", metrics.queuedCount());
        view.put("degraded", metrics.degradedCount());
        view.put("rollback", metrics.rollbackAllCount());
        view.put("restoreStockOnly", metrics.restoreStockOnlyCount());
        view.put("syncSuccess", metrics.syncSuccessCount());

        // ---- 待投递侧 ----
        // outboxPending 是「已受理但消息还没进 Broker」的真实欠账规模，
        // 它来自数据库，因此多实例部署下依然准确 —— 这一点优于上面那些进程内计数。
        view.put("outboxEnqueued", metrics.outboxEnqueuedCount());
        view.put("outboxSent", metrics.outboxSentCount());
        view.put("outboxRetried", metrics.outboxRetriedCount());
        view.put("outboxAbandoned", metrics.outboxAbandonedCount());
        view.put("outboxPurged", metrics.outboxPurgedCount());
        view.put("outboxPending", metrics.outboxPendingGauge());
        view.put("outboxFailed", metrics.outboxFailedGauge());

        // ---- MQ 投递侧 ----
        // mqEnabled 是唯一必须留在这里的信息：它是布尔语义，Prometheus 表达不了
        //（Prometheus 里只有 0/1 的数值，读的人不知道那个 0 是「没启用」还是「启用了但没投递」）。
        view.put("mqEnabled", producerProvider.getIfAvailable() != null);
        view.put("mqSent", metrics.mqSentCount());
        view.put("mqSendFailed", metrics.mqSendFailedCount());

        // ---- 消费侧 ----
        view.put("consumed", metrics.consumeConfirmedCount());
        view.put("consumedDuplicate", metrics.consumeDuplicateCount());
        view.put("consumedOrderMissing", metrics.consumeOrderMissingCount());
        view.put("consumeCancelled", metrics.consumeCancelledCount());
        view.put("consumeFailed", metrics.consumeFailedCount());
        view.put("compensated", metrics.consumeStockRestoredCount());

        // ---- 在途估算：已投递 − 已处理完。仅单实例视角，多实例下只是粗略提示 ----
        // 注意对账用的是数据库事实（COUNT(orders WHERE status='PENDING')），不依赖这个估算。
        view.put("inFlightEstimate", Math.max(0, metrics.mqSentCount() - metrics.resolvedCount()));

        // ---- 补偿侧 ----
        view.put("compensateEnqueued", metrics.compensateEnqueuedCount());
        view.put("compensateRecovered", metrics.compensateRecoveredCount());
        view.put("compensateAbandoned", metrics.compensateAbandonedCount());
        view.put("compensatePending", metrics.compensatePendingGauge());

        // ---- 对账侧：按结论分标签的计数（只统计非 CONSISTENT）----
        // 对账此前只有日志出口，而日志会滚。这里与 Prometheus 读的是同一批 Counter。
        view.put("reconcileFindings", metrics.reconcileFindingCounts());

        return Result.success("运行指标", view);
    }
}
