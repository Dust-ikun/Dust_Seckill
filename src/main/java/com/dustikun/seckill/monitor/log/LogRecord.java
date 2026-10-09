package com.dustikun.seckill.monitor.log;

/**
 * 环形缓冲里的一条日志。
 *
 * <p><b>为什么不是直接存格式化后的字符串</b>
 * <p>
 * SPEC 第 9.2 节的 {@code search_logs} 需要按 service / keyword / 时间窗 过滤，
 * 还要把结果按「不同的错误有哪几种」聚合。这些操作都需要<b>字段</b>，
 * 而一行拼好的文本里字段已经糊在一起了 —— 想按 traceId 过滤就得回头做正则解析，
 * 那既慢又容易在中文与转义字符上出错。
 * 因此在<b>写入时</b>就把结构化字段抽出来，查询时只做比较。
 *
 * <p><b>与 SPEC 第 6.2 节的字段对照</b>
 * <pre>
 *   SPEC 要求         本 record 字段        来源
 *   ----------------  -------------------  ----------------------------------
 *   traceId           traceId              MDC（由 RequestTraceFilter / MdcScope 写入）
 *   requestId         requestId            MDC（HTTP 请求每次一个）
 *   serviceName       serviceName          MDC，缺省取 seckill.monitor.service-name
 *   operation         operation            MDC（建单 / 投递 / 消费确认 …）
 *   userId（脱敏）     userId/stockId/…     MDC；<b>脱敏在输出层做，不在存储层</b>
 *   errorCode         errorCode            MDC（GlobalExceptionHandler / BizException）
 *   exception         throwable            Logback 的 IThrowableProxy 渲染结果
 *   timestamp         timestampMillis      日志事件的诞生时刻（不是被读出的时刻）
 * </pre>
 *
 * <p><b>为什么脱敏不在这里做</b>
 * <p>
 * 排障时人需要看原始值（例如按 userId 追一位用户的下单链路），而送给 LLM 时必须脱敏。
 * 把两件事放在同一个地方，就只能二选一。因此：
 * 本类<b>忠实保存</b>，脱敏发生在 {@link LogRingBuffer} 的产出层（{@code LogSink} 的查询结果），
 * 由 {@code seckill.monitor.mask.*} 的配置决定脱到什么程度。
 *
 * @param timestampMillis 事件时间（epoch 毫秒）
 * @param level           日志级别名（DEBUG / INFO / WARN / ERROR）
 * @param loggerName      产生日志的 Logger 名（即类名，用于定位代码位置）
 * @param threadName      线程名。消费线程与请求线程的日志交错时，这是唯一能区分它们的字段
 * @param message         已格式化的消息体。内含的占位符已由 Logback 替换完毕
 * @param throwable       异常渲染文本（含因果链），无异常时为 {@code null}
 * @param traceId         一次业务调用链的标识，可能为 {@code null}（应用启动早期的日志）
 * @param requestId       一次 HTTP 请求的标识，非 HTTP 路径为 {@code null}
 * @param serviceName     链路角色名，与 Prometheus 的 {@code service} 标签同源
 * @param operation       业务动作名（建单 / 投递 / 消费确认 / 对账 …）
 * @param errorCode       错误码（ErrorCode 枚举的 code，如 1000 / 5001）
 * @param userId          用户标识（<b>未脱敏</b>，见上文说明）
 * @param orderNo         业务单号（<b>未脱敏</b>）
 * @param stockId         商品标识
 */
public record LogRecord(
        long timestampMillis,
        String level,
        String loggerName,
        String threadName,
        String message,
        String throwable,
        String traceId,
        String requestId,
        String serviceName,
        String operation,
        String errorCode,
        String userId,
        String orderNo,
        String stockId
) {

    /** 消息被截断时追加的标记。查询结果里靠它区分「原文就这样」与「被我们截了」 */
    public static final String TRUNCATED_MARK = "…[truncated]";

    /**
     * 把各字段拼成一行可读文本（供 dedupe 归一化与脱敏使用）。
     * <p>顺序刻意与 {@code logback-spring.xml} 的 pattern 保持一致 ——
     * 人从文件里读到的与 Logs Tool 返回的是同一种排列，对照时不必二次换算。
     */
    public String renderForText() {
        StringBuilder sb = new StringBuilder(160);
        if (traceId != null) {
            sb.append("traceId=").append(traceId).append(' ');
        }
        if (operation != null) {
            sb.append("operation=").append(operation).append(' ');
        }
        if (errorCode != null) {
            sb.append("errorCode=").append(errorCode).append(' ');
        }
        sb.append(message);
        if (throwable != null) {
            sb.append(System.lineSeparator()).append(throwable);
        }
        return sb.toString();
    }

    /**
     * 是否与给定关键字匹配（大小写不敏感，命中任一字段即可）。
     *
     * <p><b>为什么把 throwable 也纳入匹配范围</b>：一次数据库故障的特征往往只在堆栈里
     * （{@code CommunicationsException} / {@code SocketTimeoutException}），
     * 消息体本身可能只是「本轮执行异常」这种无信息量的句子。
     * 不匹配堆栈的话，Logs Tool 对这类故障会返回「0 条」，而系统其实正在刷屏。
     *
     * <p>{@code loggerName} 是<b>全小写</b>的（Java 包名约定），因此只对关键字做一次小写化。
     *
     * @param lowerKeyword 已小写化的关键字，调用方保证非空
     */
    public boolean matchesKeyword(String lowerKeyword) {
        return contains(message, lowerKeyword)
                || contains(throwable, lowerKeyword)
                || contains(traceId, lowerKeyword)
                || contains(requestId, lowerKeyword)
                || contains(operation, lowerKeyword)
                || contains(errorCode, lowerKeyword)
                || contains(orderNo, lowerKeyword)
                // 类名走同一个 contains()：Java 的类名习惯是驼峰
                // （{@code SeckillOrderConsumer} 里那个大写 O），所以它<b>不是</b>全小写，
                // 必须和别的字段一样做大小写不敏感匹配。
                // 这里一度写成 loggerName.contains(lowerKeyword) 直接比较 ——
                // 于是按类名检索永远返回 0 条，而「查不到」会被读成「没有相关日志」。
                || contains(loggerName, lowerKeyword);
    }

    /**
     * 只在必要时做小写化：字段值多数已经是小写或纯 ASCII，
     * 而无条件 {@code toLowerCase} 会为每条日志每个字段各分配一个新字符串 ——
     * 单次查询扫描 10000 条就是 7 万次分配，这是纯粹的浪费。
     */
    private static boolean contains(String haystack, String lowerKeyword) {
        if (haystack == null) {
            return false;
        }
        if (haystack.contains(lowerKeyword)) {
            return true;
        }
        return haystack.toLowerCase(java.util.Locale.ROOT).contains(lowerKeyword);
    }
}
