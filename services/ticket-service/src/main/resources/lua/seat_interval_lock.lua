-- 座位区间占用位图原子锁座（整单 all-or-nothing）
-- Redis 脚本执行期间不插入其他命令，"预检 + 置位"在同一脚本内完成即为原子操作：
-- 一单所有座位的目标站段 bit 全部空闲才统一置 1，任一被占用则整体失败且不产生任何变更。
-- DB 侧 t_ticket 账本与购票公平锁仍保留，作为位图与账本短暂不一致时的最终兜底。
-- KEYS: 本单涉及的车厢座位占用位图 Key（按车厢去重）
-- ARGV[1]: 本单参与位图锁座的座位数 N
-- ARGV[2 + i*2 + j]: 每个座位依次为 [位图 Key 下标(1 起始), 站段数, offset1..offsetN]
local seatCount = tonumber(ARGV[1])
local argIdx = 2
-- 预检：任一目标 bit 已占用即整单失败
for _ = 1, seatCount do
    local keyIdx = tonumber(ARGV[argIdx])
    argIdx = argIdx + 1
    local segCount = tonumber(ARGV[argIdx])
    argIdx = argIdx + 1
    for _ = 1, segCount do
        local offset = ARGV[argIdx]
        argIdx = argIdx + 1
        if redis.call('GETBIT', KEYS[keyIdx], offset) == 1 then
            return 0
        end
    end
end
-- 置位：预检通过后统一写入
argIdx = 2
for _ = 1, seatCount do
    local keyIdx = tonumber(ARGV[argIdx])
    argIdx = argIdx + 1
    local segCount = tonumber(ARGV[argIdx])
    argIdx = argIdx + 1
    for _ = 1, segCount do
        local offset = ARGV[argIdx]
        argIdx = argIdx + 1
        redis.call('SETBIT', KEYS[keyIdx], offset, 1)
    end
end
return 1
