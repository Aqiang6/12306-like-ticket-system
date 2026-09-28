-- All requested interval/seat-type buckets are consumed atomically.
-- KEYS[i] bucket; ARGV[1] current time (ms); ARGV[2] refill per second;
-- ARGV[3 + (i-1)*2] capacity; the following argument is requested tokens.
local now = tonumber(ARGV[1])
local refillRate = tonumber(ARGV[2])
local buckets = {}
local allowed = 1

for i, key in ipairs(KEYS) do
    local capacity = tonumber(ARGV[3 + (i - 1) * 2])
    local requested = tonumber(ARGV[4 + (i - 1) * 2])
    local previous = redis.call('HMGET', key, 'tokens', 'last_refill_ms')
    local tokens = tonumber(previous[1])
    local lastRefillMs = tonumber(previous[2])
    if tokens == nil or lastRefillMs == nil then
        tokens = capacity
    elseif now > lastRefillMs then
        tokens = math.min(capacity, tokens + (now - lastRefillMs) / 1000 * refillRate)
    end
    if tokens < requested then
        allowed = 0
    end
    buckets[i] = {key = key, capacity = capacity, requested = requested, tokens = tokens}
end

for _, bucket in ipairs(buckets) do
    local tokens = bucket.tokens
    if allowed == 1 then
        tokens = tokens - bucket.requested
    end
    redis.call('HMSET', bucket.key, 'tokens', tokens, 'last_refill_ms', now)
    local ttlMs = math.max(60000, math.ceil(bucket.capacity / refillRate * 2 * 1000))
    redis.call('PEXPIRE', bucket.key, ttlMs)
end

return allowed
