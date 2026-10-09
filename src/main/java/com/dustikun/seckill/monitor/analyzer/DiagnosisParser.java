package com.dustikun.seckill.monitor.analyzer;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.dustikun.seckill.monitor.core.Severity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 把模型返回的正文解析成 {@link Diagnosis}，并执行 SPEC 第 11 节的 Schema 校验。
 *
 * <h2>校验分两层，这是本类最重要的一个设计决定</h2>
 * <p>
 * <b>第一层（硬性，违反即抛 {@link DiagnosisParseException}）</b>：JSON 必须能解析出来，
 * 且必须包含 {@code root_cause}、{@code evidence}、{@code suggestions}。
 * 这三样是 SPEC 第 10 节诊断规则第 8 条明写的「最终输出必须包含」的东西 ——
 * 缺任何一样，这份输出就<b>不是一个诊断</b>，只是一段散文。
 * <p>
 * <b>第二层（软性，违反则记 {@code note} 并回落）</b>：{@code severity} / {@code confidence} /
 * {@code impact} / {@code symptom} 缺失或类型不符。
 * <p>
 * 【为什么第二层不能一起抛】因为它会把一份<b>有价值的根因</b>扔掉，
 * 只因为模型把 {@code confidence} 写成了 {@code "high"}。而一次诊断要花掉
 * 3~5 轮 LLM 往返与真实的工具查询 —— 为了一个可回落的字段丢掉全部结论，
 * 是「宁可什么都不说」的另一种形式，它同样没有产出。
 * 反过来，第二层的每一次回落都会被写进 {@link Diagnosis#notes()}，
 * 因此「模型在哪里偏离了 Schema」依然完全可见。
 *
 * <h2>两个必须容忍的现实</h2>
 * <ol>
 *   <li><b>正文常常带 Markdown 围栏</b>（{@code ```json ... ```}）。这不是模型的错，
 *       很多系统提示都会诱导它这么做。见 {@link #extractJsonObject(String)}。</li>
 *   <li><b>正文可能被 {@code max_tokens} 截断</b>，于是 JSON 不闭合。
 *       此时必须报「找不到完整的 JSON 对象」而不是「语法错误」——
 *       前者的处理是「调大 max_tokens 或让它少写点」，后者是「调 prompt」。</li>
 * </ol>
 */
public final class DiagnosisParser {

    /**
     * 列表字段（证据 / 建议）最多保留的条数。
     * <p>它们最终会写进 {@code ai_diagnosis_result} 的 JSON 列并出现在查询 API 上，
     * 无上限意味着一次「模型把 200 条日志粘进证据」就能把响应撑到几 MB。
     */
    public static final int MAX_LIST_ITEMS = 20;

    /** 单条证据/建议的最大字符数。超出即截断并记 note */
    public static final int MAX_ITEM_CHARS = 1000;

    /**
     * 最多尝试解析多少个花括号候选片段。
     * <p>见 {@link #extractJsonObject(String)}：每个 '{' 都配平的话扫描会退化成 O(n²)，
     * 而这个方法是每次诊断必经的热路径。</p>
     */
    static final int MAX_CANDIDATES = 12;

    private final ObjectMapper objectMapper;

    public DiagnosisParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * @param content          模型给出的正文（可能带围栏、可能被截断）
     * @param defaults         权威默认值，见 {@link DiagnosisDefaults}
     * @param minEvidenceCount 判定证据是否充分所需的条数（不影响抛不抛异常，见 {@link Diagnosis#evidenceSufficient}）
     */
    public Diagnosis parse(String content, DiagnosisDefaults defaults, int minEvidenceCount) {
        if (content == null || content.isBlank()) {
            throw new DiagnosisParseException("模型没有返回任何正文（content 为空）", content);
        }

        String json = extractJsonObject(content);
        if (json == null) {
            throw new DiagnosisParseException(extractionFailureMessage(content), content);
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (JacksonException e) {
            // extractJsonObject 已经保证候选能被解析，因此这里理论上不可达。
            // 留一条明确的路径是为了「将来有人改了抽取策略」时不出现裸的解析异常。
            throw new DiagnosisParseException("正文不是合法 JSON：" + e.getMessage(), content);
        }
        if (root == null || !root.isObject()) {
            throw new DiagnosisParseException("正文不是 JSON 对象", content);
        }

        List<String> notes = new ArrayList<>();

        // ---------- 第一层：硬性字段 ----------
        String rootCause = text(root, "root_cause");
        if (rootCause.isBlank()) {
            throw new DiagnosisParseException(
                    "缺少根因字段 root_cause（SPEC 第 10 节规则 8：最终输出必须包含根因、证据、影响和建议）",
                    content);
        }

        List<String> evidence = textList(root.path("evidence"), "evidence", notes);
        if (evidence.isEmpty()) {
            throw new DiagnosisParseException(
                    "缺少证据字段 evidence（SPEC 第 10 节规则 1：不允许在没有证据的情况下直接下结论）",
                    content);
        }

        List<String> suggestions = textList(root.path("suggestions"), "suggestions", notes);
        if (suggestions.isEmpty()) {
            // 兼容模型写成单数的情况：spec 里是 suggestions，但 model 偶尔写 suggestion
            suggestions = textList(root.path("suggestion"), "suggestion", notes);
        }
        if (suggestions.isEmpty()) {
            throw new DiagnosisParseException(
                    "缺少处理建议字段 suggestions（SPEC 第 10 节规则 8）", content);
        }

        // ---------- 第二层：可回落字段 ----------
        Severity severity = readSeverity(root, defaults, notes);
        Double confidence = readConfidence(root.path("confidence"), notes);
        Map<String, Object> impact = readImpact(root.path("impact"), notes);

        String incidentId = defaults.incidentId();
        String modelIncidentId = text(root, "incident_id");
        if (!modelIncidentId.isBlank() && !modelIncidentId.equals(incidentId)) {
            notes.add("模型给出的 incident_id（" + abbreviate(modelIncidentId)
                    + "）与本次任务（" + abbreviate(incidentId) + "）不一致，已以任务为准");
        }

        String service = defaults.service();
        String modelService = text(root, "service");
        if (!modelService.isBlank() && !service.isBlank() && !modelService.equals(service)) {
            notes.add("模型给出的 service（" + abbreviate(modelService)
                    + "）与告警来源（" + abbreviate(service) + "）不一致，已以告警为准");
        }

        String symptom = text(root, "symptom");
        if (symptom.isBlank()) {
            symptom = defaults.symptom();
            if (!symptom.isBlank()) {
                notes.add("模型未给出 symptom，已回落到告警描述");
            }
        }

        if (evidence.size() < Math.max(1, minEvidenceCount)) {
            // 这不是解析失败，但值得在 notes 里点明：调用方会据此落 INSUFFICIENT_EVIDENCE，
            // 而写明「差多少」能让调 prompt 的人一眼看到差距。
            notes.add("证据仅 " + evidence.size() + " 条，少于要求的最小独立证据数 "
                    + Math.max(1, minEvidenceCount) + " 条");
        }

        return new Diagnosis(incidentId, severity.name(), service, symptom, rootCause,
                confidence, impact, evidence, suggestions, notes, content);
    }

    // ================================================================ 字段读取

    /**
     * 读取严重的级别：模型 → 告警级别 → {@code UNKNOWN}。
     * <p>{@code ai_diagnosis_task.severity} 是 {@code NOT NULL}，
     * 因此这条回落链的终点必须是一个真实的值，「空」不是选项。
     */
    private static Severity readSeverity(JsonNode root, DiagnosisDefaults defaults, List<String> notes) {
        Optional<Severity> fromModel = Severity.tryParse(text(root, "severity"));
        if (fromModel.isPresent()) {
            return fromModel.get();
        }
        Optional<Severity> fromAlert = Severity.tryParse(defaults.severity());
        if (fromAlert.isPresent()) {
            notes.add("模型未给出可识别的 severity，已回落到告警级别 " + fromAlert.get());
            return fromAlert.get();
        }
        notes.add("模型与告警都没有给出可识别的 severity，已记为 UNKNOWN");
        return Severity.UNKNOWN;
    }

    /**
     * 读取并规整置信度到 {@code [0,1]}。
     *
     * <table border="1">
     *   <caption>规整规则</caption>
     *   <tr><th>模型返回</th><th>结果</th><th>理由</th></tr>
     *   <tr><td>{@code 0.92}</td><td>0.92</td><td>正常</td></tr>
     *   <tr><td>{@code 1}</td><td>1.0</td><td>上界在区间内</td></tr>
     *   <tr><td>{@code 95}</td><td>0.95 + note</td><td>把百分数当小数，是实测最常见的偏离</td></tr>
     *   <tr><td>{@code -1} / {@code 200} / {@code NaN} / 非数字</td><td><b>null</b> + note</td>
     *       <td>解释不了就不猜。存 NULL 比存一个「看起来合理但错」的值安全</td></tr>
     * </table>
     *
     * <p>【为什么 {@code >1 且 ≤100} 判为百分数而不是「截断成 1.0」】因为
     * {@code confidence: 95} 的意图是明确的（95%），把它截断成 1.0 会让一条
     * 其实很勉强的结论看起来是「绝对确定」—— 那正好是诊断场景下最不能犯的错。
     */
    private static Double readConfidence(JsonNode node, List<String> notes) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            notes.add("模型未给出 confidence，已记为 NULL（不猜）");
            return null;
        }
        Double value = toDouble(node);
        if (value == null || value.isNaN() || value.isInfinite()) {
            notes.add("confidence 不是可解释的数字（" + abbreviate(node.toString()) + "），已记为 NULL");
            return null;
        }
        if (value >= 0.0 && value <= 1.0) {
            return value;
        }
        if (value > 1.0 && value <= 100.0) {
            double normalized = value / 100.0;
            notes.add("confidence=" + value + " 看起来是百分数，已规整为 " + normalized);
            return normalized;
        }
        notes.add("confidence=" + value + " 超出 [0,1] 且不像百分数，已记为 NULL（不猜）");
        return null;
    }

    /**
     * 读取影响范围。
     * <p>宽容三种形态：对象（正常）、字符串（模型只写了一句话）、缺失。
     * 字符串与缺失都会记 note —— 因为 SPEC 第 11 节要求的
     * {@code {success_rate_change, affected_requests}} 是有结构的，
     * 而「变成一句话」这件事应当可见。
     */
    private Map<String, Object> readImpact(JsonNode node, List<String> notes) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            notes.add("模型未给出 impact");
            return Map.of();
        }
        if (node.isObject()) {
            try {
                Map<String, Object> converted = objectMapper.convertValue(
                        node, new TypeReference<LinkedHashMap<String, Object>>() { });
                return converted == null ? Map.of() : converted;
            } catch (RuntimeException e) {
                notes.add("impact 无法转换为对象：" + e.getMessage());
                return Map.of();
            }
        }
        if (node.isTextual()) {
            String summary = node.asString().trim();
            notes.add("impact 是字符串而不是对象，已包成 {summary}");
            return summary.isEmpty() ? Map.of() : Map.of("summary", summary);
        }
        notes.add("impact 既不是对象也不是字符串，已包成 {value}");
        return Map.of("value", node.toString());
    }

    /**
     * 读取一个字符串列表。
     * <p>【为什么对象元素要序列化成 JSON 字符串而不是丢掉】SPEC 第 11 节的示例里
     * {@code evidence} 是纯字符串数组，但真实模型经常给
     * {@code {"metric":"db_p99","value":1900}} —— 那是一份<b>更好</b>的证据
     * （结构化、可核对）。把它丢掉等于因为「格式不合我意」而扔掉模型做对的事。
     */
    private List<String> textList(JsonNode node, String fieldName, List<String> notes) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return List.of();
        }
        if (node.isTextual()) {
            String single = node.asString().trim();
            if (single.isEmpty()) {
                return List.of();
            }
            notes.add(fieldName + " 是单个字符串而不是数组，已当成一条处理");
            return List.of(truncateItem(single, fieldName));
        }
        if (!node.isArray()) {
            notes.add(fieldName + " 不是数组（实际是 " + node.getNodeType() + "），已忽略");
            return List.of();
        }

        List<String> items = new ArrayList<>(Math.min(node.size(), MAX_LIST_ITEMS));
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        boolean truncatedItems = false;
        boolean deduped = false;
        for (JsonNode element : node) {
            String text = elementToString(element);
            if (text.isBlank()) {
                continue;
            }
            String item = truncateItem(text, fieldName);
            if (item.length() < text.length()) {
                truncatedItems = true;
            }
            if (!seen.add(item)) {
                deduped = true;
                continue;
            }
            if (items.size() >= MAX_LIST_ITEMS) {
                notes.add(fieldName + " 超过 " + MAX_LIST_ITEMS + " 条，多出的已丢弃");
                break;
            }
            items.add(item);
        }
        if (deduped) {
            notes.add(fieldName + " 里有完全重复的条目，已去重");
        }
        if (truncatedItems) {
            notes.add(fieldName + " 里有超过 " + MAX_ITEM_CHARS + " 字符的条目，已截断");
        }
        return items;
    }

    private String elementToString(JsonNode element) {
        if (element == null || element.isNull()) {
            return "";
        }
        if (element.isTextual()) {
            return element.asString().trim();
        }
        if (element.isValueNode()) {
            return element.asString().trim();
        }
        try {
            return objectMapper.writeValueAsString(element).trim();
        } catch (JacksonException e) {
            return element.toString().trim();
        }
    }

    private static String truncateItem(String text, String fieldName) {
        if (text.length() <= MAX_ITEM_CHARS) {
            return text;
        }
        return text.substring(0, MAX_ITEM_CHARS) + "…";
    }

    private static String text(JsonNode root, String field) {
        JsonNode node = root.path(field);
        if (node.isTextual()) {
            return node.asString().trim();
        }
        // 数字/布尔也接受：模型把 affected_requests 写成数字时，
        // asString() 给出的是它的文本形态，这正是我们要的。
        if (node.isNumber() || node.isBoolean()) {
            return node.asString().trim();
        }
        return "";
    }

    private static Double toDouble(JsonNode node) {
        if (node.isNumber()) {
            return node.asDouble();
        }
        if (node.isTextual()) {
            String raw = node.asString().trim().replace("%", "");
            try {
                return Double.parseDouble(raw);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static String abbreviate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 120 ? value : value.substring(0, 120) + "…";
    }

    // ================================================================ JSON 抽取

    /**
     * 从模型正文里抽出那段 JSON 对象。
     *
     * <h2>为什么是「候选列表 + 打分挑选」，而不是「取第一个配平的对象」</h2>
     * <p>
     * 因为第一版就是后者，而它在两条极常见的输入上失败：
     * <ol>
     *   <li><b>散文里有配平的花括号</b>：
     *     <pre>
     *       先说明一下：我把 {stockId} 作为输入。
     *       {"root_cause": "...", "evidence": [...]}
     *     </pre>
     *     取第一个配平对象得到 {@code {stockId}} —— 它不是合法 JSON，解析失败，
     *     而错误消息指向的是那段<b>无关的散文</b>；</li>
     *   <li><b>模型先给一段 schema 示例再给真实答案</b>：
     *     <pre>
     *       格式示例：{"root_cause": "...", "evidence": []}
     *       我的结论是：{"root_cause": "MySQL 慢 SQL", "evidence": [...]}
     *     </pre>
     *     示例本身是<b>合法 JSON 对象</b>，于是它会被选中，接着在
     *     「evidence 为空」上被拒绝 —— 一份完整的正确结论就这样被丢掉了。</li>
     * </ol>
     *
     * <p>因此这里按「像不像一份完整答案」分三轮挑（见 {@link #looksComplete}）：
     * 先要有 {@code root_cause} 且 {@code evidence} 非空；退一步只要有 {@code root_cause}；
     * 再退一步只要是 JSON 对象。这个顺序把「示例」与「散文花括号」都排到了真实答案后面，
     * 同时保留了「实在挑不出来就用第一个」的兜底 ——
     * 因为「挑错了」至少还能在下游被 Schema 校验拒绝并留下原文，
     * 而「一个都不挑」等于直接放弃。
     *
     * <p>候选数量有上限（{@link #MAX_CANDIDATES}）：正文长度本身由
     * {@code llm.max-tokens} 兜底，但「每个 '{' 都配平」的病态输入会退化成
     * O(n²) 扫描，而这个方法是每次诊断都要跑的热路径。
     *
     * @return 挑中的候选文本；一个候选都没解析成功时返回 {@code null}
     */
    String extractJsonObject(String text) {
        List<Candidate> candidates = new ArrayList<>(4);
        for (String raw : jsonCandidates(text)) {
            try {
                JsonNode node = objectMapper.readTree(raw);
                if (node != null && node.isObject()) {
                    candidates.add(new Candidate(raw, node));
                }
            } catch (JacksonException ignored) {
                // 不是合法 JSON 的候选直接跳过 —— 这正是本方法存在的理由
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        for (Candidate candidate : candidates) {
            if (looksComplete(candidate.node())) {
                return candidate.text();
            }
        }
        for (Candidate candidate : candidates) {
            if (hasRootCause(candidate.node())) {
                return candidate.text();
            }
        }
        return candidates.get(0).text();
    }

    /**
     * 一个候选片段。
     * <p>【为什么用 record 而不是两个平行 list】平行 list 会让「片段与解析结果」
     * 靠下标对齐，而一旦有一处 {@code continue} 忘了同步，两者就会错位 ——
     * 那种缺陷的表现是「挑了 A 的文本去构造 B 的结论」，极难发现。
     */
    private record Candidate(String text, JsonNode node) {
    }

    /** 「像一份完整答案」的判据：有根因，且证据列表非空 */
    private static boolean looksComplete(JsonNode node) {
        JsonNode evidence = node.path("evidence");
        return hasRootCause(node) && evidence.isArray() && !evidence.isEmpty();
    }

    private static boolean hasRootCause(JsonNode node) {
        JsonNode rootCause = node.path("root_cause");
        return rootCause.isTextual() && !rootCause.asString().isBlank();
    }

    /**
     * 抽取失败时的原因。
     * <p>它区分两种完全不同的情况，因为处理方向相反：
     * <ul>
     *   <li><b>一段花括号都没有</b>：被截断，或者模型返回了纯散文 →
     *       要动 {@code max-tokens} 或 prompt；</li>
     *   <li><b>有花括号但都不是合法 JSON</b>：模型在 JSON 里拼错了东西
     *       （中文引号、多余逗号）→ 要动 prompt 里的格式要求。
     *       这时把首个片段附上，因为它是「模型实际写了什么」的最短证据。</li>
     * </ul>
     */
    private String extractionFailureMessage(String content) {
        List<String> candidates = jsonCandidates(content);
        if (candidates.isEmpty()) {
            return "正文里找不到完整的 JSON 对象（常见原因：被 max_tokens 截断，或模型返回了纯散文）";
        }
        return "正文里有 " + candidates.size() + " 段花括号片段，但没有一段是合法的 JSON 对象；"
                + "首个片段：" + abbreviate(candidates.get(0));
    }

    /**
     * 收集所有候选 JSON 片段。
     * <p>顺序是刻意的：<b>先围栏内、再全文</b>。带围栏时扫全文<em>也</em>能找到对象，
     * 但围栏里可能还有一段<b>示例 JSON</b>（模型先给 schema 片段再给真实答案）——
     * 先锁定围栏把这种误取的概率降到最低。
     */
    private static List<String> jsonCandidates(String text) {
        String trimmed = text.trim();
        List<String> candidates = new ArrayList<>(4);

        int firstFence = trimmed.indexOf("```");
        if (firstFence >= 0) {
            int contentStart = firstFence + 3;
            // 跳过同一行上的语言标记（```json）
            int lineEnd = trimmed.indexOf('\n', contentStart);
            if (lineEnd >= 0 && lineEnd - contentStart <= 12) {
                contentStart = lineEnd + 1;
            }
            int closingFence = trimmed.lastIndexOf("```");
            if (closingFence > contentStart) {
                addBalancedObjects(trimmed.substring(contentStart, closingFence), candidates);
            }
        }
        addBalancedObjects(trimmed, candidates);

        List<String> distinct = new ArrayList<>(candidates.size());
        for (String candidate : candidates) {
            if (!distinct.contains(candidate)) {
                distinct.add(candidate);
            }
        }
        return distinct;
    }

    /** 从 {@code text} 里按出现顺序取出所有「外层配平」的花括号片段 */
    private static void addBalancedObjects(String text, List<String> out) {
        int searchFrom = 0;
        while (out.size() < MAX_CANDIDATES) {
            int start = text.indexOf('{', searchFrom);
            if (start < 0) {
                return;
            }
            String candidate = firstBalancedObject(text.substring(start));
            if (candidate == null) {
                // 从这里开始没有配平的对象（被截断）。后面也不可能有了。
                return;
            }
            out.add(candidate);
            searchFrom = start + candidate.length();
        }
    }

    /**
     * 扫描第一个花括号配平的对象。
     *
     * <p>【为什么不能简单地 {@code indexOf('{')} + {@code lastIndexOf('}')}】
     * 因为正文里常常在 JSON 之后还有一段解释文字，而解释里可能出现花括号
     * （例如「建议把 {@code {stockId}} 参数……」）。那样取出来的串在
     * {@code readTree} 时会报语法错，而错误位置指向的是一段无关的文字。
     *
     * <p>扫描器必须理解字符串字面量与转义，否则 {@code {"note":"}"}}
     * 会在那个 {@code } 处提前收尾。
     */
    static String firstBalancedObject(String text) {
        int start = text.indexOf('{');
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"' -> inString = true;
                case '{' -> depth++;
                case '}' -> {
                    if (depth == 0) {
                        // 前面有多余的 '}'，说明这段不是我们要的对象
                        return null;
                    }
                    depth--;
                    if (depth == 0) {
                        return text.substring(start, i + 1);
                    }
                }
                default -> {
                    // 其它字符不改变配平状态
                }
            }
        }
        // 没闭合 = 被截断
        return null;
    }
}
