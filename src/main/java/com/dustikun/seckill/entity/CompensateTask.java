package com.dustikun.seckill.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 待补偿任务（阶段 4 评审修复新增）。
 * <p>
 * 【解决什么问题】原实现里「回补失败」只打了一行 ERROR 日志就算完事。日志不是状态——
 * 没有人盯着日志看的时候，这部分库存就永久消失了（少卖），而且事后无从追溯到底丢了哪些。
 * 把待补偿动作落成一张表，它就有了状态：可被定时任务反复重试，可被查询，可被人工处理。
 * <p>
 * 【为什么不能只靠重试调用方】调用方（请求线程 / MQ 消费线程）本身是短命的：
 * Redis 挂了它没法等，重试几次之后就返回了。真正能兜住的是「独立于调用生命周期的持久化任务」。
 */
@Data
public class CompensateTask {

    /** 待处理：等待（或正在）重试 */
    public static final String STATUS_PENDING = "PENDING";
    /** 已处理：补偿成功，或幂等命中/活动结束等无需再处理的情形 */
    public static final String STATUS_DONE = "DONE";
    /** 已放弃：重试次数用尽仍失败，需要人工介入 */
    public static final String STATUS_FAILED = "FAILED";

    private Long id;
    private Long stockId;
    private Long userId;

    /**
     * 业务单号。
     * <p>
     * {@code RESTORE_STOCK_ONLY} 类型必须存它：该类型的幂等靠「活动 + 单号」构造的一次性去重键，
     * 重试时若换了标识就会绕过幂等保护，把库存补第二次（→ 超卖）。
     */
    private String orderNo;

    /** 需要归还的数量 */
    private Integer num;

    /** 见 {@link com.dustikun.seckill.Common.constant.CompensateType} */
    private String type;

    private String reason;
    private String status;
    private Integer retryCount;
    private LocalDateTime nextRetryTime;
    private String lastError;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
