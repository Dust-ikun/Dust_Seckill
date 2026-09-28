package com.dustikun.seckill.Config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RocketMQ 连接与消费行为配置，对应 application.yaml 里的 {@code seckill.mq.*}。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "seckill.mq")
public class RocketMqProperties {

    /**
     * 是否启用 MQ 异步落库。
     * <p>
     * 置为 false 时不会创建任何 RocketMQ 客户端，秒杀链路自动退化为「Redis 预扣 + 同步落库」，
     * 等价于阶段 3 的行为。用于本机没起 Broker 时仍能启动应用调试其它功能。
     */
    private boolean enabled = true;

    /** NameServer 地址，多个用分号分隔 */
    private String nameServer = "127.0.0.1:9876";

    /** 生产者组名 */
    private String producerGroup = "seckill-producer-group";

    /** 消费者组名。注意：同一消费组在多实例间会自动做队列负载均衡 */
    private String consumerGroup = "seckill-order-consumer-group";

    /** 下单消息 Topic */
    private String topic = "seckill-order-topic";

    /** 消息 Tag，便于同一 Topic 内做订阅过滤 */
    private String tag = "create-order";

    /** 发送超时（毫秒）。超过该时间未拿到 Broker 确认即视为投递失败 */
    private int sendTimeoutMs = 3000;

    /**
     * 最大重试消费次数。超过后消息会被投递到死信队列（%DLQ%消费者组）。
     * <p>
     * 消费端在最后一次重试时会回补 Redis 库存，避免这笔预扣永久丢失。
     */
    private int maxReconsumeTimes = 3;

    /** 消费线程数下限 */
    private int consumeThreadMin = 4;

    /** 消费线程数上限。不要超过 Hikari 连接池的 maximum-pool-size，否则消费线程会互相等连接 */
    private int consumeThreadMax = 8;
}
