package com.dustikun.seckill.monitor.alert;

import com.dustikun.seckill.monitor.core.AlertEvent;
import com.dustikun.seckill.monitor.repository.DiagnosisTaskRow;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Incident 聚合：把「同服务 + 同异常类型 + 时间窗口内」的告警并成一次诊断
 * （SPEC 第 16 节：3 Alert → 1 Diagnosis Task）。
 *
 * <h2>它为什么是一个独立的、纯函数的类</h2>
 * <p>
 * 因为它要回答的问题全都是<b>边界</b>问题，而边界问题只有在能自由构造
 * 「创建于 119 秒前」「创建于 121 秒前」「已 COMPLETED」这些情形时才测得出来：
 * <pre>
 *   窗口边界      119s 该并、121s 不该并
 *   终态边界      COMPLETED 之后来的告警必须另起任务，否则第二次故障被静默吞掉
 *   恢复边界      告警已 resolved（end_time 非空）之后来的 firing 是新的故障
 *   类型边界      同服务但不同异常类型（P99 高 vs 错误率高）不合并 ——
 *                 见 {@code MonitorIncidentProperties} 的注释
 * </pre>
 * 若这段逻辑写在「查库 + 判断 + 写库」的服务方法里，上面每一条都要起一次 MySQL
 * 才能验证，于是它们实际上不会被验证。
 *
 * <h2>「不合并」的四种理由都要能说出来</h2>
 * <p>
 * 因为这是「为什么又花了一次 LLM 费用」的唯一解释。{@link Decision#reason()}
 * 会被写进日志，并且在 Incident 聚合行为不符合预期时是第一条要看的信息。
 */
public final class IncidentAggregator {

    private final Duration window;

    public IncidentAggregator(Duration window) {
        // 兜底一个正窗口：窗口为 0 或负数会让「聚合」变成「每条告警一个任务」，
        // 而那正是 SPEC 第 16 节要防的事（P99/错误率/超时三条告警 → 3 个 Agent）。
        this.window = window == null || window.isNegative() || window.isZero()
                ? Duration.ofSeconds(120) : window;
    }

    public Duration window() {
        return window;
    }

    /**
     * 决定一条告警的去向。
     *
     * @param alert      新到的告警
     * @param candidates 候选任务（同服务 + 同异常类型，由 {@code DiagnosisTaskStore} 预筛并按创建时间倒序）
     * @param now        当前时间（显式传入而不是取 {@code Instant.now()}：否则窗口边界无法被测试）
     */
    public Decision decide(AlertEvent alert, List<DiagnosisTaskRow> candidates, Instant now) {
        if (alert == null) {
            throw new IllegalArgumentException("alert 不能为 null");
        }
        List<DiagnosisTaskRow> sameKey = candidates == null ? List.of() : candidates.stream()
                .filter(row -> row != null)
                .filter(row -> alert.serviceName().equals(row.serviceName()))
                .filter(row -> alert.alertType().equals(row.alertType()))
                .sorted(Comparator.comparing(DiagnosisTaskRow::createdAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())).reversed())
                .toList();

        if (sameKey.isEmpty()) {
            return Decision.create("没有同服务（" + alert.serviceName() + "）+ 同异常类型（"
                    + alert.alertType() + "）的历史任务");
        }

        // 【为什么是「找最新的**合格**任务」而不是「看最新的那个合不合格」】
        // 后者在一种少见但真实存在的情形下会做错：最新的任务已经结束（诊断跑完了），
        // 而它前面还有一个仍开着、且仍在窗口内的任务 —— 那是**同一个还没结束的事故**，
        // 新告警属于它。只看最新那个会另起一个任务，于是同一次故障被拆成两半，
        // 而两半各自都要付一次 LLM 费用。
        DiagnosisTaskRow newestEligible = null;
        for (DiagnosisTaskRow row : sameKey) {
            if (isEligible(row, now)) {
                newestEligible = row;
                break;
            }
        }
        if (newestEligible != null) {
            long ageSeconds = Math.max(0, Duration.between(newestEligible.createdAt(), now).toSeconds());
            // 「已经诊断完了」这条信息仍然要说出来：它解释了「为什么这次没有新的诊断结论」。
            // 但它不参与判定 —— 见 isEligible 的注释。
            String concluded = newestEligible.terminal()
                    ? "，该任务的诊断已结束（" + newestEligible.status() + "，不会重新诊断）" : "";
            return Decision.merge(newestEligible, "窗口内已有未恢复的同类型任务 "
                    + newestEligible.incidentId() + "（创建于 " + ageSeconds + "s 前，已聚合 "
                    + newestEligible.alertCount() + " 条告警" + concluded + "）");
        }

        return Decision.create(explainWhyNoneEligible(sameKey.get(0), alert, now));
    }

    /**
     * 能不能把新告警并进这个任务。
     *
     * <h2>★ 判据是「故障还没恢复」，而<b>不是</b>「诊断还没结束」</h2>
     * <p>
     * 这两条是<b>正交</b>的时间轴（见 {@code DiagnosisStatus} 的类注释）：
     * {@code end_time} 由 Alertmanager 的 resolved 通知写，它表达的是
     * 「这个故障结束了」；{@code status} 表达的是「我们那次诊断跑完了」。
     * 合并的边界只应该用前者，理由有三条：
     * <ol>
     *   <li><b>SPEC 第 16 节给的键里没有状态</b>：它写的是「同一服务 + 同一异常类型 +
     *       时间窗口内」。加上状态判据是我最初的发挥，而它带来了下面这个后果 ——</li>
     *   <li><b>它会让行为依赖「有没有配 LLM 密钥」</b>：没配密钥时诊断会<b>立刻</b>失败
     *       （{@code FAILED} 是终态），于是每来一条告警都另起一个任务 ——
     *       同一个事故在「有 LLM」时是 1 个任务、在「没 LLM」时是 N 个。
     *       一个随环境变化的聚合行为，比一个「不够聪明」的固定规则糟得多；</li>
     *   <li><b>「诊断结束了」并不是「故障结束了」</b>：一次已完成的诊断之后再收到
     *       同类告警，几乎必然是同一场还没修好的故障（120s 内没人能修好又搞坏一次），
     *       把它并入原事故才是对的。</li>
     * </ol>
     * <p>真正的「第二次故障」由 {@code end_time} 识别：Alertmanager 发过 resolved
     * 之后又 firing，才是新事故 —— 那个信号来自故障本身，比我们的诊断进度可靠。
     */
    private boolean isEligible(DiagnosisTaskRow row, Instant now) {
        if (row.endTime() != null || row.createdAt() == null) {
            return false;
        }
        return Duration.between(row.createdAt(), now).compareTo(window) <= 0;
    }

    /**
     * 说不出「并进哪个」时，必须说清「为什么不并」。
     * <p>因为这是「为什么又花了一次 LLM 费用」的唯一解释 —— 见类注释。
     * 两种原因的排查方向完全不同：窗口太小要去调
     * {@code incident.aggregate-window-seconds}；已恢复说明 Alertmanager
     * 发过 resolved（那次故障确实结束了，本次是新故障）。
     */
    private String explainWhyNoneEligible(DiagnosisTaskRow newest, AlertEvent alert, Instant now) {
        if (newest.createdAt() == null) {
            return "最近的任务 " + newest.incidentId() + " 没有创建时间，无法判定窗口";
        }
        if (newest.endTime() != null) {
            return "最近的任务 " + newest.incidentId()
                    + " 的告警已恢复（end_time 非空），本次按新故障处理";
        }
        long ageSeconds = Math.max(0, Duration.between(newest.createdAt(), now).toSeconds());
        return "最近的任务 " + newest.incidentId() + " 创建于 " + ageSeconds
                + "s 前，超出聚合窗口 " + window.toSeconds() + "s"
                + "（同服务 " + alert.serviceName() + " + 同异常类型 " + alert.alertType() + "）";
    }

    /**
     * 在同键候选里挑一个「还没恢复」的任务（告警恢复时用它定位要关闭的那个）。
     *
     * <h2>★ 判据只有 {@code end_time IS NULL} 一条 —— 这里曾经错过一次</h2>
     * <p>
     * 初版写的是「未结束（{@code !terminal()}）且未恢复」，于是出现了一个
     * <b>几乎必然发生</b>的后果：一次诊断通常十几秒就跑完了（{@code COMPLETED}），
     * 而告警要几分钟后才恢复 —— 等到 resolved 通知到达时，任务早就到了终态，
     * 于是它被过滤掉，{@code end_time} <b>永远写不上去</b>。
     * <p>那个后果有两个连锁反应，都很严重：
     * <ol>
     *   <li>「当前告警」列表永远清不空（{@code end_time} 一直是 NULL）；</li>
     *   <li>更糟的是：{@code end_time} 正是 {@link #isEligible} 用来识别
     *       「第二次故障」的唯一信号。它写不上去，就意味着<b>后续所有同类告警
     *       都会被并进这场早已结束的事故</b>，第二次故障永远不会被独立诊断。</li>
     * </ol>
     * <p>这与 {@link #isEligible} 的注释是同一条纪律：<b>判「故障结没结束」只能看
     * {@code end_time}，不能看我们的诊断进度 {@code status}</b>。
     * 名字从 {@code newestOpen} 改成 {@code newestUnresolved} 就是为了不再混淆这两件事。
     */
    public Optional<DiagnosisTaskRow> newestUnresolved(List<DiagnosisTaskRow> candidates) {
        if (candidates == null) {
            return Optional.empty();
        }
        return candidates.stream()
                .filter(row -> row != null && row.endTime() == null)
                .max(Comparator.comparing(DiagnosisTaskRow::createdAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())));
    }

    /**
     * 聚合决定。
     *
     * @param merge  是否并入已有任务
     * @param target 并入的目标（{@code merge=false} 时为 {@code null}）
     * @param reason 决定的理由（必然非空，见类注释）
     */
    public record Decision(boolean merge, DiagnosisTaskRow target, String reason) {

        static Decision merge(DiagnosisTaskRow target, String reason) {
            return new Decision(true, target, reason);
        }

        static Decision create(String reason) {
            return new Decision(false, null, reason);
        }

        public String describe() {
            return (merge ? "并入 " + target.incidentId() : "新建任务") + "：" + reason;
        }
    }
}
