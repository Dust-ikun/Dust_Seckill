package com.dustikun.seckill.monitor.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 允许 {@code null} 值的「不可变」拷贝。
 *
 * <h2>★ 为什么不能用 {@code Map.copyOf} / {@code List.copyOf}</h2>
 * <p>
 * 因为 JDK 的这两个方法<b>拒绝 null</b>（{@code Map.copyOf} 对 null 值抛 NPE），
 * 而本项目的监控层有一批字段的语义就是「取不到」：
 * <pre>
 *   MqTool 的精确 lag      取不到时返回 null 而不是 0（批次 2 定的口径）
 *   Diagnosis.impact       模型可以写 {"affected_requests": null}
 *   AgentRunResult.rawResult  finishReason 在没有收尾时本来就没有值
 * </pre>
 * 用 {@code Map.copyOf} 包它们，就会在<b>读回来的那一刻</b>抛 NPE ——
 * 而那个 NPE 出现在离根因最远的地方：写入是好的、数据库里也是好的，
 * 只有「把这一行读出来」会炸。
 *
 * <p>这个缺陷在批次 3 里真的发生过一次：{@code AgentRunResult} 用
 * {@code Map.copyOf} 包 {@code rawResult}，于是「未配置 LLM 密钥」这条
 * <b>降级路径</b>（{@code finishReason} 为 null）变成了一个 NPE ——
 * 任务仍然是失败的，但失败原因从「没配密钥」变成了「NullPointerException」，
 * 而后者把排查方向引到了代码上。同一个原因还潜伏在
 * {@code GET /api/ai/diagnosis/{id}/tools}：只要有过一次 MQ lag 查询，
 * 那一行的 {@code result} 里就带着 null，读轨迹时就会炸。
 *
 * <h2>为什么仍然要拷贝</h2>
 * <p>
 * 因为调用方拿到的对象必须是<b>不可变</b>的：{@code Diagnosis} 与轨迹对象会被
 * 多个线程读（查询 API 与写日志同时读同一份），共享一个可变 Map 的话，
 * 「谁改了它」会成为一类无法复现的问题。因此这里做的是
 * 「拷贝 + 换成允许 null 的不可变包装」，而不是干脆不拷贝。
 */
public final class SafeCollections {

    private SafeCollections() {
    }

    /** 拷贝并包装成不可变 Map，<b>允许 null 值</b>（{@code Map.copyOf} 不等价） */
    public static <K, V> Map<K, V> map(Map<K, V> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    /** 拷贝并包装成不可变 List，<b>允许 null 元素</b>（{@code List.copyOf} 不等价） */
    public static <T> List<T> list(List<T> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        return Collections.unmodifiableList(new ArrayList<>(source));
    }
}
