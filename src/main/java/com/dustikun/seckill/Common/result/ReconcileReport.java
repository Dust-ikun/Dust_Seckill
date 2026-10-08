package com.dustikun.seckill.Common.result;

import java.util.List;

/**
 * 库存对账报告。
 * <p>
 * 【为什么必须有对账】Redis 预扣 + 数据库落库这条链路上，有几个补偿逻辑**结构上照不到**的角落：
 * <ol>
 *   <li><b>Redis 故障降级</b>：请求回落到 MySQL 条件扣减并成功，但 Redis 完全不知情。
 *       Redis 恢复后它显示的还是故障前的库存，比数据库多 —— 后续请求会被 Redis 放行、
 *       再被数据库拒绝，用户看到「抢到了」却下不了单。补偿逻辑在这里帮不上忙，
 *       因为 {@code preDeducted=false}，压根不会触发。</li>
 *   <li><b>Redis 假阴性</b>：命令实际执行成功、只是响应超时。调用方以为没扣，
 *       于是这次预扣成了无人认领的孤儿。</li>
 *   <li><b>预订单失去凭据</b>：投递重试耗尽的记录在 outbox 里是 FAILED 终态，可它的订单仍是 PENDING；
 *       或者消息投出去了却没人消费（消费者组挂掉、消息丢失），订单同样永远停在 PENDING。
 *       这类单既不会落库、也不会取消，一边污染「在途」口径，
 *       一边让一笔丢失的预扣看起来完全正常
 *       （见 {@link Status#ABANDONED_PENDING} 与 {@link Status#STALE_PENDING}）。</li>
 * </ol>
 * 这三条路径都无法靠「事后补偿」修，只能靠<b>比对 Redis 与数据库的真实状态</b>发现并纠正。
 *
 * @param stockId                活动 ID
 * @param status                 对账结论
 * @param redisStock             Redis 侧剩余库存；{@code null} 表示未预热
 * @param dbStock                数据库侧剩余库存
 * @param expectedInFlight       参与不变量计算的「可信在途」——来源见 {@code inFlightSource}
 * @param inFlightSource         在途数的来源：数据库自动计算，或调用方显式声明
 * @param pendingOrders          快照里的 PENDING 预订单数（<b>未</b>剔除下面两类）
 * @param abandonedPending       其中「投递已放弃、订单仍停在 PENDING」的条数：既不是在途，也不了结
 * @param stalePending           其中「超过陈旧阈值仍是 PENDING」的条数（与上一项互斥）
 * @param unresolvedCompensations 该活动未了结的归还义务条数（待补偿任务 PENDING 或 FAILED）。
 *                                &gt; 0 时对账不会校准库存 —— 归还动作与校准都会 INCRBY 同一件库存，
 *                                两处都动手就是归还两次。它的存在让「为什么这轮没修」在报告里可见
 * @param redisBoughtCount       Redis 已购集合规模
 * @param dbOrderCount           数据库订单数
 * @param repaired               本次调用是否真的修改了数据
 * @param actions                实际执行过的修复动作明细（含被跳过的动作与原因）；{@code repair=false} 时恒为空列表
 * @param conclusion             面向人的一句话结论
 */
