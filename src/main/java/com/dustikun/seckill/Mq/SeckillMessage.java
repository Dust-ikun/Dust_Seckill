package com.dustikun.seckill.Mq;

/**
 * 秒杀下单消息体（RocketMQ 消息的 payload）。
 * <p>
 * 【为什么 orderNo 由请求线程生成、再随消息带过来，而不是消费者落库时生成】
 * 请求线程必须在返回前就告诉用户单号，用户才能拿它去查结果；而落库是之后才发生的。
 * 若让消费者生成单号，用户手里的单号就无从对应，幂等判断也失去了天然的唯一键。
 * 所以单号在这里的角色不只是「订单标识」，还是 <b>消费端幂等的唯一依据</b>：
 * 订单已在请求线程落库，同一消息被重复投递时，消费端靠它定位订单，
 * 并以「PENDING → CONFIRMED」状态流转的影响行数识别重复。
 *
 * @param orderNo    业务单号（Snowflake 生成，全局唯一且趋势递增）
 * @param userId     下单用户
 * @param stockId    秒杀商品
 * @param num        扣减数量
 * @param createTime 消息创建时间（毫秒时间戳），用于排查投递与消费的延迟
 */
public record SeckillMessage(
        String orderNo,
        Long userId,
        Long stockId,
        long num,
        long createTime
) {

    public static SeckillMessage of(String orderNo, Long userId, Long stockId, long num) {
        return new SeckillMessage(orderNo, userId, stockId, num, System.currentTimeMillis());
    }
}
