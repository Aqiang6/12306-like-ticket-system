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

import lombok.Data;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.ChangeTicketPassengerDetailDTO;

import java.util.List;

/**
 * 车票改签请求入参实体
 * 公众号：马丁玩编程，回复：加群，添加马哥微信（备注：12306）获取项目资料
 */
@Data
public class ChangeTicketReqDTO {

    /**
     * 原车票订单号
     */
    private String orderSn;

    /**
     * 改签业务类型 0：改签 1：变更到站
     */
    private Integer changeType;

    /**
     * 需要改签的子订单以及新席别集合
     */
    private List<ChangeTicketPassengerDetailDTO> passengers;

    /**
     * 改签后列车 ID
     */
    private String newTrainId;

    /**
     * 改签后出发站（必须与原票出发站一致）
     */
    private String newDeparture;

    /**
     * 改签后到达站
     */
    private String newArrival;

    /**
     * 在线选座（可选，不填由系统自动分配）
     */
    private List<String> chooseSeats;
}
