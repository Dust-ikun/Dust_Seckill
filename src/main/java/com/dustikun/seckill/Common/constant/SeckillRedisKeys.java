package com.dustikun.seckill.Common.constant;

/**
 * 秒杀相关 Redis key 统一收口，避免 key 拼写散落在各个 Service 里。
 * <p>
 * 所有 key 都统一使用 {@code {stockId}} 作为 hash tag：这样同一活动的库存 key、已购用户集合
 * 以及去重键必然落在同一个 Redis Cluster slot 上。Lua 脚本要求所有 KEYS 同 slot，
 * 集群模式下才不会报 CROSSSLOT 错误，后续横向扩展成集群时这一段不需要改动。
 */
public final class SeckillRedisKeys {

    private static final String PREFIX = "seckill:";

    private SeckillRedisKeys() {
    }

    /** 库存余量，String 类型，值为十进制整数 */
    public static String stock(Long stockId) {
        return PREFIX + "stock:{" + stockId + "}";
    }

    /** 已下单用户集合，Set 类型，用于「一人一单」去重 */
    public static String boughtUsers(Long stockId) {
        return PREFIX + "bought:{" + stockId + "}";
    }

    /**
     * 「仅回补库存」的一次性去重键。
     * <p>
     * 这种回补不摘标记（标记必须保留），因此无法像 rollback 那样靠 SREM 的返回值做幂等，
     * 只能另起一个一次性键：{@code SET NX EX} 抢占成功者才有资格把库存加回去。
     * 键里带上单号，保证同一笔订单无论被重试多少次都只回补一次。
     * <p>
     * 注意 hash tag 仍然是 {@code {stockId}}，与库存 key 同 slot（回补脚本要求三者同 slot）。
     */
    public static String restoreDedupe(Long stockId, String token) {
        return PREFIX + "restored:{" + stockId + "}:" + token;
    }
}
