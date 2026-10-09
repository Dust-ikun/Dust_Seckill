package com.dustikun.seckill.monitor.agent;

import com.dustikun.seckill.monitor.core.AlertEvent;

/**
 * 「去诊断这个任务」的入口，由告警接收侧调用。
 *
 * <h2>为什么它是一个只有一行签名的接口，而不是直接依赖 DiagnosisService</h2>
 * <p>
 * 因为两侧对<b>同步性</b>的要求是相反的，而这件事必须被显式建模：
 * <ul>
 *   <li><b>告警接收侧</b>必须立刻返回。{@code POST /api/ai/alerts} 是 Alertmanager
 *       的投递目标（单次投递超时 5s），而一次诊断要跑几秒到几十秒 ——
 *       在那里同步诊断，会让 Alertmanager 超时重投，于是<b>同一个故障被诊断多次</b>，
 *       而且重投还会让「投递失败」的假警报出现；</li>
 *   <li><b>测试与故障注入脚本</b>需要一个可以说「跑完了吗」的入口，
 *       异步接口做不到（要么轮询，要么睡一会儿再断言 —— 两者都会引入不稳定）。</li>
 * </ul>
 * 把它做成接口之后，生产用异步实现（{@code DiagnosisService} 的线程池），
 * 单测注入一个同步的 lambda，两边都得到自己需要的行为，而且
 * 「这里到底是同步还是异步」在读代码时一眼可见。
 */
@FunctionalInterface
public interface DiagnosisTrigger {

    /**
     * 安排一次诊断。实现可以同步执行，也可以入队后立即返回。
     *
     * @param taskId     {@code ai_diagnosis_task.id}
     * @param incidentId 事故编号（权威值，会写进 prompt 并要求模型原样回填）
     * @param alert      触发本次诊断的告警
     */
    void submit(long taskId, String incidentId, AlertEvent alert);
}
