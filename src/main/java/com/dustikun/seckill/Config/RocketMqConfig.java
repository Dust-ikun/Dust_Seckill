package com.dustikun.seckill.Config;

import com.dustikun.seckill.Mq.SeckillOrderConsumer;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RocketMQ 生产者与消费者的手工装配。
 * <p>
 * 【为什么不用 rocketmq-spring-boot-starter】
 * 本项目是 Spring Boot 4.0.8（Spring Framework 7），而 {@code rocketmq-spring-boot-starter}
 * 目前最高适配 Spring Boot 3.x，引入后会因框架 API 变更而启动失败。改用原生客户端 + 手写配置，
 * 代价是多写几十行装配代码，收益是启动与关闭过程完全可控（这也是这里手动调用 {@code start()}
 * 而不是用 {@code @Bean(initMethod=...)} 的原因：可以在启动后立即打印实际生效的参数）。
 * <p>
 * 两个 Bean 都声明了 {@code destroyMethod = "shutdown"}，保证应用关闭时先断开与 Broker 的连接，
 * 避免消费线程在半途被强行打断。
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(RocketMqProperties.class)
public class RocketMqConfig {

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(prefix = "seckill.mq", name = "enabled", havingValue = "true", matchIfMissing = true)
    public DefaultMQProducer seckillMqProducer(RocketMqProperties properties) throws MQClientException {
        DefaultMQProducer producer = new DefaultMQProducer(properties.getProducerGroup());
        producer.setNamesrvAddr(properties.getNameServer());
        producer.setSendMsgTimeout(properties.getSendTimeoutMs());
        // 同步发送失败时在客户端内部再重试 2 次（换 Broker 重试），仍失败才抛给业务层做库存回补
        producer.setRetryTimesWhenSendFailed(2);
        producer.setRetryAnotherBrokerWhenNotStoreOK(true);
        producer.start();

        log.info("[MQ] 生产者已启动。group={}, nameServer={}, sendTimeout={}ms",
                properties.getProducerGroup(), properties.getNameServer(), properties.getSendTimeoutMs());
        return producer;
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(prefix = "seckill.mq", name = "enabled", havingValue = "true", matchIfMissing = true)
    public DefaultMQPushConsumer seckillMqConsumer(RocketMqProperties properties,
                                                   SeckillOrderConsumer listener) throws MQClientException {
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer(properties.getConsumerGroup());
        consumer.setNamesrvAddr(properties.getNameServer());
        consumer.subscribe(properties.getTopic(), properties.getTag());
        consumer.setConsumeThreadMin(properties.getConsumeThreadMin());
        consumer.setConsumeThreadMax(properties.getConsumeThreadMax());
        // 一次只消费一条：让「重试次数」和「单条消息」严格一一对应，
        // 否则批量返回 RECONSUME_LATER 会把整批一起重投，重试计数失去意义。
        consumer.setConsumeMessageBatchMaxSize(1);
        consumer.setMaxReconsumeTimes(properties.getMaxReconsumeTimes());
        consumer.registerMessageListener(listener);
        consumer.start();

        log.info("[MQ] 消费者已启动。group={}, topic={}, tag={}, thread={}~{}, maxReconsumeTimes={}",
                properties.getConsumerGroup(), properties.getTopic(), properties.getTag(),
                properties.getConsumeThreadMin(), properties.getConsumeThreadMax(),
                properties.getMaxReconsumeTimes());
        return consumer;
    }
}
