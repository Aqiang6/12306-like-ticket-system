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

package org.opengoofy.index12306.biz.ticketservice.dto.req;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.dto.TrainPurchaseTicketRespDTO;

import java.util.List;

/**
 * 购票临界区产物上下文：持有购票锁期间完成选座、锁座、车票落库与余票扣减后的中间结果，
 * 订单创建在购票锁外基于此上下文继续执行
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TicketPurchasePrepareDTO {

    /**
     * 车次 ID
     */
    private String trainId;

    /**
     * 出发站
     */
    private String departure;

    /**
     * 到达站
     */
    private String arrival;

    /**
     * 车次编号
     */
    private String trainNumber;

    /**
     * 已保存的车票记录 ID（订单创建失败时用于补偿删除）
     */
    private List<Long> ticketIds;

    /**
     * 座位分配结果（含车厢、座位号、坐席类型、乘车人）
     */
    private List<TrainPurchaseTicketRespDTO> seatResults;
}
