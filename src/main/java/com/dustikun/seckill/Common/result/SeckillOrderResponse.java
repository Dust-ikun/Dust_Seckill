package com.dustikun.seckill.Common.result;

/**
 * 秒杀下单的受理结果。
 * <p>
 * 下单与落库是拆开的：请求线程只负责「Redis 预扣 + 建预订单/登记待投递凭据」，
 * 落库由消费线程异步完成。因此接口的返回语义是「已受理」而非「下单成功」，
 * 客户端拿到 {@code orderNo} 后需要轮询 {@link #status} 才能知道最终结果——
 * 这是把数据库热点写从请求路径上摘掉的代价。
 *
 * @param orderNo 业务单号，客户端据此轮询最终结果
 * @param status  受理状态
 * @param message 面向用户的说明
 */
public record SeckillOrderResponse(String orderNo, Status status, String message) {

    public enum Status {
        /** 已受理：预订单与待投递凭据已落库，等待后台投递与消费端异步落库 */
        QUEUED,
        /** 已成功：订单已落库（异步消费完成，或走降级同步落库时直接返回） */
        SUCCESS,
        /** 已失败：异步落库最终失败，预扣的库存已回补，用户可以重试 */
        FAILED
    }

    public static SeckillOrderResponse queued(String orderNo) {
        return new SeckillOrderResponse(orderNo, Status.QUEUED, "已受理，正在异步落库");
    }

    public static SeckillOrderResponse success(String orderNo) {
        return new SeckillOrderResponse(orderNo, Status.SUCCESS, "下单成功");
    }

    public static SeckillOrderResponse failed(String orderNo) {
        return new SeckillOrderResponse(orderNo, Status.FAILED, "下单失败，库存已返还，可重新抢购");
    }
}
