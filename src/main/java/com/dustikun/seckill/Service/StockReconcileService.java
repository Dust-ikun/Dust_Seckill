package com.dustikun.seckill.Service;

import com.dustikun.seckill.Common.Exception.BizException;
import com.dustikun.seckill.Common.Exception.ErrorCode;
import com.dustikun.seckill.Common.result.ReconcileReport;
import com.dustikun.seckill.Mapper.OrderMapper;
import com.dustikun.seckill.Mapper.StockMapper;
import com.dustikun.seckill.entity.Stock;
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
 *   <li>{@code Redis 库存 < 应有值} —— 若调用方已确认没有在途消息，就是预扣泄漏（少卖）。</li>
 * </ul>
 * 「在途数」无法由本服务自行推断（它取决于 MQ 的投递/消费进度），因此作为入参由调用方声明：
 * 手动对账时由人给出，定时任务在 MQ 空闲时传 0。
 */
@Slf4j
@Service
public class StockReconcileService {

    private final StockCacheService stockCacheService;
    private final StockMapper stockMapper;
    private final OrderMapper orderMapper;

    public StockReconcileService(StockCacheService stockCacheService,
                                 StockMapper stockMapper,
                                 OrderMapper orderMapper) {
        this.stockCacheService = stockCacheService;
        this.stockMapper = stockMapper;
        this.orderMapper = orderMapper;
    }

    /**
     * 对账。
     *
     * @param stockId         活动 ID
     * @param expectedInFlight 调用方声明的在途预扣数（已投递未落库的消息数），负值按 0 处理
     * @param repair          是否真的修改数据。false 时只检测并给出建议
     * @return 对账报告，其中的数值字段是<b>修复前</b>的快照
     */
    public ReconcileReport reconcile(Long stockId, long expectedInFlight, boolean repair) {
        Stock stock = stockMapper.selectById(stockId);
        if (stock == null) {
            throw new BizException(ErrorCode.STOCK_NOT_FOUND);
        }
        long dbStock = stock.getCount();
        long inFlight = Math.max(0L, expectedInFlight);
        long expectedRedisStock = Math.max(0L, dbStock - inFlight);

        long dbOrderCount = orderMapper.countByStockId(stockId);

        Integer redisStock = stockCacheService.remain(stockId);
        if (redisStock == null) {
            // 没预热就没有比较基准。这里刻意不去"顺手预热"——
            // 预热是有副作用的动作（会清空已购集合），不该藏在对账里。
            return new ReconcileReport(stockId, ReconcileReport.Status.NOT_PREHEATED,
                    null, dbStock, inFlight, 0L, dbOrderCount, false, List.of(),
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
                redisBoughtCount, dbOrderCount, repaired, List.copyOf(actions), conclude(status, inFlight));

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

    private String conclude(ReconcileReport.Status status, long inFlight) {
        return switch (status) {
            case CONSISTENT -> "Redis 与数据库一致。";
            case NOT_PREHEATED -> "Redis 中不存在该活动的库存 key，无法对账。";
            case REDIS_AHEAD -> "Redis 库存多于应有值：说明有扣减绕过了 Redis（通常是 Redis 故障期间的降级写库）。"
                    + "这段时间内用户会看到「抢到了」但下单失败，需要重新预热或对账修复。";
            case REDIS_BEHIND -> "Redis 库存少于应有值" + (inFlight == 0
                    ? "，且调用方确认没有在途消息：存在预扣泄漏（少卖），需要把库存补回去。"
                    : "，若在途 " + inFlight + " 条消息最终都落库，则属正常的中间状态。");
            case MARK_MISSING -> "数据库订单多于 Redis 已购标记：有用户买到了却没有留下标记，"
                    + "他会被 Redis 重新放行（数据库仍能兜住，但会白白多走一遍落库）。";
        };
    }
}
