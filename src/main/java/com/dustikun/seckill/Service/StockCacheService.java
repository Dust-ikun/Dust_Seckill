package com.dustikun.seckill.Service;

import com.dustikun.seckill.Common.Exception.BizException;
import com.dustikun.seckill.Common.Exception.ErrorCode;
import com.dustikun.seckill.Common.constant.SeckillRedisKeys;
import com.dustikun.seckill.Common.result.RollbackResult;
import com.dustikun.seckill.Mapper.StockMapper;
import com.dustikun.seckill.entity.Stock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * 库存缓存层：负责 Redis 侧库存的预热、原子扣减、回补与查询。
 * <p>
 * 阶段 3 的核心变化——把「校验 + 扣减」从数据库搬到 Redis，用一段 Lua 脚本在 Redis 单线程内
 * 原子完成，把原本打在数据库行锁上的热点流量转化成了内存操作。
 * <p>
 * 该层只负责 Redis 侧，不碰事务；数据库落库由 {@link SeckillPersistenceService} 负责。
 */
@Slf4j
@Service
public class StockCacheService {

    // ==================== 扣减脚本返回码（对应 seckill_deduct.lua） ====================
    /** 扣减成功：返回值 >= 0，数值即扣减后的剩余库存 */
    public static final int RESULT_STOCK_NOT_ENOUGH = -1;
    public static final int RESULT_REPEAT_ORDER = -2;
    public static final int RESULT_NOT_PREHEATED = -3;
    public static final int RESULT_ILLEGAL_NUM = -4;

    // ==================== 回补脚本返回码（对应 seckill_rollback.lua） ====================
    /** 入参非法（回补数量 <= 0） */
    private static final int ROLLBACK_ILLEGAL_NUM = -1;
    /** 库存 key 不存在（活动已结束 / 缓存已清理），本次未修改任何数据 */
    private static final int ROLLBACK_ACTIVITY_CLOSED = -2;
    /** 用户预扣标记不存在，说明此前已回补过；本次未修改任何数据 */
    private static final int ROLLBACK_ALREADY_DONE = -3;

    /**
     * 「仅回补库存」去重键的存活时长。
     * <p>
     * 这个 TTL 不是随便定的：它必须显著长于「同一笔回补可能被重试的全部时间窗口」，
     * 否则去重键过期后重试会再补一次，把库存补多（→ 超卖）。
     * 本项目的重试来源有两处：MQ 消费重试（秒级）与待补偿任务的定时重试（最长约 30 分钟），
     * 取 24 小时留出充分余量，同时到期自动回收，不会让 key 永久堆积。
     */
    private static final long RESTORE_DEDUPE_TTL_SECONDS = 24 * 60 * 60L;

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<Long> deductScript;
    private final RedisScript<Long> rollbackScript;
    private final RedisScript<Long> restoreStockScript;
    private final StockMapper stockMapper;

    public StockCacheService(StringRedisTemplate redisTemplate,
                             @Qualifier("seckillDeductScript") RedisScript<Long> deductScript,
                             @Qualifier("seckillRollbackScript") RedisScript<Long> rollbackScript,
                             @Qualifier("seckillRestoreStockScript") RedisScript<Long> restoreStockScript,
                             StockMapper stockMapper) {
        this.redisTemplate = redisTemplate;
        this.deductScript = deductScript;
        this.rollbackScript = rollbackScript;
        this.restoreStockScript = restoreStockScript;
        this.stockMapper = stockMapper;
    }

    /**
     * 库存预热：把数据库库存前置到 Redis，并清空上一轮的已购用户集合。
     * <p>
     * 先删已购集合再写库存，保证不会出现「新库存 + 旧已购集合」的组合；
     * 预热通常在活动开始前离线执行，此处的非原子窗口不在请求路径上。
     * <p>
     * <b>预热同时是「对账修复」的天然手段</b>：重置后的 Redis 库存直接取自数据库当前值，
     * 因此发生降级写库（Redis 与 DB 不一致）之后，重新预热即可让两边重新对齐。
     */
    public long preheat(Long stockId) {
        Stock stock = stockMapper.selectById(stockId);
        if (stock == null) {
            throw new BizException(ErrorCode.STOCK_NOT_FOUND);
        }

        redisTemplate.delete(SeckillRedisKeys.boughtUsers(stockId));
        redisTemplate.opsForValue().set(SeckillRedisKeys.stock(stockId), String.valueOf(stock.getCount()));

        log.info("[预热] stockId={} 库存已前置到 Redis，count={}", stockId, stock.getCount());
        return stock.getCount();
    }

