package com.dustikun.seckill.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class Order {

    /** 订单状态：已受理、待后台确认（迁移第二步起由请求线程写入） */
    public static final String STATUS_PENDING = "PENDING";
    /** 订单状态：已确认（本步所有落库路径的显式取值，等价于引入状态机前的「落库即成功」） */
    public static final String STATUS_CONFIRMED = "CONFIRMED";
    /** 订单状态：已取消（DB 库存不足等罕见路径，处置为补库存、留标记） */
    public static final String STATUS_CANCELLED = "CANCELLED";

    private Long id;
    private String orderNo;
    private Long userId;
    private Long stockId;
    /** 订单状态机，取值见上方常量；insert 显式携带该列，缺失时由 NOT NULL 约束直接拒绝 */
    private String status;
    private LocalDateTime createTime;
}
