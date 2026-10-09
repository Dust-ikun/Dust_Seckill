package com.dustikun.seckill.Common.handler;

import com.dustikun.seckill.Common.Exception.BizException;
import com.dustikun.seckill.Common.result.Result;
import com.dustikun.seckill.monitor.core.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常出口。除返回统一结构之外，它还负责把<b>错误码写进日志的 MDC</b>。
 *
 * <p><b>为什么错误码必须进 MDC 而不能只出现在消息里</b>
 * <p>
 * SPEC 第 6.2 节要求日志带 {@code errorCode}，Logs Tool 也要能按它检索
 * （典型的取证动作是「这次故障里有 5001 吗」）。若错误码只作为消息文本的一部分
 * （{@code "业务异常：code=5001, message=..."}），检索就必须对消息做正则 ——
 * 既能被日志内容里的其它数字误命中，也没法区分「错误码 5001」
 * 与「消息里恰好提到 5001」。
 * 写进 MDC 之后，它成为结构化的独立字段（见 {@code LogRecord.errorCode}）。
 *
 * <p><b>清理责任</b>：这里只负责写。清理由 {@code RequestTraceFilter} 的 finally 统一做 ——
 * MDC 的清理只能有一个 owner，否则「谁该清哪个键」迟早会不一致，
 * 而遗漏的后果是 traceId 跨请求泄漏（把排障引向错误的调用链）。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public Result<?> handleBizException(BizException e) {
        // 先写 MDC 再打日志：反过来时这一行日志里不会有 errorCode，
        // 而它恰恰是最需要带上错误码的那一行。
        TraceContext.put(TraceContext.ERROR_CODE, e.getCode());
        try {
            log.warn("业务异常：code={}, message={}", e.getCode(), e.getMessage());
            return Result.fail(e.getCode(), e.getMessage());
        } finally {
            // 【为什么在这里就清掉】这个错误码属于「本次请求的这一次失败」。
            // 留在 MDC 里会让同一请求内<b>之后</b>的正常日志也标上 errorCode=1000，
            // 于是「哪些日志真的出错了」这个判断彻底失效 ——
            // Logs Tool 按 errorCode 检索会捞出一堆正常日志。
            // traceId / requestId 不清：它们对整个请求都有效，由过滤器统一清理。
            TraceContext.put(TraceContext.ERROR_CODE, null);
        }
    }

    @ExceptionHandler(Exception.class)
    public Result<?> handleException(Exception e) {
        // 用 SYSTEM_BUSY 的码（5000）而不是新造一个：这个 handler 处理的正是
        // 「没被识别为业务异常的失败」，而对外返回的也是同一个 5000。
        // 两处口径一致，排查时不会出现「日志说 5000、响应里也是 5000、
        // 但其实是两套含义」这种情况。
        TraceContext.put(TraceContext.ERROR_CODE, "5000");
        try {
            log.error("系统异常", e);
            return Result.fail("500", "系统繁忙， 请稍后重试");
        } finally {
            TraceContext.put(TraceContext.ERROR_CODE, null);
        }
    }
}
