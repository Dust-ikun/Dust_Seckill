package com.dustikun.seckill.monitor.alert;

import com.dustikun.seckill.monitor.core.AlertEvent;
import com.dustikun.seckill.monitor.core.AlertType;
import com.dustikun.seckill.monitor.core.DiagnosisStatus;
import com.dustikun.seckill.monitor.repository.DiagnosisTaskRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Incident 聚合的边界判据（SPEC 第 16 节：3 Alert → 1 Diagnosis Task）。
 *
 * <h2>为什么这一组几乎全是「边界」用例</h2>
 * <p>
 * 因为聚合的正常路径（窗口内、同类型、未结束 → 合并）一眼就对，
 * 而它出错的方式恰恰都在边界上。每一条错法都有自己的代价：
 * <pre>
 *   窗口多算了一点   → 两次独立故障被合并，Agent 拿到互相矛盾的证据
 *   窗口少算了一点   → 同一次故障被拆成两个任务，各付一次 LLM 费用
 *   终态之后还合并   → 第二次故障被静默吞掉（不报错、不留痕，最危险）
 *   恢复之后还合并   → 两段不同时间的证据混在一起
 *   异常类型当键用   → 改一条规则名就悄悄多出一个 Agent
 * </pre>
 */
class IncidentAggregatorTest {

    private static final Instant NOW = Instant.parse("2026-10-09T12:00:00Z");

    private static final Duration WINDOW = Duration.ofSeconds(120);

    private final IncidentAggregator aggregator = new IncidentAggregator(WINDOW);

    private static final AlertEvent ALERT = new AlertEvent("ALT-1", "order-service",
            "seckill:http_p99_latency:5m", AlertType.LATENCY_HIGH.name(), "HIGH",
            2.1, 1.0, NOW.getEpochSecond(), "P99 超过阈值");

    private static DiagnosisTaskRow row(String incidentId, AlertType type, DiagnosisStatus status,
                                        Instant createdAt, Instant endTime, int alertCount) {
        return new DiagnosisTaskRow(1L, incidentId, "order-service", type.name(), "HIGH", status,
                createdAt, endTime, "ALT-1", alertCount, createdAt, createdAt);
    }

    private static DiagnosisTaskRow open(String incidentId, AlertType type, long ageSeconds, int count) {
        return row(incidentId, type, DiagnosisStatus.COLLECTING_EVIDENCE,
                NOW.minusSeconds(ageSeconds), null, count);
    }

    // ================================================================ 正常路径

    @Test
    @DisplayName("没有同类型历史任务 → 新建，并说明原因")
    void createsWhenNoCandidate() {
        IncidentAggregator.Decision decision = aggregator.decide(ALERT, List.of(), NOW);
        assertFalse(decision.merge());
        assertTrue(decision.reason().contains("没有同服务"), decision.reason());
        assertTrue(decision.describe().contains("新建任务"), decision.describe());
    }

    @Test
    @DisplayName("★ 窗口内已有未结束的同类型任务 → 合并（SPEC 第 16 节的核心判据）")
    void mergesIntoOpenTaskInsideWindow() {
        IncidentAggregator.Decision decision = aggregator.decide(ALERT,
                List.of(open("INC-1", AlertType.LATENCY_HIGH, 30, 2)), NOW);

        assertTrue(decision.merge());
        assertEquals("INC-1", decision.target().incidentId());
        assertTrue(decision.reason().contains("已聚合 2 条告警"), decision.reason());
    }

    @Test
    @DisplayName("刚好落在窗口边界（age == window）→ 合并")
    void mergesAtExactWindowBoundary() {
        IncidentAggregator.Decision decision = aggregator.decide(ALERT,
                List.of(open("INC-1", AlertType.LATENCY_HIGH, WINDOW.toSeconds(), 1)), NOW);
        assertTrue(decision.merge(), "边界上取「容忍」：宁可多合并一条，也别把同一故障拆成两个 Agent");
    }

    @Test
    @DisplayName("多个合格任务时并入最新的那一个")
    void mergesIntoNewestEligible() {
        IncidentAggregator.Decision decision = aggregator.decide(ALERT, List.of(
                open("INC-old", AlertType.LATENCY_HIGH, 100, 1),
                open("INC-new", AlertType.LATENCY_HIGH, 10, 1)), NOW);
        assertTrue(decision.merge());
        assertEquals("INC-new", decision.target().incidentId());
    }

    // ================================================================ 边界：不合并

