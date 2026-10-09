package com.dustikun.seckill.monitor.alert;

import com.dustikun.seckill.monitor.agent.DiagnosisTrigger;
import com.dustikun.seckill.monitor.core.AiMonitorMetrics;
import com.dustikun.seckill.monitor.core.AlertEvent;
import com.dustikun.seckill.monitor.core.SafeCollections;
import com.dustikun.seckill.monitor.repository.DiagnosisTaskRow;
import com.dustikun.seckill.monitor.repository.DiagnosisTaskStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 告警接收 + Incident 聚合（SPEC 第 15 / 16 节）。
 *
 * <h2>它只做「决定要不要花钱」，不做诊断</h2>
 * <p>
 * 一次告警到达之后的事情被切成两段，切点就是<b>费用</b>：
 * <pre>
 *   AlertIngestService（本类，同步，毫秒级，不花钱）
 *       告警 → 聚合判定 → 建任务 / 并入任务 → trigger.submit(...)
 *                                                     │
 *   DiagnosisService（异步，秒级，花 LLM 的钱）←────────┘
 * </pre>
 * 这样切的两个收益：
 * <ol>
 *   <li>Alertmanager 的投递超时只有 5s，而诊断要几十秒 —— 必须让<b>不花钱的那一段</b>
 *       同步完成并立刻返回 202；</li>
 *   <li>聚合判定（「这次该不该再起一个 Agent」）是可以用纯单测覆盖的逻辑，
 *       而它一旦和 LLM 调用写在同一个方法里，就只能靠真实调用去观察。</li>
 * </ol>
 *
 * <h2>{@code alert_count} 的口径（这里有一个必须说清的取舍）</h2>
 * <p>
 * 每来一条 firing 告警并进一个未结束的任务，{@code alert_count} 就 +1。
 * 因此它数的是<b>告警通知条数</b>，而不是「不同的告警有几条」——
 * Alertmanager 对未恢复的告警每 {@code group_interval}(30s) 会重投一次，
 * 于是同一条告警也会被重复计数。
 *
 * <p>【为什么不做「按 fingerprint 去重」】因为那需要为每个任务保存<b>全部</b>
 * 成员指纹，而 {@code ai_diagnosis_task} 是 SPEC 定的结构（只有首条 {@code alert_id}），
 * 加一张关联表或加一列都超出了「补强」的范围。权衡之后选择如实定义这个字段的含义：
 * 它回答的是「这次事故把 Agent 叫醒了几次」，而那个数字恰好也是费用相关的。
 * 真正需要「有几条不同告警」时，用 {@code alert_id} + 时间窗去 Prometheus 的
 * {@code ALERTS} 序列里查 —— 那才是权威来源。
 */
public class AlertIngestService {

    private static final Logger log = LoggerFactory.getLogger(AlertIngestService.class);

    /** 每次聚合判定的候选深度。窗口内同类型任务不可能有几十个，30 足够且避免全表扫 */
    private static final int CANDIDATE_LIMIT = 30;

    private final DiagnosisTaskStore taskStore;

    private final IncidentAggregator aggregator;

    private final DiagnosisTrigger trigger;

    private final AiMonitorMetrics metrics;

    private final Clock clock;

    public AlertIngestService(DiagnosisTaskStore taskStore, IncidentAggregator aggregator,
                              DiagnosisTrigger trigger, AiMonitorMetrics metrics) {
        this(taskStore, aggregator, trigger, metrics, Clock.systemDefaultZone());
    }