    /**
     * 原子预扣减。
     * <p>
     * 【契约（评审修复后已收敛为无歧义）】
     * <ul>
     *   <li>成功 —— 正常返回，不返回值；</li>
     *   <li>业务拒绝（库存不足 / 重复下单 / 未预热 / 参数错误）—— 抛 {@link BizException}；</li>
     *   <li>Redis 故障 —— 抛 {@code DataAccessException}，由调用方决定是否降级。</li>
     * </ul>
     * 原先的 {@code boolean} 返回值只可能是 {@code true}（失败一律走异常），
     * 却会让调用方误以为「返回 false 表示可继续往下走」。去掉它比保留一个永远为真的布尔值更不容易出错。
     */
    public void tryDeduct(Long stockId, Long userId, long num) {
        Long result = redisTemplate.execute(
                deductScript,
                keys(stockId),
                String.valueOf(userId),
                String.valueOf(num));

        if (result == null) {
            throw new BizException(ErrorCode.SYSTEM_BUSY);
        }
        if (result >= 0) {
            return;
        }

        throw switch (result.intValue()) {
            case RESULT_STOCK_NOT_ENOUGH -> new BizException(ErrorCode.STOCK_NOT_ENOUGH);
            case RESULT_REPEAT_ORDER -> new BizException(ErrorCode.REPEAT_ORDER);
            case RESULT_NOT_PREHEATED -> new BizException(ErrorCode.STOCK_NOT_READY);
            // 这个分支只会在脚本入参校验失败时出现，属于代码缺陷而非业务失败，
            // 因此显式列出来（而不是落进 default），便于日志里一眼看出是参数问题
            case RESULT_ILLEGAL_NUM -> new BizException(ErrorCode.PARM_ERROR);
            default -> new BizException(ErrorCode.SYSTEM_BUSY);
        };
    }

    /**
     * 回补 Redis 库存与用户预扣标记（补偿落库失败的那一次预扣减）。
     * <p>
     * 方法返回 {@link RollbackResult} 而不是一个 long + 魔数，原因是回补路径上存在四种
     * 互不相同的结局，调用方对它们的处理方式完全不同：
     * <ul>
     *   <li>{@code SUCCESS} —— 真的把库存还回去了，需要记录日志并累加补偿计数；</li>
     *   <li>{@code ACTIVITY_CLOSED} —— 活动已结束，本来就不需要回补，属于正常结局；</li>
     *   <li>{@code ALREADY_ROLLED_BACK} —— 幂等命中，此前已补过，<b>绝不能再补一次</b>；</li>
     *   <li>{@code REDIS_ERROR} —— 回补是否生效<b>未知</b>，需要人工确认。</li>
     * </ul>
     * 若把后三种都糊成 -1 返回，调用方既无法判断要不要重试，也无法判断要不要告警——
     * 而「误把幂等命中当成失败去重试」会直接导致库存虚增。
     *
     * @return 回补结果，含状态与回补后的剩余库存
     */
    public RollbackResult rollback(Long stockId, Long userId, long num) {
        Long result = redisTemplate.execute(
                rollbackScript,
                keys(stockId),
                String.valueOf(userId),
                String.valueOf(num));

        if (result == null) {
            // Redis 连接中断等：脚本可能执行了也可能没执行，必须如实告知「结果未知」
            log.error("[回补] Redis 未返回结果，回补状态未知，需人工确认。stockId={}, userId={}", stockId, userId);
            return RollbackResult.of(RollbackResult.Status.REDIS_ERROR);
        }
        if (result >= 0) {
            return RollbackResult.success(result.intValue());
        }

        return RollbackResult.of(switch (result.intValue()) {
            case ROLLBACK_ILLEGAL_NUM -> RollbackResult.Status.ILLEGAL_ARGUMENT;
            case ROLLBACK_ACTIVITY_CLOSED -> RollbackResult.Status.ACTIVITY_CLOSED;
            case ROLLBACK_ALREADY_DONE -> RollbackResult.Status.ALREADY_ROLLED_BACK;
            default -> RollbackResult.Status.REDIS_ERROR;
        });
    }

    /**
     * 只回补库存，<b>保留</b>用户已购标记。
     * <p>
     * 用于「用户其实已经买过」的场景（落库撞 {@code uk_user_stock}）：本次请求在 Redis 里的
     * 预扣是多余的一次，必须把库存还回去；但该用户确实持有订单，标记必须留着，
     * 否则他会被 Redis 重新放行，陷入「放行 → DB 拦截 → 回补 → 再放行」的死循环。
     *
     * @param token 幂等标识，通常传业务单号。同一笔回补无论重试多少次，只有第一次会真正生效
     */
    public RollbackResult restoreStockOnly(Long stockId, Long userId, String token, long num) {
        Long result = redisTemplate.execute(
                restoreStockScript,
                restoreKeys(stockId, token),
                String.valueOf(userId),
                String.valueOf(num),
                String.valueOf(RESTORE_DEDUPE_TTL_SECONDS));

        if (result == null) {
            log.error("[仅回补库存] Redis 未返回结果，状态未知，需人工确认。stockId={}, token={}", stockId, token);
            return RollbackResult.of(RollbackResult.Status.REDIS_ERROR);
        }
        if (result >= 0) {
            return RollbackResult.success(result.intValue());
        }

        return RollbackResult.of(switch (result.intValue()) {
            case ROLLBACK_ILLEGAL_NUM -> RollbackResult.Status.ILLEGAL_ARGUMENT;
            case ROLLBACK_ACTIVITY_CLOSED -> RollbackResult.Status.ACTIVITY_CLOSED;
            case ROLLBACK_ALREADY_DONE -> RollbackResult.Status.ALREADY_ROLLED_BACK;
            default -> RollbackResult.Status.REDIS_ERROR;
        });
    }

