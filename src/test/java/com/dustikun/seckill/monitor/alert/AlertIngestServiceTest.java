package com.dustikun.seckill.monitor.alert;

import tools.jackson.databind.ObjectMapper;
import com.dustikun.seckill.monitor.agent.DiagnosisTrigger;
import com.dustikun.seckill.monitor.core.AiMonitorMetrics;
import com.dustikun.seckill.monitor.core.AlertEvent;
import com.dustikun.seckill.monitor.core.AlertType;
import com.dustikun.seckill.monitor.core.DiagnosisStatus;
import com.dustikun.seckill.monitor.repository.DiagnosisTaskRow;
import com.dustikun.seckill.monitor.repository.InMemoryDiagnosisTaskStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 告警接收 + Incident 聚合（SPEC 第 15 / 16 节）。
 *
 * <h2>本类要回答的核心问题只有一个</h2>
 * <p>
 * <b>「P99 高 + 错误率高 + 超时高」这三条告警，会启动几个 Agent？</b>
 * SPEC 第 16 节的答案是 1 个，而这直接决定费用。因此这里的断言全部围绕
 * {@code trigger} 被调用了几次 —— 它是「花了多少次 LLM 钱」的唯一代理指标。
 */
class AlertIngestServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Instant NOW = Instant.parse("2026-10-09T12:00:00Z");

    /** 记录 trigger 调用次数的假实现（它就是「花了几次钱」的计数） */
    private static final class RecordingTrigger implements DiagnosisTrigger {

        private final List<String> submitted = new ArrayList<>();

        @Override
        public void submit(long taskId, String incidentId, AlertEvent alert) {
            submitted.add(incidentId);
        }
    }

    /** 可以手动推进的时钟（验证窗口边界不需要真的等 2 分钟） */
    private static final class MovableClock extends Clock {

        private Instant now;

        MovableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static IncomingAlert firing(String fingerprint, AlertType type, String service,
                                        long startsAtEpochSecond) {
        AlertEvent event = new AlertEvent(fingerprint, service, "seckill:http_p99_latency:5m",
                type.name(), "HIGH", 2.1, 1.0, startsAtEpochSecond, "P99 超过阈值");
        return new IncomingAlert(event, IncomingAlert.State.FIRING, fingerprint,
                startsAtEpochSecond, null, "{}");
    }

    private static IncomingAlert resolved(String fingerprint, AlertType type, long endsAt) {
        AlertEvent event = new AlertEvent(fingerprint, "order-service", "metric",
                type.name(), "HIGH", null, null, NOW.minusSeconds(300).getEpochSecond(), "描述");
        return new IncomingAlert(event, IncomingAlert.State.RESOLVED, fingerprint,
                NOW.minusSeconds(300).getEpochSecond(), endsAt, "{}");
    }

    private static AlertIngestService service(InMemoryDiagnosisTaskStore store,
                                              RecordingTrigger trigger,
                                              IncidentAggregator aggregator,
                                              MovableClock clock) {
        return new AlertIngestService(store, aggregator, trigger,
                new AiMonitorMetrics(new SimpleMeterRegistry()), clock);
    }

    // ================================================================ 核心判据

    @Test
    @DisplayName("★ 3 条同类告警 → 1 个诊断任务、只触发 1 次诊断（SPEC 第 16 节的验收判据）")
    void threeAlertsBecomeOneTask() {
        InMemoryDiagnosisTaskStore store = new InMemoryDiagnosisTaskStore();
        RecordingTrigger trigger = new RecordingTrigger();
        MovableClock clock = new MovableClock(NOW);
        AlertIngestService service = service(store, trigger, new IncidentAggregator(Duration.ofSeconds(120)), clock);

        AlertIngestService.Outcome first = service.ingestOne(
                firing("fp-1", AlertType.LATENCY_HIGH, "order-service", NOW.getEpochSecond()));
        clock.advance(Duration.ofSeconds(20));
        AlertIngestService.Outcome second = service.ingestOne(
                firing("fp-2", AlertType.LATENCY_HIGH, "order-service", NOW.getEpochSecond()));
        clock.advance(Duration.ofSeconds(20));
        AlertIngestService.Outcome third = service.ingestOne(
                firing("fp-3", AlertType.LATENCY_HIGH, "order-service", NOW.getEpochSecond()));

        assertEquals(AlertIngestService.Kind.CREATED, first.kind());
        assertEquals(AlertIngestService.Kind.MERGED, second.kind());
        assertEquals(AlertIngestService.Kind.MERGED, third.kind());

        assertEquals(1, store.size(), "3 条同类告警必须只建 1 个任务");
        assertEquals(List.of(first.incidentId()), trigger.submitted,
                "只该触发一次诊断 —— 每一次触发都是一笔 LLM 费用");
        assertEquals(first.incidentId(), second.incidentId());
        assertEquals(first.incidentId(), third.incidentId());
        assertEquals(3, store.all().get(0).alertCount(), "alert_count 要数到 3 条通知");
    }

    @Test
    @DisplayName("不同异常类型 → 各自建任务（跨类型合并需要判断「是不是同一件事」，那是 Agent 的推理）")
    void differentAlertTypesCreateSeparateTasks() {
        InMemoryDiagnosisTaskStore store = new InMemoryDiagnosisTaskStore();
        RecordingTrigger trigger = new RecordingTrigger();
        MovableClock clock = new MovableClock(NOW);
        AlertIngestService service = service(store, trigger, new IncidentAggregator(Duration.ofSeconds(120)), clock);

        service.ingestOne(firing("fp-1", AlertType.LATENCY_HIGH, "order-service", NOW.getEpochSecond()));
        service.ingestOne(firing("fp-2", AlertType.ERROR_RATE_HIGH, "order-service", NOW.getEpochSecond()));

        assertEquals(2, store.size());
        assertEquals(2, trigger.submitted.size());
    }

    @Test
    @DisplayName("★ 超出聚合窗口 → 建新任务（同一次故障被拆开是要花钱的，因此这条判据要看窗口值）")
    void outsideWindowCreatesNewTask() {
        InMemoryDiagnosisTaskStore store = new InMemoryDiagnosisTaskStore();
        RecordingTrigger trigger = new RecordingTrigger();
        MovableClock clock = new MovableClock(NOW);
        AlertIngestService service = service(store, trigger, new IncidentAggregator(Duration.ofSeconds(120)), clock);

        service.ingestOne(firing("fp-1", AlertType.LATENCY_HIGH, "order-service", NOW.getEpochSecond()));
        clock.advance(Duration.ofSeconds(121));
        service.ingestOne(firing("fp-2", AlertType.LATENCY_HIGH, "order-service", NOW.getEpochSecond()));

        assertEquals(2, store.size());
        assertEquals(2, trigger.submitted.size());
    }

    // ================================================================ 恢复通知

    @Test
    @DisplayName("告警恢复 → 只写 end_time，<b>不</b>触发诊断（「故障好了」不需要根因分析）")
    void resolvedNotificationClosesTaskWithoutDiagnosis() {
        InMemoryDiagnosisTaskStore store = new InMemoryDiagnosisTaskStore();
        RecordingTrigger trigger = new RecordingTrigger();
        MovableClock clock = new MovableClock(NOW);
        AlertIngestService service = service(store, trigger, new IncidentAggregator(Duration.ofSeconds(120)), clock);

        AlertIngestService.Outcome created = service.ingestOne(
                firing("fp-1", AlertType.LATENCY_HIGH, "order-service", NOW.getEpochSecond()));
        int triggersAfterFiring = trigger.submitted.size();

        AlertIngestService.Outcome outcome = service.ingestOne(
                resolved("fp-1", AlertType.LATENCY_HIGH, NOW.getEpochSecond() + 60));

        assertEquals(AlertIngestService.Kind.RESOLVED, outcome.kind());
        assertEquals(created.incidentId(), outcome.incidentId());
        assertEquals(triggersAfterFiring, trigger.submitted.size(), "恢复通知不该再起一次诊断");
        DiagnosisTaskRow row = store.findByIncidentId(created.incidentId()).orElseThrow();
        assertNotNull(row.endTime());
        assertEquals(Instant.ofEpochSecond(NOW.getEpochSecond() + 60), row.endTime());
        // 状态不受影响：告警恢复与诊断结束是两个正交的事实
        assertFalse(row.status().terminal());
    }

    @Test
    @DisplayName("幂等：重复的 resolved 通知不会把 end_time 往后推（否则「故障持续了多久」会随时间漂移）")
    void repeatedResolvedIsIdempotent() {
        InMemoryDiagnosisTaskStore store = new InMemoryDiagnosisTaskStore();
        MovableClock clock = new MovableClock(NOW);
        AlertIngestService service = service(store, new RecordingTrigger(),
                new IncidentAggregator(Duration.ofSeconds(120)), clock);

        AlertIngestService.Outcome created = service.ingestOne(
                firing("fp-1", AlertType.LATENCY_HIGH, "order-service", NOW.getEpochSecond()));
        service.ingestOne(resolved("fp-1", AlertType.LATENCY_HIGH, NOW.getEpochSecond() + 60));
        Instant firstEnd = store.findByIncidentId(created.incidentId()).orElseThrow().endTime();

        clock.advance(Duration.ofMinutes(5));
        service.ingestOne(resolved("fp-1", AlertType.LATENCY_HIGH, NOW.getEpochSecond() + 999));
        Instant secondEnd = store.findByIncidentId(created.incidentId()).orElseThrow().endTime();

        assertEquals(firstEnd, secondEnd, "第二次 resolved 不该改写 end_time");
    }

    @Test
    @DisplayName("收到恢复通知但没有对应任务 → 只记日志，不建任务、不报错")
    void resolvedWithoutTaskIsHarmless() {
        InMemoryDiagnosisTaskStore store = new InMemoryDiagnosisTaskStore();
        MovableClock clock = new MovableClock(NOW);
        AlertIngestService service = service(store, new RecordingTrigger(),
                new IncidentAggregator(Duration.ofSeconds(120)), clock);

        AlertIngestService.Outcome outcome = service.ingestOne(
                resolved("fp-unknown", AlertType.LATENCY_HIGH, NOW.getEpochSecond()));

        assertEquals(AlertIngestService.Kind.RESOLVED, outcome.kind());
        assertNull(outcome.incidentId());
        assertEquals(0, store.size(), "为一条孤立的恢复通知建任务会制造噪声记录");
    }

    @Test
    @DisplayName("★ 批量里的「孤立恢复通知」不能把整批搞崩：incidentIds 里的 null 必须被容忍")
    void resolvedWithoutTaskInBatchDoesNotThrow() {
        // 【这一条是真实运行踩出来的】初版 IngestResult 用 List.copyOf 包装 incidentIds，
        // 而「没有可关闭的任务」这条路径会往里面放 null —— 于是 NPE →
        // webhook 返回 500 → Alertmanager 无限重试那条**已经处理完**的通知。
        // 实测时它每 2~7 秒重投一次，把日志刷满。
        InMemoryDiagnosisTaskStore store = new InMemoryDiagnosisTaskStore();
        MovableClock clock = new MovableClock(NOW);
        AlertIngestService service = service(store, new RecordingTrigger(),
                new IncidentAggregator(Duration.ofSeconds(120)), clock);

        AlertmanagerPayload.Parsed payload = new AlertmanagerPayload.Parsed("group",
                IncomingAlert.State.RESOLVED,
                List.of(resolved("fp-unknown", AlertType.SERVICE_DOWN, NOW.getEpochSecond())), 0);

        AlertIngestService.IngestResult result = service.ingest(payload);

        assertEquals(1, result.received());
        assertEquals(1, result.resolved());
        assertEquals(1, result.incidentIds().size(),
                "逐条对应：找不到任务的那条就是 null，不能过滤掉（过滤会让下标错位）");
        assertNull(result.incidentIds().get(0));
        assertNotNull(result.toMap().get("incidentIds"),
                "响应体必须能构造出来 —— 抛异常会让 Alertmanager 无限重试一条已经处理完的通知");
    }

    @Test
    @DisplayName("★ 诊断已结束的任务，告警恢复时仍然必须能被关闭（end_time 是故障生命周期的唯一信号）")
    void resolvedClosesTaskEvenAfterDiagnosisCompleted() {
        InMemoryDiagnosisTaskStore store = new InMemoryDiagnosisTaskStore();
        MovableClock clock = new MovableClock(NOW);
        AlertIngestService service = service(store, new RecordingTrigger(),
                new IncidentAggregator(Duration.ofSeconds(120)), clock);

        AlertIngestService.Outcome created = service.ingestOne(
                firing("fp-1", AlertType.LATENCY_HIGH, "order-service", NOW.getEpochSecond()));
        // 模拟「诊断先跑完了」（真实情况下只要十几秒），而告警几分钟后才恢复
        store.updateStatus(store.findByIncidentId(created.incidentId()).orElseThrow().id(),
                DiagnosisStatus.COMPLETED, NOW);

        clock.advance(Duration.ofMinutes(3));
        AlertIngestService.Outcome outcome = service.ingestOne(
                resolved("fp-1", AlertType.LATENCY_HIGH, NOW.getEpochSecond() + 180));

        assertEquals(created.incidentId(), outcome.incidentId(),
                "诊断结束不等于故障结束 —— end_time 必须写得上去，否则「当前告警」永远清不空，"
                        + "而且后续同类告警会全被并进这场早已结束的事故");
        DiagnosisTaskRow row = store.findByIncidentId(created.incidentId()).orElseThrow();
        assertNotNull(row.endTime());
        assertEquals(DiagnosisStatus.COMPLETED, row.status(), "关闭告警不改动诊断状态");
    }

    // ================================================================ 批量与容错

    @Test
    @DisplayName("★ 批量里有一条处理失败，后面的告警仍然要被处理（Alertmanager 是成批投递的）")
    void oneFailureDoesNotAbortTheBatch() {
        InMemoryDiagnosisTaskStore store = new InMemoryDiagnosisTaskStore().failOnCreate();
        RecordingTrigger trigger = new RecordingTrigger();
        MovableClock clock = new MovableClock(NOW);
        AlertIngestService service = service(store, trigger, new IncidentAggregator(Duration.ofSeconds(120)), clock);

        AlertmanagerPayload.Parsed payload = new AlertmanagerPayload.Parsed("group",
                IncomingAlert.State.FIRING, List.of(
                firing("fp-1", AlertType.LATENCY_HIGH, "order-service", NOW.getEpochSecond()),
                firing("fp-2", AlertType.ERROR_RATE_HIGH, "order-service", NOW.getEpochSecond())), 0);

        AlertIngestService.IngestResult result = service.ingest(payload);

        assertEquals(2, result.received());
        assertEquals(2, result.failed());
        assertEquals(0, result.created());
        assertTrue(trigger.submitted.isEmpty());
    }

    @Test
    @DisplayName("IngestResult 的计数与响应体：让「Alertmanager 说投递了几条」可以直接对照")
    void ingestResultCountersAndBody() {
        InMemoryDiagnosisTaskStore store = new InMemoryDiagnosisTaskStore();
        RecordingTrigger trigger = new RecordingTrigger();
        MovableClock clock = new MovableClock(NOW);
        AlertIngestService service = service(store, trigger, new IncidentAggregator(Duration.ofSeconds(120)), clock);

        AlertmanagerPayload.Parsed payload = new AlertmanagerPayload.Parsed("group",
                IncomingAlert.State.FIRING, List.of(
                firing("fp-1", AlertType.LATENCY_HIGH, "order-service", NOW.getEpochSecond()),
                firing("fp-2", AlertType.LATENCY_HIGH, "order-service", NOW.getEpochSecond()),
                firing("fp-3", AlertType.MQ_LAG_HIGH, "order-service", NOW.getEpochSecond())), 0);

        AlertIngestService.IngestResult result = service.ingest(payload);

        assertEquals(3, result.received());
        assertEquals(2, result.created(), "store=" + store.all());
        assertEquals(1, result.merged());
        assertEquals(0, result.resolved());
        assertEquals(0, result.failed());
        // incidentIds 是**逐条告警**的映射（不是逐个任务）：这样「这条告警进了哪个事故」
        // 可以直接对照，而不用去猜合并顺序。
        assertEquals(3, result.incidentIds().size());
        assertEquals(result.incidentIds().get(0), result.incidentIds().get(1),
                "前两条同类告警必须落在同一个事故上");
        assertFalse(result.incidentIds().get(0).equals(result.incidentIds().get(2)));
        assertEquals(3, result.toMap().get("received"));
    }

    @Test
    @DisplayName("自监控告警照常建任务并触发（跳过决策在 DiagnosisService，这里不能静默丢弃）")
    void selfMonitoringAlertIsStillRecorded() {
        InMemoryDiagnosisTaskStore store = new InMemoryDiagnosisTaskStore();
        RecordingTrigger trigger = new RecordingTrigger();
        MovableClock clock = new MovableClock(NOW);
        AlertIngestService service = service(store, trigger, new IncidentAggregator(Duration.ofSeconds(120)), clock);

        AlertIngestService.Outcome outcome = service.ingestOne(
                firing("fp-1", AlertType.SELF_MONITORING, "order-service", NOW.getEpochSecond()));

        assertEquals(AlertIngestService.Kind.CREATED, outcome.kind());
        assertEquals(1, store.size(), "「不接受诊断」不等于「不记录」——静默丢弃会让告警消失");
        assertEquals(1, trigger.submitted.size(), "是否花钱由 DiagnosisService 决定，这里只负责收下");
    }

    @Test
    @DisplayName("建任务时取号撞车会自动重取（并发下两个请求同时取到同一个号）")
    void duplicateIncidentIdIsRetried() {
        InMemoryDiagnosisTaskStore store = new InMemoryDiagnosisTaskStore().failWithDuplicateKeyOnce();
        MovableClock clock = new MovableClock(NOW);
        AlertIngestService service = service(store, new RecordingTrigger(),
                new IncidentAggregator(Duration.ofSeconds(120)), clock);

        AlertIngestService.Outcome outcome = service.ingestOne(
                firing("fp-1", AlertType.LATENCY_HIGH, "order-service", NOW.getEpochSecond()));

        assertEquals(AlertIngestService.Kind.CREATED, outcome.kind());
        assertTrue(outcome.incidentId().endsWith("002"), outcome.incidentId());
        assertEquals(1, store.size());
    }
}
