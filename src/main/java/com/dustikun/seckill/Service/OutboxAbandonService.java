package com.dustikun.seckill.Service;

import com.dustikun.seckill.Common.constant.CompensateType;
import com.dustikun.seckill.Mapper.OutboxMessageMapper;
import com.dustikun.seckill.entity.CompensateTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 「放弃投递」的落库点：把 outbox 的 FAILED 终态与「这笔预扣必须归还」的待办
 * 写进<b>同一个本地事务</b>。
 *
 * <pre>
 *   旧形态（有窗口，且这个窗口没有任何持久化痕迹）
 *     投递器：markFailed（自动提交）→ 【进程在这里消失】→ 回补 Redis 库存
 *                                      ↑ 库里写着「已放弃」，Redis 里那件库存却再没人归还；
 *                                        订单仍是 PENDING，于是对账还会把它算成正常在途
 *   现形态（窗口关闭）
 *     投递器：┌ 同一事务 ──────────────────────────────┐
 *             │ markFailed(PENDING → FAILED)           │
 *             │ INSERT compensate_task(CANCEL_ORDER)   │← 「这笔必须归还」成为可查、可重试的事实
 *             └────────────────────────────────────────┘
 *             → 立刻尝试执行一次（失败也无妨，MaintenanceTask 会按退避把它做完）
 * </pre>
 *
 * 【为什么是「先落库、再动作」，而不是「先回补、失败再登记」】
 * 后者把「登记」放在了动作<b>之后</b>：动作与登记之间进程消失，那条记录就只剩一句日志。
 * 而这一步对账也救不了：崩溃前后数据库库存、PENDING 订单数、Redis 库存、已购标记
 * 全都没变，对账读到的每个输入都一样，只会报 CONSISTENT（详见 {@link StockReconcileService}
 * 的 ABANDONED_PENDING 与 {@code ReconcileReport.Status}）。把意图先写进库，
 * 「归还」才变成一个独立于调用线程生命周期的待办。
 *
 * 【为什么用 CANCEL_ORDER 而不是 ROLLBACK_ALL】两条放弃路径必须是同一套语义：
 * <ul>
 *   <li>消费端重试耗尽 → <b>先取消订单</b>，确认订单不成立后才归还库存
 *       （见 {@code SeckillOrderConsumer#handleFailure}）；</li>
 *   <li>投递端重试耗尽 → 现在也走这一套。</li>
 * </ul>
 * 而 ROLLBACK_ALL 只归还库存、<b>不取消订单</b>：一条「既不成立、也不了结」的 PENDING 单
 * 会永久留在 orders 里 —— 查单接口永远回「处理中」，而且它正是污染对账在途口径的那条僵尸。
 * 幂等性上 CANCEL_ORDER 也更结实：它的去重键是「活动 + 单号」的一次性键，
 * 而 ROLLBACK_ALL 靠「摘掉已购标记」去重，那个标记会被对账的补标动作重新装上 ——
 * 等于把去重保护撤掉。
 *
 * 【为什么必须抽成独立 Bean】{@code @Transactional} 靠代理生效，
 * {@code OutboxService} 在类内部调用自己的方法不会开启事务（self-invocation 坑）。
 * 与 {@code SeckillPersistenceService} 抽成独立 Bean 是同一条理由：让事务边界显式、可见。
 */
@Slf4j
@Service
public class OutboxAbandonService {

    private final OutboxMessageMapper outboxMapper;
    private final CompensateTaskService compensateTaskService;

    public OutboxAbandonService(OutboxMessageMapper outboxMapper,
                                CompensateTaskService compensateTaskService) {
        this.outboxMapper = outboxMapper;
        this.compensateTaskService = compensateTaskService;
    }

    /**
     * 放弃一条投递不出去的记录，并登记「取消订单 + 归还预扣」的待办。
     * <p>
     * 本方法只负责<b>让这件事成立</b>，不负责执行归还 —— 执行由调用方在本方法返回后
     * （事务已提交）触发，见 {@link CompensateTaskService#executeNow(CompensateTask)}。
     *
     * @param error 写进 {@code last_error} 与待办 reason 的失败原因
     * @return 登记出来的待办任务；<b>null 表示本次没有真的放弃</b>（记录已被投出或已被放弃过），
     *         此时调用方<b>不得再归还库存</b> —— 那条消息可能已经在消费端手上
     */
    @Transactional(rollbackFor = Exception.class)
    public CompensateTask abandon(Long outboxId, String error, Long stockId, Long userId,
                                  String orderNo, long num) {
        // 有条件的状态流转：赢下这次翻转才有资格登记归还。多实例同时放弃同一条记录时，
        // 只有一个实例的 UPDATE 会得到 1 行，另一个拿到 0 行 —— 于是只有一条待办、只归还一次。
        if (outboxMapper.markFailed(outboxId, error) == 0) {
            log.warn("[Outbox·放弃] 记录已不处于 PENDING（已被投出或已被放弃），本次不登记归还待办。id={}",
                    outboxId);
            return null;
        }

        // 与上面的状态流转同事务：插入失败会把 FAILED 一起回滚，记录退回 PENDING 继续重试，
        // 而不是留下一条「已放弃、无人归还」的记录 —— 那正是本类要关掉的那个窗口。
        return compensateTaskService.enqueueStrict(stockId, userId, orderNo, num,
                CompensateType.CANCEL_ORDER, "Outbox 投递重试耗尽：" + error);
    }
}
