/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.ratelimit;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.PurchaseTicketPassengerDetailDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.req.PurchaseTicketReqDTO;
import org.opengoofy.index12306.framework.starter.bases.Singleton;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.TRAIN_STATION_REMAINING_TICKET;

/**
 * 购票限流令牌桶
 * 经典限流语义：令牌按速率补充、突发容量上限。桶按车次、乘车区间、席别隔离，
 * 容量动态关联该区间、该席别的可用座位数（余票 × 容量倍数），
 * 补充速率固定。购票链路分层准入中位于余票缓存只读预判之后、两级公平锁之前：
 * 无票请求已在预判层秒拒（不消耗准入额度），通过本层后进入选座临界区，
 * 超卖由锁内座位位图 + DB 校验兜底保证
 */
@Slf4j
@Component
@RequiredArgsConstructor
public final class TicketPurchaseRateLimiter {

    private static final String LUA_TICKET_PURCHASE_RATE_LIMITER_PATH = "lua/ticket_purchase_rate_limiter.lua";

    private final StringRedisTemplate stringRedisTemplate;
    private final TicketPurchaseRateLimiterProperties rateLimiterProperties;

    /**
     * 尝试获取准入令牌；多席别订单在一个 Lua 调用中一次性领取，避免部分席别扣令牌。
     * <p>
     * 桶容量动态取值：当前区间、当前席别的余票快照 × 容量倍数；补充速率固定。
     *
     * @param requestParam 购票请求
     * @return {@code true} 允许进入购票临界区；{@code false} 当前请求过多，请稍后重试
     */
    public boolean tryAcquire(PurchaseTicketReqDTO requestParam) {
        if (Boolean.FALSE.equals(rateLimiterProperties.getEnabled())) {
            return true;
        }
        DefaultRedisScript<Long> redisScript = Singleton.get(LUA_TICKET_PURCHASE_RATE_LIMITER_PATH, () -> {
            DefaultRedisScript<Long> actual = new DefaultRedisScript<>();
            actual.setScriptSource(new ResourceScriptSource(new ClassPathResource(LUA_TICKET_PURCHASE_RATE_LIMITER_PATH)));
            actual.setResultType(Long.class);
            return actual;
        });
        try {
            Map<Integer, Long> demand = requestParam.getPassengers().stream()
                    .collect(Collectors.groupingBy(PurchaseTicketPassengerDetailDTO::getSeatType,
                            TreeMap::new, Collectors.counting()));
            List<String> keys = new ArrayList<>(demand.size());
            List<String> args = new ArrayList<>(2 + demand.size() * 2);
            args.add(String.valueOf(System.currentTimeMillis()));
            args.add(String.valueOf(rateLimiterProperties.getRefillRate()));
            for (Map.Entry<Integer, Long> entry : demand.entrySet()) {
                long capacity = currentCapacity(requestParam.getTrainId(), requestParam.getDeparture(),
                        requestParam.getArrival(), entry.getKey());
                if (capacity < entry.getValue()) {
                    return false;
                }
                keys.add(String.format(TicketPurchaseRateLimiterKey.PURCHASE_RATE_LIMITER_KEY,
                        requestParam.getTrainId(), requestParam.getDeparture(), requestParam.getArrival(), entry.getKey()));
                args.add(String.valueOf(capacity));
                args.add(String.valueOf(entry.getValue()));
            }
            Long allowed = stringRedisTemplate.execute(
                    redisScript, keys, args.toArray(new String[0]));
            return allowed != null && allowed == 1L;
        } catch (Throwable ex) {
            // 限流器自身故障不应阻断购票主流程，降级放行并告警
            log.error("购票限流令牌桶执行异常，降级放行。车次：{}", requestParam.getTrainId(), ex);
            return true;
        }
    }

    /**
     * 读取当前区间、当前席别余票缓存并按倍数换算桶容量；
     * 余票缓存由前置责任链余票预判负责装载，本方法读取失败按 0 处理（售罄即拒绝）
     */
    private long currentCapacity(String trainId, String departure, String arrival, Integer seatType) {
        String stockKey = TRAIN_STATION_REMAINING_TICKET + StrUtil.join("_", trainId, departure, arrival);
        Object value = stringRedisTemplate.opsForHash().get(stockKey, String.valueOf(seatType));
        long stock = value == null ? 0L : Long.parseLong(value.toString());
        return (long) Math.ceil(stock * rateLimiterProperties.getCapacityMultiplier());
    }

    /**
     * 限流令牌桶 Key 常量
     */
    private static final class TicketPurchaseRateLimiterKey {

        /**
         * 购票限流令牌桶 Key，按车次、区间和席别隔离
         */
        public static final String PURCHASE_RATE_LIMITER_KEY = "index12306-ticket-service:purchase_rate_limiter:%s_%s_%s_%s";
    }
}
