package com.dustikun.seckill.monitor.analyzer;

/**
 * Diagnosis 不符合 SPEC 第 11 节的 Schema 时抛出。
 *
 * <p>它<b>不</b>表示「诊断失败」，而是表示「模型这次的输出不能用」。
 * 调用方（ReAct 循环）把它转成 {@code INSUFFICIENT_EVIDENCE} 并把
 * {@code raw_result} 落库 —— 因为「模型到底写了什么」是调 prompt 时唯一有用的信息，
 * 而一条只说「解析失败」的日志会把那份原文丢掉。
 *
 * <h2>为什么带一个 {@link #rawContent()}</h2>
 * <p>
 * 因为它与异常消息是两种用途：消息给人看（短、一句话），
 * 原文给机器落库（可能几千字符）。若只留消息，落库的那一列就只能是截断过的摘要，
 * 而截断恰好会切掉「它究竟在哪一刻偏离了 Schema」的地方。
 */
public class DiagnosisParseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient String rawContent;

    public DiagnosisParseException(String message, String rawContent) {
        super(message);
        this.rawContent = rawContent;
    }

    /** 模型给出的原始正文（可能为 {@code null}） */
    public String rawContent() {
        return rawContent;
    }
}
