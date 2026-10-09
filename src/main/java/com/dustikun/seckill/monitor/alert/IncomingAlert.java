package com.dustikun.seckill.monitor.alert;

import com.dustikun.seckill.monitor.core.AlertEvent;

/**
 * 从 Alertmanager webhook 载荷里解析出来的一条告警。
 *
 * <h2>为什么要在 {@link AlertEvent} 之外再包一层</h2>
 * <p>
 * 因为 {@link AlertEvent} 是 SPEC 第 7.1 节定义的<b>领域模型</b>，
 * 它只描述「出了什么事」；而处理一条 webhook 告警还需要三样领域之外的东西：
 * <ul>
 *   <li>{@link #state}：这条是 firing 还是 resolved。
 *       它决定 {@code ai_diagnosis_task.end_time} 写不写（可行性报告冲突 4 的裁定）——
 *       而 resolved 的告警<b>不该触发新诊断</b>，它只是给已有任务收尾；</li>
 *   <li>{@link #fingerprint}：Alertmanager 为同一组 labels 算出的稳定哈希。
 *       它是幂等键 —— Alertmanager 会在投递失败时重试，没有它，
 *       一次网络抖动就会产生两个诊断任务、两份 LLM 费用；</li>
 *   <li>{@link #rawJson}：该条告警的原始 JSON。
 *       落进 {@code ai_diagnosis_result.raw_result} 之后，
 *       「这次诊断的输入到底是什么」不必依赖当时的 Prometheus 还在不在。</li>
 * </ul>
 *
 * @param event       领域事件（SPEC 第 7.1 节）
 * @param state       firing / resolved
 * @param fingerprint Alertmanager 的告警指纹（同一组 labels 稳定）
 * @param startsAt    异常开始时间（epoch 秒）
 * @param endsAt      异常结束时间（epoch 秒）；仍活跃时为 {@code null}
 * @param rawJson     该条告警的原始 JSON（可能为 {@code null}）
 */
public record IncomingAlert(
        AlertEvent event,
        State state,
        String fingerprint,
        Long startsAt,
        Long endsAt,
        String rawJson
) {

    public enum State {
        FIRING,
        RESOLVED;

        public static State from(String raw) {
            if (raw == null) {
                return FIRING;
            }
            return "resolved".equalsIgnoreCase(raw.trim()) ? RESOLVED : FIRING;
        }
    }

    public IncomingAlert {
        state = state == null ? State.FIRING : state;
        fingerprint = fingerprint == null ? "" : fingerprint.trim();
        rawJson = rawJson == null ? "" : rawJson;
    }

    public boolean resolved() {
        return state == State.RESOLVED;
    }

    public boolean firing() {
        return state == State.FIRING;
    }

    /**
     * 幂等键。
     * <p>优先用 Alertmanager 的 fingerprint；缺失时用
     * 「告警名 + 服务 + 开始时间」拼一个 —— 它同样是稳定的，
     * 而一个不稳定的键会让重试变成重复任务，那正是本字段要防的事。
     */
    public String idempotencyKey() {
        if (!fingerprint.isEmpty()) {
            return fingerprint;
        }
        return event.alertType() + "|" + event.serviceName() + "|" + startsAt;
    }
}
