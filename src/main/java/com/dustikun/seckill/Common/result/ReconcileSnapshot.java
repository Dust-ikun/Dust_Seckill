package com.dustikun.seckill.Common.result;

import lombok.Data;

/**
 * 对账快照：用<b>一条 SQL</b> 同时取回对账所需的三个数据库事实。
 * <p>
 * 【为什么必须单条 SQL】对账的不变量是 {@code Redis 库存 == 数据库库存 − 在途预扣数}，
 * 其中「数据库库存」和「在途预扣数（PENDING 订单数）」必须来自<b>同一时刻</b>的数据库视图。
 * 分两次查询时，两次查询之间落库的订单会让等式自己制造出假不一致
 * —— 本项目压测阶段实测踩过这个坑，因此一致性断言一律用单条 SQL 取快照。
 * <p>
 * {@code dbStock} 用包装类型：活动行不存在时该标量子查询返回 NULL，
 * 借此区分「活动不存在」（应抛 STOCK_NOT_FOUND）与「取到 0」。
 */
@Data
public class ReconcileSnapshot {

    /** 数据库侧剩余库存（stock.count）；活动行不存在时为 null */
    private Long dbStock;

    /** 该活动的 PENDING 预订单数——订单前置之后，「在途」直接有了数据库事实 */
    private long pendingOrders;

    /** 该活动的订单总数（含全部状态），用于 MARK_MISSING 判定与报告展示 */
    private long dbOrderCount;
}
