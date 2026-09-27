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

package org.opengoofy.index12306.biz.ticketservice.toolkit;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.opengoofy.index12306.biz.ticketservice.common.enums.TicketStatusEnum;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.SeatDO;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.TicketDO;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.TrainStationDO;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.SeatMapper;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.TrainStationMapper;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 车厢座位占用位图组装器：从 物理座位注册表（t_seat）+ 售卖区间账本（t_ticket 有效票）全量构建位图
 *
 * <p>组装规则（对齐 {@link SeatBitMapUtil} 位图设计）：
 * 布局内全部位置默认占用（1，含非物理座位与注册表缺失位），
 * 仅"注册表存在且当前无有效票覆盖"的站段清 0（空闲）。
 * 组装结果一次性整键写入 Redis（SET NX），杜绝逐位写入中途失败留下的半成品位图。
 */
@Component
@RequiredArgsConstructor
public class SeatBitMapAssembler {

    private final SeatMapper seatMapper;
    private final org.opengoofy.index12306.biz.ticketservice.dao.mapper.TicketMapper ticketMapper;
    private final TrainStationMapper trainStationMapper;

    /**
     * 组装指定车厢的座位占用位图；车厢无座位数据或席别不支持位图时返回 null
     */
    public byte[] assemble(String trainId, String carriageNumber) {
        List<String> stations = listStationOrdered(trainId);
        if (stations.size() < 2) {
            return null;
        }
        int stationCount = stations.size();
        List<SeatDO> seatList = seatMapper.selectList(Wrappers.lambdaQuery(SeatDO.class)
                .eq(SeatDO::getTrainId, Long.valueOf(trainId))
                .eq(SeatDO::getCarriageNumber, carriageNumber));
        if (seatList.isEmpty()) {
            return null;
        }
        Integer seatType = seatList.get(0).getSeatType();
        if (!SeatBitMapUtil.supports(seatType)) {
            return null;
        }
        int rows = SeatBitMapUtil.getRows(seatType);
        // 列位固定步长 6（A-F），与 SeatBitMapUtil 的座位索引公式一致
        int bitCount = rows * 6 * stationCount;
        byte[] bitmap = new byte[(bitCount + 7) >>> 3];
        Arrays.fill(bitmap, (byte) 0xFF);
        // 注册表物理座位索引集合；非物理位置（无该字母列）与注册表缺失位保持占用，
        // 否则会被位图统计当作空闲座位虚增余票
        Set<Integer> registrySeatIndexes = seatList.stream()
                .map(each -> SeatBitMapUtil.seatIndexOf(seatType, each.getSeatNumber()))
                .filter(index -> index >= 0)
                .collect(Collectors.toSet());
        // 售卖账本 → 每座位被有效票覆盖的站段集合（账本区间即占用事实）
        Map<Integer, Set<Integer>> occupiedSegmentsBySeat = new HashMap<>();
        List<TicketDO> validTickets = ticketMapper.selectList(Wrappers.lambdaQuery(TicketDO.class)
                .eq(TicketDO::getTrainId, Long.valueOf(trainId))
                .eq(TicketDO::getCarriageNumber, carriageNumber)
                .in(TicketDO::getTicketStatus,
                        TicketStatusEnum.UNPAID.getCode(),
                        TicketStatusEnum.PAID.getCode(),
                        TicketStatusEnum.BOARDED.getCode()));
        for (TicketDO each : validTickets) {
            int seatIndex = SeatBitMapUtil.seatIndexOf(seatType, each.getSeatNumber());
            if (seatIndex < 0) {
                continue;
            }
            int startIdx = stations.indexOf(each.getDeparture());
            int endIdx = stations.indexOf(each.getArrival());
            if (startIdx < 0 || endIdx <= startIdx) {
                continue;
            }
            Set<Integer> occupied = occupiedSegmentsBySeat.computeIfAbsent(seatIndex, k -> new HashSet<>());
            for (int segmentIdx = startIdx; segmentIdx < endIdx; segmentIdx++) {
                occupied.add(segmentIdx);
            }
        }
        for (int seatIndex : registrySeatIndexes) {
            Set<Integer> occupied = occupiedSegmentsBySeat.getOrDefault(seatIndex, Collections.emptySet());
            for (int segmentIdx = 0; segmentIdx < stationCount; segmentIdx++) {
                if (!occupied.contains(segmentIdx)) {
                    clearBit(bitmap, SeatBitMapUtil.bitOffset(seatIndex, stationCount, segmentIdx));
                }
            }
        }
        return bitmap;
    }

    /**
     * 列车沿途站点有序列表（按插入序即行程序），站名与 t_ticket.departure/arrival 同源。
     * 位图偏移公式依赖站点顺序，全工程必须共用同一份有序列表。
     */
    public List<String> listStationOrdered(String trainId) {
        return trainStationMapper.selectList(Wrappers.lambdaQuery(TrainStationDO.class)
                        .eq(TrainStationDO::getTrainId, Long.valueOf(trainId))
                        .select(TrainStationDO::getDeparture)
                        .orderByAsc(TrainStationDO::getId))
                .stream()
                .map(TrainStationDO::getDeparture)
                .collect(Collectors.toList());
    }

    private void clearBit(byte[] bitmap, long offset) {
        bitmap[(int) (offset >>> 3)] &= (byte) ~(0x80 >>> (int) (offset & 7));
    }
}
