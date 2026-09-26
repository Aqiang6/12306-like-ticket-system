-- 购票限流令牌桶（经典限流语义：按速率补充令牌，突发容量上限）
-- 与余票库存无关：令牌只在余票充足期间控制进入选座临界区的速率，防超卖由锁内位图 + DB 保证
-- KEYS[1] 令牌桶 key
-- ARGV[1] 桶容量（突发上限）
-- ARGV[2] 每秒补充令牌数（持续准入吞吐）
-- ARGV[3] 本次请求消耗令牌数
-- ARGV[4] 当前毫秒时间戳（应用侧传入，规避低版本 Redis Lua 不支持 TIME 命令的问题）
local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refillRate = tonumber(ARGV[2])
local requested = tonumber(ARGV[3])
local now = tonumber(ARGV[4])

local bucket = redis.call('HMGET', key, 'tokens', 'last_refill_ms')
local tokens = tonumber(bucket[1])
local lastRefillMs = tonumber(bucket[2])

if tokens == nil or lastRefillMs == nil then
    tokens = capacity
    lastRefillMs = now
else
    local deltaMs = now - lastRefillMs
    if deltaMs > 0 then
        tokens = math.min(capacity, tokens + deltaMs / 1000 * refillRate)
    end
end

local allowed = 0
if tokens >= requested then
    tokens = tokens - requested
    allowed = 1
end

redis.call('HMSET', key, 'tokens', tokens, 'last_refill_ms', now)
-- 空闲过期：按当前速率补满整桶所需时间的 2 倍，最低保底 60 秒
local ttlMs = math.max(60000, math.ceil(capacity / refillRate * 2 * 1000))
redis.call('PEXPIRE', key, ttlMs)

return allowed
