package com.dustikun.seckill.monitor.analyzer;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Diagnosis 解析与 Schema 校验（SPEC 第 11 节 + 可行性报告验收判据 3.4）。
 *
 * <h2>这一组用例为什么值得写这么多条</h2>
 * <p>
 * 因为这一层的<b>每一个分支都对应真实模型的某一种偏离</b>，而它们的处理方式相差很大：
 * <pre>
 *   带 Markdown 围栏         → 必须容忍（系统提示诱导它这么写）
 *   被 max_tokens 截断       → 必须报「找不到完整 JSON」而不是「语法错误」
 *   少了 root_cause          → 拒绝（这不是一个诊断）
 *   少了 confidence          → 容忍 + 记 note（为它丢掉一份真根因不值得）
 *   confidence=95            → 规整成 0.95 + 记 note
 *   confidence=-1 / "high"   → 存 NULL（不猜）
 *   证据少于 2 条            → 接受结论，但结局是 INSUFFICIENT_EVIDENCE
 * </pre>
 * 写死这些行为，是「模型输出不稳定」这条风险（可行性报告 R4）唯一的防线。
 */
class DiagnosisParserTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DiagnosisParser parser = new DiagnosisParser(MAPPER);

    private static final DiagnosisDefaults DEFAULTS =
            new DiagnosisDefaults("INC-20261009-001", "order-service", "HIGH", "订单接口 P99 升高");

    private static final int MIN_EVIDENCE = 2;

    private Diagnosis parse(String json) {
        return parser.parse(json, DEFAULTS, MIN_EVIDENCE);
    }

    private static String fullJson() {
        return """
                {
                  "incident_id": "INC-20261009-001",
                  "severity": "HIGH",
                  "service": "order-service",
                  "symptom": "订单创建接口 P99 从 120ms 升高到 2.1s",
                  "root_cause": "MySQL 慢 SQL",
                  "confidence": 0.92,
                  "impact": {"success_rate_change": -5.7, "affected_requests": 18231},
                  "evidence": ["DB P99 从 20ms 上升到 1900ms", "慢 SQL 数量增加 8 倍",
                               "Redis latency 正常", "MQ consumer lag 正常"],
                  "suggestions": ["检查订单查询 SQL 是否命中索引", "检查数据库连接池"]
                }
                """;
    }

    // ================================================================ 正常路径

    @Test
    @DisplayName("SPEC 第 11 节的完整 JSON 逐字段还原")
    void parsesSpecShape() {
        Diagnosis diagnosis = parse(fullJson());

        assertEquals("INC-20261009-001", diagnosis.incidentId());
        assertEquals("HIGH", diagnosis.severity());
        assertEquals("order-service", diagnosis.service());
        assertEquals("MySQL 慢 SQL", diagnosis.rootCause());
        assertEquals(0.92, diagnosis.confidence());
        assertEquals(-5.7, diagnosis.impact().get("success_rate_change"));
        assertEquals(4, diagnosis.evidence().size());
        assertEquals(2, diagnosis.suggestions().size());
        assertTrue(diagnosis.evidenceSufficient(2));
        assertTrue(diagnosis.notes().isEmpty(), "完全没有偏离时不该有任何 note：" + diagnosis.notes());
    }

    @Test
    @DisplayName("带 Markdown 围栏的正文能被正确剥离")
    void stripsMarkdownFence() {
        Diagnosis diagnosis = parse("好的，这是我的结论：\n```json\n" + fullJson() + "\n```\n以上。");
        assertEquals("MySQL 慢 SQL", diagnosis.rootCause());
        assertEquals(4, diagnosis.evidence().size());
    }

    @Test
    @DisplayName("★ 散文里也有花括号时，取的是第一个**能解析成 JSON 对象**的片段")
    void picksFirstParsableObjectNotFirstBalancedOne() {
        // 这一条是本类的核心回归用例：初版取「第一个配平的对象」，
        // 于是拿到的是散文里的 {stockId}，报错说「正文不是合法 JSON」，
        // 而那个错误指向的是一段完全无关的文字。
        Diagnosis diagnosis = parse("先说明一下：我把 {stockId} 作为输入。\n"
                + fullJson()
                + "\n建议把 {stockId} 参数保留。");
        assertEquals("MySQL 慢 SQL", diagnosis.rootCause());
        assertEquals(4, diagnosis.evidence().size());
    }

    @Test
    @DisplayName("模型先给一段 schema 示例再给真实答案时，跳过示例取真实的那一个")
    void skipsExampleSchemaBeforeRealAnswer() {
        Diagnosis diagnosis = parse("格式示例：{\"root_cause\": \"...\", \"evidence\": []}\n"
                + "我的结论是：" + fullJson());
        assertEquals("MySQL 慢 SQL", diagnosis.rootCause());
        assertEquals(4, diagnosis.evidence().size());
    }

    @Test
    @DisplayName("有花括号但全都不是合法 JSON 时，错误消息要说清「片段都不是合法 JSON」并给出首个片段")
    void reportsWhenNoBraceFragmentIsValidJson() {
        DiagnosisParseException e = assertThrows(DiagnosisParseException.class,
                () -> parse("我把 {stockId} 作为输入，然后 {userId} 也不对。"));
        assertTrue(e.getMessage().contains("没有一段是合法的 JSON 对象"), e.getMessage());
        assertTrue(e.getMessage().contains("stockId"), "要给出首个候选片段：" + e.getMessage());
    }

    @Test
    @DisplayName("字符串值里出现花括号不会让扫描提前收尾")
    void handlesBracesInsideString() {
        Diagnosis diagnosis = parse("""
                {"root_cause": "配置项 {a:{b}} 写错了",
                 "evidence": ["看到 } 出现在日志里", "第二条"],
                 "suggestions": ["检查配置"]}
                """);
        assertEquals("配置项 {a:{b}} 写错了", diagnosis.rootCause());
        assertEquals(2, diagnosis.evidence().size());
    }

    // ================================================================ 硬性字段

    @Test
    @DisplayName("缺少 root_cause → 拒绝（这不是一个诊断，只是一段散文）")
    void requiresRootCause() {
        DiagnosisParseException e = assertThrows(DiagnosisParseException.class, () -> parse("""
                {"evidence": ["a", "b"], "suggestions": ["x"]}
                """));
        assertTrue(e.getMessage().contains("root_cause"), e.getMessage());
        assertNotNull(e.rawContent(), "原始正文必须被带出来，供落库复查");
    }

    @Test
    @DisplayName("缺少 evidence → 拒绝（SPEC 第 10 节规则 1：不允许没有证据就下结论）")
    void requiresEvidence() {
        DiagnosisParseException e = assertThrows(DiagnosisParseException.class, () -> parse("""
                {"root_cause": "MySQL 慢 SQL", "suggestions": ["x"]}
                """));
        assertTrue(e.getMessage().contains("evidence"), e.getMessage());
    }

    @Test
    @DisplayName("evidence 是空数组 → 同样拒绝")
    void emptyEvidenceIsRejected() {
        assertThrows(DiagnosisParseException.class, () -> parse("""
                {"root_cause": "x", "evidence": [], "suggestions": ["y"]}
                """));
    }

    @Test
    @DisplayName("缺少 suggestions → 拒绝；但模型写成单数 suggestion 时容忍")
    void requiresSuggestionsWithSingularFallback() {
        assertThrows(DiagnosisParseException.class, () -> parse("""
                {"root_cause": "x", "evidence": ["a", "b"]}
                """));

        Diagnosis diagnosis = parse("""
                {"root_cause": "x", "evidence": ["a", "b"], "suggestion": ["检查索引"]}
                """);
        assertEquals(List.of("检查索引"), diagnosis.suggestions());
    }

    @Test
    @DisplayName("正文被截断（JSON 不闭合）→ 报「找不到完整的 JSON 对象」，而不是「语法错误」")
    void truncatedJsonIsReportedAsIncomplete() {
        String truncated = fullJson().substring(0, fullJson().length() / 2);
        DiagnosisParseException e = assertThrows(DiagnosisParseException.class, () -> parse(truncated));
        assertTrue(e.getMessage().contains("找不到完整的 JSON 对象"), e.getMessage());
        assertTrue(e.getMessage().contains("max_tokens"), "要指向最可能的原因：" + e.getMessage());
    }

    @Test
    @DisplayName("正文是纯散文 → 拒绝，并且原因指向「找不到 JSON 对象」")
    void proseOnlyIsRejected() {
        DiagnosisParseException e = assertThrows(DiagnosisParseException.class,
                () -> parse("我觉得是数据库的问题，但没有拿到证据。"));
        assertTrue(e.getMessage().contains("找不到完整的 JSON 对象"), e.getMessage());
    }

    @Test
    @DisplayName("空正文与 null 正文分别给出可区分的原因")
    void blankContentIsRejected() {
        assertThrows(DiagnosisParseException.class, () -> parse(""));
        assertThrows(DiagnosisParseException.class, () -> parse(null));
    }

    // ================================================================ confidence 规整

    @Test
    @DisplayName("★ confidence=95 判为百分数 → 0.95（截断成 1.0 会让勉强的结论看起来是绝对确定）")
    void normalizesPercentageConfidence() {
        Diagnosis diagnosis = parse(withConfidence("95"));
        assertEquals(0.95, diagnosis.confidence());
        assertTrue(diagnosis.notes().stream().anyMatch(n -> n.contains("百分数")), diagnosis.notes().toString());
    }

    @Test
    @DisplayName("★ confidence 解释不了时存 NULL，绝不存 0：0 是「完全不确定」，与「缺数据」相反")
    void uninterpretableConfidenceBecomesNull() {
        for (String raw : new String[] {"-1", "200", "\"high\"", "\"abc\""}) {
            Diagnosis diagnosis = parse(withConfidence(raw));
            assertNull(diagnosis.confidence(), "confidence=" + raw + " 不该被猜成一个数字");
            assertTrue(diagnosis.notes().stream().anyMatch(n -> n.contains("confidence")),
                    "必须留下说明：" + diagnosis.notes());
        }
    }

    @Test
    @DisplayName("confidence 缺失 → NULL + note（不因为一个可回落的字段丢掉整份根因）")
    void missingConfidenceBecomesNullWithNote() {
        Diagnosis diagnosis = parse("""
                {"root_cause": "MySQL 慢 SQL", "evidence": ["a", "b"], "suggestions": ["x"]}
                """);
        assertNull(diagnosis.confidence());
        assertEquals("未提供", diagnosis.confidenceText());
        assertTrue(diagnosis.notes().stream().anyMatch(n -> n.contains("未给出 confidence")),
                diagnosis.notes().toString());
    }

    @Test
    @DisplayName("confidence=1 与 0.0 都在区间内，原样保留")
    void boundaryConfidencesAreKept() {
        assertEquals(1.0, parse(withConfidence("1")).confidence());
        assertEquals(0.0, parse(withConfidence("0.0")).confidence());
    }

    // ================================================================ severity / 权威字段

    @Test
    @DisplayName("severity 大小写归一；识别不了时回落到告警级别并记 note")
    void normalizesSeverityWithFallback() {
        assertEquals("CRITICAL", parse(withSeverity("\"critical\"")).severity());
        // FATAL 是上游常见的别名
        assertEquals("CRITICAL", parse(withSeverity("\"FATAL\"")).severity());

        Diagnosis fallback = parse(withSeverity("\"非常严重\""));
        assertEquals("HIGH", fallback.severity(), "应当回落到告警级别");
        assertTrue(fallback.notes().stream().anyMatch(n -> n.contains("severity")), fallback.notes().toString());
    }

    @Test
    @DisplayName("★ incident_id 与 service 以调度侧为权威：模型改不了这条结论挂在哪次事故上")
    void authoritativeIdentityWins() {
        Diagnosis diagnosis = parse("""
                {"incident_id": "INC-99999999-999", "service": "other-service",
                 "root_cause": "MySQL 慢 SQL", "evidence": ["a", "b"], "suggestions": ["x"]}
                """);
        assertEquals("INC-20261009-001", diagnosis.incidentId());
        assertEquals("order-service", diagnosis.service());
        assertTrue(diagnosis.notes().stream().anyMatch(n -> n.contains("incident_id")), diagnosis.notes().toString());
        assertTrue(diagnosis.notes().stream().anyMatch(n -> n.contains("service")), diagnosis.notes().toString());
    }

    @Test
    @DisplayName("模型没给 symptom 时回落到告警描述")
    void symptomFallsBackToAlertDescription() {
        Diagnosis diagnosis = parse("""
                {"root_cause": "x", "evidence": ["a", "b"], "suggestions": ["y"]}
                """);
        assertEquals("订单接口 P99 升高", diagnosis.symptom());
    }

    // ================================================================ impact 与列表的宽容处理

    @Test
    @DisplayName("impact 是字符串 → 包成 {summary}；缺失 → 空对象；两种都记 note")
    void toleratesImpactShapes() {
        Diagnosis asString = parse("""
                {"root_cause": "x", "evidence": ["a", "b"], "suggestions": ["y"],
                 "impact": "订单成功率下降 5.7%"}
                """);
        assertEquals("订单成功率下降 5.7%", asString.impact().get("summary"));
        assertTrue(asString.notes().stream().anyMatch(n -> n.contains("impact")), asString.notes().toString());

        Diagnosis missing = parse("""
                {"root_cause": "x", "evidence": ["a", "b"], "suggestions": ["y"]}
                """);
        assertTrue(missing.impact().isEmpty());
    }

    @Test
    @DisplayName("证据里出现对象元素时序列化成紧凑 JSON 保留下来（那是一份更好的证据，不该丢）")
    void keepsStructuredEvidence() {
        Diagnosis diagnosis = parse("""
                {"root_cause": "x",
                 "evidence": [{"metric": "db_p99", "value": 1900}, "慢 SQL 数量增加 8 倍"],
                 "suggestions": ["y"]}
                """);
        assertEquals(2, diagnosis.evidence().size());
        assertTrue(diagnosis.evidence().get(0).contains("db_p99"), diagnosis.evidence().get(0));
        assertTrue(diagnosis.evidence().get(0).contains("1900"), diagnosis.evidence().get(0));
    }

    @Test
    @DisplayName("重复证据被去重并记 note：换个说法算不算第二条证据，这一层先兜一道")
    void dedupesEvidence() {
        Diagnosis diagnosis = parse("""
                {"root_cause": "x",
                 "evidence": ["慢 SQL 增加", "慢 SQL 增加", "P99 升高"],
                 "suggestions": ["y"]}
                """);
        assertEquals(2, diagnosis.evidence().size());
        assertTrue(diagnosis.notes().stream().anyMatch(n -> n.contains("重复")), diagnosis.notes().toString());
    }

    @Test
    @DisplayName("单条证据超长被截断；条目数超过上限被裁剪，两件事都要记 note")
    void capsEvidenceLengthAndCount() {
        String longItem = "证".repeat(DiagnosisParser.MAX_ITEM_CHARS + 50);
        StringBuilder evidence = new StringBuilder();
        for (int i = 0; i < DiagnosisParser.MAX_LIST_ITEMS + 5; i++) {
            evidence.append(i == 0 ? "\"" + longItem + "\"" : "\"证据" + i + "\"").append(',');
        }
        evidence.setLength(evidence.length() - 1);
        Diagnosis diagnosis = parse("{\"root_cause\":\"x\",\"evidence\":[" + evidence + "],"
                + "\"suggestions\":[\"y\"]}");

        assertEquals(DiagnosisParser.MAX_LIST_ITEMS, diagnosis.evidence().size());
        assertTrue(diagnosis.notes().stream().anyMatch(n -> n.contains("超过")), diagnosis.notes().toString());
        assertTrue(diagnosis.notes().stream().anyMatch(n -> n.contains("截断")), diagnosis.notes().toString());
    }

    @Test
    @DisplayName("evidence 写成单个字符串 → 当成一条处理并记 note（不因为形状不合就丢掉）")
    void toleratesEvidenceAsSingleString() {
        Diagnosis diagnosis = parse("""
                {"root_cause": "x", "evidence": "只有一条证据", "suggestions": ["y"]}
                """);
        assertEquals(List.of("只有一条证据"), diagnosis.evidence());
        assertTrue(diagnosis.notes().stream().anyMatch(n -> n.contains("单个字符串")), diagnosis.notes().toString());
    }

    // ================================================================ 证据充分性

    @Test
    @DisplayName("★ 证据不足时仍然产出结论，但 evidenceSufficient=false（调用方据此落 INSUFFICIENT_EVIDENCE）")
    void insufficientEvidenceIsStillParsed() {
        Diagnosis diagnosis = parse("""
                {"root_cause": "可能是 MySQL", "evidence": ["只有一个现象"],
                 "suggestions": ["再查一次"], "confidence": 0.3}
                """);
        assertEquals(1, diagnosis.evidence().size());
        assertFalse(diagnosis.evidenceSufficient(MIN_EVIDENCE));
        assertTrue(diagnosis.notes().stream().anyMatch(n -> n.contains("少于要求")), diagnosis.notes().toString());
        // 结论本身要保留：它记录的是「Agent 当时怎么想的」，是调 prompt 最有价值的输入
        assertEquals("可能是 MySQL", diagnosis.rootCause());
        assertEquals(0.3, diagnosis.confidence());
    }

    // ================================================================ 测试数据构造

    /** 在完整 JSON 里替换 confidence 的值（刻意用字符串拼接，避免引号转义把用例写乱） */
    private static String withConfidence(String rawConfidence) {
        return fullJson().replace("\"confidence\": 0.92", "\"confidence\": " + rawConfidence);
    }

    private static String withSeverity(String rawSeverity) {
        return fullJson().replace("\"severity\": \"HIGH\"", "\"severity\": " + rawSeverity);
    }

    @Test
    @DisplayName("解析出的 Map 与 List 都是不可变的：调用方改不动结论")
    void resultCollectionsAreImmutable() {
        Diagnosis diagnosis = parse(fullJson());
        assertThrows(UnsupportedOperationException.class,
                () -> diagnosis.evidence().add("偷偷加一条证据"));
        assertThrows(UnsupportedOperationException.class,
                () -> diagnosis.impact().put("x", 1));
        Map<String, Object> impact = diagnosis.impact();
        assertEquals(2, impact.size());
    }
}
