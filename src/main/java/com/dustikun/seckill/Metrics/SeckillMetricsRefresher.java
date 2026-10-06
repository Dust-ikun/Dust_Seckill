package com.dustikun.seckill.Metrics;

import com.dustikun.seckill.Service.CompensateTaskService;
import com.dustikun.seckill.Service.OutboxService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Gauge 缓存刷新器：把「数据库事实」按固定间隔搬进 {@link SeckillMetrics} 的缓存。
 *
 * <p><b>为什么必须有这一层，而不是让 Gauge 直接查库</b>
 * <p>
 * Micrometer 的 Gauge 是在<b>每次抓取时</b>求值的。若把 {@code countPending()} 直接挂上去，
 * 抓取间隔 15 秒就意味着一整天 5760 次 {@code SELECT COUNT(*)}——监控系统反而成了负载源。
 * 这里把「求值」和「抓取」解耦：定时任务负责查库（默认 30 秒一次），
 * 抓取只读本地 {@link java.util.concurrent.atomic.AtomicLong}，零 IO。
 * 代价是 Gauge 最多滞后一个刷新周期，对「欠账规模」这类慢变量完全够用。
 *
 * <p><b>为什么不用 15 秒对齐抓取间隔</b>
 * <p>
 * 对齐并没有收益（值本来就不会更快地变化），却会把查询次数翻倍。30 秒是刻意选的：
 * 慢于抓取间隔、快于任何有意义的告警窗口。
 *
 * <p><b>为什么失败时只在「首次」告警</b>
 * <p>
 * 数据库长时间不可用时，每个周期打一行 WARN 会刷出上万行日志、把真正有用的信息淹掉。
 * 所以这里用 {@code lastRefreshFailed} 做边沿触发：进入失败态打一次 WARN，
 * 恢复时打一次 INFO，中间静默（Gauge 保留上一次的已知值，Prometheus 侧看得到曲线是平的）。
 */
@Slf4j
@Component
public class SeckillMetricsRefresher {

    private final SeckillMetrics metrics;
    private final OutboxService outboxService;
    private final CompensateTaskService compensateTaskService;

    /** 上一次刷新是否失败，用于把重复告警收敛成边沿触发 */
    private volatile boolean lastRefreshFailed = false;

    public SeckillMetricsRefresher(SeckillMetrics metrics,
                                   OutboxService outboxService,
                                   CompensateTaskService compensateTaskService) {
        this.metrics = metrics;
        this.outboxService = outboxService;
        this.compensateTaskService = compensateTaskService;
    }

    @Scheduled(fixedDelayString = "${seckill.metrics.gauge-refresh-ms:30000}")
    public void refreshGauges() {
        try {
            metrics.refreshOutboxGauges(outboxService.countPending(), outboxService.countFailed());
            metrics.refreshCompensatePending(compensateTaskService.countPending());
            if (lastRefreshFailed) {
                lastRefreshFailed = false;
                log.info("[指标·刷新] 数据库已恢复，outbox / 待补偿 Gauge 恢复刷新");
            }
        } catch (Exception e) {
            // 刷新失败不能让调度线程挂掉（虽然 Spring 单次异常不会终止任务，
            // 但显式收敛异常能让日志归因清楚），Gauge 保留上次已知值。
            if (!lastRefreshFailed) {
                lastRefreshFailed = true;
                log.warn("[指标·刷新失败] 无法读取数据库事实，Gauge 暂时保留上次的值，后续每周期重试。原因={}",
                        e.toString());
            } else {
                log.debug("[指标·刷新失败] 仍在失败中，静默重试。原因={}", e.toString());
            }
        }
    }
}
