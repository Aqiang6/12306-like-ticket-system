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

package org.opengoofy.index12306.biz.ticketservice.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.SeatDO;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.CarriageSeatCountDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.SeatTypeCountDTO;

import java.util.List;

/**
 * 座位持久层（t_seat 为物理座位注册表，可用性一律按 t_ticket 售卖区间账本重叠判断）
 * 公众号：马丁玩编程，回复：加群，添加马哥微信（备注：12306）获取项目资料
 */
public interface SeatMapper extends BaseMapper<SeatDO> {

    /**
     * 获取列车指定车厢在 [departure, arrival) 区间内可用座位数（不与任何有效票记录重叠），按车厢聚合
     */
    List<CarriageSeatCountDTO> listCarriageSeatCount(@Param("trainId") Long trainId,
                                                     @Param("departure") String departure,
                                                     @Param("arrival") String arrival,
                                                     @Param("trainCarriageList") List<String> trainCarriageList);

    /**
     * 获取列车 startStation 到 endStation 区间可用座位数量
     */
    List<SeatTypeCountDTO> listSeatTypeCount(@Param("trainId") Long trainId, @Param("startStation") String startStation, @Param("endStation") String endStation, @Param("seatTypes") List<Integer> seatTypes);

    /**
     * 查询指定车厢内指定区间可用的座位号（位图缺失时的数据库兜底查询）
     */
    List<String> listAvailableSeatNumber(@Param("trainId") Long trainId,
                                         @Param("carriageNumber") String carriageNumber,
                                         @Param("seatType") Integer seatType,
                                         @Param("departure") String departure,
                                         @Param("arrival") String arrival);
}
