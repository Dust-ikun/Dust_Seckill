package com.dustikun.seckill.monitor.tool;

import com.dustikun.seckill.monitor.core.MaskProperties;
import com.dustikun.seckill.monitor.core.Masker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ToolRegistry}：白名单、参数校验、超时、结果上限的唯一落点。
 *
 * <h2>验收判据的直接对应</h2>
 * <p>
 * 可行性报告批次 2 的任务 2.1 判据是「未注册的 Tool 名一律拒绝」，
 * 任务 2.7 是「超长结果被截断并标 truncated=true」。
 * 下面各有一条用例直接对应它们 —— 判据必须能在测试里被指出来，
 * 否则它只是一句写在文档里的期望。
 */
class ToolRegistryTest {

    private static ResultShaper shaper() {
        return new ResultShaper(new Masker(new MaskProperties(null, null, null)),
                new ObjectMapper(), 8000);
    }

    private static ToolRegistry registry(long timeoutMillis, MonitorTool... tools) {
        return new ToolRegistry(List.of(tools), shaper(), timeoutMillis);
    }

    @Test
    @DisplayName("未注册的 Tool 名一律拒绝，且错误消息里带上白名单（模型据此能自己改对）")
    void unknownToolIsRejectedWithWhitelist() {
        ToolRegistry registry = registry(1000, new StubTool("query_metric"));
        try {
            ToolResult result = registry.invoke("drop_everything", Map.of());

            assertEquals(ToolStatus.REJECTED, result.status());
            assertTrue(result.errorMessage().contains("未注册的 Tool"), result.errorMessage());
            assertTrue(result.errorMessage().contains("query_metric"),
                    "错误消息要列出白名单：" + result.errorMessage());
            assertNotNull(result.llmText(),
                    "被拒绝的结果也要整形：否则批次 3 拿到的 llmText 是 null，"
                            + "表现为「轨迹里有记录、模型侧什么都没有」");
            assertTrue(result.llmText().contains("REJECTED"), result.llmText());
        } finally {
            registry.close();
        }
    }

    @Test
    @DisplayName("空工具名同样被拒绝，而不是抛 NPE")
    void blankToolNameIsRejected() {
        ToolRegistry registry = registry(1000, new StubTool("query_metric"));
        try {
            assertEquals(ToolStatus.REJECTED, registry.invoke(null, Map.of()).status());
            assertEquals(ToolStatus.REJECTED, registry.invoke("   ", Map.of()).status());
        } finally {
            registry.close();
        }
    }

    @Test
    @DisplayName("工具名大小写不敏感（模型回填时大小写不稳定，而名字不是业务数据）")
    void toolNameIsCaseInsensitive() {
        ToolRegistry registry = registry(1000, new StubTool("query_metric"));
        try {
            assertEquals(ToolStatus.SUCCESS, registry.invoke("Query_Metric", Map.of()).status());
        } finally {
            registry.close();
        }
    }

    @Test
    @DisplayName("参数校验失败 → REJECTED，且数据源一次都没被触达")
    void parameterFailureIsRejectedBeforeTouchingTheSource() {
        StubTool tool = new StubTool("query_metric");
        ToolRegistry registry = registry(1000, tool);
        try {
            ToolResult result = registry.invoke("query_metric", Map.of("limit", 100000));

            assertEquals(ToolStatus.REJECTED, result.status());
            // 【断言的是「数据源没被触达」，不是「execute 没被调用」】
            // 本项目的参数校验是在工具内部按需读取时发生的（见 ToolArguments 的类注释），
            // 因此 execute() 一定会进入；真正的保证是「校验先于任何 I/O」。
            // 断言「方法没被调用」会把实现细节写进测试，反而漏掉真正的风险。
            assertFalse(tool.touchedSource.get(), "参数不合法时不该触达数据源");
            assertTrue(result.errorMessage().contains("[1, 200]"), result.errorMessage());
            assertNotNull(result.llmText(), "被拒绝的结果同样要经过整形（批次 3 要用它回灌给模型）");
        } finally {
            registry.close();
        }
    }

    @Test
    @DisplayName("工具抛异常 → FAILED，且不影响后续调用（一次工具失败不该中断诊断）")
    void toolExceptionBecomesFailedAndRegistryStaysUsable() {
        StubTool boom = new StubTool("query_metric");
        boom.failWith = new IllegalStateException("Prometheus 连接被拒绝");
        ToolRegistry registry = registry(1000, boom);
        try {
            ToolResult failed = registry.invoke("query_metric", Map.of());
            assertEquals(ToolStatus.FAILED, failed.status());
            assertTrue(failed.errorMessage().contains("Prometheus 连接被拒绝"), failed.errorMessage());

            boom.failWith = null;
            assertEquals(ToolStatus.SUCCESS, registry.invoke("query_metric", Map.of()).status());
        } finally {
            registry.close();
        }
    }

