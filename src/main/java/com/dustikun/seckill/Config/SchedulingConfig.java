package com.dustikun.seckill.Config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 开启定时调度，供后台任务使用。
 * <p>
 * 【为什么这里不带 @ConditionalOnProperty】「维护任务」和「Outbox 投递器」都依赖调度，
 * 却是两个独立的开关。把 {@code @EnableScheduling} 做成无条件开启、由各任务自己用
 * {@code @ConditionalOnProperty} 决定是否注册 —— 否则关掉维护任务会连带把投递器一起关掉，
 * 那会导致 outbox 记录永远躺在 PENDING：接口返回「已受理」，订单却再也不会落库。
 * <p>
 * 【为什么用 Spring 自带调度而不是 XXL-JOB 之类】当前是单机项目，
 * 待补偿重试、库存对账、Outbox 投递都是「低频、可重复、失败不影响主链路」的任务，
 * 一个 {@code @Scheduled} 就够了。等出现多实例竞争（同一批任务被多个实例同时捞起）
 * 或需要任务分片时再引入分布式调度——那时才真正需要它，现在引入只是多一个要运维的组件。
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
