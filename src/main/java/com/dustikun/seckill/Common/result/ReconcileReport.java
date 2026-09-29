package com.dustikun.seckill.Common.result;

import java.util.List;

/**
 * 库存对账报告（阶段 4 评审修复新增）。
 * <p>
 * 【为什么必须有对账】Redis 预扣 + 数据库落库这条链路上，有两个补偿逻辑**结构上照不到**的角落：
 * <ol>
 *   <li><b>Redis 故障降级</b>：请求回落到 MySQL 条件扣减并成功，但 Redis 完全不知情。
 *       Redis 恢复后它显示的还是故障前的库存，比数据库多 —— 后续请求会被 Redis 放行、
 *       再被数据库拒绝，用户看到「抢到了」却下不了单。补偿逻辑在这里帮不上忙，
 *       因为 {@code preDeducted=false}，压根不会触发。</li>
 *   <li><b>Redis 假阴性</b>：命令实际执行成功、只是响应超时。调用方以为没扣，
 *       于是这次预扣成了无人认领的孤儿。</li>
 * </ol>
 * 这两条路径都无法靠「事后补偿」修，只能靠<b>比对 Redis 与数据库的真实状态</b>发现并纠正。
 *
 * @param stockId            活动 ID
 * @param status             对账结论
 * @param redisStock         Redis 侧剩余库存；{@code null} 表示未预热
 * @param dbStock            数据库侧剩余库存
 * @param expectedInFlight   参与不变量计算的「在途预扣数」——来源见 {@code inFlightSource}
 * @param inFlightSource     在途数的来源：数据库自动计算，或调用方显式声明
 * @param pendingOrders      快照里的 PENDING 预订单数（AUTO 模式下即 inFlight 的取值来源）
 * @param redisBoughtCount   Redis 已购集合规模
 * @param dbOrderCount       数据库订单数
 * @param repaired           本次调用是否真的修改了数据
 * @param actions            实际执行（或建议执行）的动作明细
 * @param conclusion         面向人的一句话结论
 */
public record ReconcileReport(
        Long stockId,
        Status status,
        Integer redisStock,
        long dbStock,
        long expectedInFlight,
        InFlightSource inFlightSource,
        long pendingOrders,
        long redisBoughtCount,
        long dbOrderCount,
        boolean repaired,
        List<String> actions,
        String conclusion
) {

    /**
     * 在途预扣数的来源。
     * <pre>
     *   AUTO   —— 数据库自动计算：COUNT(orders WHERE stock_id=? AND status='PENDING')。
     *            订单前置之后「在途」直接有了数据库事实，这是默认模式；
     *   MANUAL —— 调用方显式传入（expectedInFlight >= 0）。保留它是为了覆盖
     *            「我明知有 N 条在途、只想按这个数判」的场景，以及旧脚本兼容。
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
         * AUTO 模式下这<b>基本可以确定</b>是异常：期望值已按「数据库库存 − 全部 PENDING 预订单」
         * 放宽，即使算上所有在途仍对不上，只能是绕过订单记录的预扣泄漏（少卖）。
         * 唯一的误报来源是读偏斜（Redis 恰好在快照之后、读数之前被扣，见对账服务类注释），
         * 定时对账用排空门控规避；MANUAL 模式下它还可能是调用方少报了在途。
         */
        REDIS_BEHIND,
        /**
         * 库存对得上，但数据库订单数多于 Redis 标记数 —— 有用户买到了却没留下标记，
         * 他会被 Redis 重新放行（数据库还能兜住，但每次都要白白走一遍落库）。
         */
        MARK_MISSING
    }

    public boolean healthy() {
        return status == Status.CONSISTENT;
    }
}
