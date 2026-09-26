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

package org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.base;

import java.util.List;
import java.util.Map;

/**
 * 高铁商务座在线选座校验
 * 公众号：马丁玩编程，回复：加群，添加马哥微信（备注：12306）获取项目资料
 */
public class TrainBusinessCheckSeat implements BitMapCheckSeat {

    /**
     * 高铁商务座选择座位是否被占用
     *
     * @param chooseSeatList 选择座位
     * @param actualSeats    座位状态数组
     * @param SEAT_Y_INT     坐标转换 Map
     * @return
     */
    @Override
    public boolean checkChooseSeat(List<String> chooseSeatList, int[][] actualSeats, Map<Character, Integer> SEAT_Y_INT) {
        boolean isExists = true;
        for (int i = 0; i < chooseSeatList.size(); i++) {
            if (chooseSeatList.size() == 1) {
                String chooseSeat = chooseSeatList.get(i);
                int seatX = Integer.parseInt(chooseSeat.substring(1));
                int seatY = SEAT_Y_INT.get(chooseSeat.charAt(0));
                if (actualSeats[seatX][seatY] != 0 && actualSeats[1][seatY] != 0) {
                    break;
                }
            } else {
                String chooseSeat = chooseSeatList.get(i);
                int seatX = Integer.parseInt(chooseSeat.substring(1));
                int seatY = SEAT_Y_INT.get(chooseSeat.charAt(0));
                if (actualSeats[seatX][seatY] != 0) {
                    isExists = false;
                    break;
                }
            }
        }
        return isExists;
    }
}
