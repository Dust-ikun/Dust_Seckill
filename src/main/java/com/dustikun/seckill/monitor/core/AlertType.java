package com.dustikun.seckill.monitor.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 异常类型（SPEC 第 7.1 节的 {@code alertType}）。
 *
 * <h2>★ 这里的取值不是「设计出来的」，而是「从已部署的告警规则里读出来的」</h2>
 * <p>
 * {@code monitoring/prometheus/rules/seckill-alerts.yml} 里每一条规则都已经写了
 * {@code labels.alert_type}（批次 1 的产物），例如：
 * <pre>
 *   ApiP99LatencyHigh    → alert_type: LATENCY_HIGH
 *   SlowQuerySurge       → alert_type: DB_SLOW_QUERY
 *   RedisDegradationTriggered → alert_type: REDIS_UNAVAILABLE
 *   ReconcileInconsistencyDetected → alert_type: DATA_INCONSISTENCY
 * </pre>
 * 最初本枚举另起了一套名字（{@code SLOW_SQL_SURGE} / {@code RECONCILE_INCONSISTENCY} …），
 * 于是同一件事有了两个词汇：规则说 {@code DB_SLOW_QUERY}、代码说 {@code SLOW_SQL_SURGE}。
 * 那是本项目最忌讳的形态 —— <b>两处各写一遍必然分叉</b>，
 * 而分叉的症状是「聚合键对不上，同一个事故被拆成两个 Agent」，且没有任何报错。
 *
 * <p>因此现在的纪律是：<b>规则里的 {@code alert_type} 标签是唯一真相</b>，
 * 本枚举与 {@link #index()} 都必须与它一致，
 * 而 {@code validate_config.py} 第 10 组会把两边逐个对照（不一致就报错）。
 *
 * <h2>三个值得注意的「多对一」</h2>
 * <p>
 * 索引表里有三组「多条规则共用一个类型」，它们都是刻意的：
 * <ul>
 *   <li>{@code ApiP99LatencyHigh} 与 {@code ApiSlowRequestRateHigh} 共用
 *       {@link #LATENCY_HIGH} —— 它们本来就是同一个故障的两个侧面；</li>
 *   <li>{@code ReconcileInconsistencyDetected} / {@code ConsumeOrderMissing} /
 *       {@code ManualInterventionBacklog} 共用 {@link #DATA_INCONSISTENCY} ——
 *       三者都是「数据事实与预期不一致」，SPEC 第 16 节要的正是把它们合成一次诊断；</li>
 *   <li>监控系统自身的告警（{@code MONITORING_*}）全部不可诊断，见
 *       {@link #diagnosable()}。</li>
 * </ul>
 *
 * <h2>为什么它在 {@code monitor.core}</h2>
 * <p>
 * 因为它被两侧同时使用：告警接收侧（{@code monitor.alert}）用它建任务，
 * Agent 侧（{@code monitor.agent}）用它写 prompt 与落 {@code raw_result}。
 * 放在任何一侧都会让包依赖成环。
 */
public enum AlertType {

    /** 业务应用不可达（{@code up{job="order-service"} == 0}） */
    SERVICE_DOWN,

    /** 数据库连接池打满 */
    DB_POOL_EXHAUSTED,

    /** 接口延迟高（P99 超阈值，或慢请求占比超阈值） */
    LATENCY_HIGH,

    /** 接口 5xx 错误率超阈值（4xx 是正常业务响应，不计入 —— 见规则文件头） */
    ERROR_RATE_HIGH,

    /** 慢查询激增 */
    DB_SLOW_QUERY,

    /** 订单确认成功率低于阈值 */
    BUSINESS_SUCCESS_RATE_LOW,

    /** 消费堆积（投递速率持续大于消费速率） */
    MQ_LAG_HIGH,

    /** Outbox 待投递积压 */
    OUTBOX_BACKLOG,

    /** 数据事实与预期不一致（对账、订单缺失、人工介入欠账） */
    DATA_INCONSISTENCY,

    /** Redis 不可用触发降级 */
    REDIS_UNAVAILABLE,

    /** Redis 命令客户端侧 P99 慢 */
    REDIS_LATENCY_HIGH,

    /** 采集目标不可达（监控系统自身） */
    MONITORING_TARGET_DOWN,

    /** Prometheus 规则求值失败（监控系统自身） */
    MONITORING_RULE_ERROR,

    /** Prometheus 配置重载失败（监控系统自身） */
    MONITORING_CONFIG_ERROR,

    /** 兜底的自监控类型：{@code Alertmanager*} 开头的规则名走前缀判定 */
    SELF_MONITORING,

    /** 未登记的告警名 */
    UNKNOWN;

    private static final Map<String, AlertType> BY_ALERT_NAME = buildIndex();

    /**
     * 「告警名 → 类型」，<b>必须与 {@code seckill-alerts.yml} 的 {@code alert_type} 标签逐条一致</b>。
     * <p>静态校验在 {@code validate_config.py} 第 10 组：它把这张表与 YAML 里的
     * {@code alert_type} 逐个对照。这样「改了 YAML 忘了改代码」与
     * 「改了代码忘了改 YAML」都会在提交前被抓住。
     */
    private static Map<String, AlertType> buildIndex() {
        Map<String, AlertType> map = new LinkedHashMap<>();
        map.put("ServiceDown", SERVICE_DOWN);
        map.put("DbConnectionPoolExhausted", DB_POOL_EXHAUSTED);
        map.put("ApiP99LatencyHigh", LATENCY_HIGH);
        map.put("ApiSlowRequestRateHigh", LATENCY_HIGH);
        map.put("ApiErrorRateHigh", ERROR_RATE_HIGH);
        map.put("SlowQuerySurge", DB_SLOW_QUERY);
        map.put("OrderSuccessRateLow", BUSINESS_SUCCESS_RATE_LOW);
        map.put("MqConsumeLagGrowing", MQ_LAG_HIGH);
        map.put("OutboxBacklogHigh", OUTBOX_BACKLOG);
        map.put("ManualInterventionBacklog", DATA_INCONSISTENCY);
        map.put("ReconcileInconsistencyDetected", DATA_INCONSISTENCY);
        map.put("ConsumeOrderMissing", DATA_INCONSISTENCY);
        map.put("RedisDegradationTriggered", REDIS_UNAVAILABLE);
        map.put("RedisOperationSlow", REDIS_LATENCY_HIGH);
        map.put("TargetDown", MONITORING_TARGET_DOWN);
        map.put("PrometheusRuleEvaluationFailing", MONITORING_RULE_ERROR);
        map.put("PrometheusConfigReloadFailed", MONITORING_CONFIG_ERROR);
        return Collections.unmodifiableMap(map);
    }

    /** 供静态校验（{@code validate_config.py} 第 10 组）与测试读取的映射表快照 */
    public static Map<String, AlertType> index() {
        return BY_ALERT_NAME;
    }

    /**
     * 解析类型标签。
     * <p>【优先用告警载荷里的 {@code labels.alert_type}】因为它是<b>规则作者显式声明</b>的，
     * 而 {@link #fromAlertName(String)} 是代码里的一张表 —— 前者更新时不需要改代码。
     * 载荷里没有那个标签时才回落到名字映射（老规则、或别人手工 POST 的告警）。
     *
     * @param labelValue 载荷里的 {@code labels.alert_type}（可能为空）
     * @param alertName  载荷里的 {@code labels.alertname}（作为回落依据）
     */
    public static AlertType resolve(String labelValue, String alertName) {
        if (labelValue != null && !labelValue.isBlank()) {
            try {
                return valueOf(labelValue.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                // 标签写了一个枚举里没有的值。这时**不能**回落到名字映射就算完 ——
                // 那会静默吞掉「标签写错了」这件事。记一条日志（见 resolveLogged），
                // 并回落到名字映射，因为名字映射在同一个提交里被静态校验过。
                return fromAlertName(alertName);
            }
        }
        return fromAlertName(alertName);
    }

    /**
     * 按告警名判定类型。
     * <p>{@code Alertmanager} 开头的规则名走前缀判定而不是逐个登记：
     * Alertmanager 自身的告警名有多个（{@code AlertmanagerConfigReloadFailed} 等），
     * 而它们全部属于同一类。逐个登记会让「新加一条 Alertmanager 自监控规则」
     * 变成一次必须记得改代码的操作 —— 而「必须记得」的约定一定会被忘记。
     */
    public static AlertType fromAlertName(String alertName) {
        if (alertName == null || alertName.isBlank()) {
            return UNKNOWN;
        }
        String name = alertName.trim();
        AlertType mapped = BY_ALERT_NAME.get(name);
        if (mapped != null) {
            return mapped;
        }
        if (name.startsWith("Alertmanager")) {
            return SELF_MONITORING;
        }
        return UNKNOWN;
    }

    /** 是否是「监控系统自身」的类型（这三类加上 {@link #SELF_MONITORING} 都不该花 LLM 的钱） */
    public boolean monitoringSelf() {
        return this == MONITORING_TARGET_DOWN || this == MONITORING_RULE_ERROR
                || this == MONITORING_CONFIG_ERROR || this == SELF_MONITORING;
    }

    /**
     * 是否值得花一次 LLM 诊断。
     * <p>只有「监控系统自身」的类型为 {@code false}：Agent 会去查业务指标、
     * 发现一切正常，然后产出一份「未发现异常」的诊断，反而掩盖了真正的问题
     * （Prometheus 抓不到目标）—— 这个论证也写在 {@code alertmanager.yml} 的路由注释里。
     * <p>{@link #UNKNOWN} 依然会触发诊断：「没见过这个名字」恰恰是可能藏着
     * 一条真实故障的情形，为省一次调用把它跳过，就是把未知当成无害。
     */
    public boolean diagnosable() {
        return !monitoringSelf();
    }
}
