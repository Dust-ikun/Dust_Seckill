package com.dustikun.seckill.Bench;

import lombok.Data;

/**
 * 压测影子表 bench_stock 的实体。
 * <p>
 * 只为一组对照实验服务：在同一行热点上比较「条件 UPDATE」与「乐观锁 version CAS」两种并发模型。
 * 刻意不使用业务表 stock，避免为实验改动生产表结构（不用给 stock 加 version 列）。
 */
@Data
public class BenchStock {
    private Long id;
    private Long count;
    private Long version;
}
