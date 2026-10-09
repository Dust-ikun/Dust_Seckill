package com.dustikun.seckill.monitor.tool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次 Tool 调用的结果（对应 {@code ai_tool_execution} 的 arguments / result / status /
 * execution_time_ms / error_message 五列，外加给 LLM 的那段文本）。
 *
 * <h2>为什么结果体是 Map 而不是每个 Tool 一个 record</h2>
 * <p>
 * 结果最终要经过三道<b>与语义无关</b>的加工（脱敏 → 体积收缩 → {@code <untrusted_data>} 包裹），
 * 而这三道加工都需要「递归遍历一个结构」。给每个 Tool 定义强类型 record 之后，
 * 这三道加工要么变成 5 份重复实现，要么要先序列化成 JSON 再解析回来 ——
 * 后者会把「脱敏遗漏」的风险放在一个更难检查的位置。
 *
 * <p>代价是 map 的键没有编译期约束。对冲手段是：每个 Tool 都把返回值结构写在
 * 自己的类注释里（对齐 SPEC 第 9 节的返回示例），并由单测锁住键名 ——
 * 键名一旦变化，单元测试会失败，而那正是它该失败的地方。
 *
 * @param toolName      工具名（轨迹里用于自描述；白名单校验由 {@link ToolRegistry} 负责）
 * @param status        结局，见 {@link ToolStatus}
 * @param data          业务数据。<b>可能已被体积收缩裁剪过</b>（见 {@code truncated}）
 * @param truncated     是否发生过裁剪。为 true 时 {@link #notes()} 里必定有说明原因的一条
 * @param notes         给人和模型看的口径说明（「Prometheus 不可达，本结果为空不代表无异常」这类）
 * @param elapsedMillis 本次调用耗时。对应 SPEC 第 25 节「Tool 单次调用 &lt; 1s」的度量点
 * @param errorMessage  {@code status != SUCCESS} 时的原因；成功时为 {@code null}
 * @param llmText       真正交给 LLM 的那段文本（已脱敏、已收缩、已用
 *                      {@code <untrusted_data>} 包裹）。由 {@link ResultShaper} 填充，
 *                      未整形前为 {@code null}
 * @param arguments     落 {@code ai_tool_execution.arguments} 的值。取哪一份由
 *                      {@link ToolRegistry} 按下面的规则决定，**不是**随手记一下：
 *                      <ul>
 *                        <li><b>REJECTED（没有执行）→ 原始入参</b>。此时「校验后的值」
 *                            并不存在，而排查需要看到的恰恰是模型到底传了什么
 *                            （「它为什么被拒」的全部信息都在原始串里）；</li>
 *                        <li><b>SUCCESS / FAILED（已执行）→ {@link ToolArguments#accepted()}</b>。
 *                            那才是工具真正使用的值（含默认值），也是解释
 *                            「为什么返回这个结果」的依据。</li>
 *                      </ul>
 *                      【为什么参数不做脱敏】它是<b>模型自己的输出</b>，不是从数据源读回的内容，
 *                      而且轨迹的价值就在于「当时到底问了什么」——脱敏掉它，
 *                      {@code ai_tool_execution} 就不再可复现。真正需要脱敏的是
 *                      {@code data} / {@code llmText}（那些来自日志与数据库），
 *                      它们已经过了 {@link ResultShaper}。
 */
public record ToolResult(
        String toolName,
        ToolStatus status,
        Map<String, Object> data,
        boolean truncated,
        List<String> notes,
        long elapsedMillis,
        String errorMessage,
        String llmText,
        Map<String, Object> arguments
) {

    /**
     * 成功结果。{@code elapsedMillis} 先置 0，由 {@link ToolRegistry} 统一回填 ——
     * 计时的<b>唯一</b>位置应当在调用方，否则「工具自己计时」与「注册表计时」
     * 会给出两个不同的数字（前者漏掉了参数校验与结果整形的时间）。
     */
    public static ToolResult ok(String toolName, Map<String, Object> data, List<String> notes) {
        return new ToolResult(toolName, ToolStatus.SUCCESS,
                data == null ? Map.of() : data,
                false,
                notes == null ? List.of() : List.copyOf(notes),
                0L, null, null, Map.of());
    }

    public static ToolResult ok(String toolName, Map<String, Object> data) {
        return ok(toolName, data, List.of());
    }

    public static ToolResult failed(String toolName, String errorMessage, List<String> notes) {
        return new ToolResult(toolName, ToolStatus.FAILED, Map.of(), false,
                notes == null ? List.of() : List.copyOf(notes), 0L, errorMessage, null, Map.of());
    }

    public static ToolResult rejected(String toolName, String errorMessage) {
        return new ToolResult(toolName, ToolStatus.REJECTED, Map.of(), false,
                List.of(), 0L, errorMessage, null, Map.of());
    }

    public boolean successful() {
        return status == ToolStatus.SUCCESS;
    }

    /** 回填耗时并返回新实例（record 不可变，因此只能这样） */
    public ToolResult withElapsed(long millis) {
        return new ToolResult(toolName, status, data, truncated, notes, millis,
                errorMessage, llmText, arguments);
    }

    /**
     * 回填「落 {@code ai_tool_execution.arguments} 的那份值」。
     * <p>它由 {@link ToolRegistry} 在整形之前调用，取值规则见 {@link #arguments} 的注释。
     */
    public ToolResult withArguments(Map<String, Object> newArguments) {
        return new ToolResult(toolName, status, data, truncated, notes, elapsedMillis,
                errorMessage, llmText, newArguments == null ? Map.of() : newArguments);
    }

    /**
     * 用整形后的内容替换结果。一次调用完成整形后，这个对象就是
     * 「模型看到了什么 + 数据库里存了什么」的<b>唯一</b>一致来源 ——
     * 若两者各算一遍，复盘时就会出现「轨迹里的 JSON 与模型当时的输入不一致」，
     * 而那种不一致会让可解释性彻底失效。
     */
    public ToolResult withShaped(Map<String, Object> newData, boolean newTruncated,
                                 List<String> newNotes, String newLlmText) {
        return new ToolResult(toolName, status, newData, newTruncated, newNotes,
                elapsedMillis, errorMessage, newLlmText, arguments);
    }

    /**
     * 追加一条 note 并返回新实例。用于注册表补充「参数里有未定义字段」这类
     * 只有它才知道的信息。
     * <p>注意它<b>清空</b> {@code llmText}：notes 变了，之前渲染的文本就不再对应这个结果。
     * 整形发生在追加 note 之后（见 {@code ToolRegistry#invoke}），因此这条不会造成信息丢失。
     */
    public ToolResult withNote(String note) {
        if (note == null || note.isBlank()) {
            return this;
        }
        List<String> merged = new ArrayList<>(notes.size() + 1);
        merged.addAll(notes);
        merged.add(note);
        return new ToolResult(toolName, status, data, truncated, List.copyOf(merged),
                elapsedMillis, errorMessage, null, arguments);
    }

    /**
     * 结构化返回体（{@code <untrusted_data>} 标签<b>内部</b>的 JSON 形态，
     * 也是 {@code ai_tool_execution.result} 列的内容）。
     * <p>注意真正的渲染由 {@link ResultShaper} 负责 —— 它会把 notes 与 truncated
     * 一起编进 body。本方法保留给「不需要包裹的场合」（例如直接落库的测试）。
     * <p>【为什么 {@code arguments} 不在里面】因为它有自己的一列
     * （{@code ai_tool_execution.arguments}）。把同一份数据放进两列，
     * 某天它们不一致时就没有第二处能对照。
     */
    public Map<String, Object> envelope() {
        Map<String, Object> body = new LinkedHashMap<>(6);
        body.put("status", status.name());
        body.put("data", data);
        if (!notes.isEmpty()) {
            body.put("notes", notes);
        }
        if (truncated) {
            body.put("truncated", true);
        }
        if (errorMessage != null) {
            body.put("error", errorMessage);
        }
        body.put("elapsedMs", elapsedMillis);
        return body;
    }

    /** 供日志与测试阅读的一行摘要 */
    public String summarize() {
        if (errorMessage != null) {
            return status + " " + toolName + "：" + errorMessage + "（" + elapsedMillis + "ms）";
        }
        return status + " " + toolName + "：keys=" + data.keySet()
                + (truncated ? "（已截断）" : "") + "（" + elapsedMillis + "ms）";
    }

    /** 只读的空结果，避免各处 {@code Map.of()} 的静态导入噪音 */
    public static Map<String, Object> emptyData() {
        return Collections.emptyMap();
    }
}
