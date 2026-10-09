package com.dustikun.seckill.monitor.agent;

import com.dustikun.seckill.monitor.core.AiMonitorMetrics;
import com.dustikun.seckill.monitor.core.AlertEvent;
import com.dustikun.seckill.monitor.core.DiagnosisStatus;
import com.dustikun.seckill.monitor.repository.DiagnosisResultStore;
import com.dustikun.seckill.monitor.repository.DiagnosisTaskStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * 诊断的执行与落库边界：把「一次诊断」从触发到写进三张表串起来。
 *
 * <h2>它负责的三件事，以及为什么是这三件</h2>
 * <ol>
 *   <li><b>异步化</b>：{@link #submit} 立刻返回，真正的工作在线程池里跑。
 *       理由见 {@link DiagnosisTrigger} 的注释（Alertmanager 的投递超时只有 5s）；</li>
 *   <li><b>状态机落库</b>：把 {@link AgentExecutor.StatusListener} 的回调写成
 *       {@code ai_diagnosis_task.status} 的更新。SPEC 第 13 节的状态机在这里才真正生效 ——
 *       {@code AgentExecutor} 只负责「报告状态变了」，怎么落库是这一层的事；</li>
 *   <li><b>结论落库</b>：有结论走 {@code save}，没有结论走 {@code saveFailure}。
 *       这两条路的区别见 {@link AgentRunResult} 的注释，而<b>只有这里能分清它们</b>。</li>
 * </ol>
 *
 * <h2>为什么整体包了一层 try/catch</h2>
 * <p>
 * 因为这个方法跑在<b>线程池</b>里。抛出去的异常不会有人接 ——
 * 它只会变成一行线程堆栈，而任务永远停在 {@code RUNNING}，
 * 界面上看就是「诊断卡住了」。SPEC 第 25 节的「Agent 异常不能阻塞交易请求」
 * 在这里的具体含义就是：把任何逃逸的异常变成一条 {@code FAILED} 记录。
 */
public class DiagnosisService implements DiagnosisTrigger {

    private static final Logger log = LoggerFactory.getLogger(DiagnosisService.class);

    /**
     * 单次诊断的并发上限。
     * <p>【为什么它是常量而不是配置】因为它是「付费失控」的闸门，不是调优参数。
     * 本项目对同类开关的取向写在 {@code MonitorLlmProperties} 的注释里：
     * 一个可以被随手改掉的闸门，会在赶工时被改掉。要提并发请改代码并 code review ——
     * 那时你会顺带想一下「同时跑 10 个诊断的账单是多少」。
     */
    public static final int MAX_CONCURRENT_DIAGNOSES = 2;

    private final AgentExecutor executor;

    private final DiagnosisTaskStore taskStore;

    private final DiagnosisResultStore resultStore;

    private final AiMonitorMetrics metrics;

    private final Executor pool;

    private final Clock clock;

    public DiagnosisService(AgentExecutor executor, DiagnosisTaskStore taskStore,
                            DiagnosisResultStore resultStore, AiMonitorMetrics metrics,
                            Executor pool) {
        this(executor, taskStore, resultStore, metrics, pool, Clock.systemDefaultZone());
    }

    /** 供测试注入固定时钟 */
    public DiagnosisService(AgentExecutor executor, DiagnosisTaskStore taskStore,
                            DiagnosisResultStore resultStore, AiMonitorMetrics metrics,
                            Executor pool, Clock clock) {
        this.executor = executor;
        this.taskStore = taskStore;
        this.resultStore = resultStore;
        this.metrics = metrics;
        this.pool = pool;
        this.clock = clock;
    }

    // ================================================================ 触发

    @Override
    public void submit(long taskId, String incidentId, AlertEvent alert) {
        try {
            pool.execute(() -> diagnose(taskId, incidentId, alert));
        } catch (RejectedExecutionException e) {
            // 队列满。这必须留下一条明确的失败记录，而不是静默丢弃 ——
            // 被丢弃的诊断在界面上与「还在排队」完全一样。
            String reason = "诊断线程池已满（并发上限 " + MAX_CONCURRENT_DIAGNOSES
                    + "），本次诊断被拒绝。降低告警频率或调大上限后重试。";
            log.warn("[DiagnosisService] task={} {}", taskId, reason);
            metrics.diagnosis("SKIPPED");
            failTask(taskId, reason);
        }
    }

    /**
     * 同步跑一次诊断并落库。返回最终结果，供测试与故障注入脚本使用。
     * <p>它<b>不</b>吞异常（{@link #submit} 的那一层才吞）——
     * 直接调用它的人需要看到失败。
     */
    public AgentRunResult diagnose(long taskId, String incidentId, AlertEvent alert) {
        Instant startedAt = clock.instant();
        try {
            // ---------- 不值得花 LLM 费用的告警：直接记账，不去问模型 ----------
            if (!alert.type().diagnosable()) {
                String reason = "该告警描述的是监控系统自身（" + alert.alertType()
                        + "），按 alertmanager 的路由约定不进入 AI 诊断";
                log.info("[DiagnosisService] task={} 跳过诊断：{}", taskId, reason);
                metrics.diagnosis("SKIPPED");
                taskStore.updateStatus(taskId, DiagnosisStatus.FAILED, startedAt);
                resultStore.saveFailure(taskId, reason, rawOf(alert), startedAt);
                return new AgentRunResult(DiagnosisStatus.FAILED, null, 0, 0, 0L, reason,
                        null, rawOf(alert), java.util.List.of(reason));
            }

            AgentRunResult result = executor.run(taskId, incidentId, alert,
                    status -> taskStore.updateStatus(taskId, status, clock.instant()));

            Instant finishedAt = clock.instant();
            if (result.hasDiagnosis()) {
                resultStore.save(taskId, result.diagnosis(), result.rawResult(), finishedAt);
            } else {
                resultStore.saveFailure(taskId, result.failureReason(), result.rawResult(), finishedAt);
            }
            // 最终状态再写一次：中间态的写入由 listener 负责，而「最后一次转换」必须是
            // 本方法的职责 —— 否则将来有人改动 listener 的调用时机时，
            // 任务可能永远停在 ANALYZING，而那种问题看起来像「模型没收尾」。
            taskStore.updateStatus(taskId, result.status(), finishedAt);
            log.info("[DiagnosisService] task={} incident={} 结束：{}",
                    taskId, incidentId, result.summarize());
            return result;
        } catch (RuntimeException e) {
            // 见类注释最后一段：跑在线程池里的异常必须变成一条 FAILED 记录。
            log.error("[DiagnosisService] task={} 诊断过程抛出未预期异常", taskId, e);
            String reason = "诊断过程异常：" + e.getClass().getSimpleName() + "：" + e.getMessage();
            metrics.diagnosis(DiagnosisStatus.FAILED.name());
            failTask(taskId, reason);
            return new AgentRunResult(DiagnosisStatus.FAILED, null, 0, 0, 0L, reason,
                    null, rawOf(alert), java.util.List.of(reason));
        }
    }

    /** 把任务标成失败并留一条「没有结论」的记录 */
    private void failTask(long taskId, String reason) {
        Instant now = clock.instant();
        try {
            taskStore.updateStatus(taskId, DiagnosisStatus.FAILED, now);
            resultStore.saveFailure(taskId, reason, Map.of(), now);
        } catch (RuntimeException e) {
            // 落库都失败时只能记日志：再抛出去会覆盖掉真正的根因异常。
            log.error("[DiagnosisService] task={} 失败状态落库也失败了：{}", taskId, e.toString());
        }
    }

    private static Map<String, Object> rawOf(AlertEvent alert) {
        Map<String, Object> raw = new LinkedHashMap<>(4);
        raw.put("service", alert.serviceName());
        raw.put("alertType", alert.alertType());
        raw.put("alertId", alert.alertId());
        return raw;
    }
}
