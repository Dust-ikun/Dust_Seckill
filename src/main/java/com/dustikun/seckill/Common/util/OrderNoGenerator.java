package com.dustikun.seckill.Common.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 时间有序单号生成器（Snowflake）。
 * <p>
 * 为什么替换掉原来的 {@code UUID.randomUUID()}：
 * orders 表上 {@code uk_order_no} 是唯一索引，无序 UUID 会让索引页的写入位置在 B+Tree 里
 * 完全随机分布，导致频繁的页分裂与随机 IO；换成时间有序的单号后，新单号总是追加到索引尾部，
 * 页分裂与范围查询成本都显著下降。
 * <p>
 * 结构：1bit 符号位（恒 0） + 41bit 毫秒时间戳 + 10bit 机器位 + 12bit 序列号，
 * 输出 19 位十进制字符串，单机每毫秒可产出 4096 个不重复单号。
 * <p>
 * 注意：orders 表的主键仍然是自增 BIGINT，本身就是顺序写入、不存在页分裂问题，
 * 因此这里只替换业务单号，不去动主键。
 */
@Component
public class OrderNoGenerator {

    /** 自定义起始纪元 2024-01-01 00:00:00 UTC，41bit 时间戳可用约 69 年 */
    private static final long EPOCH = 1704067200000L;
    private static final long WORKER_ID_BITS = 10L;
    private static final long SEQUENCE_BITS = 12L;
    private static final long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS);
    private static final long SEQUENCE_MASK = ~(-1L << SEQUENCE_BITS);
    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;

    /** 可容忍的时钟回拨上限：小幅回拨自旋等待追平，超过该值直接失败 */
    private static final long MAX_BACKWARD_MS = 5L;

    private final long workerId;
    private long lastTimestamp = -1L;
    private long sequence = 0L;

    public OrderNoGenerator(@Value("${seckill.worker-id:0}") long workerId) {
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException("seckill.worker-id 必须在 [0, " + MAX_WORKER_ID + "] 之间");
        }
        this.workerId = workerId;
    }

    /**
     * 生成下一个单号。多实例部署时靠 workerId 区分，单实例内部靠序列号区分。
     */
    public synchronized String next() {
        long timestamp = System.currentTimeMillis();

        // 时钟回拨：小幅回拨等待追平，大幅回拨宁可直接失败，也绝不生成可能重复的单号
        if (timestamp < lastTimestamp) {
            long offset = lastTimestamp - timestamp;
            if (offset > MAX_BACKWARD_MS) {
                throw new IllegalStateException("系统时钟回拨 " + offset + "ms，拒绝生成单号");
            }
            timestamp = waitUntil(lastTimestamp);
        }

        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0L) {
                // 当前毫秒的序列号已用尽，自旋到下一毫秒再分配
                timestamp = waitUntil(lastTimestamp + 1L);
            }
        } else {
            sequence = 0L;
        }

        lastTimestamp = timestamp;

        long id = ((timestamp - EPOCH) << TIMESTAMP_SHIFT)
                | (workerId << WORKER_ID_SHIFT)
                | sequence;
        return Long.toString(id);
    }

    private long waitUntil(long target) {
        long now = System.currentTimeMillis();
        while (now < target) {
            Thread.onSpinWait();
            now = System.currentTimeMillis();
        }
        return now;
    }
}
