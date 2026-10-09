package com.dustikun.seckill.monitor.alert;

/**
 * Alertmanager webhook 载荷无法解析时抛出。
 *
 * <p>它与 {@code LlmException} 的地位不同：这个异常会变成 <b>HTTP 400</b>，
 * 因为载荷不合法意味着<b>发送方</b>有问题（Alertmanager 配置写错、
 * 或者有人手工 POST 了一个坏请求体）。返回 2xx 会让 Alertmanager 认为投递成功，
 * 于是那次告警<b>彻底消失</b> —— 而「告警消失了」比「告警投递失败（可重试）」
 * 危险得多。
 */
public class AlertmanagerPayloadException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AlertmanagerPayloadException(String message) {
        super(message);
    }

    public AlertmanagerPayloadException(String message, Throwable cause) {
        super(message, cause);
    }
}
