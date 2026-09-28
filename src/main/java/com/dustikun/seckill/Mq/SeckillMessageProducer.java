package com.dustikun.seckill.Mq;

import com.dustikun.seckill.Common.Exception.BizException;
import com.dustikun.seckill.Common.Exception.ErrorCode;
import com.dustikun.seckill.Config.RocketMqProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 秒杀订单消息投递器。
 * <p>
 * 【为什么用同步发送而不是异步发送】
 * 请求线程在投递之前已经在 Redis 里把库存扣掉了。如果这里用异步发送，消息在真正进入 Broker 之前
 * 请求线程就返回了「已受理」，一旦此后投递失败，用户在界面上看到的是「抢到了」，
 * 而系统里既没有消息也没有库存——这就是最难排查的一类数据不一致。
 * 同步发送把「消息已落 Broker」这个事实压实在返回之前，投递失败可以当场感知并回补库存。
 * 代价是请求线程多等一次网络往返（本机约 1~3ms），这是为可观测的正确性付的合理代价。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "seckill.mq", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SeckillMessageProducer {

    /**
     * 单批消息体的软上限。
     * <p>
     * RocketMQ 对「一次 send 传集合」的体量上限是 1MB；超过之后客户端会<b>自己拆批</b>，
     * 而拆批是「前几批可能已成功、后几批失败」的局部成功语义 ——
     * 调用方拿到异常时无法知道哪几条真的进去了，只能整批不标记 SENT 再逐条重投。
     * 这里把上限定在 128KB（约 1MB 的 1/8），确保单批绝不会被客户端拆分，
     * 「批量投递要么全到达、要么全失败」这个前提才成立。超过即抛异常，由调用方退回逐条投递。
     */
    private static final int MAX_BATCH_BYTES = 128 * 1024;

    private final DefaultMQProducer producer;
    private final ObjectMapper objectMapper;
    private final RocketMqProperties properties;

    private final AtomicInteger sentCount = new AtomicInteger(0);
    private final AtomicInteger failedCount = new AtomicInteger(0);

    public SeckillMessageProducer(DefaultMQProducer producer,
                                  ObjectMapper objectMapper,
                                  RocketMqProperties properties) {
        this.producer = producer;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /**
     * 投递「订单已创建」消息。投递未得到 Broker 确认时抛出 {@link BizException}，
     * 由调用方负责回补 Redis 预扣。
     */
    public void sendOrderCreated(SeckillMessage message) {
        try {
            SendResult result = producer.send(toMqMessage(message));
            checkSendResult(result, message.orderNo());
            sentCount.incrementAndGet();
            log.debug("[投递成功] orderNo={}, msgId={}, queue={}",
                    message.orderNo(), result.getMsgId(), result.getMessageQueue().getQueueId());
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            // 序列化失败、RemotingException、MQBrokerException、超时等统一收敛为「投递失败」
            log.error("[投递异常] orderNo={}", message.orderNo(), e);
            failedCount.incrementAndGet();
            throw new BizException(ErrorCode.MQ_SEND_FAILED);
        }
    }

    /**
     * 批量投递「订单已创建」消息，<b>要么全到达、要么抛异常</b>。
     * <p>
     * 【为什么需要它】Outbox 投递器按批捞记录，如果逐条同步发送，一批 N 条就是 N 次网络往返；
     * 实测本机每条约 12ms，一批 200 条要 2~3.5 秒，投递吞吐被压到约 60 条/秒 ——
     * 请求侧 1000+ TPS 受理进来的欠账，投递侧要按分钟级才还得清。
     * 批量发送把 N 次往返压成 1 次，同时让调用方能用一条 {@code UPDATE ... IN} 标记整批已投出。
     * <p>
     * 【为什么上界是硬失败而不是自己拆批】见 {@link #MAX_BATCH_BYTES}：拆分会让「整批成功」的
     * 前提失效，宁可让调用方退回逐条投递，也不接受「不知道哪几条已到达」的状态。
     *
     * @param messages 同一 topic、同一 tag 的一批消息（调用方保证非空）
     */
    public void sendOrderCreatedBatch(List<SeckillMessage> messages) {
        List<Message> batch = new ArrayList<>(messages.size());
        int bytes = 0;
        try {
            for (SeckillMessage message : messages) {
                Message mqMessage = toMqMessage(message);
                bytes += mqMessage.getBody().length;
                batch.add(mqMessage);
            }
        } catch (Exception e) {
            log.error("[批量投递异常] 序列化阶段失败，本批 {} 条", messages.size(), e);
            failedCount.addAndGet(messages.size());
            throw new BizException(ErrorCode.MQ_SEND_FAILED);
        }

        if (bytes > MAX_BATCH_BYTES) {
            log.warn("[批量投递] 本批 {} 条共 {} 字节，超过 {} 字节上限，交由调用方逐条投递",
                    messages.size(), bytes, MAX_BATCH_BYTES);
            throw new BizException(ErrorCode.MQ_SEND_FAILED);
        }

        try {
            SendResult result = producer.send(batch);
            checkSendResult(result, messages.get(0).orderNo() + " 等 " + messages.size() + " 条");
            sentCount.addAndGet(messages.size());
            log.debug("[批量投递成功] {} 条，msgId={}", messages.size(), result.getMsgId());
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("[批量投递异常] 本批 {} 条全部未确认", messages.size(), e);
            failedCount.addAndGet(messages.size());
            throw new BizException(ErrorCode.MQ_SEND_FAILED);
        }
    }

    private Message toMqMessage(SeckillMessage message) {
        return new Message(
                properties.getTopic(),
                properties.getTag(),
                // key 用单号：RocketMQ 会为消息建立索引，便于在控制台按单号定位这一条消息
                message.orderNo(),
                objectMapper.writeValueAsBytes(message));
    }

    private void checkSendResult(SendResult result, String orderNo) {
        if (result == null || result.getSendStatus() != SendStatus.SEND_OK) {
            String status = result == null ? "null" : String.valueOf(result.getSendStatus());
            log.error("[投递失败] Broker 未确认。orderNo={}, sendStatus={}", orderNo, status);
            failedCount.incrementAndGet();
            throw new BizException(ErrorCode.MQ_SEND_FAILED);
        }
    }

    public int getSentCount() {
        return sentCount.get();
    }

    public int getFailedCount() {
        return failedCount.get();
    }
}