    @Test
    @DisplayName("★ 超出窗口 → 新建，且原因里带上实际年龄与窗口（那是「为什么又花了一次钱」的解释）")
    void createsWhenOutsideWindow() {
        IncidentAggregator.Decision decision = aggregator.decide(ALERT,
                List.of(open("INC-1", AlertType.LATENCY_HIGH, 121, 1)), NOW);

        assertFalse(decision.merge());
        assertTrue(decision.reason().contains("超出聚合窗口"), decision.reason());
        assertTrue(decision.reason().contains("121s"), decision.reason());
        assertTrue(decision.reason().contains("120s"), decision.reason());
    }

    @Test
    @DisplayName("★ 诊断已结束但故障未恢复 → 仍然合并（合并的边界是「故障生命周期」而不是「诊断进度」）")
    void mergesEvenWhenDiagnosisConcluded() {
        // 【这一条是设计判据，不是实现细节】最初的实现把「诊断已结束」也当作不合并的理由，
        // 后果是：没配 LLM 密钥时诊断会立刻 FAILED（终态），于是同一个事故在
        // 「有 LLM」时聚合成 1 个任务、在「没 LLM」时裂成 N 个 —— 一个随环境变化的聚合行为。
        // 现在判据只有「告警未恢复 + 窗口内」，与 SPEC 第 16 节给的键（服务 + 类型 + 窗口）一致。
        for (DiagnosisStatus concluded : List.of(DiagnosisStatus.COMPLETED,
                DiagnosisStatus.FAILED, DiagnosisStatus.INSUFFICIENT_EVIDENCE)) {
            DiagnosisTaskRow done = row("INC-1", AlertType.LATENCY_HIGH, concluded,
                    NOW.minusSeconds(10), null, 1);
            IncidentAggregator.Decision decision = aggregator.decide(ALERT, List.of(done), NOW);

            assertTrue(decision.merge(), concluded + " 之后仍应并入（故障还没恢复）");
            assertTrue(decision.reason().contains("诊断已结束"), decision.reason());
            assertTrue(decision.reason().contains(concluded.name()), decision.reason());
            assertTrue(decision.reason().contains("不会重新诊断"),
                    "要说清「这次不会产出新结论」，否则人会以为又诊断了一遍：" + decision.reason());
        }
    }

    @Test
    @DisplayName("诊断已结束 + 故障已恢复 → 不合并（恢复是真故障边界，它来自 Alertmanager）")
    void doesNotMergeConcludedAndResolved() {
        DiagnosisTaskRow resolved = row("INC-1", AlertType.LATENCY_HIGH,
                DiagnosisStatus.COMPLETED, NOW.minusSeconds(30), NOW.minusSeconds(5), 1);
        assertFalse(aggregator.decide(ALERT, List.of(resolved), NOW).merge());
    }

    @Test
    @DisplayName("★ 告警已恢复（end_time 非空）→ 新建：那段故障结束了，并进去会把两段时间的证据混在一起")
    void createsWhenAlertAlreadyResolved() {
        DiagnosisTaskRow resolved = row("INC-1", AlertType.LATENCY_HIGH,
                DiagnosisStatus.COMPLETED, NOW.minusSeconds(30), NOW.minusSeconds(5), 3);
        IncidentAggregator.Decision decision = aggregator.decide(ALERT, List.of(resolved), NOW);

        assertFalse(decision.merge());
        assertTrue(decision.reason().contains("已恢复"), decision.reason());
    }

    @Test
    @DisplayName("★ 同服务但不同异常类型 → 不合并（跨类型合并需要判断「是不是同一件事」，那是 Agent 的推理）")
    void doesNotMergeAcrossAlertTypes() {
        IncidentAggregator.Decision decision = aggregator.decide(ALERT,
                List.of(open("INC-1", AlertType.ERROR_RATE_HIGH, 10, 1)), NOW);
        assertFalse(decision.merge());
        assertTrue(decision.reason().contains("没有同服务"), decision.reason());
    }

    @Test
    @DisplayName("不同类型：同服务 + 同类型才是键，另一个服务同类型也不算")
    void doesNotMergeAcrossServices() {
        DiagnosisTaskRow otherService = new DiagnosisTaskRow(1L, "INC-1", "other-service",
                AlertType.LATENCY_HIGH.name(), "HIGH", DiagnosisStatus.COLLECTING_EVIDENCE,
                NOW.minusSeconds(10), null, "ALT-9", 1, NOW.minusSeconds(10), NOW.minusSeconds(10));
        assertFalse(aggregator.decide(ALERT, List.of(otherService), NOW).merge());
    }

