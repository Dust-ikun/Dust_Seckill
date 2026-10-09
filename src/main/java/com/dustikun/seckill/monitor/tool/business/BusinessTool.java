package com.dustikun.seckill.monitor.tool.business;

import com.dustikun.seckill.Common.result.ReconcileReport;
import com.dustikun.seckill.Metrics.SeckillMetrics;
import com.dustikun.seckill.Service.CompensateTaskService;
import com.dustikun.seckill.Service.OutboxService;
import com.dustikun.seckill.Service.StockReconcileService;
import com.dustikun.seckill.monitor.tool.MonitorTool;
import com.dustikun.seckill.monitor.tool.ToolArguments;
import com.dustikun.seckill.monitor.tool.ToolResult;
import com.dustikun.seckill.monitor.tool.ToolSchema;
import com.dustikun.seckill.monitor.tool.metrics.MetricCatalog;
import com.dustikun.seckill.monitor.tool.metrics.PromSeries;
import com.dustikun.seckill.monitor.tool.metrics.PrometheusQuerier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Business Tool（SPEC 第 9.5 节）：{@code get_order_statistics} / {@code get_order_success_rate} /
 * {@code get_inventory_consistency} / {@code get_outbox_pending_count}。
 *
 * <h2>它与其他四个 Tool 的根本区别：<b>它读的是业务语义，不是基础设施</b></h2>
 * <p>
 * Metrics Tool 说「P99 = 2.1s」，Business Tool 说「成功率的分子分母各是多少、库存两侧差几件」。
 * 故障注入的验收要求 Agent 的根因与注入的故障一致（可行性报告批次 4），
 * 而基础设施指标只能指出「哪里慢」，业务语义才能指出「<b>用户受到了什么影响</b>」——
 * SPEC 第 11 节要求 Diagnosis 含 {@code impact}，它的唯一来源就是这个工具。
 *
 * <h2>它大部分是「复用」而不是「重写」</h2>
 * <p>
 * 可行性报告 §3.2 对 SPEC 第 9.5 节的判定是「**已有现成实现**」：
 * {@code StockReconcileService} / {@code OutboxService} / {@code CompensateTaskService}
 * 就是要的数据。本类因此只做三件事：选对现有方法、把口径写清楚、把结果摊平。
 * <b>尤其不重新实现一致性判定</b> —— 那套不变量（Redis 库存 == 数据库库存 − 可信在途）
 * 有大量边界（已放弃未了结、陈旧未了结、悬空归还义务），重写一遍必然与对账本身分叉，
 * 于是同一时刻会出现「Agent 说一致、对账说 REDIS_BEHIND」这种最坏的局面。
 *
 * <h2>它为什么必须走 {@code inspect()} 而不是 {@code reconcile()}</h2>
 * <p>
 * 见 {@link StockReconcileService#inspect}：用 reconcile 会形成
 * 「告警 → Agent 调查 → 计数增加 → 同一条告警再次触发」的自激闭环，
 * 并把它自己打的 ERROR 日志变成下一次搜索的证据。
 * 本类的所有动作都是<b>只读且无痕</b>的，这是 SPEC 第 17.1 节在业务层的落点。
 */
public final class BusinessTool implements MonitorTool {

    private static final Logger log = LoggerFactory.getLogger(BusinessTool.class);

    /** 工具名 */
    public static final String NAME = "query_business";

    /** SPEC 第 9.5 节的四个动作 */
    public enum Operation {
        GET_ORDER_STATISTICS,
        GET_ORDER_SUCCESS_RATE,
        GET_INVENTORY_CONSISTENCY,
        GET_OUTBOX_PENDING_COUNT
    }

    private final StockReconcileService reconcileService;

    private final OutboxService outboxService;

    private final CompensateTaskService compensateTaskService;

    private final SeckillMetrics metrics;

    private final PrometheusQuerier querier;

    private final MetricCatalog catalog;

    public BusinessTool(StockReconcileService reconcileService, OutboxService outboxService,
                        CompensateTaskService compensateTaskService, SeckillMetrics metrics,
                        PrometheusQuerier querier, MetricCatalog catalog) {
        this.reconcileService = reconcileService;
        this.outboxService = outboxService;
        this.compensateTaskService = compensateTaskService;
        this.metrics = metrics;
        this.querier = querier;
        this.catalog = catalog;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "查询业务链路的状态与不变量（SPEC 的 orderCount / successRate / "
                + "inventory consistency / outbox pending）。"
                + "operation=GET_ORDER_STATISTICS 看订单计数（进程内累计 + 数据库事实）；"
                + "GET_ORDER_SUCCESS_RATE 看成功率/失败率；"
                + "GET_INVENTORY_CONSISTENCY 做一次只读对账（Redis 与数据库是否一致，需 stock_id）；"
                + "GET_OUTBOX_PENDING_COUNT 看待投递欠账与补偿积压。"
                + "本工具只读：它绝不会修数据，也不会触发对账的告警计数。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        Map<String, Object> properties = new LinkedHashMap<>(4);
        properties.put("operation", ToolSchema.enumeration(
                "要查询的内容。GET_ORDER_STATISTICS=订单计数；GET_ORDER_SUCCESS_RATE=成功率；"
                        + "GET_INVENTORY_CONSISTENCY=只读对账；GET_OUTBOX_PENDING_COUNT=待投递欠账",
                java.util.Arrays.stream(Operation.values()).map(Enum::name).toList()));
        properties.put("stock_id", ToolSchema.integer(
                "活动 ID。GET_INVENTORY_CONSISTENCY 必填；其余操作不需要", 1, Long.MAX_VALUE));
        return ToolSchema.object(properties, "operation");
    }

    @Override
    public ToolResult execute(ToolArguments args) {
        Operation operation = args.enumOrDefault("operation", Operation.class,
                Operation.GET_ORDER_STATISTICS, List.of(Operation.values()));
        List<String> notes = new ArrayList<>(4);
        Map<String, Object> data = new LinkedHashMap<>(10);
        data.put("operation", operation.name());

        switch (operation) {
            case GET_ORDER_STATISTICS -> orderStatistics(data, notes);
            case GET_ORDER_SUCCESS_RATE -> successRate(data, notes);
            case GET_INVENTORY_CONSISTENCY -> inconsistency(args, data, notes);
            case GET_OUTBOX_PENDING_COUNT -> outboxPending(data, notes);
        }
        return ToolResult.ok(name(), data, notes);
    }

    // ================================================================ 四个动作

    private void orderStatistics(Map<String, Object> data, List<String> notes) {
        // 进程内累计计数。它们的口径是「本进程启动以来」，重启归零 ——
        // 这一点必须写进返回体：把它当成「历史总量」是这里最容易犯的错。
        Map<String, Object> counters = new LinkedHashMap<>(12);
        counters.put("queued", metrics.queuedCount());
        counters.put("degraded", metrics.degradedCount());
        counters.put("syncSuccess", metrics.syncSuccessCount());
        counters.put("rollbackAll", metrics.rollbackAllCount());
        counters.put("restoreStockOnly", metrics.restoreStockOnlyCount());
        counters.put("consumeConfirmed", metrics.consumeConfirmedCount());
        counters.put("consumeCancelled", metrics.consumeCancelledCount());
        counters.put("consumeFailed", metrics.consumeFailedCount());
        counters.put("consumeDuplicate", metrics.consumeDuplicateCount());
        counters.put("consumeOrderMissing", metrics.consumeOrderMissingCount());
        counters.put("compensateEnqueued", metrics.compensateEnqueuedCount());
        counters.put("compensateAbandoned", metrics.compensateAbandonedCount());
        data.put("counters", counters);
        data.put("caliber", "counters 是本进程启动以来的累计（重启归零），"
                + "且不区分活动；要按活动或按时间窗统计请用 query_db 的 GET_BUSINESS_STATISTICS "
                + "与 query_metric");
        notes.add("consumeOrderMissing 大于 0 与 consumeCancelled 大于 0 是**真正需要人工过问**的两个信号："
                + "前者说明消费时找不到对应订单，后者说明订单被取消（库存不足或重试耗尽）。");
    }

    private void successRate(Map<String, Object> data, List<String> notes) {
        // 【两个口径同时给，并说明各自的时间范围】这不是冗余：
        // 记录规则给的是 5 分钟窗口（能看出「刚才发生了什么」），
        // 进程计数给的是启动以来（Prometheus 不可用时仍可用，且能看出「总共怎么样」）。
        // 只给一个口径时，最常见的误读是拿 5 分钟窗口的数去说「今天成功率很低」。
        Double windowed = instant("order_success_rate");
        data.put("successRateWindow5m", windowed);

        long confirmed = metrics.consumeConfirmedCount();
        long cancelled = metrics.consumeCancelledCount();
        long failed = metrics.consumeFailedCount();
        long closed = confirmed + cancelled + failed;
        data.put("confirmedSinceStart", confirmed);
        data.put("cancelledSinceStart", cancelled);
        data.put("failedSinceStart", failed);
        if (closed > 0) {
            data.put("successRateSinceStart", round((double) confirmed / closed));
            data.put("failureRateSinceStart", round((double) failed / closed));
            data.put("cancelRateSinceStart", round((double) cancelled / closed));
        } else {
            // 不给 0：分母为 0 时「成功率 = 0」与「还没有结案的消息」是完全相反的两件事。
            data.put("successRateSinceStart", null);
            notes.add("本进程启动以来还没有任何「已结案」的消费结果，因此成功率无法计算（返回 null）。"
                    + "**不要把它当成 0**。");
        }
        data.put("caliber", "分母统一取「已结案」= 确认 + 取消 + 失败，"
                + "与记录规则 seckill:order_success_rate:5m 完全一致。"
                + "取消（库存不足等）属正常业务结局，不计入失败");

        if (windowed != null && windowed < 0.99) {
            notes.add("5 分钟窗口内的成功率 " + windowed + " 低于 0.99。"
                    + "请对照取消率与失败率：取消率高说明库存/预热问题，失败率高说明落库或依赖故障。");
        }
        if (windowed == null) {
            notes.add("没有取到 5 分钟窗口的成功率（Prometheus 不可达或该序列窗口内无数据）。"
                    + "下面的「启动以来」口径仍可用，但它对刚刚发生的故障不敏感。");
        }
    }

    private void inconsistency(ToolArguments args, Map<String, Object> data, List<String> notes) {
        Long stockId = args.optionalLong("stock_id", 1L, Long.MAX_VALUE);
        if (stockId == null) {
            throw new com.dustikun.seckill.monitor.tool.ToolArgumentException("stock_id",
                    "GET_INVENTORY_CONSISTENCY 必须指定 stock_id（对账是「某个活动」的属性，"
                            + "不存在「全部活动的库存一致性」这个结论）。");
        }
        ReconcileReport report;
        try {
            // 【只读巡检，不是 reconcile】见类注释最后一段。
            report = reconcileService.inspect(stockId);
        } catch (RuntimeException e) {
            // 最常见的是 STOCK_NOT_FOUND（活动不存在）。它是「这个 stockId 没有意义」，
            // 不是「库存不一致」—— 必须分开表达，否则 Agent 会去修一个不存在的问题。
            notes.add("对账无法完成（" + e.getMessage() + "）。这通常意味着该 stockId 不存在，"
                    + "请先用 query_db 的 GET_BUSINESS_STATISTICS 确认活动 ID。"
                    + "**它不是「库存不一致」的证据**。");
            data.put("consistent", null);
            return;
        }

        data.put("stockId", stockId);
        data.put("status", report.status().name());
        data.put("consistent", report.status() == ReconcileReport.Status.CONSISTENT);
        data.put("redisStock", report.redisStock());
        data.put("dbStock", report.dbStock());
        data.put("expectedInFlight", report.expectedInFlight());
        data.put("inFlightSource", report.inFlightSource().name());
        data.put("pendingOrders", report.pendingOrders());
        data.put("abandonedPending", report.abandonedPending());
        data.put("stalePending", report.stalePending());
        data.put("unresolvedCompensations", report.unresolvedCompensations());
        data.put("redisBoughtCount", report.redisBoughtCount());
        data.put("dbOrderCount", report.dbOrderCount());
        data.put("conclusion", report.conclusion());
        data.put("caliber", "不变量：Redis 库存 == 数据库库存 − 可信在途；"
                + "可信在途 = PENDING − 已放弃未了结 − 陈旧未了结。"
                + "本次为只读巡检：未修改任何数据，也未计入 reconcileFindings 指标");

        // 把「下一步该做什么」按结论给出来。SPEC 第 11 节要求 suggestion 是低风险建议，
        // 而「建议」的正确内容完全由结论决定 —— 在这里给出比让模型凭常识猜要准得多。
        switch (report.status()) {
            case CONSISTENT -> notes.add("两侧一致（库存差已按可信在途放宽）。"
                    + "若用户侧仍报错，问题不在库存不变量上。");
            case NOT_PREHEATED -> notes.add("Redis 里没有该活动的库存 key：活动还没预热。"
                    + "此时秒杀会直接返回 1003，与「库存不一致」是两件事。"
                    + "修复动作是执行预热（POST /seckill/preheat?stockId=…），"
                    + "但请先确认这是预期的活动状态再建议。");
            case REDIS_AHEAD -> notes.add("Redis 库存**大于**应有值。这是可以确定的异常："
                    + "Redis 只在扣减时减少，不可能「落后得更少」，因此必然有人绕过了 Redis 直接写了数据库"
                    + "（即降级路径）。建议：先确认降级是否仍在发生（query_metric 的 "
                    + "redis_command_latency_p99 与 /seckill/metrics 的 degraded），再谈校准。");
            case REDIS_BEHIND -> notes.add("Redis 库存**小于**应有值 = 少卖。期望值已按全部可信在途放宽，"
                    + "仍对不上说明存在绕过订单记录的预扣泄漏。"
                    + "注意唯一的误报来源是读偏斜（对账读 Redis 与读数据库之间恰好有一笔预扣），"
                    + "定时对账用排空门控规避，手工查询没有 —— 请隔 30 秒再查一次确认是否稳定复现。");
            case MARK_MISSING -> notes.add("库存对得上，但数据库订单数多于 Redis 已购标记："
                    + "有用户买到了却没留下标记，他会被 Redis 重新放行（数据库是最后一道防线，"
                    + "所以不会超卖，但每次都要白走一遍落库）。"
                    + "**本机库里有历史订单残留，MARK_MISSING 是已知的稳定现象**"
                    + "（见 docs/批次1_健康基线快照.md §5）。判据应当看增量，而不是它是否存在。");
            case ABANDONED_PENDING -> notes.add("存在「投递已放弃（outbox=FAILED）但订单仍停在 PENDING」"
                    + "的预订单 " + report.abandonedPending() + " 条。这些单既不是在途也不会自行了结，"
                    + "用户看到的是「订单永远处理中」。低风险处置：为它们补登记"
                    + "「取消订单 + 归还预扣」待办（由补偿任务执行），而不是直接改库存。");
            case STALE_PENDING -> notes.add("存在「超过陈旧阈值仍是 PENDING」的预订单 "
                    + report.stalePending() + " 条：消息投出去了却没人消费（消费者组挂掉、"
                    + "消息丢失），或根本没有凭据。请先用 query_mq 的 GET_CONSUMER_STATUS "
                    + "确认消费者是否活着。");
        }
        if (report.unresolvedCompensations() > 0) {
            notes.add("该活动还有 " + report.unresolvedCompensations()
                    + " 条未了结的归还义务（待补偿任务）：在它们完成前**不要**建议校准 Redis 库存 —— "
                    + "归还动作与校准都会 INCRBY 同一件库存，两处都动手就是归还两次（库存虚增）。");
        }
    }

    private void outboxPending(Map<String, Object> data, List<String> notes) {
        int pending = outboxService.countPending();
        int failed = outboxService.countFailed();
        int compensatePending = compensateTaskService.countPending();

        data.put("outboxPending", pending);
        data.put("outboxFailed", failed);
        data.put("compensatePending", compensatePending);
        // 「需人工介入」= 两条不会自愈的终态之和，与记录规则
        // seckill:manual_intervention_backlog 的口径一致（那里是 outbox_failed + compensate_pending）
        data.put("manualInterventionBacklog", failed + compensatePending);
        data.put("caliber", "三个数都来自数据库 COUNT（多实例下依然准确），"
                + "与 Prometheus 的 seckill_outbox_pending / _failed / seckill_compensate_pending "
                + "同源；manualInterventionBacklog 与记录规则 seckill:manual_intervention_backlog 口径一致");

        if (pending > 0) {
            notes.add("有 " + pending + " 条待投递记录：它们已受理但消息还没进 Broker。"
                    + "持续不为零说明投递器追不上，或 Broker 不可用。");
        }
        if (failed > 0) {
            notes.add("outboxFailed = " + failed + "：投递重试已耗尽，这些单对应的预扣由补偿任务归还。"
                    + "**它不会自愈**，需要人工确认。");
        }
        if (compensatePending > 0) {
            notes.add("compensatePending = " + compensatePending + "：还有归还动作没做完。"
                    + "在此期间对账不会校准库存（避免重复归还）。");
        }
        if (pending == 0 && failed == 0 && compensatePending == 0) {
            notes.add("待投递、投递失败、待补偿三项全部为 0 —— 这是健康的排空态。");
        }
    }

    // ================================================================ 辅助

    /**
     * 取一条目录指标的瞬时值。
     * <p>Prometheus 不可达或没有数据时返回 {@code null}（而不是 0）——
     * 「成功率取不到」与「成功率为 0」在诊断上是完全相反的两件事。
     */
    private Double instant(String metricKey) {
        MetricCatalog.Entry entry = catalog.find(metricKey).orElse(null);
        if (entry == null) {
            log.error("[BusinessTool] 指标目录里缺少条目 {}，请检查 MetricCatalog 的装配", metricKey);
            return null;
        }
        List<PromSeries> series = querier.queryInstant(entry.render(null, null));
        double total = 0;
        boolean any = false;
        for (PromSeries one : series) {
            double value = one.lastValue();
            if (Double.isFinite(value)) {
                total += value;
                any = true;
            }
        }
        return any ? round(total) : null;
    }

    private static Double round(double value) {
        return Math.round(value * 1_000_000d) / 1_000_000d;
    }
}
