-- 原子性获取给定key，若key存在返回其值，若key不存在则设置key并返回null
local key = KEYS[1]
local value = ARGV[1]
local expire_time_ms = ARGV[2]

local set_result = redis.call('SETNX', key, value)
if set_result == 1 then
    redis.call('PEXPIRE', key, expire_time_ms)
    return nil
else
    return redis.call('GET', key)
end
