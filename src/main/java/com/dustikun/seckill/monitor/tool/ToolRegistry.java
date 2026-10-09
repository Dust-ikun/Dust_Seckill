package com.dustikun.seckill.monitor.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Tool 白名单与<b>唯一</b>执行入口（SPEC 第 23.1 / 23.2 节，验收判据「未注册的 Tool 名一律拒绝」）。
 *
 * <h2>为什么「注册表」同时必须是「执行器」</h2>
 * <p>
 * 若注册表只提供 {@code find(name)}，而执行散落在调用方（批次 3 的 ReAct 循环里），
 * 那么下面这五件事就会出现「绕过注册表」的第二条路径：
 * <pre>
 *   白名单校验      未注册的名字要拒绝
 *   参数校验        类型与范围
 *   执行超时        SPEC 第 25 节：Tool 单次调用 &lt; 1s
 *   结果体积上限    SPEC 第 23.3 节
 *   脱敏与不可信包裹 SPEC 第 17.2 / 17.3 节
 * </pre>
 * 这五件事有一个共同点：<b>它们都不是 Tool 自己的事，而是「所有 Tool 都必须遵守」的事</b>。
 * 因此正确的切分是 —— Tool 只关心「怎么取数据」，注册表关心「怎么安全地取」。
 * 这也正是 SPEC 第 22 节列出的「真实实现需要补充的 8 项」里，
 * 属于 Tool 层的那 4 项（白名单 / 参数校验 / 结果大小限制 / 日志脱敏）的落点。
 *
 * <h2>超时为什么用「虚拟线程 + Future.get」而不是给每个工具传 deadline</h2>
 * <p>
 * 给每个工具传 deadline 需要每个实现自己检查，漏一个就等于没有超时 ——
 * 而漏掉的那个必然是「将来新加的工具」。虚拟线程让「一次调用一个线程」的代价
 * 低到可以忽略（JDK 21 起是标准能力），于是超时能被<b>强制</b>执行：
 * <p>
 * 【一个必须说清的边界】{@code future.cancel(true)} 只能中断 Java 侧，
 * 底层 JDBC 查询不一定立即停止。因此超时的语义是
 * 「<b>Agent 不必再等</b>」，而不是「数据源侧的查询已经终止」。
 * 数据库侧的失控由另外两道闸门兜住：DB Tool 只跑固定的只读语句，
 * 且 JdbcTemplate 上设了 queryTimeout（见 {@code MonitorToolConfiguration}）。
 * 把这一点写在这里，是因为「超时了但线程还在跑」是一个会被误认为泄漏的现象。
 *
 * <h2>为什么工具名要归一化大小写</h2>
 * <p>
 * 模型回填函数名时大小写不稳定（{@code query_metric} / {@code Query_Metric}）。
 * 名字不是业务数据，判错了只会白白损失一次取证机会，因此按小写归一匹配；
 * 而<b>未注册的名字仍然会被拒绝</b>（错误消息里带上全部白名单），
 * 因为那通常意味着模型在编造一个不存在的工具。
 */
