package com.dustikun.seckill.Config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 注册本地消息表（Outbox）的配置绑定。
 * <p>
 * Outbox 本身不需要额外的 Bean（表访问走 Mapper，投递器是普通组件），
 * 这里只负责把 {@code seckill.outbox.*} 绑成类型安全的配置对象。
 */
@Configuration
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxConfig {
}
