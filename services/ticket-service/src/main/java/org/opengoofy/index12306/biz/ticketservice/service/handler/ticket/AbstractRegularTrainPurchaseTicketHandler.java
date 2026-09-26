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

package org.opengoofy.index12306.biz.ticketservice.service.handler.ticket;

import cn.hutool.core.collection.CollUtil;
import org.opengoofy.index12306.biz.ticketservice.common.enums.VehicleSeatTypeEnum;
import org.opengoofy.index12306.biz.ticketservice.common.enums.VehicleTypeEnum;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.PurchaseTicketPassengerDetailDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.req.PurchaseTicketReqDTO;
import org.opengoofy.index12306.biz.ticketservice.service.SeatService;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.base.AbstractTrainPurchaseTicketTemplate;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.dto.SelectSeatDTO;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.dto.TrainPurchaseTicketRespDTO;
import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;

import java.util.ArrayList;
import java.util.List;

/**
 * 普速列车（软卧/硬卧/硬座/无座）选座策略抽象类
 * 按乘车人顺序依次分配指定区间内的可用席位，不提供在线选座能力
 */
public abstract class AbstractRegularTrainPurchaseTicketHandler extends AbstractTrainPurchaseTicketTemplate {

    protected final SeatService seatService;

    protected AbstractRegularTrainPurchaseTicketHandler(SeatService seatService) {
        this.seatService = seatService;
    }

    /**
     * 当前策略对应的席别
     */
    protected abstract VehicleSeatTypeEnum seatType();

    /**
     * 当前策略对应的车型，普通车席别与动车席别共用顺序分配逻辑
     */
    protected VehicleTypeEnum trainType() {
        return VehicleTypeEnum.REGULAR_TRAIN;
    }

    @Override
    public String mark() {
        return trainType().getName() + seatType().getName();
    }

    @Override
    protected List<TrainPurchaseTicketRespDTO> selectSeats(SelectSeatDTO requestParam) {
        PurchaseTicketReqDTO purchaseTicketReqDTO = requestParam.getRequestParam();
        String trainId = purchaseTicketReqDTO.getTrainId();
        String departure = purchaseTicketReqDTO.getDeparture();
        String arrival = purchaseTicketReqDTO.getArrival();
        List<PurchaseTicketPassengerDetailDTO> passengerSeatDetails = requestParam.getPassengerSeatDetails();
        List<String> carriageList = seatService.listUsableCarriageNumber(trainId, seatType().getCode(), departure, arrival);
        if (CollUtil.isEmpty(carriageList)) {
            throw new ServiceException("站点余票不足，请尝试更换座位类型或选择其它站点");
        }
        List<TrainPurchaseTicketRespDTO> actualResult = new ArrayList<>(passengerSeatDetails.size());
        for (String carriageNumber : carriageList) {
            if (actualResult.size() >= passengerSeatDetails.size()) {
                break;
            }
            List<String> availableSeatList = seatService.listAvailableSeat(trainId, carriageNumber, seatType().getCode(), departure, arrival);
            for (String seatNumber : availableSeatList) {
                if (actualResult.size() >= passengerSeatDetails.size()) {
                    break;
                }
                PurchaseTicketPassengerDetailDTO passengerDetail = passengerSeatDetails.get(actualResult.size());
                TrainPurchaseTicketRespDTO result = new TrainPurchaseTicketRespDTO();
                result.setSeatNumber(seatNumber);
                result.setSeatType(seatType().getCode());
                result.setCarriageNumber(carriageNumber);
                result.setPassengerId(passengerDetail.getPassengerId());
                actualResult.add(result);
            }
        }
        if (actualResult.size() < passengerSeatDetails.size()) {
            throw new ServiceException("站点余票不足，请尝试更换座位类型或选择其它站点");
        }
        return actualResult;
    }
}
