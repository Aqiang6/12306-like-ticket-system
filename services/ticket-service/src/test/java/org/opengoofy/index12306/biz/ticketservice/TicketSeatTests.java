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

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.CarriageDO;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.SeatDO;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.CarriageMapper;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.SeatMapper;
import org.opengoofy.index12306.biz.ticketservice.toolkit.SeatBitMapUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** 手工初始化示例：每个物理座位只登记一次，不按乘车区间复制。 */
@SpringBootTest
class TicketSeatTests {
    @Autowired
    private SeatMapper seatMapper;
    @Autowired
    private CarriageMapper carriageMapper;

    @Test
    void testInitData() {
        long trainId = 1L;
        for (CarriageDO carriage : carriageMapper.selectList(Wrappers.lambdaQuery(CarriageDO.class)
                .eq(CarriageDO::getTrainId, trainId))) {
            Integer type = carriage.getCarriageType();
            if (!SeatBitMapUtil.supports(type)) {
                throw new IllegalArgumentException("不支持的座位类型：" + type);
            }
            for (int row = 1; row <= SeatBitMapUtil.getRows(type); row++) {
                for (char letter : SeatBitMapUtil.getLetters(type).toCharArray()) {
                    String number = String.format("%02d%c", row, letter);
                    if (seatMapper.selectCount(Wrappers.lambdaQuery(SeatDO.class)
                            .eq(SeatDO::getTrainId, trainId)
                            .eq(SeatDO::getCarriageNumber, carriage.getCarriageNumber())
                            .eq(SeatDO::getSeatNumber, number)) == 0) {
                        seatMapper.insert(SeatDO.builder().trainId(trainId)
                                .carriageNumber(carriage.getCarriageNumber())
                                .seatNumber(number).seatType(type).build());
                    }
                }
            }
        }
    }
}
