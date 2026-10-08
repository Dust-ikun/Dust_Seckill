package com.dustikun.seckill.Config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;


/**
 * Redis 相关 Bean 装配。
 * <p>
 * StringRedisTemplate 由 Spring Boot 自动装配（key / value / 脚本参数统一使用 String 序列化，
 * 因此传给 Lua 的 ARGV 就是原始字符串，不需要额外处理）。
 * 这里只负责把 classpath 下的 Lua 脚本加载成单例 Bean：
 * DefaultRedisScript 首次执行走 EVALSHA，后续命中服务端脚本缓存只发 SHA1，避免每次传输整段脚本文本。
 */
@Configuration
public class RedisConfig {

    @Bean("seckillDeductScript")
    public RedisScript<Long> seckillDeductScript() {
        return loadScript("lua/seckill_deduct.lua");
    }

    @Bean("seckillRollbackScript")
    public RedisScript<Long> seckillRollbackScript() {
        return loadScript("lua/seckill_rollback.lua");
    }

    /**
     * 「仅回补库存、保留已购标记」脚本。
     * 用于用户此前已经买过（撞 uk_user_stock）时把多余的 Redis 预扣还回去。
     */
    @Bean("seckillRestoreStockScript")
    public RedisScript<Long> seckillRestoreStockScript() {
        return loadScript("lua/seckill_restore_stock.lua");
    }

    /**
     * 对账校准脚本：带值比对（乐观锁）的库存改写。
     * <p>
     * 它取代了原先「对账直接把 Redis SET 成应有值」的写法：读与写之间若有请求完成预扣，
     * 那次 SET 会把预扣抹掉（Redis 多出一件可售），而订单已经落库 —— 用户随后会看到
     * 「抢到了却下单失败」。值比对让这种情况变成「本轮不修」，下一轮基于新值重新判定。
     */
    @Bean("seckillSyncStockScript")
    public RedisScript<Long> seckillSyncStockScript() {
        return loadScript("lua/seckill_sync_stock.lua");
    }

    private RedisScript<Long> loadScript(String classpathLocation) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource(classpathLocation)));
        script.setResultType(Long.class);
        return script;
    }
}
