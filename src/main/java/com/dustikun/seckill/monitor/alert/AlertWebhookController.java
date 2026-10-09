package com.dustikun.seckill.monitor.alert;

import tools.jackson.databind.ObjectMapper;
import com.dustikun.seckill.Common.result.Result;
import com.dustikun.seckill.monitor.core.AlertEvent;
import com.dustikun.seckill.monitor.core.AlertType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 告警接收（SPEC 第 15 节的触发链路终点：Alertmanager → AI Monitor API）。
 *
 * <h2>两个端点，两种调用方</h2>
 * <pre>
 *   POST /api/ai/alerts     Alertmanager 的 webhook（v4 载荷，一批告警）
 *   POST /api/ai/diagnosis   SPEC 第 21 节的「创建诊断任务」（手工 / 故障注入脚本）
 * </pre>
 * 手工入口不是「顺手加的」：SPEC 第 19 节的 5 个故障注入 Case 需要能
 * <b>在不制造真实故障的前提下</b>验证诊断链路（先证明「收到告警会诊断」，
 * 再证明「制造故障会产生告警」）。没有它，任何一次链路验证都必须先真的把数据库弄慢。
 *
 * <h2>★ 为什么这里必须自己管 HTTP 状态码，而不能交给 GlobalExceptionHandler</h2>
 * <p>
 * 项目的全局异常处理器把任何异常都转成 <b>HTTP 200</b> + {@code {"code":"500"}}。
 * 对业务接口这是合理的（前端统一按 code 判断），但对 webhook 是<b>致命</b>的：
 * <ul>
 *   <li>Alertmanager 只看 HTTP 状态码。返回 200 意味着「投递成功」，
 *       于是它不会重试，而那条告警<b>就此消失</b> ——
 *       「告警静默丢失」是监控系统能犯的最严重的错误；</li>
 *   <li>反过来，载荷不合法（400）与内部故障（5xx）必须分开：
 *       前者重试一万次也不会好，后者重试是对的。</li>
 * </ul>
 * 因此这两个方法<b>不抛异常</b>：所有失败都在本地转成显式的 HTTP 状态码。
 * 这是本项目唯一一处这样写的地方，理由就写在上面。
 *
 * <h2>请求体大小</h2>
 * <p>webhook 不带鉴权（Alertmanager 只能配自定义头），因此它是内网上一个
 * 任何人都能 POST 的端点。除了 {@code AlertmanagerPayload.MAX_ALERTS} 限制条数之外，
 * 这里再挡一道请求体字节数 —— 否则一个 500 MB 的 body 在读进内存那一步就已经生效了。
 */
