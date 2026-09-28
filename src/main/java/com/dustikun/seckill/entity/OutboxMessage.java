package com.dustikun.seckill.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 待投递消息（阶段 5：本地消息表 / Outbox）。
 * <p>
 * 【它解决什么问题】阶段 4 的请求链路是「Redis 预扣 → 同步投递 MQ → 返回已受理」。
 * 只要请求线程在投递成功之前消失（进程被杀、机器断电、OOM），
 * 系统里就<b>没有任何地方记录过「这笔预扣存在过」</b>：
 * Redis 少了 1 件库存，Broker 里没有消息，补偿逻辑也没有异常可以捕获——
 * 库存永久消失，而且事后无从查起。
 * <p>
 * 【本表如何根治】把「这笔预扣需要落库」这件事在返回之前写进数据库。
 * 于是一旦写入成功，这笔预扣就有了独立于请求线程生命周期的凭据：
 * 请求线程消失不影响投递，后台投递器会扫到它并继续把消息发出去。
 * <p>
 * 【它不是幂等表】订单幂等依旧靠 {@code orders} 上的两个唯一索引。
 * 本表的作用是「保证这笔预扣最终一定会被投递出去」，不做去重语义。
 * <p>
 * 【为什么先写它再返回，而不是异步写】写它就是这个方案的全部价值所在：
 * 它是「消息一定会投出」这个承诺的唯一凭据。异步写等于把窗口原样保留。
 * 代价是请求 RT 里多了一次窄表 INSERT——不与库存行争锁、无条件 UPDATE、纯追加，
 * 比阶段 3 那个「扣库存 + 插订单」的事务轻得多。这笔交易是划算的。
 */
@Data
public class OutboxMessage {

    /** 待投递：尚未成功进入 MQ */
    public static final String STATUS_PENDING = "PENDING";
    /** 已投递：已拿到 Broker 确认，等待消费端落库 */
    public static final String STATUS_SENT = "SENT";
    /** 已放弃：重试次数用尽仍无法投递，Redis 预扣已回补，需人工介入 */
    public static final String STATUS_FAILED = "FAILED";

    private Long id;

    /**
     * 业务单号。
     * <p>
     * 请求线程生成（不是在落库时才生成），因为用户手里的单号必须与库里的对得上；
     * 同时它也是唯一的业务标识，用于「订单还没落库时把 outbox 记录查回来判断状态」。
     */
    private String orderNo;

    private Long userId;
    private Long stockId;
    private Integer num;

    /** 见 STATUS_* 常量 */
    private String status;

    private Integer retryCount;
    private LocalDateTime nextRetryTime;
    private String lastError;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