public record ReconcileReport(
        Long stockId,
        Status status,
        Integer redisStock,
        long dbStock,
        long expectedInFlight,
        InFlightSource inFlightSource,
        long pendingOrders,
        long abandonedPending,
        long stalePending,
        long unresolvedCompensations,
        long redisBoughtCount,
        long dbOrderCount,
        boolean repaired,
        List<String> actions,
        String conclusion
) {

    /**
     * 在途预扣数的来源。
     * <pre>
     *   AUTO   —— 数据库自动计算：COUNT(orders WHERE stock_id=? AND status='PENDING')，
     *             再减去「投递已放弃、订单仍为 PENDING」的条数（那些已经不是可了结的在途）。
     *             订单前置之后「在途」直接有了数据库事实，这是默认模式；
     *   MANUAL —— 调用方显式传入（expectedInFlight >= 0）。保留它是为了覆盖
     *             「我明知有 N 条在途、只想按这个数判」的场景，以及旧脚本兼容。
     * </pre>
     */
    public enum InFlightSource {
        AUTO,
        MANUAL
    }

    public enum Status {
        /** Redis 里没有这个活动的库存 key，对账无从谈起（应先预热） */
        NOT_PREHEATED,
        /** 两侧一致，且标记集合没有缺人 */
        CONSISTENT,
        /**
         * Redis 库存 <b>大于</b>应有值。
         * <p>
         * 这是<b>可以确定</b>的异常：Redis 只在扣减时减少库存，因此它不可能比数据库「落后得更少」。
         * 出现它必然意味着有人绕过 Redis 直接写了数据库——也就是降级路径。
         */
        REDIS_AHEAD,
        /**
         * Redis 库存 <b>小于</b>应有值。
         * <p>
         * 期望值已按「数据库库存 − 可信在途」放宽，即使算上所有在途仍对不上，
         * 只能是绕过订单记录的预扣泄漏（少卖）。唯一的误报来源是读偏斜
         * （Redis 恰好在快照之后、读数之前被扣，见对账服务类注释），定时对账用排空门控规避；
         * MANUAL 模式下它还可能是调用方少报了在途。
         */
        REDIS_BEHIND,
        /**
         * 库存对得上，但数据库订单数多于 Redis 标记数 —— 有用户买到了却没留下标记，
         * 他会被 Redis 重新放行（数据库还能兜住，但每次都要白白走一遍落库）。
         */
        MARK_MISSING,
        /**
         * 存在「投递已放弃（outbox = FAILED）、订单仍停在 PENDING」的预订单。
         * <p>
         * 为什么它必须单独成为一个结论，而不是并进上面任何一条：
         * <ul>
         *   <li>这些单<b>不是在途</b>：没有消息会再去确认它们，放弃路径也不取消订单，
         *       它们永远不会消耗数据库库存。算进在途就等于给不变量两边同时减一，
         *       「已扣 Redis、没扣数据库、也没人认领」的那件库存会被差值抵消，
         *       真正的泄漏于是读成 CONSISTENT —— 这正是本状态要堵的洞。</li>
         *   <li>它们<b>也不一定表现为库存差额</b>：归还动作可能已经成功（Redis 已经是对的），
         *       只是订单还挂在 PENDING。按库存判定会得到 CONSISTENT，
         *       而用户侧的事实是「订单永远停在处理中」，必须有人知道。</li>
         *   <li>只要还有这种单，库存校准就<b>不能</b>做：归还动作（待补偿任务）与对账的校准
         *       都会去 INCRBY 同一件库存，谁先谁后都会多还一次。</li>
         * </ul>
         * 处置方式是「为它们补登记归还待办」，而不是「对账自己改库存」，
         * 见 {@code StockReconcileService#reconcile}。
         */
        ABANDONED_PENDING,

        /**
         * 存在「超过陈旧阈值仍是 PENDING」的预订单（阈值见 {@code stale-pending-minutes}）。
         * <p>
         * 与 {@link #ABANDONED_PENDING} 是同一族问题的另一种成因：
         * <ul>
         *   <li><b>消息投出去了却没人消费</b>：消费者组挂了、消息在 Broker 侧丢失。
         *       此时 outbox 是 SENT（甚至已被归档），订单永远停在 PENDING，
         *       而排空门控会一直关着 —— 这正是本状态要与「门控封顶」一起引入的原因。</li>
         *   <li><b>根本没有凭据</b>：{@code seckill.outbox.enabled=false} 的对照链路上，
         *       进程在「订单已建、消息未投」之间消失。这种单没有任何数据库事实能证明它已被放弃，
         *       只能靠「时间久到不可能再有正当理由」来判定。</li>
         * </ul>
         * 它同样不是在途（不会消耗数据库库存，Redis 侧的预扣也应当收回），
         * 处置方式与已放弃的一致：补登记「取消订单 + 归还预扣」待办。
         * <p>
         * 【为什么要给阈值留足余量】正常链路上一条订单从建单到确认只需秒级，
         * 但 Broker 长时间不可用时投递会退避重试：默认 15 次、延迟 2 秒起指数翻倍
         * （单次上限 5 分钟，而 base=2 时实际最大只有 128 秒），14 次等待合计 1150 秒 ≈ 19 分钟，
         * 再加消费端重试（10s/30s/1min）。阈值小于这条最坏时延就会把「还活着的单」误判成陈旧 ——
         * 那会导致库存被提前收回、用户订单被取消（少卖，且用户可见）。
         * 默认 90 分钟是「大于最坏时延约 4 倍」的取值；而默认 {@code auto-repair=false} 时
         * 本状态只告警、不自动取消，误判的代价仅是一条需要人工核对的报告。
         */
        STALE_PENDING
    }

    public boolean healthy() {
        return status == Status.CONSISTENT;
    }
}
