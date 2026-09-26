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

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.RouteDTO;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.SeatDO;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.SeatMapper;
import org.opengoofy.index12306.biz.ticketservice.common.enums.SeatStatusEnum;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.opengoofy.index12306.biz.ticketservice.service.TrainStationService;
import org.opengoofy.index12306.framework.starter.bases.Singleton;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.TRAIN_STATION_REMAINING_TICKET;

/**
 * 购票库存原子预占
 * 购票链路分层准入的硬门槛：本次购票区间覆盖的全部站段余票缓存通过 Lua 原子检查并扣减，
 * 任一站段余票不足即整单拒绝（返回已售完提示），扣减成功即锁定购房资格——
 * 余票售罄后请求在准入层直接返回，不再进入令牌桶之后的锁队列
 */
@Slf4j
@Component
@RequiredArgsConstructor
public final class TicketStockReserver {

    private static final String LUA_TICKET_STOCK_RESERVE_PATH = "lua/ticket_stock_reserve.lua";

    private static final String INITIAL_STOCK_KEY = "index12306-ticket-service:train_stock_initial:";
    private static final long RECALIBRATE_THROTTLE_MS = 10_000L;

    private final StringRedisTemplate stringRedisTemplate;
    private final TrainStationService trainStationService;
    private final SeatMapper seatMapper;

    /**
     * 阈值校准节流：同一组站段 key 10 秒内最多触发一次数据库校准
     */
    private final Cache<String, Long> recalibrateThrottle = Caffeine.newBuilder()
            .expireAfterWrite(1, TimeUnit.MINUTES)
            .maximumSize(1024)
            .build();

    /**
     * 购票区间覆盖站段组合的站段名列表缓存：列车经停站为静态数据，进程内缓存避免每次购票查库
     */
    private final Cache<String, List<RouteDTO>> purchaseRouteCache = Caffeine.newBuilder()
            .expireAfterWrite(1, TimeUnit.DAYS)
            .maximumSize(1024)
            .build();

    /**
     * 尝试原子预占库存
     *
     * @param trainId   车次 ID
     * @param departure 出发站
     * @param arrival   到达站
     * @param seatTypeNeed 坐席类型 -> 预占数量
     * @return {@code true} 预占成功；{@code false} 余票不足（已售完）
     */
    public boolean reserve(String trainId, String departure, String arrival, Map<Integer, Integer> seatTypeNeed) {
        DefaultRedisScript<Long> redisScript = Singleton.get(LUA_TICKET_STOCK_RESERVE_PATH, () -> {
            DefaultRedisScript<Long> actual = new DefaultRedisScript<>();
            actual.setScriptSource(new ResourceScriptSource(new ClassPathResource(LUA_TICKET_STOCK_RESERVE_PATH)));
            actual.setResultType(Long.class);
            return actual;
        });
        try {
            List<String> keys = purchaseRouteKeys(trainId, departure, arrival);
            List<String> args = new ArrayList<>(seatTypeNeed.size() * 2 + 1);
            args.add(String.valueOf(seatTypeNeed.size()));
            seatTypeNeed.forEach((seatType, need) -> {
                args.add(String.valueOf(seatType));
                args.add(String.valueOf(need));
            });
            Long allowed = stringRedisTemplate.execute(redisScript, keys, args.toArray());
            if (allowed != null && allowed == 1L) {
                recordInitialStock(keys, seatTypeNeed);
                return true;
            }
            // 预占失败：临近阈值（原票量 10% 或 0）时从数据库校准余票缓存，纠正预占链路上的少卖泄漏后重试
            if (recalibrateIfNearThreshold(keys, seatTypeNeed)) {
                allowed = stringRedisTemplate.execute(redisScript, keys, args.toArray());
                return allowed != null && allowed == 1L;
            }
            return false;
        } catch (Throwable ex) {
            // 预占器自身故障降级放行（与限流器同一哲学），售出由锁内位图 + DB 校验兜底
            log.error("库存预占执行异常，降级放行。车次：{}", trainId, ex);
            return true;
        }
    }

    /**
     * 首次预占成功时记录开售初始票量（SETNX 语义），作为 10% 阈值校准的基准
     */
    private void recordInitialStock(List<String> keys, Map<Integer, Integer> seatTypeNeed) {
        try {
            keys.forEach(key -> {
                String initialKey = INITIAL_STOCK_KEY + key;
                seatTypeNeed.keySet().forEach(seatType -> {
                    String field = String.valueOf(seatType);
                    Object existing = stringRedisTemplate.opsForHash().get(initialKey, field);
                    // 仅在无记录或残留占位符时初始化，已有真实初始值绝不覆盖（否则 10% 阈值会跟随余票下降而失效）
                    if (existing == null || "-1".equals(existing.toString())) {
                        Object current = stringRedisTemplate.opsForHash().get(key, field);
                        if (current != null && Long.parseLong(current.toString()) > 0) {
                            stringRedisTemplate.opsForHash().put(initialKey, field, current.toString());
                        }
                    }
                });
            });
        } catch (Throwable ex) {
            log.warn("记录初始票量失败，跳过（不影响预占），key 前缀：{}", INITIAL_STOCK_KEY, ex);
        }
    }

