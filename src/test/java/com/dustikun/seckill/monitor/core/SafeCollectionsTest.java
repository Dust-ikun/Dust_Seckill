package com.dustikun.seckill.monitor.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 锁定 {@link SafeCollections} 的契约：<b>允许 null，但仍然不可变</b>。
 *
 * <h2>为什么这个工具类需要专属测试</h2>
 * <p>
 * 因为它存在的唯一理由是「{@code Map.copyOf} / {@code List.copyOf} 拒绝 null」，
 * 而这两者在 JDK 里看起来更「标准」—— 一个后来者读到它，第一反应很可能是
 * 「为什么要自己包一层？换成 {@code Map.copyOf} 更简洁」，然后<b>把已经修好三次的
 * 缺陷重新引入</b>（本批次里它真的发生了三次：`AgentRunResult` 的降级路径、
 * `Diagnosis.impact`、`AlertIngestService.IngestResult` 的孤立恢复通知）。
 *
 * <p>因此这一组用例的写法是刻意的：<b>先用 JDK 的方法证明它会抛</b>，
 * 再断言我们的方法不抛。这样后来者看到的不是「一条规定」，而是一个可复现的事实。
 */
class SafeCollectionsTest {

    @Test
    @DisplayName("★ 先用 JDK 证明 Map.copyOf 会拒绝 null 值 —— 这不是「规定」，是可复现的事实")
    void jdkMapCopyOfRejectsNullValues() {
        Map<String, Object> withNull = new HashMap<>();
        withNull.put("finishReason", null);
        assertThrows(NullPointerException.class, () -> Map.copyOf(withNull));
        // 与本项目真实踩到的那一次完全同形：降级路径上的 finishReason 就是 null
        assertThrows(NullPointerException.class, () -> Map.of("a", null));
    }

    @Test
    @DisplayName("★ List.copyOf 拒绝 null 元素 —— 孤立恢复通知的 incidentId 就是 null")
    void jdkListCopyOfRejectsNullElements() {
        List<String> withNull = new ArrayList<>();
        withNull.add("INC-20261009-001");
        withNull.add(null);
        assertThrows(NullPointerException.class, () -> List.copyOf(withNull));
    }

    @Test
    @DisplayName("SafeCollections.map：保留 null 值，且结果不可变")
    void mapKeepsNullValuesAndStaysImmutable() {
        Map<String, Object> source = new HashMap<>();
        source.put("model", "deepseek-chat");
        source.put("finishReason", null);
        source.put("toolCalls", 3);

        Map<String, Object> safe = SafeCollections.map(source);

        assertEquals(3, safe.size());
        assertTrue(safe.containsKey("finishReason"), "null 值不能被丢掉，否则「本来就没有」会变成「字段不存在」");
        assertEquals(null, safe.get("finishReason"));
        assertThrows(UnsupportedOperationException.class, () -> safe.put("x", 1));
    }

    @Test
    @DisplayName("SafeCollections.list：保留 null 元素，且结果不可变")
    void listKeepsNullElementsAndStaysImmutable() {
        List<String> safe = SafeCollections.list(List.of("a", "", "b"));
        assertEquals(3, safe.size());
        assertThrows(UnsupportedOperationException.class, () -> safe.add("c"));

        List<String> withNull = new ArrayList<>();
        withNull.add("INC-1");
        withNull.add(null);
        List<String> kept = SafeCollections.list(withNull);
        assertEquals(2, kept.size(), "过滤 null 会让「第 n 条告警进了哪个事故」的下标错位");
        assertEquals(null, kept.get(1));
    }

    @Test
    @DisplayName("null 与空集合都归一成不可变的空集合（调用方不必判空）")
    void nullAndEmptyBecomeImmutableEmpty() {
        assertTrue(SafeCollections.map(null).isEmpty());
        assertTrue(SafeCollections.map(Map.of()).isEmpty());
        assertTrue(SafeCollections.list(null).isEmpty());
        assertTrue(SafeCollections.list(List.of()).isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> SafeCollections.map(null).put("x", 1));
        assertThrows(UnsupportedOperationException.class, () -> SafeCollections.list(null).add("x"));
    }

    @Test
    @DisplayName("拷贝语义：改原集合不影响已包装的那一份")
    void copiesInsteadOfWrapping() {
        Map<String, Object> source = new HashMap<>();
        source.put("a", 1);
        Map<String, Object> safe = SafeCollections.map(source);
        source.put("b", 2);
        assertEquals(1, safe.size(), "共享同一个可变 Map 会让「谁改了它」变成无法复现的问题");

        List<String> listSource = new ArrayList<>(List.of("x"));
        List<String> safeList = SafeCollections.list(listSource);
        listSource.add("y");
        assertEquals(1, safeList.size());
    }
}
