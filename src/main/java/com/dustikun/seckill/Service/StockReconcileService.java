package com.dustikun.seckill.Service;

import com.dustikun.seckill.Common.Exception.BizException;
import com.dustikun.seckill.Common.Exception.ErrorCode;
import com.dustikun.seckill.Common.result.ReconcileReport;
import com.dustikun.seckill.Common.result.ReconcileSnapshot;
import com.dustikun.seckill.Mapper.OrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 库存对账：比对 Redis 与数据库的真实状态，发现并（可选）纠正不一致。
 * <p>
 * 【它补的是补偿逻辑照不到的两个角落】
 * <ol>
 *   <li><b>Redis 故障降级</b>：请求回落 MySQL 条件扣减并成功，Redis 完全不知情。Redis 恢复后
 *       它的库存比数据库多，后续请求被 Redis 放行、被数据库拒绝，用户看到「抢到了」却下不了单。
 *       这条路径上 {@code preDeducted=false}，补偿逻辑压根不会触发——只能靠对账。</li>
 *   <li><b>Redis 假阴性</b>：命令执行成功但响应超时，调用方以为没扣，这次预扣无人认领。</li>
 * </ol>
 *
 * 【一致性不变量】
 * <pre>
 *     Redis 库存 == 数据库库存 − 在途预扣数
 * </pre>
 * 因为 Redis 总是先扣、数据库后扣，所以 Redis 只可能「扣得更多」（更小）。
 * 由此得到一个<b>单向可证</b>的判据：
 * <ul>
 *   <li>{@code Redis 库存 > 应有值} —— 不可能由正常流程产生，<b>必然是异常</b>（降级写库造成）；</li>
 *   <li>{@code Redis 库存 < 应有值} —— AUTO 模式下期望值已按全部在途放宽，仍对不上即泄漏；
 *       MANUAL 模式下结论取决于调用方声明的在途数是否准确。</li>
 * </ul>
 *
 * 【在途数从哪来——订单前置之后不再需要人工声明】
 * 每一笔受理都会留下一条 {@code orders(status='PENDING')} 预订单，消费确认
 * （PENDING → CONFIRMED + 扣库存）在同一事务里完成，因此「Redis 已扣、数据库尚未扣」
 * 的差额恰好等于 PENDING 行数。在途数由此变成可以直接查库的事实（AUTO 模式，默认），
 * 「调用方声明」降级为可选覆盖（MANUAL，{@code expectedInFlight >= 0}）。
 * 两条路径都等价地覆盖了 CANCELLED 边缘语义：取消的订单未扣数据库库存、预扣也已归还 Redis，
 * 公式两边同时减去它，仍然成立——所以只认 PENDING 行，不要用「受理总数 − 确认数」去推。
 *
 * 【读偏斜窗口（如实说明）】数据库快照与 Redis 读数无法原子：若一笔请求恰好
 * 「已扣 Redis、还没插入 PENDING」，本轮会把正常的中间态读成 REDIS_BEHIND(1)。
 * 定时对账用「链路排空」门控规避（见 MaintenanceTask#pipelineDrained）；
 * 手动对账若恰在活动高峰执行，遇到差值极小的 REDIS_BEHIND 应先复核再动手修复。
 *
 * 【快照纪律】数据库侧的三个数（库存 / PENDING / 订单总数）来自同一条 SQL
 * （{@link OrderMapper#selectReconcileSnapshot}）——分次查询会读到不同时刻的库，
 * 让等式自己制造假不一致，本项目实测踩过这个坑。
 */
@Slf4j
@Service
public class StockReconcileService {

    private final StockCacheService stockCacheService;
    private final OrderMapper orderMapper;

    public StockReconcileService(StockCacheService stockCacheService,
                                 OrderMapper orderMapper) {
        this.stockCacheService = stockCacheService;
        this.orderMapper = orderMapper;
    }

    /**
     * 对账（在途数自动模式，默认入口）。等价于 {@code reconcile(stockId, -1, repair)}：
     * 在途数按「数据库库存 − PENDING 预订单数」自动计算，不需要调用方声明。
     *
     * @param stockId 活动 ID
     * @param repair  是否真的修改数据。false 时只检测并给出建议
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
     *                         传 {@link #AUTO_IN_FLIGHT}（-1）表示由数据库自动计算
     * @param repair           是否真的修改数据。false 时只检测并给出建议
     * @return 对账报告，其中的数值字段是<b>修复前</b>的快照
     */
    public ReconcileReport reconcile(Long stockId, long expectedInFlight, boolean repair) {
        boolean autoInFlight = expectedInFlight < 0;

        // ---------- 数据库侧快照：三个事实同一时刻 ----------
        ReconcileSnapshot snapshot = orderMapper.selectReconcileSnapshot(stockId);
        if (snapshot == null || snapshot.getDbStock() == null) {
            throw new BizException(ErrorCode.STOCK_NOT_FOUND);
        }
        long dbStock = snapshot.getDbStock();
        long pendingOrders = snapshot.getPendingOrders();
        long dbOrderCount = snapshot.getDbOrderCount();

        // ---------- 在途数：AUTO 用数据库事实，MANUAL 用调用方声明 ----------
        long inFlight;
        ReconcileReport.InFlightSource inFlightSource;
        if (autoInFlight) {
            inFlight = pendingOrders;
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
                    null, dbStock, inFlight, inFlightSource, pendingOrders,
                    0L, dbOrderCount, false, List.of(),
                    "Redis 中不存在该活动的库存 key，无法对账。若活动正在进行，请先执行预热。");
        }

        long redisBoughtCount = stockCacheService.boughtCount(stockId);

        ReconcileReport.Status status;
        if (redisStock > expectedRedisStock) {
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

        if (repair && (status == ReconcileReport.Status.REDIS_AHEAD
                || status == ReconcileReport.Status.REDIS_BEHIND)) {
            // 两个方向都直接校准为「数据库 − 在途」。这是把 Redis 拉回不变量上最直接的办法，
            // 而且它天然是可重复执行的（幂等）—— 再跑一次结果相同。
            stockCacheService.syncStock(stockId, expectedRedisStock);
            actions.add("Redis 库存校准为 " + expectedRedisStock
                    + "（数据库 " + dbStock + " − 在途 " + inFlight + "），修复前为 " + redisStock);
            repaired = true;
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
                inFlightSource, pendingOrders, redisBoughtCount, dbOrderCount, repaired,
                List.copyOf(actions), conclude(status, inFlight, inFlightSource));

        if (status == ReconcileReport.Status.CONSISTENT) {
            log.info("[对账] stockId={} 一致。Redis={}, DB={}, 在途={}", stockId, redisStock, dbStock, inFlight);
        } else if (repaired) {
            log.warn("[对账] stockId={} 发现 {} 并已修复：{}", stockId, status, actions);
        } else {
            log.error("[对账] stockId={} 发现不一致 {}，未自动修复。Redis库存={}, 应有={}, Redis标记={}, DB订单={}",
                    stockId, status, redisStock, expectedRedisStock, redisBoughtCount, dbOrderCount);
        }
        return report;
    }

    private String conclude(ReconcileReport.Status status, long inFlight,
                            ReconcileReport.InFlightSource inFlightSource) {
        return switch (status) {
            case CONSISTENT -> "Redis 与数据库一致。";
            case NOT_PREHEATED -> "Redis 中不存在该活动的库存 key，无法对账。";
            case REDIS_AHEAD -> "Redis 库存多于应有值：说明有扣减绕过了 Redis（通常是 Redis 故障期间的降级写库）。"
                    + "这段时间内用户会看到「抢到了」但下单失败，需要重新预热或对账修复。";
            case REDIS_BEHIND -> "Redis 库存少于应有值，" + switch (inFlightSource) {
                // AUTO 的期望值已按全部 PENDING 预订单放宽，仍对不上就是泄漏，不再有「正常中间态」的解释
                case AUTO -> "且期望值已计入全部 " + inFlight + " 条在途预订单："
                        + "存在绕过订单记录的预扣泄漏（少卖），需要把库存补回去。";
                case MANUAL -> inFlight == 0
                        ? "且调用方确认没有在途消息：存在预扣泄漏（少卖），需要把库存补回去。"
                        : "若在途 " + inFlight + " 条消息最终都落库，则属正常的中间状态。";
            };
            case MARK_MISSING -> "数据库订单多于 Redis 已购标记：有用户买到了却没有留下标记，"
                    + "他会被 Redis 重新放行（数据库仍能兜住，但会白白多走一遍落库）。";
        };
    }
}
