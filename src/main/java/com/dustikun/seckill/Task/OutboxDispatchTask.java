package com.dustikun.seckill.Task;

import com.dustikun.seckill.Config.OutboxProperties;
import com.dustikun.seckill.Service.OutboxService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Outbox 投递器。
 * <p>
 * 【为什么用轮询而不是「写完立刻异步投」】写完立刻投需要额外一个内存队列把任务转交给后台线程，
 * 而那个队列一旦丢任务，outbox 记录就会一直躺在 PENDING 无人问津——
 * 等于把刚消灭的「内存中间态」又请回来了。轮询的好处是<b>状态只有一处真相</b>：
 * 投递器只认数据库里的 PENDING，进程重启、队列丢失都不影响它把账还清。
 * 代价是最多一个轮询间隔的延迟（默认 1 秒）。
 * <p>
 * 【多实例说明】当前不做抢占（见 {@link OutboxService} 的说明）。
 * 多个实例同时轮询会重复投递同一条消息，而消费端幂等，因此只浪费一点带宽，不会产生重复订单。
 * 真需要消除重复时，再引入 {@code SELECT ... FOR UPDATE SKIP LOCKED} 或按 stockId 分片。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "seckill.outbox", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OutboxDispatchTask {

    private final OutboxService outboxService;
    private final OutboxProperties properties;

    public OutboxDispatchTask(OutboxService outboxService, OutboxProperties properties) {
        this.outboxService = outboxService;
        this.properties = properties;
    }

    /**
     * 投递到期的待投递记录。
     * <p>
     * 定时任务里绝不能把异常抛出去：一次执行失败会连带影响后续调度，
     * 而「投递」恰恰是最可能失败的一步（Broker 不可用），必须让它自己扛住。
     */
    @Scheduled(fixedDelayString = "${seckill.outbox.dispatch-interval-ms:1000}")
    public void dispatch() {
        try {
            long start = System.currentTimeMillis();
            int sent = outboxService.dispatchDue(properties.getBatchSize());
            if (sent > 0) {
                int pending = outboxService.countPending();
                log.info("[Outbox·投递] 本轮投出 {} 条，耗时 {} ms，仍待投递 {} 条",
                        sent, System.currentTimeMillis() - start, pending);
            }
        } catch (Exception e) {
            log.error("[Outbox·投递] 本轮执行异常", e);
        }
    }

    /**
     * 归档清理：删除超过保留期的已投递记录。
     * <p>
     * 表会持续增长是 Outbox 的固有运维成本，必须有人负责清；
     * 但清理本身不该影响投递，因此单独一个低频任务。
     */
    @Scheduled(fixedDelayString = "${seckill.outbox.purge-interval-ms:600000}")
    public void purge() {
        try {
            outboxService.purgeExpired();
        } catch (Exception e) {
            log.error("[Outbox·归档] 本轮执行异常", e);
        }
    }
}
