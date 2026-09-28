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

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opengoofy.index12306.biz.ticketservice.common.enums.TicketStatusEnum;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.SeatDO;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.TicketDO;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.SeatMapper;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.TicketMapper;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.CarriageSeatCountDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.SeatTypeCountDTO;
import org.opengoofy.index12306.biz.ticketservice.service.SeatService;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.dto.TrainPurchaseTicketRespDTO;
import org.opengoofy.index12306.biz.ticketservice.toolkit.SeatBitMapAssembler;
import org.opengoofy.index12306.biz.ticketservice.toolkit.SeatBitMapUtil;
import org.opengoofy.index12306.framework.starter.cache.DistributedCache;
import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.data.redis.connection.RedisStringCommands.SetOption;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


/**
 * 座位接口层实现
 *
 * <p>库存模型（重构后）：Redis 座位区间占用位图是热路径唯一准入（锁座经 Lua 脚本"预检 + 置位"整单原子），
 * {@code t_ticket} 售卖区间账本是持久事实，{@code t_seat} 仅为物理座位注册表。
 * 位图可随时由"注册表 + 账本"全量组装，内存构建后以 {@code SET NX} 一次写入，
 * 无需分布式锁（NX 天然裁决唯一写入者）；可用座位查询优先走位图，位图缺失时由账本重建。
 *
 * <p>防超卖层次：位图 Lua CAS（热路径唯一裁决）→ 购票公平锁（trainId+seatType 串行化）
 * → 对账任务周期比对位图与账本并修复漂移。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeatServiceImpl extends ServiceImpl<SeatMapper, SeatDO> implements SeatService {

    private static final String LUA_SEAT_INTERVAL_LOCK_PATH = "lua/seat_interval_lock.lua";

    private final SeatMapper seatMapper;
    private final TicketMapper ticketMapper;
    private final SeatBitMapAssembler seatBitMapAssembler;
    private final DistributedCache distributedCache;

    /**
     * 整单原子锁座脚本：预检全部座位目标站段 bit 空闲才统一置 1，任一占用则整单失败
     */
    private static final RedisScript<Long> SEAT_INTERVAL_LOCK_SCRIPT;

    static {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource(LUA_SEAT_INTERVAL_LOCK_PATH)));
        script.setResultType(Long.class);
        SEAT_INTERVAL_LOCK_SCRIPT = script;
    }

    @Override
    public void initSeatBitMap(String trainId, String carriageNumber) {
        ensureSeatBitMapExists(trainId, carriageNumber, SeatBitMapUtil.buildKey(trainId, carriageNumber));
    }

    @Override
    public List<String> listAvailableSeat(String trainId, String carriageNumber, Integer seatType, String departure, String arrival) {
        if (!SeatBitMapUtil.supports(seatType)) {
            throw new ServiceException("不支持的座位类型");
        }
        return listAvailableSeatFromBitMap(trainId, carriageNumber, seatType, departure, arrival);
    }

    /**
     * 从座位区间占用位图查询可用座位（位图缺失时由注册表 + 账本组装）
     */
    private List<String> listAvailableSeatFromBitMap(String trainId, String carriageNumber, Integer seatType, String departure, String arrival) {
        String key = SeatBitMapUtil.buildKey(trainId, carriageNumber);
        ensureSeatBitMapExists(trainId, carriageNumber, key);
        if (!distributedCache.hasKey(key)) {
            throw new ServiceException("座位位图未就绪，请稍后重试");
        }
        List<String> stations = seatBitMapAssembler.listStationOrdered(trainId);
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

    @Override
    public List<Integer> listSeatRemainingTicket(String trainId, String departure, String arrival, List<String> trainCarriageList) {
        // 按账本重叠语义统计各车厢可用座位数，并按入参车厢顺序对齐（缺失车厢计 0）
        List<CarriageSeatCountDTO> countList = seatMapper.listCarriageSeatCount(Long.valueOf(trainId), departure, arrival, trainCarriageList);
        Map<String, Integer> countByCarriage = countList.stream()
                .collect(Collectors.toMap(CarriageSeatCountDTO::getCarriageNumber, CarriageSeatCountDTO::getSeatCount, (a, b) -> a));
        return trainCarriageList.stream()
                .map(carriage -> countByCarriage.getOrDefault(carriage, 0))
                .collect(Collectors.toList());
    }

    @Override
    public List<String> listUsableCarriageNumber(String trainId, Integer carriageType, String departure, String arrival) {
        // 注册表提供候选车厢（静态数据），再按账本重叠语义保留区间内仍有可用座位的车厢
        List<String> candidateCarriages = lambdaQuery()
                .eq(SeatDO::getTrainId, Long.valueOf(trainId))
                .eq(SeatDO::getSeatType, carriageType)
                .groupBy(SeatDO::getCarriageNumber)
                .select(SeatDO::getCarriageNumber)
                .list()
                .stream()
                .map(SeatDO::getCarriageNumber)
                .sorted(Comparator.naturalOrder())
                .collect(Collectors.toList());
        if (candidateCarriages.isEmpty()) {
            return candidateCarriages;
        }
        List<CarriageSeatCountDTO> countList = seatMapper.listCarriageSeatCount(Long.valueOf(trainId), departure, arrival, candidateCarriages);
        return countList.stream()
                .filter(each -> each.getSeatCount() != null && each.getSeatCount() > 0)
                .map(CarriageSeatCountDTO::getCarriageNumber)
                .sorted(Comparator.naturalOrder())
                .collect(Collectors.toList());
    }

    @Override
    public List<SeatTypeCountDTO> listSeatTypeCount(Long trainId, String startStation, String endStation, List<Integer> seatTypes) {
        return seatMapper.listSeatTypeCount(trainId, startStation, endStation, seatTypes);
    }

    @Override
    public void lockSeat(String trainId, String departure, String arrival, List<TrainPurchaseTicketRespDTO> trainPurchaseTicketRespList) {
        // 位图先行：Lua 脚本内"预检 + 置位"整单原子（all-or-nothing），随后账本落库由调用方事务完成。
        List<String> stations = seatBitMapAssembler.listStationOrdered(trainId);
        int departureIdx = stations.indexOf(departure);
        int arrivalIdx = stations.indexOf(arrival);
        if (departureIdx < 0 || arrivalIdx <= departureIdx) {
            throw new ServiceException("列车站点信息不合法，座位锁定失败");
        }
        int stationCount = stations.size();
        StringRedisTemplate stringRedisTemplate = (StringRedisTemplate) distributedCache.getInstance();
        // 一次锁座可能跨车厢（如二等座跨车厢降级分配）：按位图 Key 分组，组内记录各座位站段偏移
        Map<String, List<Long>> occupiedBitOffsets = new LinkedHashMap<>();
        for (TrainPurchaseTicketRespDTO each : trainPurchaseTicketRespList) {
            int seatIndex = SeatBitMapUtil.seatIndexOf(each.getSeatType(), each.getSeatNumber());
            if (seatIndex < 0) {
                throw new ServiceException("座位类型或座位编号不合法");
            }
            String bitMapKey = SeatBitMapUtil.buildKey(trainId, each.getCarriageNumber());
            List<Long> offsets = occupiedBitOffsets.computeIfAbsent(bitMapKey, k -> new ArrayList<>());
            for (int segmentIdx = departureIdx; segmentIdx < arrivalIdx; segmentIdx++) {
                offsets.add(SeatBitMapUtil.bitOffset(seatIndex, stationCount, segmentIdx));
            }
        }
        if (occupiedBitOffsets.isEmpty()) {
            return;
        }
        // 位图缺失即席别不匹配/无座位数据， CAS 前先保证就绪（组装 + SET NX，无锁、毫秒级）
        for (String bitMapKey : occupiedBitOffsets.keySet()) {
            String carriageNumber = bitMapKey.substring(bitMapKey.lastIndexOf('_') + 1);
            ensureSeatBitMapExists(trainId, carriageNumber, bitMapKey);
            if (!distributedCache.hasKey(bitMapKey)) {
                throw new ServiceException("座位锁定失败，请稍后重试");
            }
        }
        // KEYS: 车厢位图 Key；ARGV: [座位数, (Key 下标, 站段数, offset...)...]
        List<String> keys = new ArrayList<>(occupiedBitOffsets.keySet());
        Map<String, Integer> keyIndexMap = new HashMap<>(keys.size());
        for (int i = 0; i < keys.size(); i++) {
            keyIndexMap.put(keys.get(i), i + 1);
        }
        List<String> args = new ArrayList<>(1 + occupiedBitOffsets.values().stream().mapToInt(List::size).sum() + occupiedBitOffsets.size() * 2);
        args.add(String.valueOf(occupiedBitOffsets.size()));
        for (Map.Entry<String, List<Long>> entry : occupiedBitOffsets.entrySet()) {
            args.add(String.valueOf(keyIndexMap.get(entry.getKey())));
            args.add(String.valueOf(entry.getValue().size()));
            entry.getValue().forEach(offset -> args.add(String.valueOf(offset)));
        }
        // 注意 execute 的 args 是可变参数：必须摊平为数组传入，否则整个 List 会被当作单个 ARGV
        Long success = stringRedisTemplate.execute(SEAT_INTERVAL_LOCK_SCRIPT, keys, args.toArray(new String[0]));
        if (success == null || success != 1L) {
            throw new ServiceException("座位已被占用，请重新选择");
        }
    }

    @Override
    public void unlock(String trainId, String departure, String arrival, List<TrainPurchaseTicketRespDTO> trainPurchaseTicketResults) {
        // 1. 账本作废：该座位在该区间的有效票记录流转为 CLOSED。
        //    退款/改签路径已先行把账本流转为 REFUNDED/CHANGED 的记录不会被匹配，天然幂等。
        for (TrainPurchaseTicketRespDTO each : trainPurchaseTicketResults) {
            ticketMapper.update(null, Wrappers.lambdaUpdate(TicketDO.class)
                    .eq(TicketDO::getTrainId, Long.valueOf(trainId))
                    .eq(TicketDO::getCarriageNumber, each.getCarriageNumber())
                    .eq(TicketDO::getSeatNumber, each.getSeatNumber())
                    .eq(TicketDO::getDeparture, departure)
                    .eq(TicketDO::getArrival, arrival)
                    .in(TicketDO::getTicketStatus,
                            TicketStatusEnum.UNPAID.getCode(),
                            TicketStatusEnum.PAID.getCode(),
                            TicketStatusEnum.BOARDED.getCode())
                    .set(TicketDO::getTicketStatus, TicketStatusEnum.CLOSED.getCode()));
        }
        // 2. 位图释放：pipeline 清位；失败只会"少卖"不会"超卖"，由对账任务兜底修复
        try {
            List<String> stations = seatBitMapAssembler.listStationOrdered(trainId);
            int departureIdx = stations.indexOf(departure);
            int arrivalIdx = stations.indexOf(arrival);
            if (departureIdx < 0 || arrivalIdx <= departureIdx) {
                return;
            }
            int stationCount = stations.size();
            Map<String, List<Long>> releaseBitOffsets = new LinkedHashMap<>();
            for (TrainPurchaseTicketRespDTO each : trainPurchaseTicketResults) {
                int seatIndex = SeatBitMapUtil.seatIndexOf(each.getSeatType(), each.getSeatNumber());
                if (seatIndex < 0) {
                    throw new ServiceException("座位类型或座位编号不合法");
                }
                String bitMapKey = SeatBitMapUtil.buildKey(trainId, each.getCarriageNumber());
                List<Long> offsets = releaseBitOffsets.computeIfAbsent(bitMapKey, k -> new ArrayList<>());
                for (int segmentIdx = departureIdx; segmentIdx < arrivalIdx; segmentIdx++) {
                    offsets.add(SeatBitMapUtil.bitOffset(seatIndex, stationCount, segmentIdx));
                }
            }
            if (releaseBitOffsets.isEmpty()) {
                return;
            }
            StringRedisTemplate stringRedisTemplate = (StringRedisTemplate) distributedCache.getInstance();
            releaseBitOffsets.forEach((key, offsets) -> stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
                for (Long offset : offsets) {
                    connection.stringCommands().setBit(keyBytes, offset, false);
                }
                return null;
            }));
        } catch (Throwable ex) {
            log.error("[释放座位] 车次：{} 区间：{}→{} 清理座位占用位图失败，等待对账任务修复", trainId, departure, arrival, ex);
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
     * 车厢座位占用位图缺失时，由"注册表 + 账本"全量组装后以 SET NX 一次原子写入。
     * 无分布式锁：并发组装者各自构建完整位图，NX 保证仅首个写入者生效，其余丢弃，
     * 消除了逐位 pipeline 写入的"半成品位图"风险与整列车锁粒度阻塞。
     */
    private void ensureSeatBitMapExists(String trainId, String carriageNumber, String bitMapKey) {
        if (distributedCache.hasKey(bitMapKey)) {
            return;
        }
        byte[] bitmap = seatBitMapAssembler.assemble(trainId, carriageNumber);
        if (bitmap == null) {
            log.warn("[座位位图] 车次：{} 车厢：{} 席别不支持位图或无座位数据，跳过初始化", trainId, carriageNumber);
            return;
        }
        StringRedisTemplate stringRedisTemplate = (StringRedisTemplate) distributedCache.getInstance();
        Boolean success = stringRedisTemplate.execute((RedisCallback<Boolean>) connection ->
                connection.stringCommands().set(bitMapKey.getBytes(StandardCharsets.UTF_8), bitmap,
                        Expiration.persistent(), SetOption.SET_IF_ABSENT));
        if (!Boolean.TRUE.equals(success)) {
            // 其他线程/实例已写入：以对方为准（双方数据源一致，仅快照时点差异，对账任务兜底）
            log.debug("[座位位图] 车次：{} 车厢：{} 位图已被其他实例写入，跳过", trainId, carriageNumber);
        }
    }
}
