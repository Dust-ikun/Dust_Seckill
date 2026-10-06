--[[
  秒杀库存原子扣减脚本

  把「查库存 -> 判断 -> 扣减 -> 用户去重」四步合并成 Redis 单线程内的一条原子脚本执行，
  彻底消除 check-then-act 竞态；同时把数据库行锁热点转化为纯内存操作。

  KEYS[1] = 库存 key          seckill:stock:{stockId}
  KEYS[2] = 已购用户集合 key  seckill:bought:{stockId}
  ARGV[1] = userId
  ARGV[2] = 扣减数量
  ARGV[3] = 已购集合 TTL（秒）：仅在该集合尚无 TTL 时设置一次，绝不随下单刷新

  返回：
    >= 0 : 扣减成功，返回值为扣减后的剩余库存
    -1   : 库存不足
    -2   : 重复下单（该用户已在已购集合中）
    -3   : 活动未预热（库存 key 不存在）
    -4   : 参数非法（扣减数量缺失或 <= 0）

  注意：两个 key 都使用 {stockId} 作为 hash tag，保证落在同一个 Redis Cluster slot，
  后续横向扩展成集群时脚本才能正常执行。
--]]

local stockKey  = KEYS[1]
local boughtKey = KEYS[2]
local userId    = ARGV[1]
local num       = tonumber(ARGV[2])

-- 校验入参，避免非法数量导致库存被扣成负数
if num == nil or num <= 0 then
    return -4
end

-- 一人一单：先做用户级去重，同一用户重复请求在这里就被拦住，不会进入扣减逻辑
if redis.call('SISMEMBER', boughtKey, userId) == 1 then
    return -2
end

local stock = redis.call('GET', stockKey)
if not stock then
    -- 库存未预热，直接拒绝，避免绕过 Redis 走 DB 造成双写数据不一致
    return -3
end

stock = tonumber(stock)
if stock < num then
    return -1
end

redis.call('DECRBY', stockKey, num)
redis.call('SADD', boughtKey, userId)

-- 已购集合 TTL 只设一次、不随下单刷新：预热时集合刚被 DEL（对空键 EXPIRE 无效），
-- 因此锚定在「键创建后的第一次 SADD」；TTL == -1 表示从未设过，命中才补设。
-- 到期时间固定 = 首次写入 + TTL，仅作为 clear() 缺席时的内存回收兜底，
-- 取值必须长于活动全程 + 滞后重试窗口（见 StockCacheService#BOUGHT_SET_TTL_SECONDS）。
if redis.call('TTL', boughtKey) == -1 then
    redis.call('EXPIRE', boughtKey, tonumber(ARGV[3]))
end

return stock - num
