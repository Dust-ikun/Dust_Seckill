package com.dustikun.seckill.Service;

import com.dustikun.seckill.Common.Exception.BizException;
import com.dustikun.seckill.Common.Exception.ErrorCode;
import com.dustikun.seckill.Common.constant.CompensateType;
import com.dustikun.seckill.Common.result.ReconcileReport;
import com.dustikun.seckill.Common.result.ReconcileSnapshot;
import com.dustikun.seckill.Mapper.OrderMapper;
import com.dustikun.seckill.Metrics.SeckillMetrics;
import com.dustikun.seckill.entity.Order;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 库存对账：比对 Redis 与数据库的真实状态，发现并（可选）纠正不一致。
 * <p>
 * 【它补的是补偿逻辑照不到的四个角落】
 * <ol>
 *   <li><b>Redis 故障降级</b>：请求回落 MySQL 条件扣减并成功，Redis 完全不知情。Redis 恢复后
 *       它的库存比数据库多，后续请求被 Redis 放行、被数据库拒绝，用户看到「抢到了」却下不了单。
 *       这条路径上 {@code preDeducted=false}，补偿逻辑压根不会触发——只能靠对账。</li>
 *   <li><b>Redis 假阴性</b>：命令执行成功但响应超时，调用方以为没扣，这次预扣无人认领。</li>
 *   <li><b>已放弃但未了结的预订单</b>：投递重试耗尽的记录在 outbox 里是 FAILED 终态，订单却停在
 *       PENDING（见 {@link ReconcileReport.Status#ABANDONED_PENDING}）。</li>
 *   <li><b>陈旧未了结的预订单</b>：消息投出去了却没人消费（消费者组挂掉、消息在 Broker 侧丢失），
 *       或对照链路（关掉 outbox）在「订单已建、消息未投」之间崩掉（见
 *       {@link ReconcileReport.Status#STALE_PENDING}）。</li>
 * </ol>
 * 后两条路径上没有异常可捕获、也没有动作可重试，唯一能发现它们的地方就是这里。
 *
 * 【一致性不变量】
 * <pre>
 *     Redis 库存 == 数据库库存 − 可信在途
 *     可信在途 = PENDING 预订单 − 已放弃未了结 − 陈旧未了结
 * </pre>
 * 因为 Redis 总是先扣、数据库后扣，所以 Redis 只可能「扣得更多」（更小）。
 * 由此得到一个<b>单向可证</b>的判据：
 * <ul>
 *   <li>{@code Redis 库存 > 应有值} —— 不可能由正常流程产生，<b>必然是异常</b>（降级写库造成）；</li>
 *   <li>{@code Redis 库存 < 应有值} —— 期望值已按全部可信在途放宽，仍对不上即泄漏；
 *       MANUAL 模式下结论取决于调用方声明的在途数是否准确。</li>
 * </ul>
 *
 * 【在途数从哪来——订单前置之后不再需要人工声明】
 * 每一笔受理都会留下一条 {@code orders(status='PENDING')} 预订单，消费确认
 * （PENDING → CONFIRMED + 扣库存）在同一事务里完成，因此「Redis 已扣、数据库尚未扣」
 * 的差额恰好等于 PENDING 行数 —— <b>前提是每条 PENDING 最终都会走向这两种结局之一</b>。
 * 上面第 3、4 类单打破了这个前提：它们既不会被确认、也不会自行取消。这类单必须从在途里剔掉，
 * 否则等式两边同时减一，一笔真正丢失的预扣会被差值抵消成「一致」——
 * 那是<b>看起来最健康</b>的一种坏状态。在途数因此变成「PENDING 减去那两类」（AUTO 模式，默认），
 * 「调用方声明」降级为可选覆盖（MANUAL，{@code expectedInFlight >= 0}）。
 * 两条路径都等价地覆盖了 CANCELLED 边缘语义：取消的订单未扣数据库库存、预扣也已归还 Redis，
 * 公式两边同时减去它，仍然成立——所以只认 PENDING 行，不要用「受理总数 − 确认数」去推。
 *
 * 【读偏斜窗口（如实说明）】数据库快照与 Redis 读数无法原子：若一笔请求恰好
 * 「已扣 Redis、还没插入 PENDING」，本轮会把正常的中间态读成 REDIS_BEHIND(1)。
 * 定时对账用「链路排空」门控规避（见 MaintenanceTask#pipelineDrained），
 * 且该门控被陈旧阈值封了顶：链路一直不排空正是最需要出声的时候，不能被门控永久挡住。
 *
 * 【修复动作分三类，都不能放大错误】
 * <ul>
 *   <li><b>校准 Redis 库存</b>（带值比对的一次改写，见
 *       {@link StockCacheService#syncStockIfUnchanged}）：只在「没有任何悬空的归还义务」时才能做。
 *       归还动作（待补偿任务）与校准都会去 INCRBY 同一件库存，两处都动手就是归还两次——
 *       库存虚增，而它随后会被当成「Redis 比数据库多」再修一轮。
 *       判据是 {@code countUnresolvedByStockId} 为 0；并且写入前还要确认「读到的值没被改过」，
 *       否则会把读之后发生的那笔预扣覆盖掉。</li>
 *   <li><b>补登记归还待办</b>（{@code compensate_task}，幂等、可重试）：处理上面第 3、4 类预订单。
 *       对账在这一点上只负责<b>让这件事有主</b>，不负责自己动手还库存。</li>
 *   <li><b>补齐已购标记</b>（只增不删）：漏标会让用户被重复放行，误删标记会让已买到的人重抢，
 *       两者代价不对称，因此只补不删。</li>
 * </ul>
 *
 * 【快照纪律】数据库侧的五个数（库存 / PENDING / 已放弃未了结 / 陈旧未了结 / 订单总数）
 * 来自同一条 SQL（{@link OrderMapper#selectReconcileSnapshot}）——分次查询会读到不同时刻的库，
 * 让等式自己制造假不一致，本项目实测踩过这个坑。
 */
@Slf4j
@Service
public class StockReconcileService {

    /**
     * 本项目每笔下单固定扣 1 件（{@code DEDUCT_NUM}）。
     * <p>
     * 对账为「未了结」的预订单补登记待办时需要还原 {@code num}，而 {@code orders}
     * 表并不存件数（订单与 Redis 预扣都是一人一件）。若将来支持多件下单，这里必须与
     * {@code SeckillService} 一起改，否则补登记的归还会少还。
     */
    private static final long DEDUCT_NUM = 1L;

    private final StockCacheService stockCacheService;
    private final OrderMapper orderMapper;
    private final CompensateTaskService compensateTaskService;
    private final SeckillMetrics metrics;

    /**
     * 「陈旧」的判定阈值（分钟）：PENDING 超过它仍未被了结，就不再被当作可信在途。
     * <p>
     * 取值必须大于<b>最坏链路时延</b>：投递退避重试 14 次等待合计 1150 秒 ≈ 19 分钟
     * （15 次重试、延迟 2 秒起指数翻倍、单次上限 5 分钟；base=2 时实际最大 128 秒），
     * 再加 MQ 消费重试（10s/30s/1min）。取小了会把还活着的单误判成陈旧
     * （提前收回库存、取消用户订单）。详见 {@code ReconcileReport.Status#STALE_PENDING}。
     */
    private final long stalePendingMinutes;

    public StockReconcileService(StockCacheService stockCacheService,
                                 OrderMapper orderMapper,
                                 CompensateTaskService compensateTaskService,
                                 SeckillMetrics metrics,
                                 @Value("${seckill.maintenance.reconcile.stale-pending-minutes:90}")
                                 long stalePendingMinutes) {
        this.stockCacheService = stockCacheService;
        this.orderMapper = orderMapper;
        this.compensateTaskService = compensateTaskService;
        this.metrics = metrics;
        this.stalePendingMinutes = Math.max(1L, stalePendingMinutes);
    }

    /**
     * 对账（在途数自动模式，默认入口）。等价于 {@code reconcile(stockId, -1, repair)}：
     * 在途数按「PENDING − 已放弃未了结 − 陈旧未了结」自动计算，不需要调用方声明。
     *
     * @param stockId 活动 ID
     * @param repair  是否真的修改数据。false 时只检测不动数据（actions 恒为空，不产生修复建议）
     * @return 对账报告，其中的数值字段是<b>修复前</b>的快照
     */
    public ReconcileReport reconcile(Long stockId, boolean repair) {
        return reconcile(stockId, AUTO_IN_FLIGHT, repair);
    }

    /** expectedInFlight 的哨兵值：负值表示「在途数由数据库自动计算」（AUTO 模式） */
    public static final long AUTO_IN_FLIGHT = -1L;

    /**
     * 对账。
     *
     * @param stockId          活动 ID
     * @param expectedInFlight 调用方声明的在途预扣数（已投递未落库的消息数），负值按 0 处理；
     *                         传 {@link #AUTO_IN_FLIGHT}（-1）表示由数据库自动计算。
     *                         注意 MANUAL 模式下<b>不会</b>替调用方剔除「已放弃 / 陈旧」的那些，
     *                         声明时须自己排除（它们不是在途）
     * @param repair           是否真的修改数据。false 时只检测不动数据（actions 恒为空，不产生修复建议）
     * @return 对账报告，其中的数值字段是<b>修复前</b>的快照
     */
    public ReconcileReport reconcile(Long stockId, long expectedInFlight, boolean repair) {
        boolean autoInFlight = expectedInFlight < 0;
        LocalDateTime staleCutoff = LocalDateTime.now().minusMinutes(stalePendingMinutes);

        // ---------- 数据库侧快照：五个事实同一时刻 ----------
        ReconcileSnapshot snapshot = orderMapper.selectReconcileSnapshot(stockId, staleCutoff);
        if (snapshot == null || snapshot.getDbStock() == null) {
            throw new BizException(ErrorCode.STOCK_NOT_FOUND);
        }
        long dbStock = snapshot.getDbStock();
        long pendingOrders = snapshot.getPendingOrders();
        long abandonedPending = snapshot.getAbandonedPending();
        long stalePending = snapshot.getStalePending();
        long dbOrderCount = snapshot.getDbOrderCount();

        // ---------- 在途数：AUTO 用数据库事实（并剔掉两类未了结的），MANUAL 用调用方声明 ----------
        long inFlight;
        ReconcileReport.InFlightSource inFlightSource;
        if (autoInFlight) {
            // 已放弃/陈旧的预订单不是在途：没有消息会再来确认它们，也没有流程会取消它们。
            // 不剔掉它们，一笔丢失的预扣会被抵消成「一致」（详见类注释与两个 *_PENDING 结论）。
            inFlight = Math.max(0L, pendingOrders - abandonedPending - stalePending);
            inFlightSource = ReconcileReport.InFlightSource.AUTO;
        } else {
            inFlight = Math.max(0L, expectedInFlight);
            inFlightSource = ReconcileReport.InFlightSource.MANUAL;
        }
        long expectedRedisStock = Math.max(0L, dbStock - inFlight);

        // Redis 读数在快照之后：两者之间没有原子性，读偏斜窗口见类注释。
        Integer redisStock = stockCacheService.remain(stockId);
        if (redisStock == null) {
            // 没预热就没有比较基准。这里刻意不去"顺手预热"——
            // 预热是有副作用的动作（会清空已购集合），不该藏在对账里。
            return new ReconcileReport(stockId, ReconcileReport.Status.NOT_PREHEATED,
                    null, dbStock, inFlight, inFlightSource, pendingOrders, abandonedPending, stalePending,
                    0L, 0L, dbOrderCount, false, List.of(),
                    "Redis 中不存在该活动的库存 key，无法对账。若活动正在进行，请先执行预热。");
        }

        long redisBoughtCount = stockCacheService.boughtCount(stockId);

        // 悬空的归还义务：只要还有一条没走完，对账就不能直接改写 Redis 库存（会重复归还）。
        long unresolvedCompensations = compensateTaskService.countUnresolvedByStockId(stockId);

        ReconcileReport.Status status;
        if (abandonedPending > 0) {
            // 与库存差额无关：即使 Redis 库存恰好正确（归还已经完成），订单仍永远停在处理中，
            // 而且在这批单了结之前，任何库存校准都可能与归还动作重复归还同一件。
            status = ReconcileReport.Status.ABANDONED_PENDING;
        } else if (stalePending > 0) {
            // 成因不同（没人消费 vs 已被放弃），处置手段相同：补登记归还待办
            status = ReconcileReport.Status.STALE_PENDING;
        } else if (redisStock > expectedRedisStock) {
            status = ReconcileReport.Status.REDIS_AHEAD;
        } else if (redisStock < expectedRedisStock) {
            status = ReconcileReport.Status.REDIS_BEHIND;
        } else if (redisBoughtCount < dbOrderCount) {
            status = ReconcileReport.Status.MARK_MISSING;
        } else {
            status = ReconcileReport.Status.CONSISTENT;
        }

        List<String> actions = new ArrayList<>();
        boolean repaired = false;

        if (repair && status == ReconcileReport.Status.ABANDONED_PENDING) {
            repaired = enqueueReleaseTasks(orderMapper.selectAbandonedPending(stockId),
                    "对账发现：投递已放弃但订单仍为 PENDING，补登记归还待办", stockId, actions);
        }

        if (repair && status == ReconcileReport.Status.STALE_PENDING) {
            repaired = enqueueReleaseTasks(orderMapper.selectStalePending(stockId, staleCutoff),
                    "对账发现：预订单超过陈旧阈值仍未被了结，补登记归还待办", stockId, actions);
        }

        if (repair && (status == ReconcileReport.Status.REDIS_AHEAD
                || status == ReconcileReport.Status.REDIS_BEHIND)) {
            if (unresolvedCompensations > 0) {
                // 两处都 INCRBY 同一件库存 = 归还两次 = 库存虚增（随后还会被当成 REDIS_AHEAD 再修一轮）。
                // 宁可少卖、绝不超卖：这里选择不动手，并让「为什么不动手」留在报告里。
                actions.add("库存校准已跳过：该活动还有 " + unresolvedCompensations
                        + " 条未了结的归还义务（待补偿任务），此时改写 Redis 库存会与它们把同一件库存归还两次");
                log.warn("[对账·修复] stockId={} 库存校准已跳过：尚有 {} 条未了结的归还义务",
                        stockId, unresolvedCompensations);
            } else if (stockCacheService.syncStockIfUnchanged(stockId, redisStock, expectedRedisStock)) {
                actions.add("Redis 库存校准为 " + expectedRedisStock
                        + "（数据库 " + dbStock + " − 可信在途 " + inFlight + "），修复前为 " + redisStock);
                repaired = true;
            } else {
                // 值比对没通过：读之后有人扣过或补过库存，或者这个 key 的值不是规范十进制。
                // 两种都按「不许覆盖」处理 —— 这不是失败，而是「对账不该跟正在跑的流量抢同一个 key」
                // 这条纪律的体现；下一轮会基于新值重新判定。
                actions.add("库存校准已跳过：写入前的值比对未通过（读到的 " + redisStock
                        + " 与当前值不一致，可能期间有新预扣/归还，或该 key 的值不是规范十进制），"
                        + "本轮不覆盖，下一轮对账会基于新值重新判定");
            }
        }

        if (repair && status == ReconcileReport.Status.MARK_MISSING) {
            // 只补不删：漏标只是让用户多跑一次 DB（DB 兜得住），
            // 误删标记却会让已经买到的用户被重新放行。两者代价不对称，因此策略也不该对称。
            Set<String> existing = stockCacheService.boughtUserIds(stockId);
            List<String> missing = orderMapper.selectUserIdsByStockId(stockId).stream()
                    .map(String::valueOf)
                    .filter(userId -> !existing.contains(userId))
                    .toList();
            long added = stockCacheService.addBoughtUsers(stockId, missing);
            actions.add("补齐 Redis 已购标记 " + added + " 个"
                    + "（数据库订单 " + dbOrderCount + " 条，Redis 原有 " + redisBoughtCount + " 个）");
            repaired = added > 0;
        }

        ReconcileReport report = new ReconcileReport(stockId, status, redisStock, dbStock, inFlight,
                inFlightSource, pendingOrders, abandonedPending, stalePending, unresolvedCompensations,
                redisBoughtCount, dbOrderCount, repaired, List.copyOf(actions),
                conclude(status, inFlight, inFlightSource, abandonedPending, stalePending));

        if (status == ReconcileReport.Status.CONSISTENT) {
            log.info("[对账] stockId={} 一致。Redis={}, DB={}, 可信在途={}", stockId, redisStock, dbStock, inFlight);
        } else if (repaired) {
            log.warn("[对账] stockId={} 发现 {} 并已修复：{}", stockId, status, actions);
        } else {
            // 不是「一致」就落一条计数（带结论标签）：日志会滚，指标不会 —— 告警规则可以直接写
            // rate(seckill_reconcile_findings_total{status="STALE_PENDING"}[5m]) > 0
            log.error("[对账] stockId={} 发现不一致 {}，未自动修复。Redis库存={}, 应有={}, Redis标记={}, "
                            + "DB订单={}, PENDING={}, 已放弃未了结={}, 陈旧未了结={}, 未了结归还义务={}",
                    stockId, status, redisStock, expectedRedisStock, redisBoughtCount, dbOrderCount,
                    pendingOrders, abandonedPending, stalePending, unresolvedCompensations);
        }
        if (status != ReconcileReport.Status.CONSISTENT) {
            metrics.onReconcileFinding(status);
        }
        return report;
    }

    /**
     * 链路是否「卡住」了：该活动最老的 PENDING 预订单是否已经超过陈旧阈值。
     * <p>
     * 它是给对账的排空门控封顶用的（见 {@code MaintenanceTask#pipelineDrained}）：
     * 门控原本只看「还有没有未投出 / 未消费的东西」，消费者组一旦挂掉，门控会一直关着，
     * 对账连报都不报 —— 而「一直不排空」恰恰是最需要它出声的时候。
     * <p>
     * 阈值与 {@code STALE_PENDING} 共用一份配置是有意的：判定「陈旧」和判定「卡住」
     * 必须是同一件事，否则门控会挡住自己本该报出来的问题。
     */
    public boolean pipelineStuck(Long stockId) {
        LocalDateTime oldest = orderMapper.selectOldestPendingCreatedAt(stockId);
        return oldest != null && oldest.isBefore(LocalDateTime.now().minusMinutes(stalePendingMinutes));
    }

    /**
     * 为一组「不会被了结的预订单」补登记「取消订单 + 归还预扣」待办。
     * <p>
     * 【为什么要补登记，而不是对账直接归还】正常链路上这件事在放弃的那一刻就随 FAILED
     * 同事务登记好了（见 {@code OutboxAbandonService}）。走到这里的是它漏掉的：改动上线前
     * 留下的历史记录、任务本身重试耗尽被标成 FAILED 的、以及「消息投出去却没人消费」这类
     * 根本没有放弃动作可依赖的场景。对账在这里扮演的是「保证这件事有主」的角色 ——
     * 归还动作有幂等键、可重试、有状态可查，而对账自己动手既没有重试、也没有记录，
     * 失败就只剩一行日志。
     * <p>
     * 【为什么不去动 Redis 库存】同一件库存会被两个地方归还：这里的校准与待办任务的 INCRBY。
     * 两处都成功就是库存虚增（超卖方向），因此这条路径只登记、不改库存。
     *
     * @param candidates 待处理的预订单明细（已由调用方按判据查好）
     * @param reason     写进待办 reason 的原因，便于事后还原「是谁登记的」
     * @return 是否至少补登记了一条待办
     */
    private boolean enqueueReleaseTasks(List<Order> candidates, String reason,
                                        Long stockId, List<String> actions) {
        if (candidates.isEmpty()) {
            // 快照（标量子查询）与这份明细之间隔了语句，理论上可能刚好清空：本轮不动手，
            // 下一轮会重新判定。宁可不做，也不要基于过期的明细去动库存。
            return false;
        }

        Set<String> withTask = compensateTaskService.orderNosWithTask(stockId);
        int enqueued = 0;
        int already = 0;
        int failed = 0;
        for (Order order : candidates) {
            if (withTask.contains(order.getOrderNo())) {
                // 已经登记过（可能已完成、可能还在重试、也可能已 FAILED 等人工）。
                // 不重复登记：两条待办各自幂等、彼此不幂等，都成功就是归还两次。
                already++;
                continue;
            }
            try {
                compensateTaskService.enqueueStrict(order.getStockId(), order.getUserId(), order.getOrderNo(),
                        DEDUCT_NUM, CompensateType.CANCEL_ORDER, reason);
                enqueued++;
            } catch (Exception e) {
                // 单条失败不能影响同一活动的其它记录：这一轮把能登记的登记完，失败的下一轮还会被扫到
                failed++;
                log.error("[对账·修复] 补登记归还待办失败，需人工介入。stockId={}, orderNo={}",
                        stockId, order.getOrderNo(), e);
            }
        }

        actions.add("为 " + enqueued + " 条未了结预订单补登记「取消订单 + 归还预扣」待办"
                + (already > 0 ? "（另有 " + already + " 条此前已登记，未重复登记；若其中存在 FAILED，需人工介入）" : "")
                + (failed > 0 ? "；" + failed + " 条本次登记失败，下一轮对账会再试" : "")
                + "。本轮不改写 Redis 库存：归还由待办任务完成，两处都改会重复归还同一件库存");
        return enqueued > 0;
    }

    private String conclude(ReconcileReport.Status status, long inFlight,
                            ReconcileReport.InFlightSource inFlightSource,
                            long abandonedPending, long stalePending) {
        return switch (status) {
            case CONSISTENT -> "Redis 与数据库一致。";
            case NOT_PREHEATED -> "Redis 中不存在该活动的库存 key，无法对账。";
            case REDIS_AHEAD -> "Redis 库存多于应有值：说明有扣减绕过了 Redis（通常是 Redis 故障期间的降级写库）。"
                    + "这段时间内用户会看到「抢到了」但下单失败，需要重新预热或对账修复。";
            case REDIS_BEHIND -> "Redis 库存少于应有值，" + switch (inFlightSource) {
                // AUTO 的期望值已按全部可信在途放宽，仍对不上就是泄漏，不再有「正常中间态」的解释
                case AUTO -> "且期望值已计入全部 " + inFlight + " 条可信在途预订单："
                        + "存在绕过订单记录的预扣泄漏（少卖），需要把库存补回去。";
                case MANUAL -> inFlight == 0
                        ? "且调用方确认没有在途消息：存在预扣泄漏（少卖），需要把库存补回去。"
                        : "若在途 " + inFlight + " 条消息最终都落库，则属正常的中间状态。";
            };
            case MARK_MISSING -> "数据库订单多于 Redis 已购标记：有用户买到了却没有留下标记，"
                    + "他会被 Redis 重新放行（数据库仍能兜住，但会白白多走一遍落库）。";
            case ABANDONED_PENDING -> "存在 " + abandonedPending
                    + " 条「投递已放弃、订单仍停在 PENDING」的预订单：它们不会再被确认，"
                    + "也没有任何流程会取消它们。这些单既不是在途、也不会消耗数据库库存，"
                    + "却会让一笔已丢失的预扣看起来像正常在途（本结论因此优先于库存差额判定）。"
                    + "处置方式是由待补偿任务「先取消订单、再归还预扣」；"
                    + "在它们了结之前不做库存校准，否则会与归还动作重复归还同一件库存。";
            case STALE_PENDING -> "存在 " + stalePending
                    + " 条超过陈旧阈值仍是 PENDING 的预订单：投递成功却没人消费（消费者组挂了、"
                    + "消息在 Broker 侧丢失），或对照链路根本没有待投递凭据。"
                    + "与「已放弃」同理，它们不是在途，也会把一笔丢失的预扣伪装成正常中间态。"
                    + "处置方式同样是补登记「取消订单 + 归还预扣」待办；"
                    + "若确认消费者只是积压（而非卡死），应先扩容消费线程再复核本结论。";
        };
    }
}
