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
import org.opengoofy.index12306.framework.starter.bases.Singleton;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

import java.util.Collections;

import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.TRAIN_STATION_REMAINING_TICKET;

/**
 * 购票限流令牌桶
 * 经典限流语义：令牌按速率补充、突发容量上限。桶容量动态关联车次余票（余票总量 × 容量倍数），
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
     * 尝试获取准入令牌，按车次维度限流（选座临界区锁同样按车次 + 席别粒度竞争）
     * <p>
     * 桶容量动态取值：当前区间余票缓存总量 × 容量倍数（{@code ticket.purchase.rate-limiter.capacity-multiplier}），
     * 票量越大准入越多、临近售罄准入自动收紧；补充速率固定（{@code ticket.purchase.rate-limiter.refill-rate}）
     *
     * @param trainId   车次 ID
     * @param departure 出发站
     * @param arrival   到达站
     * @return {@code true} 允许进入购票临界区；{@code false} 当前请求过多，请稍后重试
     */
    public boolean tryAcquire(String trainId, String departure, String arrival) {
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
            long capacity = currentCapacity(trainId, departure, arrival);
            if (capacity <= 0) {
                return false;
            }
            Long allowed = stringRedisTemplate.execute(
                    redisScript,
                    Collections.singletonList(String.format(TicketPurchaseRateLimiterKey.PURCHASE_RATE_LIMITER_KEY, trainId)),
                    String.valueOf(capacity),
                    String.valueOf(rateLimiterProperties.getRefillRate()),
                    "1",
                    String.valueOf(System.currentTimeMillis()));
            return allowed != null && allowed == 1L;
        } catch (Throwable ex) {
            // 限流器自身故障不应阻断购票主流程，降级放行并告警
            log.error("购票限流令牌桶执行异常，降级放行。车次：{}", trainId, ex);
            return true;
        }
    }

    /**
     * 读取当前区间余票缓存总量并按倍数换算桶容量；
     * 余票缓存由前置责任链余票预判负责装载，本方法读取失败按 0 处理（售罄即拒绝）
     */
    private long currentCapacity(String trainId, String departure, String arrival) {
        String stockKey = TRAIN_STATION_REMAINING_TICKET + StrUtil.join("_", trainId, departure, arrival);
        long stock = 0;
        for (Object each : stringRedisTemplate.opsForHash().values(stockKey)) {
            if (each == null) {
                continue;
            }
            try {
                stock += Long.parseLong(each.toString());
            } catch (NumberFormatException ignored) {
            }
        }
        return (long) Math.ceil(stock * rateLimiterProperties.getCapacityMultiplier());
    }

    /**
     * 限流令牌桶 Key 常量
     */
    private static final class TicketPurchaseRateLimiterKey {

        /**
         * 购票限流令牌桶 Key，Key Prefix + 列车ID
         */
        public static final String PURCHASE_RATE_LIMITER_KEY = "index12306-ticket-service:purchase_rate_limiter:%s";
    }
}