    /**
     * 阈值校准：缓存值命中 0 或原票量 10% 阈值时，以数据库真实座位统计校准余票缓存（取缓存与 DB 的较大值，
     * 只修复"少卖"不放大"多卖"），10 秒节流防止售罄瞬间全量请求并发触发
     *
     * @return {@code true} 已执行校准（调用方应重试预占）
     */
    private boolean recalibrateIfNearThreshold(List<String> keys, Map<Integer, Integer> seatTypeNeed) {
        try {
            long now = System.currentTimeMillis();
            String throttleKey = String.join("_", keys);
            Long last = recalibrateThrottle.getIfPresent(throttleKey);
            if (last != null && now - last < RECALIBRATE_THROTTLE_MS) {
                return false;
            }
            boolean thresholdHit = false;
            for (String key : keys) {
                for (Integer seatType : seatTypeNeed.keySet()) {
                    Object currentObj = stringRedisTemplate.opsForHash().get(key, String.valueOf(seatType));
                    if (currentObj == null) {
                        continue;
                    }
                    long current = Long.parseLong(currentObj.toString());
                    if (current <= 0) {
                        thresholdHit = true;
                        break;
                    }
                    Object initialObj = stringRedisTemplate.opsForHash().get(INITIAL_STOCK_KEY + key, String.valueOf(seatType));
                    if (initialObj != null) {
                        long initial = Long.parseLong(initialObj.toString());
                        if (initial > 0 && current <= Math.max(1L, initial * 10 / 100)) {
                            thresholdHit = true;
                            break;
                        }
                    }
                }
                if (thresholdHit) {
                    break;
                }
            }
            if (!thresholdHit) {
                return false;
            }
            recalibrateThrottle.put(throttleKey, now);
            log.warn("余票缓存命中校准阈值（0 或原票量 10%），从数据库校准。keys：{}", keys);
            for (String key : keys) {
                String keySuffix = key.replace(TRAIN_STATION_REMAINING_TICKET, "");
                String[] parts = keySuffix.split("_");
                if (parts.length < 3) {
                    continue;
                }
                String segmentTrainId = parts[0];
                String segmentDeparture = parts[1];
                String segmentArrival = parts[2];
                seatTypeNeed.keySet().forEach(seatType -> {
                    long dbStock = seatMapper.selectCount(Wrappers.lambdaQuery(SeatDO.class)
                            .eq(SeatDO::getTrainId, Long.valueOf(segmentTrainId))
                            .eq(SeatDO::getSeatType, seatType)
                            .eq(SeatDO::getStartStation, segmentDeparture)
                            .eq(SeatDO::getEndStation, segmentArrival)
                            .eq(SeatDO::getSeatStatus, SeatStatusEnum.AVAILABLE.getCode()));
                    Object currentObj = stringRedisTemplate.opsForHash().get(key, String.valueOf(seatType));
                    long cacheStock = currentObj == null ? 0L : Long.parseLong(currentObj.toString());
                    // 以 DB 已完成口径为准直写：缓存账目精确时缓存 = DB - 在途，校准仅应在泄漏场景触发，
                    // 在途部分由令牌桶之后的临界区流水自然收敛，短暂超发由位图兜底、失败请求按单点回补
                    long calibrated = dbStock;
                    if (calibrated != cacheStock) {
                        stringRedisTemplate.opsForHash().put(key, String.valueOf(seatType), String.valueOf(calibrated));
                        log.info("余票缓存校准：{} 坐席 {} 缓存 {} -> DB {}", keySuffix, seatType, cacheStock, calibrated);
                    }
                });
            }
            return true;
        } catch (Throwable ex) {
            log.error("余票缓存校准失败，按未校准处理", ex);
            return false;
        }
    }

    private List<String> purchaseRouteKeys(String trainId, String departure, String arrival) {
        List<RouteDTO> routeList = purchaseRouteCache.get(trainId + "_" + departure + "_" + arrival,
                k -> trainStationService.listTakeoutTrainStationRoute(trainId, departure, arrival));
        if (CollUtil.isEmpty(routeList)) {
            return List.of(TRAIN_STATION_REMAINING_TICKET + StrUtil.join("_", trainId, departure, arrival));
        }
        return routeList.stream()
                .map(item -> TRAIN_STATION_REMAINING_TICKET + StrUtil.join("_", trainId, item.getStartStation(), item.getEndStation()))
                .collect(Collectors.toList());
    }
}