    /**
     * 查询某个用户是否持有该活动的预扣标记（Redis 集合的 O(1) 判断）。
     * <p>
     * 承担两个职责：
     * <ol>
     *   <li>「一人一单」的判据；</li>
     *   <li>「这笔预扣仍在处理中」的凭据——订单查询接口据此区分「仍在排队」与「最终失败」。</li>
     * </ol>
     * 另外它还是 Redis 故障降级时的探测手段：请求在 Pre 扣减时抛异常后，
     * 用它可以尽力判断「命令到底执行成功了没有」（见 {@code SeckillService#probePreDeducted}）。
     */
    public boolean isBought(Long stockId, Long userId) {
        return Boolean.TRUE.equals(redisTemplate.opsForSet()
                .isMember(SeckillRedisKeys.boughtUsers(stockId), String.valueOf(userId)));
    }

    /**
     * 查询 Redis 侧剩余库存，未预热返回 null。
     */
    public Integer remain(Long stockId) {
        String value = redisTemplate.opsForValue().get(SeckillRedisKeys.stock(stockId));
        return value == null ? null : Integer.valueOf(value);
    }

    // ==================== 以下为对账（阶段 4 评审修复）所需的读写能力 ====================

    /** 已购用户集合的规模（对账用，SCARD 是 O(1)） */
    public long boughtCount(Long stockId) {
        Long size = redisTemplate.opsForSet().size(SeckillRedisKeys.boughtUsers(stockId));
        return size == null ? 0L : size;
    }

    /** 已购用户集合快照，仅对账修复路径调用（SMEMBERS 在大集合上是 O(N)，不在请求路径上） */
    public Set<String> boughtUserIds(Long stockId) {
        Set<String> members = redisTemplate.opsForSet().members(SeckillRedisKeys.boughtUsers(stockId));
        return members == null ? Set.of() : members;
    }

    /**
     * 把 Redis 库存直接校准为给定值。
     * <p>
     * <b>仅供对账修复调用</b>：它绕过了 Lua 的原子校验，是一次「相信数据库」的强制覆盖。
     * 调用方必须先确认没有在途预扣，否则会把在途占用的名额一并释放出去。
     */
    public void syncStock(Long stockId, long value) {
        redisTemplate.opsForValue().set(SeckillRedisKeys.stock(stockId), String.valueOf(value));
        log.warn("[对账修复] Redis 库存已强制校准。stockId={}, value={}", stockId, value);
    }

    /**
     * 批量补写已购标记。
     * <p>
     * 只做加法、不做删除：漏标会让用户被重复放行（DB 还能兜住，是「可控的错」），
     * 而误删标记会让已经买到的用户重新抢到（DB 会拦，但也可能是别人抢走了他的名额）。
     * 且当前实现无法区分「标记该有但没有」与「标记是别人在途预扣产生的」，
     * 因此只补不删是这里唯一安全的策略。
     *
     * @return 实际新增的标记数
     */
    public long addBoughtUsers(Long stockId, Collection<String> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return 0L;
        }
        Long added = redisTemplate.opsForSet()
                .add(SeckillRedisKeys.boughtUsers(stockId), userIds.toArray(new String[0]));
        return added == null ? 0L : added;
    }

    /**
     * 清理活动缓存（活动结束后调用），避免 Redis 里堆积无用的 key。
     * <p>
     * 回补去重键不在这里删除：它带 TTL（24 小时），会自行回收；
     * 而提前删掉反而会给「同一笔回补被重复执行」打开窗口。
     */
    public void clear(Long stockId) {
        redisTemplate.delete(keys(stockId));
        log.info("[清理] stockId={} 的活动缓存已清除", stockId);
    }

    private List<String> keys(Long stockId) {
        return List.of(SeckillRedisKeys.stock(stockId), SeckillRedisKeys.boughtUsers(stockId));
    }

    private List<String> restoreKeys(Long stockId, String token) {
        return List.of(
                SeckillRedisKeys.stock(stockId),
                SeckillRedisKeys.boughtUsers(stockId),
                SeckillRedisKeys.restoreDedupe(stockId, token));
    }
}
