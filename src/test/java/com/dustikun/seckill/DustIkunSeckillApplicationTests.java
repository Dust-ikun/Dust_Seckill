package com.dustikun.seckill;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 上下文加载冒烟测试。
 * <p>
 * {@code @ActiveProfiles("test")} 必须与其它测试类保持一致：Spring 的上下文缓存以配置组合为键，
 * 配置不同就会多创建一份上下文，而每个上下文都会启动一个 RocketMQ 消费者 ——
 * 同一个消费组内出现多个消费者实例时，Broker 会把队列对半分，导致依赖消费进度的断言假失败。
 */
@SpringBootTest
@ActiveProfiles("test")
class DustIkunSeckillApplicationTests {

    @Test
    void contextLoads() {
    }

}