    /** 供测试注入固定时钟（窗口边界靠它才可复现） */
    public AlertIngestService(DiagnosisTaskStore taskStore, IncidentAggregator aggregator,
                              DiagnosisTrigger trigger, AiMonitorMetrics metrics, Clock clock) {
        this.taskStore = taskStore;
        this.aggregator = aggregator;
        this.trigger = trigger;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** 处理一次 webhook 投递 */
    public IngestResult ingest(AlertmanagerPayload.Parsed payload) {
        List<String> incidents = new ArrayList<>(payload.alerts().size());
        int created = 0;
        int merged = 0;
        int resolved = 0;
        int failed = 0;

        for (IncomingAlert incoming : payload.alerts()) {
            try {
                Outcome outcome = ingestOne(incoming);
                incidents.add(outcome.incidentId());
                switch (outcome.kind()) {
                    case CREATED -> created++;
                    case MERGED -> merged++;
                    case RESOLVED -> resolved++;
                    default -> failed++;
                }
            } catch (RuntimeException e) {
                // 【为什么一条失败不能拖垮整批】Alertmanager 是成批投递的
                // （group_by 之后一批里可能有多条）。让第一条的异常中断整个循环，
                // 会让后面的告警一起消失 —— 而它们可能描述的是另一个故障。
                failed++;
                log.error("[AlertIngest] 处理告警失败，已跳过并继续：{}", incoming.event().summarize(), e);
            }
        }
        return new IngestResult(payload.alerts().size(), created, merged, resolved, failed, incidents);
    }

    /** 处理一条告警（firing 或 resolved） */
    public Outcome ingestOne(IncomingAlert incoming) {
        Instant now = clock.instant();
        AlertEvent event = incoming.event();

        if (incoming.resolved()) {
            return handleResolved(event, incoming, now);
        }
        return handleFiring(event, now);
    }

    /**
     * 告警恢复：写 {@code end_time}，<b>不</b>触发诊断。
     * <p>【为什么恢复通知不诊断】「故障好了」不需要根因分析，它需要的是把事故关掉
     * （否则 Dashboard 上的「当前告警数」永远下不去，见 {@code alertmanager.yml} 里
     * {@code send_resolved} 的注释）。而诊断这件事本身与告警是否恢复无关 ——
     * 已结束的告警，其诊断仍然有历史价值。
     *
     * <p>【找不到对应任务时只记日志】这很正常：应用重启过、或者那次告警
     * 因为 AI Monitor 不可达而没收到。为它建一个任务反而会制造一条
     * 「类型对、但没有诊断」的噪声记录。
     */
    private Outcome handleResolved(AlertEvent event, IncomingAlert incoming, Instant now) {
        List<DiagnosisTaskRow> candidates = taskStore.findCandidates(
                event.serviceName(), event.alertType(), CANDIDATE_LIMIT);
        Optional<DiagnosisTaskRow> open = aggregator.newestUnresolved(candidates);
        if (open.isEmpty()) {
            log.info("[AlertIngest] 收到恢复通知但没有可关闭的任务：{}", event.summarize());
            return Outcome.resolved(null);
        }
        Instant endTime = incoming.endsAt() == null ? now : Instant.ofEpochSecond(incoming.endsAt());
        boolean updated = taskStore.markResolved(open.get().incidentId(), endTime, now);
        log.info("[AlertIngest] 事故 {} 标记为已恢复（end_time={}，本次{}）",
                open.get().incidentId(), endTime,
                updated ? "写入成功" : "已是恢复态，未改动");
        return Outcome.resolved(open.get().incidentId());
    }

    /** 告警触发：聚合判定 → 并入或新建 → 触发诊断 */
    private Outcome handleFiring(AlertEvent event, Instant now) {
        List<DiagnosisTaskRow> candidates = taskStore.findCandidates(
                event.serviceName(), event.alertType(), CANDIDATE_LIMIT);
        IncidentAggregator.Decision decision = aggregator.decide(event, candidates, now);

        if (decision.merge()) {
            DiagnosisTaskRow target = decision.target();
            taskStore.mergeInto(target.id(), now);
            metrics.diagnosis("MERGED");
            log.info("[AlertIngest] {} → {}（聚合窗口 {}s）",
                    event.summarize(), decision.describe(), aggregator.window().toSeconds());
            return Outcome.merged(target.incidentId());
        }

        DiagnosisTaskRow created = taskStore.create(event, now);
        log.info("[AlertIngest] 新建诊断任务 {}：{}（原因：{}）",
                created.incidentId(), event.summarize(), decision.reason());
        trigger.submit(created.id(), created.incidentId(), event);
        return Outcome.created(created.incidentId());
    }

    /** 一条告警的处理结局 */
    public enum Kind {
        CREATED,
        MERGED,
        RESOLVED,
        FAILED
    }

    /**
     * @param kind       结局
     * @param incidentId 关联的事故编号；恢复通知找不到任务时为 {@code null}
     */
    public record Outcome(Kind kind, String incidentId) {

        static Outcome created(String incidentId) {
            return new Outcome(Kind.CREATED, incidentId);
        }

        static Outcome merged(String incidentId) {
            return new Outcome(Kind.MERGED, incidentId);
        }

        static Outcome resolved(String incidentId) {
            return new Outcome(Kind.RESOLVED, incidentId);
        }
    }

    /**
     * 一批告警的处理汇总。它是 webhook 的响应体 ——
     * 让「Alertmanager 说它投递了，AI Monitor 说它收了几条」可以直接对照，
     * 而不是靠去翻两边的日志。
     *
     * <p>【{@code incidentIds} 里**可能有 null**】恢复通知找不到对应任务时
     * （应用重启过、或那次告警我们没收到），那一条就是 {@code null}。
     * 保留 null 而不是过滤掉，是为了让它与 {@code requested} 的<b>下标一一对应</b>：
     * 「第 2 条告警进了哪个事故」可以直接读，而过滤会让下标错位。
     *
     * <p>★ 【不能用 {@code List.copyOf}】它拒绝 null 元素，会把这条完全正常的路径
     * 变成一个 NPE → webhook 返回 500 → Alertmanager 无限重试。这个缺陷在
     * 一次真实运行里被踩到过（见 {@code SafeCollections} 的类注释）。
     */
    public record IngestResult(int received, int created, int merged, int resolved,
                               int failed, List<String> incidentIds) {

        public IngestResult {
            incidentIds = SafeCollections.list(incidentIds);
        }

        public java.util.Map<String, Object> toMap() {
            java.util.Map<String, Object> map = new java.util.LinkedHashMap<>(10);
            map.put("received", received);
            map.put("created", created);
            map.put("merged", merged);
            map.put("resolved", resolved);
            map.put("failed", failed);
            map.put("incidentIds", incidentIds);
            return map;
        }
    }
}