    @Test
    @DisplayName("工具返回 null 违反契约 → FAILED，并在轨迹里留下「没有返回结果」这条事实")
    void nullResultBecomesFailed() {
        MonitorTool nullTool = new MonitorTool() {
            @Override
            public String name() {
                return "null_tool";
            }

            @Override
            public String description() {
                return "故意返回 null";
            }

            @Override
            public Map<String, Object> parametersSchema() {
                return ToolSchema.object(new LinkedHashMap<>());
            }

            @Override
            public ToolResult execute(ToolArguments args) {
                return null;
            }
        };
        ToolRegistry registry = registry(1000, nullTool);
        try {
            ToolResult result = registry.invoke("null_tool", Map.of());
            assertEquals(ToolStatus.FAILED, result.status());
            assertTrue(result.errorMessage().contains("null"), result.errorMessage());
        } finally {
            registry.close();
        }
    }

    @Test
    @DisplayName("执行超时 → FAILED（Agent 不必再等，而不是整次诊断被挂住）")
    void slowToolTimesOut() {
        MonitorTool slow = new MonitorTool() {
            @Override
            public String name() {
                return "slow_tool";
            }

            @Override
            public String description() {
                return "故意睡很久";
            }

            @Override
            public Map<String, Object> parametersSchema() {
                return ToolSchema.object(new LinkedHashMap<>());
            }

            @Override
            public ToolResult execute(ToolArguments args) {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return ToolResult.ok("slow_tool", Map.of("never", "returned"));
            }
        };
        ToolRegistry registry = registry(200, slow);
        long start = System.currentTimeMillis();
        try {
            ToolResult result = registry.invoke("slow_tool", Map.of());

            assertEquals(ToolStatus.FAILED, result.status());
            assertTrue(result.errorMessage().contains("超时"), result.errorMessage());
            assertTrue(System.currentTimeMillis() - start < 3000,
                    "应当在超时后立即返回，而不是等工具自己跑完");
        } finally {
            registry.close();
        }
    }

    @Test
    @DisplayName("超长结果被截断并标 truncated=true（批次 2 任务 2.7 的判据）")
    void oversizedResultIsTruncatedAndMarked() {
        MonitorTool chatty = new MonitorTool() {
            @Override
            public String name() {
                return "chatty_tool";
            }

            @Override
            public String description() {
                return "返回很多内容";
            }

            @Override
            public Map<String, Object> parametersSchema() {
                return ToolSchema.object(new LinkedHashMap<>());
            }

            @Override
            public ToolResult execute(ToolArguments args) {
                List<Map<String, Object>> rows = new ArrayList<>();
                for (int i = 0; i < 300; i++) {
                    rows.add(Map.of("text", "第 " + i + " 行内容，用来把结果撑到上限之外"));
                }
                return ToolResult.ok("chatty_tool", Map.of("rows", rows));
            }
        };
        ToolRegistry registry = new ToolRegistry(List.of(chatty),
                new ResultShaper(new Masker(new MaskProperties(null, null, null)),
                        new ObjectMapper(), 1000), 3000);
        try {
            ToolResult result = registry.invoke("chatty_tool", Map.of());

            assertEquals(ToolStatus.SUCCESS, result.status());
            assertTrue(result.truncated(), "必须标记截断");
            assertTrue(result.llmText().length() <= 1000,
                    "给 LLM 的文本必须落在上限内，实际 " + result.llmText().length());
            assertTrue(result.llmText().contains("truncated=\"true\""), result.llmText());
        } finally {
            registry.close();
        }
    }

    @Test
    @DisplayName("未定义字段被记进 notes，但调用仍然成功（拒绝整次调用会白白损失一次取证）")
    void unrecognizedArgumentsAreNotedNotFatal() {
        ToolRegistry registry = registry(1000, new StubTool("query_metric"));
        try {
            ToolResult result = registry.invoke("query_metric",
                    Map.of("limit", 10, "reason", "我怀疑是慢查询"));

            assertEquals(ToolStatus.SUCCESS, result.status());
            assertTrue(result.notes().stream().anyMatch(n -> n.contains("未定义字段") && n.contains("reason")),
                    "notes 应当说明被忽略的字段：" + result.notes());
        } finally {
            registry.close();
        }
    }