    @Test
    @DisplayName("最新的那个已恢复、更早的还开着且在窗口内 → 并入那个还开着的")
    void mergesIntoOlderTaskWhenNewestIsResolved() {
        IncidentAggregator.Decision decision = aggregator.decide(ALERT, List.of(
                open("INC-open", AlertType.LATENCY_HIGH, 60, 1),
                row("INC-resolved", AlertType.LATENCY_HIGH, DiagnosisStatus.COMPLETED,
                        NOW.minusSeconds(5), NOW.minusSeconds(1), 1)), NOW);

        assertTrue(decision.merge(), "只看最新那个会另起任务，而更早那个事故其实还没结束");
        assertEquals("INC-open", decision.target().incidentId());
    }

    // ================================================================ 退化输入

    @Test
    @DisplayName("创建时间为 null 的任务不可并入（无法判定窗口，不猜）")
    void skipsRowsWithoutCreatedAt() {
        DiagnosisTaskRow broken = row("INC-1", AlertType.LATENCY_HIGH,
                DiagnosisStatus.COLLECTING_EVIDENCE, null, null, 1);
        IncidentAggregator.Decision decision = aggregator.decide(ALERT, List.of(broken), NOW);

        assertFalse(decision.merge());
        assertTrue(decision.reason().contains("没有创建时间"), decision.reason());
    }

    @Test
    @DisplayName("窗口配成 0 或负数时回落到 120s：否则「聚合」会退化成「每条告警一个任务」")
    void zeroWindowFallsBackToDefault() {
        assertEquals(Duration.ofSeconds(120), new IncidentAggregator(Duration.ZERO).window());
        assertEquals(Duration.ofSeconds(120), new IncidentAggregator(Duration.ofSeconds(-5)).window());
        assertEquals(Duration.ofSeconds(120), new IncidentAggregator(null).window());
    }

    @Test
    @DisplayName("candidates 为 null 时按「没有候选」处理，而不是 NPE")
    void toleratesNullCandidates() {
        assertFalse(aggregator.decide(ALERT, null, NOW).merge());
        assertTrue(aggregator.newestUnresolved(null).isEmpty());
    }

    @Test
    @DisplayName("★ newestUnresolved 只按 end_time 判「还没恢复」：诊断已结束的任务同样必须能被关闭")
    void newestUnresolvedIgnoresDiagnosisStatus() {
        // 【这一条是真实运行踩出来的回归用例】初版用「未结束（!terminal()）且未恢复」筛选，
        // 于是出现了一个几乎必然发生的后果：诊断十几秒就跑完（COMPLETED），
        // 而告警要几分钟后才恢复 —— resolved 到达时任务早已终态，被过滤掉，
        // end_time 永远写不上去。连锁反应是「当前告警」清不空 +
        // 后续同类告警全被并进这场早已结束的事故（第二次故障永远不会被独立诊断）。
        for (DiagnosisStatus concluded : List.of(DiagnosisStatus.COMPLETED,
                DiagnosisStatus.FAILED, DiagnosisStatus.INSUFFICIENT_EVIDENCE)) {
            assertTrue(aggregator.newestUnresolved(List.of(
                            row("INC-concluded", AlertType.LATENCY_HIGH, concluded,
                                    NOW.minusSeconds(5), null, 1))).isPresent(),
                    concluded + " 的任务只要 end_time 还是 NULL，就必须能被 resolved 关闭");
        }

        // 真正该被排除的只有「已经恢复过」的
        assertTrue(aggregator.newestUnresolved(List.of(
                row("INC-resolved", AlertType.LATENCY_HIGH, DiagnosisStatus.RUNNING,
                        NOW.minusSeconds(10), NOW.minusSeconds(1), 1))).isEmpty());

        // 多个未恢复时取最新的；且**不看窗口** —— 关闭告警与「窗口内该不该合并」是两件事，
        // 一个 10 分钟前开的任务，其 resolved 通知同样必须能把 end_time 写上去。
        assertEquals("INC-2", aggregator.newestUnresolved(List.of(
                open("INC-1", AlertType.LATENCY_HIGH, 200, 1),
                open("INC-2", AlertType.LATENCY_HIGH, 20, 1))).orElseThrow().incidentId());
    }
}
