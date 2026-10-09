package com.dustikun.seckill.monitor.repository;

import com.dustikun.seckill.monitor.core.DiagnosisStatus;
import com.dustikun.seckill.monitor.core.AlertEvent;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * {@code ai_diagnosis_task} 的读写（SPEC 第 14.1 节）。
 *
 * <p>【它为什么是接口】有两个实现上的理由，都没有一个是为了「将来可能换数据库」：
 * <ol>
 *   <li>{@code AlertIngestService} 的聚合行为需要被单测 —— 而「3 条告警 → 1 个任务」
 *       这条判据只有在能控制「查询返回什么候选任务」时才可复现。
 *       用接口 + 内存实现，这条判据就不需要起 MySQL；</li>
 *   <li>真正的实现在 {@code JdbcDiagnosisTaskStore}，它只关心 SQL 与列映射，
 *       不含任何聚合判断（那些在 {@code IncidentAggregator} 里）。</li>
 * </ol>
 */
public interface DiagnosisTaskStore {

    /**
     * 新建一个诊断任务，并返回刚写入的整行。
     *
     * <p>【事故编号由这里生成，不由调用方传】因为
     * {@code INC-YYYYMMDD-NNN} 是按天的序列，两个请求同时取号必然撞车。
     * 这里不做悲观锁（为一次告警去锁一张表不值得），而是依赖
     * {@code uk_incident_id} 唯一键：撞了就<b>重取一次号</b>再插。
     * 重取之所以有效，是因为冲突的那一行已经落库，{@link #nextIncidentId} 会看到它。
     * 把这段重试留在 Store 内部，调用方（聚合服务）就不必知道「号码是会撞的」——
     * 否则那段重试逻辑会在第二个调用点上被漏掉。
     *
     * @return 写入后的整行（含自增 id、生成的事故编号、初始状态 {@code CREATED}）
     */
    DiagnosisTaskRow create(AlertEvent alert, Instant now);

    Optional<DiagnosisTaskRow> findByIncidentId(String incidentId);

    Optional<DiagnosisTaskRow> findById(long taskId);

    /**
     * 候选任务（同服务 + 同异常类型，按创建时间倒序）。
     * <p>它只做<b>预筛</b>；「该不该并进去」由 {@code IncidentAggregator} 判定。
     * 把窗口与终态判断留在 SQL 里会让它们无法被单测覆盖。
     */
    List<DiagnosisTaskRow> findCandidates(String serviceName, String alertType, int limit);

    /** 把一条告警并进已有任务：{@code alert_count = alert_count + 1} */
    boolean mergeInto(long taskId, Instant now);

    /** 推进状态机（SPEC 第 13 节）。任务不存在时返回 {@code false} */
    boolean updateStatus(long taskId, DiagnosisStatus status, Instant now);

    /**
     * 记录告警恢复（Alertmanager 的 resolved 通知，可行性报告冲突 4 的裁定）。
     * <p>它<b>只</b>写 {@code end_time}，不动 {@code status} —— 理由见
     * {@code DiagnosisStatus} 的类注释（「告警恢复了」与「诊断结束了」是两个正交的事实）。
     */
    boolean markResolved(String incidentId, Instant endTime, Instant now);

    /** 历史诊断（{@code GET /api/ai/diagnosis/history}），按创建时间倒序 */
    List<DiagnosisTaskRow> history(int limit, int offset);

    /**
     * 仍然活跃的事故（{@code end_time IS NULL}），按创建时间倒序。
     * <p>【为什么用 {@code end_time} 而不是 {@code status} 来筛】因为它们是两条不同的时间轴，
     * 见 {@code DiagnosisStatus} 的注释：「诊断结束了」不等于「故障恢复了」。
     * 一张「当前告警」列表要回答的是后者 —— 用 {@code status} 去筛会漏掉
     * 「诊断已完成、但故障还在持续」的事故，而它们恰恰是最需要人看的。
     */
    List<DiagnosisTaskRow> findActive(int limit);

    /** 生成下一个事故编号 {@code INC-YYYYMMDD-NNN} */
    String nextIncidentId(Instant now);
}
