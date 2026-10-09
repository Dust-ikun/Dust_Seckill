package com.dustikun.seckill.monitor.agent;

import com.dustikun.seckill.monitor.repository.ToolExecutionRow;
import com.dustikun.seckill.monitor.repository.ToolExecutionStore;
import com.dustikun.seckill.monitor.tool.ToolResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 内存版轨迹存储：让 {@code AgentExecutor} 的循环可以在<b>不连数据库</b>的情况下被断言。
 *
 * <p>它同时用于一个反向的用例：{@link #failOnRecord} 为 true 时抛异常，
 * 验证「轨迹写不进去也不能中断诊断」这条不变量 ——
 * 那条路径在真库上很难构造（要正好让 INSERT 失败），而它恰好是
 * 「静默吞掉写库失败」这类缺陷最容易藏身的地方。
 */
final class InMemoryToolExecutionStore implements ToolExecutionStore {

    private final List<ToolExecutionRow> rows = new CopyOnWriteArrayList<>();

    private volatile boolean failOnRecord;

    InMemoryToolExecutionStore failOnRecord() {
        this.failOnRecord = true;
        return this;
    }

    @Override
    public void record(long taskId, int sequenceNo, ToolResult result, Instant now) {
        if (failOnRecord) {
            throw new IllegalStateException("测试构造的落库失败");
        }
        rows.add(new ToolExecutionRow(rows.size() + 1L, taskId, sequenceNo, result.toolName(),
                result.arguments(), result.envelope(), result.status().name(),
                result.elapsedMillis(), result.errorMessage(), now));
    }

    @Override
    public List<ToolExecutionRow> findByTaskId(long taskId) {
        return rows.stream().filter(row -> row.taskId() == taskId).toList();
    }

    @Override
    public int countByTaskId(long taskId) {
        return (int) rows.stream().filter(row -> row.taskId() == taskId).count();
    }

    List<ToolExecutionRow> all() {
        return new ArrayList<>(rows);
    }

    /** 按顺序取出每个工具调用的 {@code 工具名/状态}，用于断言轨迹形态 */
    List<String> sequence() {
        return rows.stream().map(row -> row.sequenceNo() + ":" + row.toolName() + ":" + row.status()).toList();
    }

    /** 取第 n 条（1 起算）的 arguments，用于断言「落库的是 accepted 还是原始入参」 */
    Map<String, Object> argumentsOf(int oneBasedIndex) {
        return rows.get(oneBasedIndex - 1).arguments();
    }

    @Override
    public String toString() {
        return rows.toString();
    }
}
