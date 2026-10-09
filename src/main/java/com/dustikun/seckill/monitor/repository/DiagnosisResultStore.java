package com.dustikun.seckill.monitor.repository;

import com.dustikun.seckill.monitor.analyzer.Diagnosis;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code ai_diagnosis_result} 的读写（SPEC 第 14.2 节）。
 *
 * <p>与 task 是 1 : N 而不是 1 : 1，这是刻意的（建表语句里写了理由）：
 * 同一次事故在「证据不足」之后可能被重新诊断，两次结论都保留 ——
 * 丢掉「Agent 第一次为什么没判断出来」这条信息，等于丢掉了调 prompt 最有价值的输入。
 */
public interface DiagnosisResultStore {

    /**
     * 落一条诊断结论。
     *
     * @param taskId    任务 id
     * @param diagnosis 解析并规整后的结论（可能来自「证据不足」的路径 ——
     *                  那也要存：它记录的是「Agent 当时是怎么想的」）
     * @param rawResult 原始返回。<b>刻意不是 {@code diagnosis.rawContent()}</b>：
     *                  Schema 校验失败时 {@code diagnosis} 根本不存在，
     *                  而那时唯一能落的就是原始正文。因此这一项由调用方决定，
     *                  允许是「原始正文包成的 JSON」或「失败原因对象」
     */
    void save(long taskId, Diagnosis diagnosis, Map<String, Object> rawResult, Instant now);

    /**
     * 落一条「没有结论」的记录。
     * <p>【为什么这个入口是必需的】诊断失败（LLM 不可达、超时、输出不是 JSON）时，
     * 如果什么都不写，{@code ai_diagnosis_result} 就没有这一行 ——
     * 于是查询 API 只能回答「这个任务没有结果」，而无法回答
     * 「它是失败了，还是还在跑」。这两件事对运维是相反的：前者要去看配置，后者要等。
     *
     * <p>【为什么不把失败原因写进 {@code root_cause}】因为那一列的含义是
     * 「Agent 判断的根因」，而失败时 Agent <b>根本没有给出根因</b>。
     * 把「未配置 LLM 密钥」写进根因，会让按根因做的统计与检索从此混入运维信息；
     * 而人读这份记录时也会误以为 Agent 曾经有过结论。
     * 因此约定：{@code root_cause} / {@code severity} / {@code confidence} / {@code impact} /
     * {@code evidence} / {@code suggestion} 全部留 NULL，
     * 原因写进 {@code raw_result} 的 {@code failure} 键。
     */
    void saveFailure(long taskId, String reason, Map<String, Object> rawResult, Instant now);

    List<DiagnosisResultRow> findByTaskId(long taskId);

    /** 最新一条结论（查询 API 展示的那一条） */
    Optional<DiagnosisResultRow> findLatestByTaskId(long taskId);
}
