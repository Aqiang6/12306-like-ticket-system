-- 购票库存原子预占：本次购票区间覆盖的全部站段余票缓存，任一坐席余票不足即整单拒绝，
-- 全部充足则原子扣减——扣减成功即获得购票资格，后续选座/订单失败由调用方按相同口径回补
-- KEYS[i] 站段余票缓存 key（购买区间覆盖的全部站段组合）
-- ARGV[1] 预占组合数 K；其后每两个参数一组：坐席类型、预占数量
local K = tonumber(ARGV[1])
for i = 1, #KEYS do
    for j = 0, K - 1 do
        local seatType = ARGV[2 + j * 2]
        local need = tonumber(ARGV[3 + j * 2])
        local stock = redis.call('HGET', KEYS[i], seatType)
        if stock == false or tonumber(stock) < need then
            return 0
        end
    end
end
for i = 1, #KEYS do
    for j = 0, K - 1 do
        redis.call('HINCRBY', KEYS[i], ARGV[2 + j * 2], -tonumber(ARGV[3 + j * 2]))
    end
end
return 1