public final class ToolRegistry implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    /** 名字 → 工具。键统一为小写，见类注释 */
    private final Map<String, MonitorTool> byName;

    /** 注册顺序（保持配置里的声明顺序，便于启动日志与 tools 数组稳定可 diff） */
    private final List<MonitorTool> ordered;

    private final ResultShaper shaper;

    private final long timeoutMillis;

    private final ExecutorService executor;

    public ToolRegistry(List<MonitorTool> tools, ResultShaper shaper, long timeoutMillis) {
        if (tools == null || tools.isEmpty()) {
            throw new IllegalStateException("Tool 白名单为空。SPEC 第 24 节要求至少 5 个 Tool，"
                    + "请检查 seckill.monitor.enabled 与 MonitorToolConfiguration 的装配。");
        }
        this.shaper = shaper;
        this.timeoutMillis = Math.max(100L, timeoutMillis);

        Map<String, MonitorTool> map = new LinkedHashMap<>(tools.size() * 2);
        List<String> duplicates = new ArrayList<>();
        for (MonitorTool tool : tools) {
            if (tool == null) {
                continue;
            }
            String key = normalize(tool.name());
            if (map.putIfAbsent(key, tool) != null) {
                duplicates.add(tool.name());
            }
        }
        if (!duplicates.isEmpty()) {
            // 【重名必须启动即失败】重名意味着其中一个工具的调用永远到不了，
            // 而在「Agent 说它查过了」的场景下这是最危险的形态：
            // 轨迹里会出现 query_metric 的成功记录，但它其实来自另一个实现。
            throw new IllegalStateException("Tool 名称重复：" + duplicates
                    + "。名称是白名单的唯一键，必须全局唯一。");
        }
        this.byName = Collections.unmodifiableMap(map);
        this.ordered = List.copyOf(map.values());
        // 命名线程便于在 jstack / 日志里辨认「卡住的是哪次工具调用」
        this.executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("monitor-tool-", 0).factory());
    }

    // ================================================================ 白名单

    /** 白名单里的工具名（声明顺序） */
    public List<String> names() {
        return ordered.stream().map(MonitorTool::name).toList();
    }

    public boolean isRegistered(String name) {
        return name != null && byName.containsKey(normalize(name));
    }

    public int size() {
        return ordered.size();
    }

    /**
     * OpenAI / DeepSeek 的 {@code tools} 数组。
     * <p>只暴露白名单里的工具 —— 模型无从知道未注册工具的存在，
     * 这比「先让它调用再拒绝」更省一次往返。
     */
    public List<Map<String, Object>> definitions() {
        List<Map<String, Object>> definitions = new ArrayList<>(ordered.size());
        for (MonitorTool tool : ordered) {
            definitions.add(tool.definition());
        }
        return definitions;
    }

    /** 启动自检用：打印每个工具接受的参数，让「工具与 prompt 不一致」在启动时就可见 */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        for (MonitorTool tool : ordered) {
            sb.append("\n  - ").append(tool.name()).append("(参数: ")
                    .append(String.join(", ", tool.parameterNames())).append(')');
        }
        return sb.toString();
    }

    // ================================================================ 执行

    /**
     * 执行一次 Tool 调用。<b>本方法不抛异常</b> —— 所有失败都变成
     * {@link ToolStatus#REJECTED} 或 {@link ToolStatus#FAILED} 的结果。
     *
     * <p>【为什么不抛异常】调用方是 ReAct 循环。若它需要 try/catch 才能继续，
     * 那么「工具失败」就会变成「诊断中断」，而 SPEC 第 10 节的诊断规则第 4 条
     * 要求证据不足时也要给出带不确定性的结论。把失败编码进返回值，
     * 循环就只需要处理一种形态。
     *
     * @param toolName  模型给出的工具名（大小写不敏感）
     * @param arguments 模型给出的原始参数；{@code null} 视作无参
     * @return 永远非 {@code null}
     */
    public ToolResult invoke(String toolName, Map<String, Object> arguments) {
        long startNanos = System.nanoTime();

        if (toolName == null || toolName.isBlank()) {
            return finish(ToolResult.rejected("-",
                    "未提供工具名。可用工具：" + String.join(", ", names())), startNanos);
        }
        MonitorTool tool = byName.get(normalize(toolName));
        if (tool == null) {
            // 错误消息里带上白名单：这是唯一能让模型（或下一次调试的人）
            // 自己纠正的工具，而它几乎不花成本。
            return finish(ToolResult.rejected(toolName.trim(), "未注册的 Tool（白名单拒绝）。可用工具："
                    + String.join(", ", names())), startNanos);
        }

        ToolArguments args = ToolArguments.of(tool.name(), arguments);
        ToolResult result;
        try {
            result = executeWithTimeout(tool, args);
        } catch (ToolArgumentException e) {
            // 参数校验失败：没有触达数据源，属于 REJECTED
            log.warn("[ToolRegistry] 参数校验拒绝：tool={}, 原因={}", tool.name(), e.describe());
            return finish(ToolResult.rejected(tool.name(), e.describe()), startNanos);
        } catch (ToolTimeoutException e) {
            log.warn("[ToolRegistry] Tool 执行超时：tool={}, 超时={}ms", tool.name(), timeoutMillis);
            return finish(ToolResult.failed(tool.name(),
                    "执行超时（上限 " + timeoutMillis + "ms）。可能是数据源不可达或查询过重；"
                            + "请缩小查询范围或改用其它证据来源。", List.of()), startNanos);
        } catch (RuntimeException e) {
            log.error("[ToolRegistry] Tool 执行异常：tool={}", tool.name(), e);
            return finish(ToolResult.failed(tool.name(),
                    "执行异常 " + e.getClass().getSimpleName() + "：" + e.getMessage(), List.of()),
                    startNanos);
        }
        if (result == null) {
            // 实现返回 null 是契约违反，但把它变成一次 FAILED 比抛 NPE 更有用：
            // 轨迹里会留下「这个工具没有返回结果」这条事实。
            return finish(ToolResult.failed(tool.name(), "Tool 返回了 null，违反契约。", List.of()),
                    startNanos);
        }

        // 参数登记放在执行之后：只有走完 getter 的字段才算「被认识」的，
        // 这样「未定义字段」这条 note 才是准确的（见 ToolArguments#markUnrecognized）。
        args.markUnrecognized();
        if (!args.ignored().isEmpty()) {
            result = result.withNote("入参里包含未定义字段，已忽略：" + String.join(", ", args.ignored())
                    + "。请只使用参数说明里列出的字段。");
        }
        return finish(result, startNanos);
    }

    /**
     * 所有返回路径的<b>唯一</b>出口：回填耗时 → 整形（脱敏 + 体积收缩 + 不可信包裹）→ 记日志。
     *
     * <p>【为什么必须收敛成一个出口】初版让「未注册的工具」「参数被拒」「执行超时」三条路径
     * 各自 {@code return}，于是它们的结果<b>没有经过整形</b>：
     * {@link ToolResult#llmText()} 是 {@code null}。这在批次 2 里看不出来（测试只断言 status），
     * 但批次 3 要把它塞回对话时就会拿到 null —— 而那正是「工具被拒绝了，
     * 模型却什么也看不到」这种最难查的形态（轨迹里有记录，模型侧是空的）。
     * 更糟的是：被拒绝的参数里可能带着敏感原文，绕过整形就等于绕过脱敏。
     */
    private ToolResult finish(ToolResult result, long startNanos) {
        ToolResult timed = result.withElapsed(elapsedMillis(startNanos));
        ResultShaper.Shaped shaped = shaper.shape(timed);
        ToolResult finalResult = timed.withShaped(shaped.data(), shaped.truncated(),
                shaped.notes(), shaped.text());

        if (!finalResult.successful()) {
            log.warn("[ToolRegistry] {}", finalResult.summarize());
        } else if (finalResult.truncated()) {
            log.info("[ToolRegistry] {}（结果已收缩到上限内）", finalResult.summarize());
        } else {
            log.debug("[ToolRegistry] {}", finalResult.summarize());
        }
        return finalResult;
    }

    /**
     * 便捷重载：直接拿给 LLM 的文本（批次 3 把观测内容塞回对话时用的就是它）。
     * <p>读的是 {@link ToolResult#llmText()} —— 由整形阶段一次性生成，
     * 而不是在这里重新渲染一遍：重新渲染会得到一份「第二个版本」的文本，
     * 而复盘时需要的恰恰是「模型当时看到的<b>那一份</b>」。
     */
    public String invokeForLlm(String toolName, Map<String, Object> arguments) {
        return invoke(toolName, arguments).llmText();
    }

    private ToolResult executeWithTimeout(MonitorTool tool, ToolArguments args) {
        Future<ToolResult> future = executor.submit(() -> tool.execute(args));
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new ToolTimeoutException(tool.name());
        } catch (InterruptedException e) {
            // 恢复中断标志：吞掉它会让上层（例如应用的优雅停机）失去感知
            Thread.currentThread().interrupt();
            throw new ToolTimeoutException(tool.name() + "（调用线程被中断）");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ToolArgumentException argumentException) {
                throw argumentException;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Tool 执行失败：" + cause, cause);
        }
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static String normalize(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    /** 暴露给测试的白名单快照（不可变）。用于断言「白名单恰好是这 5 个」 */
    public Set<String> registeredNames() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(byName.keySet()));
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    /** 超时的内部信号，不对外暴露（对外统一是 {@link ToolStatus#FAILED}） */
    private static final class ToolTimeoutException extends RuntimeException {
        ToolTimeoutException(String toolName) {
            super("Tool 执行超时：" + toolName);
        }
    }
}
