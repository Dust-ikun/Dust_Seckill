package com.dustikun.seckill.monitor.repository;

import com.dustikun.seckill.monitor.tool.ToolResult;

import java.time.Instant;
import java.util.List;

/**
 * {@code ai_tool_execution} 的读写（SPEC 第 14.3 节），批次 3 的「轨迹」落点。
 *
 * <h2>写入的时机是契约的一部分</h2>
 * <p>
 * 每一条工具调用都必须在<b>返回之后立刻</b>写入，包括 {@code REJECTED} 与 {@code FAILED}。
 * 这一点很容易在实现里走偏：一个自然的写法是「收集到列表里，诊断结束时批量写」——
 * 它看起来更高效，但会在<b>最需要轨迹的时候</b>恰好丢掉轨迹：
 * <ul>
 *   <li>诊断因超时而 {@code FAILED} 时，那份「已经查到了什么」的列表随对象一起被丢掉；</li>
 *   <li>应用被重启/被杀时，内存里的轨迹全部消失。</li>
 * </ul>
 * 而这两种情况恰恰是「我要看看它到底卡在哪」的时候。
 * 因此这里是<b>逐条写入</b>，<b>不</b>提供批量入口。
 */
public interface ToolExecutionStore {

    /**
     * 记录一次工具调用。
     *
     * @param taskId    任务 id
     * @param sequenceNo 同一任务内的序号，从 1 开始（见 {@link ToolExecutionRow#sequenceNo()}）
     * @param result    调用结果。{@code arguments} 取 {@link ToolResult#arguments()}，
     *                  {@code status} 取 {@link ToolResult#status()} —— 都与
     *                  {@code ai_tool_execution} 的列一一对应，不做二次映射
     */
    void record(long taskId, int sequenceNo, ToolResult result, Instant now);

    /** 按 task 取完整轨迹（{@code GET /api/ai/diagnosis/{incidentId}/tools}） */
    List<ToolExecutionRow> findByTaskId(long taskId);

    /** 轨迹条数。用于查询 API 的摘要与测试断言 */
    int countByTaskId(long taskId);
}
