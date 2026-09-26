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

package org.opengoofy.index12306.biz.ticketservice.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opengoofy.index12306.biz.ticketservice.common.enums.SeatStatusEnum;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.SeatDO;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.TrainStationDO;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.SeatMapper;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.TrainStationMapper;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.RouteDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.SeatTypeCountDTO;
import org.opengoofy.index12306.biz.ticketservice.service.SeatService;
import org.opengoofy.index12306.biz.ticketservice.service.TrainStationService;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.dto.TrainPurchaseTicketRespDTO;
import org.opengoofy.index12306.biz.ticketservice.toolkit.SeatBitMapUtil;
import org.opengoofy.index12306.framework.starter.cache.DistributedCache;
import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.LOCK_SEAT_BIT_MAP;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.TRAIN_CARRIAGE_SEAT_STATUS;
import java.util.HashSet;
import java.util.Set;

import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.TRAIN_STATION_CARRIAGE_REMAINING_TICKET;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.TRAIN_STATION_REMAINING_TICKET;

/**
 * 座位接口层实现
 *
 * <p>座位占用状态以 Redis BitMap 为读取与写入的第一入口（对齐 12306 项目余票 BitMap 方案）：
 * 每个车厢一个位图，每个座位按沿途相邻站段各占一个 bit，bit=1 已售出占用、bit=0 空闲。
 * 可用座位查询优先走位图，位图缺失时从 {@code t_seat} 全量重建；
 * 锁座先置位图再落库（条件更新防超卖），释放先落库再清位图——任意失败顺序都保证
 * "位图占用 ≥ 数据库占用"，宁可少卖不会超卖。位图可随时由数据库重建。
 *
 * 公众号：马丁玩编程，回复：加群，添加马哥微信（备注：12306）获取项目资料
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeatServiceImpl extends ServiceImpl<SeatMapper, SeatDO> implements SeatService {

    private final SeatMapper seatMapper;
    private final TrainStationService trainStationService;
    private final DistributedCache distributedCache;
    private final TrainStationMapper trainStationMapper;
    private final RedissonClient redissonClient;

    @Override
    public void initSeatBitMap(String trainId, String carriageNumber) {
        ensureSeatBitMapExists(trainId, carriageNumber, SeatBitMapUtil.buildKey(trainId, carriageNumber));
    }

    @Override
    public List<String> listAvailableSeat(String trainId, String carriageNumber, Integer seatType, String departure, String arrival) {
        if (SeatBitMapUtil.supports(seatType)) {
            return listAvailableSeatFromBitMap(trainId, carriageNumber, seatType, departure, arrival);
        }
        return listAvailableSeatFromDataBase(trainId, carriageNumber, seatType, departure, arrival);
    }

    /**
     * 从座位区间占用位图查询可用座位（先走 Redis BitMap，位图缺失时由数据库重建）
     */
    private List<String> listAvailableSeatFromBitMap(String trainId, String carriageNumber, Integer seatType, String departure, String arrival) {
        String key = SeatBitMapUtil.buildKey(trainId, carriageNumber);
        ensureSeatBitMapExists(trainId, carriageNumber, key);
        if (!distributedCache.hasKey(key)) {
            // 位图初始化被跳过（席别不支持或无座位数据）：回退数据库查询，避免将缺失位图误判为全部空闲
            return listAvailableSeatFromDataBase(trainId, carriageNumber, seatType, departure, arrival);
        }
        List<String> stations = listStationOrdered(trainId);
        int departureIdx = stations.indexOf(departure);
        int arrivalIdx = stations.indexOf(arrival);
        if (departureIdx < 0 || arrivalIdx <= departureIdx) {
            // 站点不在该列车路线上：无任何可用座位
            return new ArrayList<>(0);
        }
        int stationCount = stations.size();
        StringRedisTemplate stringRedisTemplate = (StringRedisTemplate) distributedCache.getInstance();
        byte[] bitmap = stringRedisTemplate.execute((RedisCallback<byte[]>) connection ->
                connection.stringCommands().get(key.getBytes(StandardCharsets.UTF_8)));
        List<String> availableSeatList = new ArrayList<>();
        int rows = SeatBitMapUtil.getRows(seatType);
        for (int row = 1; row <= rows; row++) {
            for (char letter : SeatBitMapUtil.getLetters(seatType).toCharArray()) {
                String seatNumber = String.format("%02d", row) + letter;
                int seatIndex = SeatBitMapUtil.seatIndexOf(seatType, seatNumber);
                if (seatIndex < 0 || !isSeatRangeFree(bitmap, seatIndex, stationCount, departureIdx, arrivalIdx)) {
                    continue;
                }
                availableSeatList.add(seatNumber);
            }
        }
        return availableSeatList;
    }

    /**
     * 从数据库查询可用座位（非高铁席别等未定义座位布局的场景沿用）
     */
    private List<String> listAvailableSeatFromDataBase(String trainId, String carriageNumber, Integer seatType, String departure, String arrival) {
        LambdaQueryWrapper<SeatDO> queryWrapper = Wrappers.lambdaQuery(SeatDO.class)
                .eq(SeatDO::getTrainId, trainId)
                .eq(SeatDO::getCarriageNumber, carriageNumber)
                .eq(SeatDO::getSeatType, seatType)
                .eq(SeatDO::getStartStation, departure)
                .eq(SeatDO::getEndStation, arrival)
                .eq(SeatDO::getSeatStatus, SeatStatusEnum.AVAILABLE.getCode())
                .select(SeatDO::getSeatNumber);
        List<SeatDO> seatDOList = seatMapper.selectList(queryWrapper);
        return seatDOList.stream().map(SeatDO::getSeatNumber).collect(Collectors.toList());
    }

    @Override
    public List<Integer> listSeatRemainingTicket(String trainId, String departure, String arrival, List<String> trainCarriageList) {
        String keySuffix = StrUtil.join("_", trainId, departure, arrival);
        if (distributedCache.hasKey(TRAIN_STATION_CARRIAGE_REMAINING_TICKET + keySuffix)) {
            StringRedisTemplate stringRedisTemplate = (StringRedisTemplate) distributedCache.getInstance();
            List<Object> trainStationCarriageRemainingTicket =
                    stringRedisTemplate.opsForHash().multiGet(TRAIN_STATION_CARRIAGE_REMAINING_TICKET + keySuffix, Arrays.asList(trainCarriageList.toArray()));
            if (CollUtil.isNotEmpty(trainStationCarriageRemainingTicket)) {
                return trainStationCarriageRemainingTicket.stream().map(each -> Integer.parseInt(each.toString())).collect(Collectors.toList());
            }
        }
        SeatDO seatDO = SeatDO.builder()
                .trainId(Long.parseLong(trainId))
                .startStation(departure)
                .endStation(arrival)
                .build();
        return seatMapper.listSeatRemainingTicket(seatDO, trainCarriageList);
    }

    @Override
    public List<String> listUsableCarriageNumber(String trainId, Integer carriageType, String departure, String arrival) {
        LambdaQueryWrapper<SeatDO> queryWrapper = Wrappers.lambdaQuery(SeatDO.class)
                .eq(SeatDO::getTrainId, trainId)
                .eq(SeatDO::getSeatType, carriageType)
                .eq(SeatDO::getStartStation, departure)
                .eq(SeatDO::getEndStation, arrival)
                .eq(SeatDO::getSeatStatus, SeatStatusEnum.AVAILABLE.getCode())
                .groupBy(SeatDO::getCarriageNumber)
                .select(SeatDO::getCarriageNumber);
        List<SeatDO> seatDOList = seatMapper.selectList(queryWrapper);
        return seatDOList.stream().map(SeatDO::getCarriageNumber).collect(Collectors.toList());
    }

    @Override
    public List<SeatTypeCountDTO> listSeatTypeCount(Long trainId, String startStation, String endStation, List<Integer> seatTypes) {
        return seatMapper.listSeatTypeCount(trainId, startStation, endStation, seatTypes);
    }

    @Override
    public void lockSeat(String trainId, String departure, String arrival, List<TrainPurchaseTicketRespDTO> trainPurchaseTicketRespList) {
        // 位图先行：先走 redis bitmap 写入占用，再落数据库——失败顺序保证"位图占用 ≥ 数据库占用"，宁可少卖不会超卖
        Map<String, List<Long>> occupiedBitOffsets = new LinkedHashMap<>();
        List<RouteDTO> routeList = trainStationService.listTakeoutTrainStationRoute(trainId, departure, arrival);
        try {
            List<String> stations = listStationOrdered(trainId);
            int departureIdx = stations.indexOf(departure);
            int arrivalIdx = stations.indexOf(arrival);
            if (departureIdx < 0 || arrivalIdx <= departureIdx) {
                throw new ServiceException("列车站点信息不合法，座位锁定失败");
            }
            int stationCount = stations.size();
            StringRedisTemplate stringRedisTemplate = (StringRedisTemplate) distributedCache.getInstance();
            for (TrainPurchaseTicketRespDTO each : trainPurchaseTicketRespList) {
                if (!SeatBitMapUtil.supports(each.getSeatType())) {
                    continue;
                }
                int seatIndex = SeatBitMapUtil.seatIndexOf(each.getSeatType(), each.getSeatNumber());
                if (seatIndex < 0) {
                    continue;
                }
                // 一次锁座可能跨车厢（如二等座跨车厢降级分配），位图 Key 按座位所在车厢区分
                String bitMapKey = SeatBitMapUtil.buildKey(trainId, each.getCarriageNumber());
                List<Long> offsets = occupiedBitOffsets.computeIfAbsent(bitMapKey, k -> new ArrayList<>());
                for (int segmentIdx = departureIdx; segmentIdx < arrivalIdx; segmentIdx++) {
                    long offset = SeatBitMapUtil.bitOffset(seatIndex, stationCount, segmentIdx);
                    stringRedisTemplate.opsForValue().setBit(bitMapKey, offset, true);
                    offsets.add(offset);
                }
            }
        } catch (ServiceException ex) {
            clearSeatBitMap(occupiedBitOffsets);
            throw ex;
        } catch (Throwable ex) {
            clearSeatBitMap(occupiedBitOffsets);
            log.error("[锁座] 车次：{} 区间：{}→{} 写入座位占用位图失败", trainId, departure, arrival, ex);
            throw new ServiceException("座位锁定失败，请稍后重试");
        }
        // 数据库落库：仅当座位沿途区段均为 AVAILABLE 时才允许锁定，防止位图与数据库短暂不一致时超卖；
        // 同一座位的全部站段组合行合并为一条 UPDATE，减少临界区内的数据库往返
        Set<String> conflictedSeats = new HashSet<>();
        for (TrainPurchaseTicketRespDTO each : trainPurchaseTicketRespList) {
            LambdaUpdateWrapper<SeatDO> updateWrapper = Wrappers.lambdaUpdate(SeatDO.class)
                    .eq(SeatDO::getTrainId, trainId)
                    .eq(SeatDO::getCarriageNumber, each.getCarriageNumber())
                    .eq(SeatDO::getSeatNumber, each.getSeatNumber())
                    .eq(SeatDO::getSeatStatus, SeatStatusEnum.AVAILABLE.getCode())
                    .and(wrapper -> {
                        for (RouteDTO item : routeList) {
                            wrapper.or(x -> x.eq(SeatDO::getStartStation, item.getStartStation())
                                    .eq(SeatDO::getEndStation, item.getEndStation()));
                        }
                    });
            SeatDO updateSeatDO = SeatDO.builder()
                    .seatStatus(SeatStatusEnum.LOCKED.getCode())
                    .build();
            if (seatMapper.update(updateSeatDO, updateWrapper) < routeList.size()) {
                conflictedSeats.add(each.getCarriageNumber() + "_" + each.getSeatNumber());
            }
        }
        if (!conflictedSeats.isEmpty()) {
            // 相信 DB：冲突座位在 DB 中已被占用，位图同步标记占用——清回空闲会固化"幻影空闲"座位，
            // 导致后续请求在同一个座位上反复冲突；未冲突座位随本事务回滚恢复可售，位图一并释放；
            // 同时按扣减对称回补余票缓存，避免冲突造成库存缓存泄漏（缓存小于真实库存引发误拒）
            StringRedisTemplate stringRedisTemplate = (StringRedisTemplate) distributedCache.getInstance();
            List<String> stations = listStationOrdered(trainId);
            int departureIdx = stations.indexOf(departure);
            int arrivalIdx = stations.indexOf(arrival);
            int stationCount = stations.size();
            for (TrainPurchaseTicketRespDTO each : trainPurchaseTicketRespList) {
                if (!SeatBitMapUtil.supports(each.getSeatType())) {
                    continue;
                }
                int seatIndex = SeatBitMapUtil.seatIndexOf(each.getSeatType(), each.getSeatNumber());
                if (seatIndex < 0) {
                    continue;
                }
                String bitMapKey = SeatBitMapUtil.buildKey(trainId, each.getCarriageNumber());
                boolean seatConflict = conflictedSeats.contains(each.getCarriageNumber() + "_" + each.getSeatNumber());
                for (int segmentIdx = departureIdx; segmentIdx < arrivalIdx; segmentIdx++) {
                    stringRedisTemplate.opsForValue().setBit(bitMapKey, SeatBitMapUtil.bitOffset(seatIndex, stationCount, segmentIdx), seatConflict);
                }
            }
            // 余票缓存的回补由预占层单点负责（purchaseTicketsV2 catch），此处只修正位图，避免多重回补
            throw new ServiceException("座位已被占用，请重新选择");
        }
    }

    @Override
    public void unlock(String trainId, String departure, String arrival, List<TrainPurchaseTicketRespDTO> trainPurchaseTicketResults) {
        // 数据库先行释放，再清理位图占用；位图清理失败只会"少卖"不会"超卖"，尽力而为并记录日志
        List<RouteDTO> routeList = trainStationService.listTakeoutTrainStationRoute(trainId, departure, arrival);
        trainPurchaseTicketResults.forEach(each -> routeList.forEach(item -> {
            LambdaUpdateWrapper<SeatDO> updateWrapper = Wrappers.lambdaUpdate(SeatDO.class)
                    .eq(SeatDO::getTrainId, trainId)
                    .eq(SeatDO::getCarriageNumber, each.getCarriageNumber())
                    .eq(SeatDO::getStartStation, item.getStartStation())
                    .eq(SeatDO::getEndStation, item.getEndStation())
                    .eq(SeatDO::getSeatNumber, each.getSeatNumber());
            SeatDO updateSeatDO = SeatDO.builder()
                    .seatStatus(SeatStatusEnum.AVAILABLE.getCode())
                    .build();
            seatMapper.update(updateSeatDO, updateWrapper);
        }));
        try {
            List<String> stations = listStationOrdered(trainId);
            int departureIdx = stations.indexOf(departure);
            int arrivalIdx = stations.indexOf(arrival);
            if (departureIdx < 0 || arrivalIdx <= departureIdx) {
                return;
            }
            int stationCount = stations.size();
            StringRedisTemplate stringRedisTemplate = (StringRedisTemplate) distributedCache.getInstance();
            for (TrainPurchaseTicketRespDTO each : trainPurchaseTicketResults) {
                if (!SeatBitMapUtil.supports(each.getSeatType())) {
                    continue;
                }
                int seatIndex = SeatBitMapUtil.seatIndexOf(each.getSeatType(), each.getSeatNumber());
                if (seatIndex < 0) {
                    continue;
                }
                String bitMapKey = SeatBitMapUtil.buildKey(trainId, each.getCarriageNumber());
                for (int segmentIdx = departureIdx; segmentIdx < arrivalIdx; segmentIdx++) {
                    long offset = SeatBitMapUtil.bitOffset(seatIndex, stationCount, segmentIdx);
                    stringRedisTemplate.opsForValue().setBit(bitMapKey, offset, false);
                }
            }
        } catch (Throwable ex) {
            log.error("[释放座位] 车次：{} 区间：{}→{} 清理座位占用位图失败，座位将保持占用状态直至位图重建", trainId, departure, arrival, ex);
        }
    }

    /**
     * 判断座位在 [出发站, 到达站) 的所有相邻站段 bit 是否全为 0（全 0 才可售）
     */
    private boolean isSeatRangeFree(byte[] bitmap, int seatIndex, int stationCount, int departureIdx, int arrivalIdx) {
        for (int segmentIdx = departureIdx; segmentIdx < arrivalIdx; segmentIdx++) {
            if (SeatBitMapUtil.getBit(bitmap, SeatBitMapUtil.bitOffset(seatIndex, stationCount, segmentIdx))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 车厢座位占用位图缺失时，由数据库 t_seat 全量重建：
     * 先将布局内全部位置置 1（含非物理座位），再将数据库中 AVAILABLE 区段清 0
     */
    private void ensureSeatBitMapExists(String trainId, String carriageNumber, String bitMapKey) {
        if (distributedCache.hasKey(bitMapKey)) {
            return;
        }
        RLock lock = redissonClient.getLock(String.format(LOCK_SEAT_BIT_MAP, trainId));
        lock.lock();
        try {
            if (distributedCache.hasKey(bitMapKey)) {
                return;
            }
            List<String> stations = listStationOrdered(trainId);
            int stationCount = stations.size();
            Integer seatType = lambdaQuery()
                    .eq(SeatDO::getTrainId, Long.valueOf(trainId))
                    .eq(SeatDO::getCarriageNumber, carriageNumber)
                    .last("LIMIT 1")
                    .oneOpt()
                    .map(SeatDO::getSeatType)
                    .orElse(null);
            if (!SeatBitMapUtil.supports(seatType)) {
                log.warn("[座位位图] 车次：{} 车厢：{} 席别不支持位图，跳过初始化", trainId, carriageNumber);
                return;
            }
            int rows = SeatBitMapUtil.getRows(seatType);
            List<SeatDO> seatDOList = lambdaQuery()
                    .eq(SeatDO::getTrainId, Long.valueOf(trainId))
                    .eq(SeatDO::getCarriageNumber, carriageNumber)
                    .list();
            StringRedisTemplate stringRedisTemplate = (StringRedisTemplate) distributedCache.getInstance();
            stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                var stringCommands = connection.stringCommands();
                byte[] keyBytes = bitMapKey.getBytes(StandardCharsets.UTF_8);
                // 布局内全部位置默认占用（含非物理座位与已售/已锁区段）
                for (int seatIndex = 0; seatIndex < rows * 6; seatIndex++) {
                    for (int segmentIdx = 0; segmentIdx < stationCount; segmentIdx++) {
                        stringCommands.setBit(keyBytes, SeatBitMapUtil.bitOffset(seatIndex, stationCount, segmentIdx), true);
                    }
                }
                // 站段占用按"任一覆盖组合行非 AVAILABLE 即占用"聚合：
                // 同一座位的组合行可能存在部分售出的历史混合状态，逐 AVAILABLE 行清位会把实际占用的站段错误标成空闲
                Map<Integer, Set<Integer>> occupiedSegmentsBySeat = new HashMap<>();
                Set<Integer> seenSeatIndexes = new HashSet<>();
                for (SeatDO each : seatDOList) {
                    int seatIndex = SeatBitMapUtil.seatIndexOf(seatType, each.getSeatNumber());
                    if (seatIndex >= 0) {
                        seenSeatIndexes.add(seatIndex);
                    }
                    if (SeatStatusEnum.AVAILABLE.getCode().equals(each.getSeatStatus())) {
                        continue;
                    }
                    int startIdx = stations.indexOf(each.getStartStation());
                    int endIdx = stations.indexOf(each.getEndStation());
                    if (seatIndex < 0 || startIdx < 0 || endIdx <= startIdx) {
                        continue;
                    }
                    Set<Integer> occupied = occupiedSegmentsBySeat.computeIfAbsent(seatIndex, k -> new HashSet<>());
                    for (int segmentIdx = startIdx; segmentIdx < endIdx; segmentIdx++) {
                        occupied.add(segmentIdx);
                    }
                }
                // 仅清"布局内物理位且 DB 存在座位行"的位：非物理位置（无该字母列）与布局有但
                // DB 缺座位行的位置都保持占用——否则会被位图统计当作空闲座位虚增余票，
                // 或被选座命中后在 DB 行校验处必然冲突
                Set<Integer> clearableSeatIndexes = new HashSet<>();
                for (int row = 1; row <= rows; row++) {
                    for (char letter : SeatBitMapUtil.getLetters(seatType).toCharArray()) {
                        int seatIndex = (row - 1) * 6 + "ABCDEF".indexOf(letter);
                        if (seenSeatIndexes.contains(seatIndex)) {
                            clearableSeatIndexes.add(seatIndex);
                        }
                    }
                }
                for (int seatIndex : clearableSeatIndexes) {
                    Set<Integer> occupied = occupiedSegmentsBySeat.getOrDefault(seatIndex, Collections.emptySet());
                    for (int segmentIdx = 0; segmentIdx < stationCount; segmentIdx++) {
                        if (!occupied.contains(segmentIdx)) {
                            stringCommands.setBit(keyBytes, SeatBitMapUtil.bitOffset(seatIndex, stationCount, segmentIdx), false);
                        }
                    }
                }
                return null;
            });
        } finally {
            lock.unlock();
        }
    }

    /**
     * 清理座位占用位图（回滚锁座写入），按车厢分 Key 管道执行
     */
    private void clearSeatBitMap(Map<String, List<Long>> occupiedBitOffsets) {
        if (occupiedBitOffsets == null || occupiedBitOffsets.isEmpty()) {
            return;
        }
        try {
            StringRedisTemplate stringRedisTemplate = (StringRedisTemplate) distributedCache.getInstance();
            occupiedBitOffsets.forEach((key, offsets) -> stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
                for (Long offset : offsets) {
                    connection.stringCommands().setBit(keyBytes, offset, false);
                }
                return null;
            }));
        } catch (Throwable ex) {
            log.error("[锁座] 回滚座位占用位图失败，位图 Key：{}", occupiedBitOffsets.keySet(), ex);
        }
    }

    /**
     * 列车沿途站点有序列表（按插入序即行程序），站名与 t_seat.start_station/end_station 同源
     */
    private List<String> listStationOrdered(String trainId) {
        return trainStationMapper.selectList(Wrappers.lambdaQuery(TrainStationDO.class)
                        .eq(TrainStationDO::getTrainId, Long.valueOf(trainId))
                        .select(TrainStationDO::getDeparture)
                        .orderByAsc(TrainStationDO::getId))
                .stream()
                .map(TrainStationDO::getDeparture)
                .collect(Collectors.toList());
    }
}
