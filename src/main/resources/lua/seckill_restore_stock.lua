--[[
  仅回补库存、保留用户「已购」标记（阶段 4 评审修复）

  使用场景：落库时命中 uk_user_stock，说明该用户对这件商品**此前已经有订单**。
  本次请求在 Redis 侧的预扣是多余的，必须把库存还回去；
  但该用户确实持有订单，标记必须保留 —— 否则用户在 Redis 侧会被重新放行，
  陷入「Redis 放行 → DB 拦截 → 回补 → 再放行」的死循环。

  与 seckill_rollback.lua 的分工：
    rollback          回补「库存 + 摘掉标记」→ 这次下单根本不该成立（落库失败 / 投递失败）
    restoreStockOnly  回补「库存」、保留标记  → 用户其实已经买过，这次预扣是多余的一次

  幂等保护：本脚本没有标记可摘（标记本来就要留着），因此改用一次性去重键。
  KEYS[3] 由调用方按「活动 + 单号」构造；只有 SET NX 抢占成功的调用才有资格 INCRBY，
  重复调用返回 -3 且不改动任何数据。

  KEYS[1] = 库存 key        seckill:stock:{stockId}
  KEYS[2] = 已购集合 key    seckill:bought:{stockId}
  KEYS[3] = 回补去重键      seckill:restored:{stockId}:{订单号或任务标识}
  ARGV[1] = userId
  ARGV[2] = 回补数量
  ARGV[3] = 去重键 TTL（秒）

  返回：
    >= 0 : 回补成功，返回值为回补后的剩余库存
    -1   : 入参非法（数量 <= 0）
    -2   : 库存 key 不存在（活动已结束 / 缓存已清理），未修改任何数据
    -3   : 幂等命中（此前已回补过），未修改任何数据

  注意：三个 key 都用 {stockId} 作为 hash tag，保证同 slot（集群模式下 Lua 要求所有 KEYS 同 slot）。
--]]

local stockKey  = KEYS[1]
local boughtKey = KEYS[2]
local dedupeKey = KEYS[3]

local userId = ARGV[1]
local num    = tonumber(ARGV[2])
local ttl    = tonumber(ARGV[3])

if num == nil or num <= 0 then
    return -1
end

-- 活动已结束 / 缓存已清理：库存 key 都没了，没什么可还的。
-- 刻意不抢占去重键，保持此分支「纯只读」。
if redis.call('EXISTS', stockKey) == 0 then
    return -2
end

-- 抢占一次性回补权。SET NX 失败 = 已经被别的调用补过，直接返回幂等命中
local acquired = redis.call('SET', dedupeKey, '1', 'EX', ttl, 'NX')
if not acquired then
    return -3
end

-- 该用户确实持有订单，确保标记在集合里（SADD 本身幂等）
redis.call('SADD', boughtKey, userId)

return redis.call('INCRBY', stockKey, num)
