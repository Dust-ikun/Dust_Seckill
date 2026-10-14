package com.dustikun.seckill.Common.handler;

import com.dustikun.seckill.Common.Exception.BizException;
import com.dustikun.seckill.Common.Exception.ErrorCode;
import com.dustikun.seckill.Common.result.Result;
import com.dustikun.seckill.monitor.core.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
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
 * <p><b>清理责任（按 key 划分 owner）</b>：errorCode 是「本次请求的这一次失败」的属性，
 * 由本类在 finally 里当场清掉（见 {@code handleBizException} 的内联注释）；
 * traceId / requestId 等对整个请求有效的键，清理由 {@code RequestTraceFilter}
 * 的 try-with-resources（{@code TraceContext.Scope.close()}）统一做。
 * 每个键只允许有一个 owner，否则「谁该清哪个键」迟早会不一致，
 * 而遗漏的后果是 traceId 跨请求泄漏（把排障引向错误的调用链）。
 *
 * <p><b>HTTP 状态码的两层语义（批次修订）</b>：业务失败返回 4xx（参数错误 400 /
 * 资源不存在 404 / 业务冲突 409），系统级失败返回 5xx —— 只有让状态码说实话，
 * {@code seckill:http_error_rate}（分子为 {@code status=~"5.."}）与依赖它的告警、
 * 健康分才能看见真实故障。body 仍保持统一的 {@link Result} 契约：
 * 状态码给机器（监控 / 重试 / 网关），code 与 message 给人（前端 / 日志）。
 * 映射规则见 {@link #statusForBizCode(String)}。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<?>> handleBizException(BizException e) {
        // 先写 MDC 再打日志：反过来时这一行日志里不会有 errorCode，
        // 而它恰恰是最需要带上错误码的那一行。
        TraceContext.put(TraceContext.ERROR_CODE, e.getCode());
        try {
            log.warn("业务异常：code={}, message={}", e.getCode(), e.getMessage());
            return ResponseEntity.status(statusForBizCode(e.getCode()))
                    .body(Result.fail(e.getCode(), e.getMessage()));
        } finally {
            // 【为什么在这里就清掉】这个错误码属于「本次请求的这一次失败」。
            // 留在 MDC 里会让同一请求内<b>之后</b>的正常日志也标上 errorCode=1000，
            // 于是「哪些日志真的出错了」这个判断彻底失效 ——
            // Logs Tool 按 errorCode 检索会捞出一堆正常日志。
            // traceId / requestId 不清：它们对整个请求都有效，由过滤器统一清理。
            TraceContext.put(TraceContext.ERROR_CODE, null);
        }
    }

    /**
     * 业务错误码 → HTTP 状态码。
     *
     * <p><b>为什么错误码不能一律回 200</b>：{@code seckill:http_error_rate} 的分子是
     * {@code status=~"5.."}（见 {@code monitoring/prometheus/rules/seckill-recording-rules.yml}）。
     * 若系统故障也回 200，错误率对真实故障恒为 0 —— 告警与健康分都会失明。
     * 因此这里让状态码说实话：能重试的失败归 5xx，请求本身有问题的归 4xx，
     * 请求合法但与当前状态冲突（库存不足、重复下单）的归 409。
     *
     * @param code {@link ErrorCode} 的码；未识别的码按「业务冲突」（409）处理 ——
     *             未知不是服务端故障的证据，把它算进 5xx 会让错误率被未知因素污染
     */
    private static HttpStatus statusForBizCode(String code) {
        if (ErrorCode.PARM_ERROR.getCode().equals(code)) {
            return HttpStatus.BAD_REQUEST;              // 400：请求参数问题，重试无意义
        }
        if (ErrorCode.STOCK_NOT_FOUND.getCode().equals(code)) {
            return HttpStatus.NOT_FOUND;                // 404：目标资源不存在
        }
        if (ErrorCode.SYSTEM_BUSY.getCode().equals(code)
                || ErrorCode.MQ_SEND_FAILED.getCode().equals(code)
                || ErrorCode.COMPENSATE_FAILED.getCode().equals(code)) {
            return HttpStatus.INTERNAL_SERVER_ERROR;    // 500：服务端故障，必须计入错误率
        }
        return HttpStatus.CONFLICT;                     // 409：合法请求与当前状态冲突（库存不足 / 重复下单 / 未预热）
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<?> handleException(Exception e) {
        // 用 SYSTEM_BUSY 的码（5000）而不是新造一个：这个 handler 处理的正是
        // 「没被识别为业务异常的失败」，而对外返回的也是同一个 5000。
        // 两处口径一致，排查时不会出现「日志说 5000、响应里也是 5000、
        // 但其实是两套含义」这种情况。
        // @ResponseStatus 让 HTTP 状态码也是真实的 500：错误率指标的分子
        // （status=~"5.."）与「服务端错误」的告警必须能看见这条故障。
        TraceContext.put(TraceContext.ERROR_CODE, ErrorCode.SYSTEM_BUSY.getCode());
        try {
            log.error("系统异常", e);
            return Result.fail(ErrorCode.SYSTEM_BUSY);
        } finally {
            TraceContext.put(TraceContext.ERROR_CODE, null);
        }
    }
}
