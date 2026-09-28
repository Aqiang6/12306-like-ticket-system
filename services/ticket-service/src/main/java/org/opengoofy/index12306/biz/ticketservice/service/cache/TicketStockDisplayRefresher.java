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

package org.opengoofy.index12306.biz.ticketservice.service.cache;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.CarriageDO;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.CarriageMapper;
import org.opengoofy.index12306.biz.ticketservice.toolkit.SeatBitMapAssembler;
import org.opengoofy.index12306.biz.ticketservice.toolkit.SeatBitMapUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 余票缓存刷新器：余票缓存为只读展示层（"按钮亮不亮"），不做准入计数（准入由令牌桶负责）。
 * 周期（默认 3 秒）从座位占用位图 pipeline 拉取位图、本地聚合出各站段组合 × 坐席的可售数，
 * 周期性重写余票缓存（TRAIN_STATION_REMAINING_TICKET）；令牌桶容量与首页余票均读该缓存。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TicketStockDisplayRefresher {

    private static final String DISPLAY_KEY = RedisKeyConstant.TRAIN_STATION_REMAINING_TICKET;
    private static final String BITMAP_KEY = RedisKeyConstant.TRAIN_CARRIAGE_SEAT_STATUS;
    private static final String SOLD_OUT_KEY = RedisKeyConstant.TRAIN_INTERVAL_SOLD_OUT;

    /**
     * 售罄广播自愈 TTL：刷新器每 3s 续期，车次退出活跃集后标志最多残留 15s
     */
    private static final long SOLD_OUT_TTL_SECONDS = 15L;

    private static final byte[] SOLD_OUT_VALUE = "1".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    private final StringRedisTemplate stringRedisTemplate;
    private final CarriageMapper carriageMapper;
    private final SeatBitMapAssembler seatBitMapAssembler;
    private final org.opengoofy.index12306.biz.ticketservice.service.SeatService seatService;

    /**
     * 活跃车次登记：被首页查询/购票触达的车次才参与周期刷新，TTL 过后自动退出
     */
    private final Cache<String, Boolean> activeTrains = Caffeine.newBuilder()
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .maximumSize(4096)
            .build();

    /**
     * 车厢列表缓存：静态数据，低频过期
     */
    private final Cache<String, List<CarriageDO>> carriageCache = Caffeine.newBuilder()
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .maximumSize(4096)
            .build();

    /**
     * 站点顺序缓存：静态数据
     */
    private final Cache<String, List<String>> stationCache = Caffeine.newBuilder()
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .maximumSize(4096)
            .build();

    private ScheduledExecutorService scheduler;

    @PostConstruct
    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "stock-display-refresher");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::refreshAll, 3, 3, TimeUnit.SECONDS);
        log.info("余票展示层刷新器已启动，刷新周期 3s");
    }

    @PreDestroy
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /**
     * 登记活跃车次：首页查询与购票入口调用，命中过的车次才会被周期刷新
     */
    public void touch(String trainId) {
        if (StrUtil.isNotBlank(trainId)) {
            activeTrains.put(trainId, Boolean.TRUE);
        }
    }

    /**
     * 当前活跃车次快照（供对账任务复用同一活跃集合，避免重复维护）
     */
    public java.util.Set<String> activeTrainIds() {
        return activeTrains.asMap().keySet();
    }

    /**
     * 区间售罄广播查询：任一所需席别在 车次×区间 的展示余票被刷新为 0 即返回 true。
     * 标志由刷新周期维护（余票为 0 → 置位并续期；恢复 > 0 → 清除），座位位图仍是可售性的最终裁决。
     */
    public boolean anySoldOut(String trainId, String departure, String arrival, java.util.Collection<Integer> seatTypes) {
        if (CollUtil.isEmpty(seatTypes)) {
            return false;
        }
        List<String> keys = seatTypes.stream()
                .map(seatType -> soldOutKey(trainId, departure, arrival, seatType))
                .collect(Collectors.toList());
        List<String> values = stringRedisTemplate.opsForValue().multiGet(keys);
        return values != null && values.stream().anyMatch(java.util.Objects::nonNull);
    }

    private String soldOutKey(String trainId, String departure, String arrival, Integer seatType) {
        return SOLD_OUT_KEY + trainId + "_" + departure + "_" + arrival + "_" + seatType;
    }

    private void refreshAll() {
        try {
            String[] trains = activeTrains.asMap().keySet().toArray(new String[0]);
            if (trains.length > 0) {
                log.info("余票展示层刷新周期执行，活跃车次：{}", String.join(",", trains));
            }
            for (String trainId : trains) {
                try {
                    refreshBitmapTrain(trainId);
                } catch (Throwable ex) {
                    log.warn("余票展示层刷新失败，车次：{}", trainId, ex);
                }
            }
        } catch (Throwable ex) {
            log.error("余票展示层刷新周期执行异常", ex);
        }
    }

    private void refreshBitmapTrain(String trainId) {
        List<CarriageDO> carriages = carriageCache.get(trainId,
                k -> carriageMapper.selectList(Wrappers.lambdaQuery(CarriageDO.class).eq(CarriageDO::getTrainId, Long.valueOf(trainId))
                        .in(CarriageDO::getCarriageType, 0, 1, 2)));
        if (CollUtil.isEmpty(carriages)) {
            log.warn("余票展示层刷新跳过：车次 {} 无车厢数据", trainId);
            return;
        }
        List<String> stations = stationCache.get(trainId, seatBitMapAssembler::listStationOrdered);
        if (CollUtil.isEmpty(stations) || stations.size() < 2) {
            log.warn("余票展示层刷新跳过：车次 {} 站点数据不足", trainId);
            return;
        }
        int stationCount = stations.size();
        List<String> bitmapKeys = carriages.stream()
                .map(each -> BITMAP_KEY + trainId + "_" + each.getCarriageNumber())
                .collect(Collectors.toList());
        // 位图以"车厢"为 Key、以"座位 × 相邻站段"为 bit；pipeline 原始字节读取，避免模板字符串反序列化损坏二进制
        List<byte[]> bitmaps = stringRedisTemplate.execute((org.springframework.data.redis.core.RedisCallback<List<byte[]>>) connection -> {
            List<byte[]> results = new ArrayList<>(bitmapKeys.size());
            for (String key : bitmapKeys) {
                results.add(connection.stringCommands().get(key.getBytes(StandardCharsets.UTF_8)));
            }
            return results;
        });
        // 位图缺失 = 尚未预热：触发构建（放票初始化语义），本轮不写展示数据，下一周期位图就绪后再统计
        List<String> missingCarriages = new ArrayList<>();
        for (int i = 0; i < carriages.size(); i++) {
            if (bitmaps.get(i) == null) {
                missingCarriages.add(carriages.get(i).getCarriageNumber());
            }
        }
        if (!missingCarriages.isEmpty()) {
            missingCarriages.forEach(each -> seatService.initSeatBitMap(trainId, each));
            log.info("余票展示层预热车厢位图，车次：{}，车厢：{}", trainId, missingCarriages);
            return;
        }
        // counts[seatType][comboIdx] = 该坐席在该站段组合上整段可售的座位数
        Map<Integer, int[]> countsBySeatType = new HashMap<>();
        List<int[]> comboIndexes = new ArrayList<>();
        for (int dep = 0; dep < stationCount - 1; dep++) {
            for (int arr = dep + 1; arr < stationCount; arr++) {
                comboIndexes.add(new int[]{dep, arr});
            }
        }
        for (int i = 0; i < carriages.size(); i++) {
            CarriageDO carriage = carriages.get(i);
            byte[] bitmap = bitmaps.get(i);
            int[] comboCounts = countsBySeatType.computeIfAbsent(carriage.getCarriageType(), k -> new int[stationCount * (stationCount - 1) / 2]);
            int rows6 = SeatBitMapUtil.getRows(carriage.getCarriageType()) * 6;
            for (int c = 0; c < comboIndexes.size(); c++) {
                int dep = comboIndexes.get(c)[0];
                int arr = comboIndexes.get(c)[1];
                int count = 0;
                for (int seatIndex = 0; seatIndex < rows6; seatIndex++) {
                    boolean allFree = true;
                    for (int segmentIdx = dep; segmentIdx < arr; segmentIdx++) {
                        long offset = (long) seatIndex * stationCount + segmentIdx;
                        int bit = (bitmap[(int) (offset >>> 3)] >>> (7 - (int) (offset & 7))) & 1;
                        if (bit != 0) {
                            allFree = false;
                            break;
                        }
                    }
                    if (allFree) {
                        count++;
                    }
                }
                comboCounts[c] += count;
            }
        }
        // 写展示缓存：每个站段组合一个 Hash，field = 坐席类型
        writeDisplayKeys(trainId, stations, countsBySeatType);
    }

    private void writeDisplayKeys(String trainId, List<String> stations, Map<Integer, int[]> countsBySeatType) {
        List<byte[]> soldOutKeys = new ArrayList<>();
        List<byte[]> restoredKeys = new ArrayList<>();
        stringRedisTemplate.executePipelined((org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
            int comboIdx = 0;
            for (int dep = 0; dep < stations.size() - 1; dep++) {
                for (int arr = dep + 1; arr < stations.size(); arr++) {
                    String displayKey = DISPLAY_KEY + trainId + "_" + stations.get(dep) + "_" + stations.get(arr);
                    byte[] keyBytes = displayKey.getBytes(StandardCharsets.UTF_8);
                    for (Map.Entry<Integer, int[]> entry : countsBySeatType.entrySet()) {
                        int count = entry.getValue()[comboIdx];
                        connection.hashCommands().hSet(keyBytes, String.valueOf(entry.getKey()).getBytes(StandardCharsets.UTF_8),
                                String.valueOf(count).getBytes(StandardCharsets.UTF_8));
                        // 区间售罄广播：余票刷新为 0 → 置位（15s 自愈 TTL），恢复 > 0 → 清除
                        byte[] flagKey = soldOutKey(trainId, stations.get(dep), stations.get(arr), entry.getKey())
                                .getBytes(StandardCharsets.UTF_8);
                        if (count == 0) {
                            soldOutKeys.add(flagKey);
                        } else {
                            restoredKeys.add(flagKey);
                        }
                    }
                    comboIdx++;
                }
            }
            return null;
        });
        if (!soldOutKeys.isEmpty() || !restoredKeys.isEmpty()) {
            stringRedisTemplate.executePipelined((org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
                for (byte[] key : soldOutKeys) {
                    connection.stringCommands().set(key, SOLD_OUT_VALUE,
                            org.springframework.data.redis.core.types.Expiration.seconds(SOLD_OUT_TTL_SECONDS),
                            org.springframework.data.redis.connection.RedisStringCommands.SetOption.UPSERT);
                }
                for (byte[] key : restoredKeys) {
                    connection.keyCommands().del(key);
                }
                return null;
            });
        }
        log.debug("余票展示层已刷新，车次：{}，售罄标志 {} 个、恢复 {} 个", trainId, soldOutKeys.size(), restoredKeys.size());
    }

}