@RestController
@RequestMapping("/api/ai")
@ConditionalOnProperty(prefix = "seckill.monitor", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class AlertWebhookController {

    private static final Logger log = LoggerFactory.getLogger(AlertWebhookController.class);

    /** 请求体上限（字节）。实测 Alertmanager 一批告警的载荷在 10 KB 量级，1 MB 极宽松 */
    private static final int MAX_BODY_BYTES = 1024 * 1024;

    private final AlertmanagerPayload payloadParser;

    private final AlertIngestService ingestService;

    private final ObjectMapper objectMapper;

    private final com.dustikun.seckill.monitor.log.MonitorLogProperties logProperties;

    public AlertWebhookController(AlertmanagerPayload payloadParser, AlertIngestService ingestService,
                                  ObjectMapper objectMapper,
                                  com.dustikun.seckill.monitor.log.MonitorLogProperties logProperties) {
        this.payloadParser = payloadParser;
        this.ingestService = ingestService;
        this.objectMapper = objectMapper;
        this.logProperties = logProperties;
    }

    /**
     * 默认服务名。
     * <p>【为什么复用 {@code MonitorLogProperties.serviceName} 而不是新增一个配置项】
     * 因为「本服务叫什么」这个事实已经有一个家：那个值同时被 {@code prometheus.yml}
     * 的 job 标签与日志底座使用，两边必须一致（不一致时 Logs Tool 按 service 过滤
     * 会一条都查不到，而 Metrics Tool 照常返回数据 —— Agent 于是得出
     * 「有指标异常但没有任何相关日志」这种错误结论）。
     * 再开一个 {@code seckill.monitor.service-name} 就等于允许它们不一致，
     * 而那种不一致没有任何症状。
     */
    private String defaultService() {
        return logProperties.normalized().serviceName();
    }

    /**
     * Alertmanager webhook。
     * <p>返回 <b>202 Accepted</b>：告警已经<b>收下并落库</b>，但诊断还在排队 ——
     * 这个状态码恰好就是这件事的语义。用 200 会让人以为「连诊断都做完了」。
     */
    @PostMapping("/alerts")
    public ResponseEntity<Result<Map<String, Object>>> receive(@RequestBody(required = false) String body) {
        if (body == null || body.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Result.fail("400", "请求体为空，不是 Alertmanager 的 webhook 载荷"));
        }
        if (body.length() > MAX_BODY_BYTES) {
            // 413 不会让 Alertmanager 重试（它按 4xx 处理），这是有意的：
            // 重试一个超大载荷只会重复占用内存。
            log.warn("[AlertWebhook] 载荷过大：{} 字符（上限 {}）", body.length(), MAX_BODY_BYTES);
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body(Result.fail("413", "载荷超过 " + MAX_BODY_BYTES + " 字节上限"));
        }

        AlertmanagerPayload.Parsed parsed;
        try {
            parsed = payloadParser.parse(body, defaultService());
        } catch (AlertmanagerPayloadException e) {
            // 400：发送方的载荷有问题，重试无用。必须让 Alertmanager 知道投递失败了
            // （它会在日志里报出来），而不是回 200 把这条告警吞掉。
            log.warn("[AlertWebhook] 载荷解析失败：{}", e.getMessage());
            return ResponseEntity.badRequest().body(Result.fail("400", e.getMessage()));
        }

        try {
            AlertIngestService.IngestResult result = ingestService.ingest(parsed);
            if (parsed.dropped() > 0) {
                log.warn("[AlertWebhook] 本次投递有 {} 条告警因超过上限被丢弃", parsed.dropped());
            }
            log.info("[AlertWebhook] 收到 {} 条告警（firing {} / resolved {}）：{}",
                    result.received(), parsed.firingCount(), parsed.resolvedCount(), result.toMap());
            return ResponseEntity.accepted().body(Result.success("已受理", result.toMap()));
        } catch (RuntimeException e) {
            // 5xx：内部故障，让 Alertmanager 重试。这是「宁可重复也不能丢」的取向 ——
            // 重复的告警会被 Incident 聚合吸收，而丢掉的告警不会自己回来。
            log.error("[AlertWebhook] 处理告警时发生内部错误，已返回 500 让 Alertmanager 重试", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Result.fail("500", "内部错误：" + e.getMessage()));
        }
    }

    /**
     * 手工创建诊断任务（SPEC 第 21 节）。
     *
     * <p>请求体（与 SPEC 原文一致，并允许补上 {@code value} / {@code threshold} /
     * {@code metricName} / {@code description}，因为它们正是 SPEC 第 10 节动态上下文要用的）：
     * <pre>
     * {
     *   "alertId": "ALT-001",
     *   "service": "order-service",
     *   "alertType": "LATENCY_HIGH",
     *   "severity": "HIGH",
     *   "metricName": "seckill:http_p99_latency:5m",
     *   "value": 2.1,
     *   "threshold": 1.0,
     *   "description": "订单创建接口 P99 超过阈值"
     * }
     * </pre>
     *
     * <p>【{@code alertType} 为什么不接受任意字符串】因为它同时是 Incident 聚合的键。
     * 允许自由输入会让「手工造的告警」永远聚合不到真实告警上（键不同），
     * 于是故障注入时会莫名其妙地多出一堆并行任务。因此它必须落在
     * {@link AlertType} 的白名单里 —— 与工具白名单同一个取向：
     * <b>可配置的能力边界不是边界</b>。
     */
    @PostMapping("/diagnosis")
    public ResponseEntity<Result<Map<String, Object>>> create(@RequestBody(required = false) String body) {
        Map<String, Object> request;
        try {
            request = body == null || body.isBlank()
                    ? Map.of()
                    : objectMapper.readValue(body,
                            new tools.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Result.fail("400", "请求体不是合法 JSON：" + e.getMessage()));
        }

        String alertTypeRaw = text(request, "alertType");
        AlertType alertType;
        try {
            alertType = alertTypeRaw == null || alertTypeRaw.isBlank()
                    ? AlertType.UNKNOWN : AlertType.valueOf(alertTypeRaw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Result.fail("400",
                    "alertType 不在白名单里：" + alertTypeRaw + "。可用值：" + List.of(AlertType.values())));
        }

        String service = text(request, "service");
        String alertId = text(request, "alertId");
        AlertEvent event = new AlertEvent(
                alertId == null || alertId.isBlank()
                        ? "MANUAL-" + Instant.now().getEpochSecond() : alertId,
                service == null || service.isBlank() ? defaultService() : service,
                text(request, "metricName"),
                alertType.name(),
                text(request, "severity"),
                number(request.get("value")),
                number(request.get("threshold")),
                Instant.now().getEpochSecond(),
                text(request, "description"));

        try {
            AlertIngestService.Outcome outcome = ingestService.ingestOne(new IncomingAlert(
                    event, IncomingAlert.State.FIRING, event.alertId(), event.timestamp(), null, body));
            Map<String, Object> data = new LinkedHashMap<>(8);
            data.put("outcome", outcome.kind().name());
            data.put("incidentId", outcome.incidentId());
            data.put("alert", event.toMap());
            log.info("[AlertWebhook] 手工创建诊断任务：{} → {}", event.summarize(), outcome.kind());
            return ResponseEntity.accepted().body(Result.success("已受理", data));
        } catch (RuntimeException e) {
            log.error("[AlertWebhook] 手工创建诊断任务失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Result.fail("500", "内部错误：" + e.getMessage()));
        }
    }

    // ================================================================ 入参读取

    private static String text(Map<String, Object> request, String field) {
        Object value = request.get(field);
        return value == null ? null : String.valueOf(value).trim();
    }

    /**
     * 读取数值。
     * <p>JSON 里的数字会反序列化成 {@code Integer} / {@code Double} / {@code Long}，
     * 而字符串形式（有人会写 {@code "2.1"}）也要接受 —— 但<b>解析不出来就返回 null</b>，
     * 绝不返回 0：见 {@code AlertEvent} 的注释，0 与「没取到」会让 Agent 得出相反结论。
     */
    private static Double number(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
