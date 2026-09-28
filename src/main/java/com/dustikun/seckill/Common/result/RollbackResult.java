package com.dustikun.seckill.Common.result;

/**
 * Redis 库存回补的结果。
 * <p>
 * 取代原先「返回 long、用 -1 当万能错误码」的写法。回补路径上存在四种互不相同的结局，
 * 全部用 -1 表示会让调用方无法区分下面两种关键差异：
 * <ul>
 *   <li>「活动已结束，本来就不需要回补」—— 正常情况，不该告警；</li>
 *   <li>「已经回补过了，本次什么都没做」—— 幂等命中，更不该告警。</li>
 * </ul>
 * 后者若被误判成「回补失败」而触发重试，每次重试都会把库存加一遍，导致 <b>库存虚增</b>。
 * 因此这里用独立状态码把每种结局区分开，并把「剩余库存」作为独立字段，不再和状态码挤在同一个返回值里。
 *
 * @param status      回补结果状态
 * @param remainStock 回补后的剩余库存；仅 {@link Status#SUCCESS} 时有值，其余情况为 {@code null}
 */
public record RollbackResult(Status status, Integer remainStock) {

    public enum Status {
        /** 回补成功：库存与用户预扣标记都已还原 */
        SUCCESS,
        /** 库存 key 不存在（活动已结束或缓存已清理），本次未修改任何数据 */
        ACTIVITY_CLOSED,
        /** 用户预扣标记不存在，说明此前已回补过；本次未修改任何数据（幂等保护） */
        ALREADY_ROLLED_BACK,
        /** 入参非法（回补数量 <= 0），拒绝执行 */
        ILLEGAL_ARGUMENT,
        /** Redis 未返回结果（连接异常等），回补是否生效未知，需人工确认 */
        REDIS_ERROR
    }

    /** 本次调用是否真的把库存还回去了。 */
    public boolean rolledBack() {
        return status == Status.SUCCESS;
    }

    /** 是否属于「不需要处理」的正常结局，用于决定要不要打告警日志。 */
    public boolean benign() {
        return status == Status.ACTIVITY_CLOSED || status == Status.ALREADY_ROLLED_BACK;
    }

    public static RollbackResult success(int remainStock) {
        return new RollbackResult(Status.SUCCESS, remainStock);
    }

    public static RollbackResult of(Status status) {
        return new RollbackResult(status, null);
    }
}
