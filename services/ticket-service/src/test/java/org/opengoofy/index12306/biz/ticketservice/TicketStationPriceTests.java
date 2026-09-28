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

package org.opengoofy.index12306.biz.ticketservice;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.biz.ticketservice.common.enums.VehicleTypeEnum;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.TrainDO;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.TrainStationDO;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.TrainStationPriceDO;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.TrainMapper;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.TrainStationMapper;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.TrainStationPriceMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@SpringBootTest
class TicketStationPriceTests {

    @Autowired
    private TrainMapper trainMapper;

    @Autowired
    private TrainStationMapper trainStationMapper;

    @Autowired
    private TrainStationPriceMapper trainStationPriceMapper;

    @Test
    void testInitData() {
        String trainId = "1";
        Map<String, Map<Integer, Integer>> priceMap = new HashMap<>();
        priceMap.put("北京南-济南西", Map.of(0, 78200, 1, 37700, 2, 22400));
        List<TrainStationDO> trainStations = selectTrainStations(trainId);
        List<TrainStationPriceDO> trainStationPrices = buildTrainStationPrices(trainId, priceMap, trainStations);
        trainStationPrices.forEach(each -> trainStationPriceMapper.insert(each));
    }

    private List<TrainStationDO> selectTrainStations(String trainId) {
        LambdaQueryWrapper<TrainStationDO> queryWrapper = Wrappers.lambdaQuery(TrainStationDO.class)
                .eq(TrainStationDO::getTrainId, trainId);
        return trainStationMapper.selectList(queryWrapper);
    }

    private List<TrainStationPriceDO> buildTrainStationPrices(String trainId, Map<String, Map<Integer, Integer>> priceMap, List<TrainStationDO> trainStations) {
        TrainDO trainDO = trainMapper.selectById(trainId);
        Integer trainType = trainDO.getTrainType();
        List<Integer> seatTypes = VehicleTypeEnum.findSeatTypesByCode(trainType);
        List<TrainStationPriceDO> result = new ArrayList<>();
        for (int i = 0; i < trainStations.size() - 1; i++) {
            TrainStationDO trainStationDO = trainStations.get(i);
            for (int j = i + 1; j < trainStations.size(); j++) {
                for (Integer seatType : seatTypes) {
                    TrainStationPriceDO actual = new TrainStationPriceDO();
                    actual.setTrainId(trainStationDO.getTrainId());
                    String departure = trainStations.get(i).getDeparture();
                    actual.setDeparture(departure);
                    String arrival = trainStations.get(j).getDeparture();
                    actual.setArrival(arrival);
                    Map<Integer, Integer> integerIntegerMap = priceMap.get(departure + "-" + arrival);
                    if (integerIntegerMap == null) {
                        continue;
                    }
                    Integer price = integerIntegerMap.get(seatType);
                    actual.setPrice(price);
                    actual.setSeatType(seatType);
                    actual.setCreateTime(new Date());
                    actual.setUpdateTime(new Date());
                    actual.setDelFlag(0);
                    result.add(actual);
                }
            }
        }
        return result;
    }

}
