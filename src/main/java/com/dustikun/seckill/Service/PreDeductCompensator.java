package com.dustikun.seckill.Service;

import com.dustikun.seckill.Common.constant.CompensateType;
import com.dustikun.seckill.Common.result.RollbackResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Redis 预扣的「完整回滚」补偿动作（阶段 5 抽取）。
 * <p>
 * 【为什么要抽出来】这个动作现在有两个调用方：请求线程（写待投递记录失败、降级同步落库失败）
 * 与后台投递器（消息重试耗尽）。两处若各写一遍，最容易出现的偏差是
 * 「一边登记了待补偿任务、另一边只打了日志」——而后者正是库存静默丢失的来源。
 * <p>
 * 【为什么归属到「待补偿任务」而不是就地重试】调用方都是短命的：Redis 挂了它不可能在那里等。
 * 只有把「这笔库存要还回去」写进数据库，它才获得独立于调用线程的生命周期。
 */
@Slf4j
@Service
public class PreDeductCompensator {

    /** 回补结局。三种结局对调用方的意义完全不同，因此不压缩成 boolean */
    public enum Outcome {
        /** 真的把库存与标记归还了 */
        ROLLED_BACK,
        /** 无需归还：此前已补过、或活动已结束。属于正常结局，不该告警 */
        BENIGN,
        /** 回补失败，已登记待补偿任务，需要人工关注 */
        FAILED
    }

    private final StockCacheService stockCacheService;
    private final CompensateTaskService compensateTaskService;

    public PreDeductCompensator(StockCacheService stockCacheService,
                                CompensateTaskService compensateTaskService) {
        this.stockCacheService = stockCacheService;
        this.compensateTaskService = compensateTaskService;
    }

    /**
     * 完整回滚一次 Redis 预扣（库存归还 + 摘掉已购标记）。
     *
     * @param reason 登记原因，写进待补偿任务的 reason 与日志，便于事后还原现场
     */
    public Outcome rollbackAll(Long stockId, Long userId, long num, String reason) {
        try {
            RollbackResult result = stockCacheService.rollback(stockId, userId, num);
            if (result.rolledBack()) {
                log.warn("[回补] Redis 库存已回补。stockId={}, userId={}, reason={}, remain={}",
                        stockId, userId, reason, result.remainStock());
                return Outcome.ROLLED_BACK;
            }
            if (result.benign()) {
                // 已补过 / 活动已结束，都属正常结局，不计入告警
                log.info("[回补] 无需回补（{}）。stockId={}, userId={}, reason={}",
                        result.status(), stockId, userId, reason);
                return Outcome.BENIGN;
            }
            log.error("[回补失败·已登记待补偿] status={}, stockId={}, userId={}, reason={}",
                    result.status(), stockId, userId, reason);
            compensateTaskService.enqueue(stockId, userId, null, num, CompensateType.ROLLBACK_ALL,
                    reason + " | 回补失败：" + result.status());
            return Outcome.FAILED;
        } catch (Exception ex) {
            log.error("[回补异常·已登记待补偿] stockId={}, userId={}, reason={}", stockId, userId, reason, ex);
            compensateTaskService.enqueue(stockId, userId, null, num, CompensateType.ROLLBACK_ALL,
                    reason + " | 回补异常：" + ex.getClass().getSimpleName());
            return Outcome.FAILED;
        }
    }
}