    @Test
    @DisplayName("耗时被回填：SPEC 第 25 节要求 Tool 单次调用 < 1s，这是一个可度量的点")
    void elapsedIsRecorded() {
        ToolRegistry registry = registry(1000, new StubTool("query_metric"));
        try {
            ToolResult result = registry.invoke("query_metric", Map.of());
            assertTrue(result.elapsedMillis() >= 0);
            assertNotNull(result.llmText());
            assertTrue(result.llmText().contains("elapsedMs"), result.llmText());
        } finally {
            registry.close();
        }
    }

    @Test
    @DisplayName("重名的 Tool 在装配期就失败（重名会让其中之一的调用永远到不了，而轨迹里看不出）")
    void duplicateNamesFailFast() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> registry(1000, new StubTool("query_metric"), new StubTool("query_metric")));
        assertTrue(e.getMessage().contains("重复"), e.getMessage());
    }

    @Test
    @DisplayName("空白名单在装配期失败（SPEC 第 24 节要求至少 5 个 Tool）")
    void emptyRegistryFailsFast() {
        assertThrows(IllegalStateException.class, () -> registry(1000));
    }

    @Test
    @DisplayName("definitions() 产出 OpenAI / DeepSeek 的 tools 结构，且只含白名单里的工具")
    void definitionsOnlyExposeWhitelistedTools() {
        ToolRegistry registry = registry(1000, new StubTool("query_metric"), new StubTool("search_logs"));
        try {
            List<Map<String, Object>> definitions = registry.definitions();

            assertEquals(2, definitions.size());
            assertEquals("function", definitions.get(0).get("type"));
            @SuppressWarnings("unchecked")
            Map<String, Object> function = (Map<String, Object>) definitions.get(0).get("function");
            assertEquals("query_metric", function.get("name"));
            assertTrue(function.containsKey("parameters"));
            assertEquals(List.of("query_metric", "search_logs"), registry.names());
        } finally {
            registry.close();
        }
    }

    @Test
    @DisplayName("并发调用安全：多个虚拟线程同时执行同一个工具不会互相串结果")
    void concurrentInvocationsAreIsolated() throws Exception {
        ToolRegistry registry = registry(3000, new EchoTool());
        int threads = 16;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<String> seen = java.util.Collections.synchronizedList(new ArrayList<>());
        List<Thread> workers = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                int index = i;
                Thread worker = new Thread(() -> {
                    ready.countDown();
                    try {
                        go.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    ToolResult result = registry.invoke("echo_tool", Map.of("value", "v" + index));
                    seen.add(String.valueOf(result.data().get("value")));
                });
                worker.start();
                workers.add(worker);
            }
            ready.await(2, TimeUnit.SECONDS);
            go.countDown();
            for (Thread worker : workers) {
                worker.join(5000);
            }
            assertEquals(threads, seen.size());
            for (int i = 0; i < threads; i++) {
                assertTrue(seen.contains("v" + i), "缺少 v" + i + "：" + seen);
            }
        } finally {
            registry.close();
        }
    }

    /** 最小工具：区分「进入 execute」与「触达数据源」两个时刻，用于验证校验先于 I/O */
    private static final class StubTool implements MonitorTool {

        private final String name;

        private final AtomicInteger calls = new AtomicInteger();

        /** 只有在参数校验全部通过之后才置位 —— 它就是「数据源被触达」这个事实 */
        private final java.util.concurrent.atomic.AtomicBoolean touchedSource =
                new java.util.concurrent.atomic.AtomicBoolean();

        private RuntimeException failWith;

        private StubTool(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "测试桩";
        }

        @Override
        public Map<String, Object> parametersSchema() {
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("limit", ToolSchema.integer("条数", 1, 200));
            return ToolSchema.object(properties);
        }

        @Override
        public ToolResult execute(ToolArguments args) {
            calls.incrementAndGet();
            // 先校验（可能抛 ToolArgumentException），后触达数据源
            int limit = args.intOrDefault("limit", 20, 1, 200);
            touchedSource.set(true);
            if (failWith != null) {
                throw failWith;
            }
            return ToolResult.ok(name, Map.of("called", calls.get(), "limit", limit));
        }
    }

    /** 把入参回显出来，用于并发隔离测试 */
    private static final class EchoTool implements MonitorTool {

        @Override
        public String name() {
            return "echo_tool";
        }

        @Override
        public String description() {
            return "回显入参";
        }

        @Override
        public Map<String, Object> parametersSchema() {
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("value", ToolSchema.string("值"));
            return ToolSchema.object(properties, "value");
        }

        @Override
        public ToolResult execute(ToolArguments args) {
            return ToolResult.ok(name(), Map.of("value", args.requireString("value", 20)));
        }
    }
}
