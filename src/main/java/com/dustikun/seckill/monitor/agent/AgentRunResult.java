package com.dustikun.seckill.monitor.agent;

import com.dustikun.seckill.monitor.analyzer.Diagnosis;
import com.dustikun.seckill.monitor.core.DiagnosisStatus;
import com.dustikun.seckill.monitor.core.SafeCollections;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次 ReAct 执行的结局。
 *
 * <h2>为什么「有结论」与「没结论」必须是两个字段，而不是用 status 兼表</h2>
 * <p>
 * 因为 {@code INSUFFICIENT_EVIDENCE} 有<b>两种</b>来源，而它们要做的事不同：
 * <ul>
 *   <li>模型给了结论、但自查的证据条数不够 → {@link #diagnosis()} 非空，
 *       要把它<b>存进</b> {@code ai_diagnosis_result}（那是「Agent 当时怎么想的」）；</li>
 *   <li>模型输出根本不是合法 JSON → {@link #diagnosis()} 为空、
 *       {@link #rawContent()} 有值，只能走 {@code saveFailure}（没有根因可存）。</li>
 * </ul>
 * 只看 status 分不出这两者，而调用方（写库的那一段）必须分得清 ——
 * 否则要么把「没有根因」当成有根因写进去（{@code root_cause} 变成一句失败原因），
 * 要么把一份真实结论丢掉。
 *
 * @param status       最终状态（写进 {@code ai_diagnosis_task.status}）
 * @param diagnosis    解析成功的结论；未产出结论时为 {@code null}
 * @param toolCalls    实际执行的工具调用次数（对应 SPEC 第 23.2 节的上限）
 * @param llmCalls     LLM 往返次数。它与 {@code toolCalls} 不是一回事：
 *                     一轮回复里可以有多个工具调用，而一次诊断也常常有「收尾」那一轮
 * @param elapsedMillis 整次执行耗时
 * @param failureReason 失败/降级原因（成功时为 {@code null}）
 * @param rawContent    模型最后给出的原始正文（可能不是 JSON）
 * @param rawResult     准备写进 {@code ai_diagnosis_result.raw_result} 的对象
 * @param steps         执行时间线（人读的一步步记录，写启动日志与排障用）
 */
public record AgentRunResult(
        DiagnosisStatus status,
        Diagnosis diagnosis,
        int toolCalls,
        int llmCalls,
        long elapsedMillis,
        String failureReason,
        String rawContent,
        Map<String, Object> rawResult,
        List<String> steps
) {

    public AgentRunResult {
        // 【不能用 Map.copyOf】它拒绝 null 值，而降级路径上的 finishReason 本来就是 null ——
        // 用 Map.copyOf 会把「未配置 LLM 密钥」变成一个 NPE（详见 SafeCollections 的类注释）。
        rawResult = SafeCollections.map(rawResult);
        steps = SafeCollections.list(steps);
    }

    public boolean hasDiagnosis() {
        return diagnosis != null;
    }

    public boolean failed() {
        return status == DiagnosisStatus.FAILED;
    }

    /** 一行摘要（写日志与 {@code /api/ai/diagnosis} 的摘要字段） */
    public String summarize() {
        StringBuilder sb = new StringBuilder(160);
        sb.append(status).append("（工具 ").append(toolCalls).append(" 次，LLM ")
                .append(llmCalls).append(" 轮，").append(elapsedMillis).append("ms）");
        if (failureReason != null && !failureReason.isBlank()) {
            sb.append(' ').append(failureReason);
        }
        if (diagnosis != null) {
            sb.append(" 根因=").append(abbreviate(diagnosis.rootCause()))
                    .append(" 置信度=").append(diagnosis.confidenceText())
                    .append(" 证据=").append(diagnosis.evidence().size()).append(" 条");
        }
        return sb.toString();
    }

    /** 组装 {@code raw_result} 的公共部分，避免三处各写一遍导致字段名分叉 */
    public static Map<String, Object> rawResultOf(String model, String finishReason,
                                                  int llmCalls, int toolCalls, String rawContent) {
        Map<String, Object> map = new LinkedHashMap<>(10);
        map.put("model", model);
        map.put("finishReason", finishReason);
        map.put("llmCalls", llmCalls);
        map.put("toolCalls", toolCalls);
        if (rawContent != null) {
            // 【为什么原始正文放在一个键里而不是当成整个 raw_result】
            // 因为它常常不是合法 JSON（被截断、带散文）。MySQL 的 JSON 列会直接
            // 拒绝写入，而拒绝的后果是「诊断结果整条丢失」—— 恰好丢掉最该复查的那一条。
            map.put("rawContent", rawContent);
        }
        return map;
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replace('\n', ' ').trim();
        return oneLine.length() <= 120 ? oneLine : oneLine.substring(0, 120) + "…";
    }
}
