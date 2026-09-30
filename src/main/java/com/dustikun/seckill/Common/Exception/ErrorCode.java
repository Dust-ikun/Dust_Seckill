package com.dustikun.seckill.Common.Exception;

import lombok.Getter;

@Getter
public enum ErrorCode {
    STOCK_NOT_ENOUGH("1000", "库存不足"),
    REPEAT_ORDER("1001", "请勿重复下单"),
    PARM_ERROR("1002", "参数错误"),
    STOCK_NOT_READY("1003", "活动库存未预热，请先执行预热"),
    STOCK_NOT_FOUND("1004", "秒杀商品不存在"),
    SYSTEM_BUSY("5000", "系统繁忙，请稍后重试"),
    /** 消息未成功投递到 MQ。此时已回补 Redis 预扣，用户可以重试 */
    MQ_SEND_FAILED("5001", "下单请求投递失败，请稍后重试"),
    /**
     * 业务失败之后的「补偿」也失败了。
     * <p>
     * 这个状态与普通业务失败有本质区别：库里的订单没有落成，Redis 预扣也没能归还，
     * 这部分库存既不可售也没有归属，<b>需要人工介入</b>。因此必须用独立错误码把上游（与告警）
     * 从「普通的库存不足 / 重复下单」里区分出来，不能被原始异常盖过去。
     * 同时已经落了一条待补偿记录，定时任务会持续重试。
     */
    COMPENSATE_FAILED("5002", "下单失败且库存回收异常，已记录待处理，请勿重复提交");


    private final String code;
    private final String message;

    ErrorCode(String code, String message) {
        this.code = code;
        this.message = message;
    }
}
