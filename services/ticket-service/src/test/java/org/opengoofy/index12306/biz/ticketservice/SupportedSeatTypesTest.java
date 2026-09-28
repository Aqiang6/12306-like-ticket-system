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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.opengoofy.index12306.biz.ticketservice.common.enums.VehicleSeatTypeEnum;
import org.opengoofy.index12306.biz.ticketservice.common.enums.VehicleTypeEnum;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.SeatMapper;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.TicketMapper;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.PurchaseTicketPassengerDetailDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.req.PurchaseTicketReqDTO;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.dto.TrainPurchaseTicketRespDTO;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.filter.purchase.TrainPurchaseTicketParamNotNullChainHandler;
import org.opengoofy.index12306.biz.ticketservice.service.impl.SeatServiceImpl;
import org.opengoofy.index12306.biz.ticketservice.toolkit.SeatBitMapAssembler;
import org.opengoofy.index12306.framework.starter.cache.DistributedCache;
import org.opengoofy.index12306.framework.starter.convention.exception.ClientException;
import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SupportedSeatTypesTest {
    @Test
    void catalogueContainsOnlyImplementedSeatTypes() {
        assertEquals(List.of(0, 1, 2), Arrays.stream(VehicleSeatTypeEnum.values())
                .map(VehicleSeatTypeEnum::getCode).toList());
        assertEquals(List.of(0, 1, 2), VehicleTypeEnum.findSeatTypesByCode(0));
        assertTrue(VehicleTypeEnum.findSeatTypesByCode(1).isEmpty());
        assertTrue(VehicleTypeEnum.findSeatTypesByCode(2).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void supportedSeatTypesPassPurchaseValidation(int type) {
        assertDoesNotThrow(() -> new TrainPurchaseTicketParamNotNullChainHandler().handler(request(type)));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 999})
    void removedAndUnknownTypesAreRejectedBeforeInventoryAccess(int type) {
        assertThrows(ClientException.class,
                () -> new TrainPurchaseTicketParamNotNullChainHandler().handler(request(type)));
        SeatMapper seats = mock(SeatMapper.class);
        TicketMapper tickets = mock(TicketMapper.class);
        SeatBitMapAssembler assembler = mock(SeatBitMapAssembler.class);
        DistributedCache cache = mock(DistributedCache.class);
        SeatServiceImpl service = new SeatServiceImpl(seats, tickets, assembler, cache);
        assertThrows(ServiceException.class, () -> service.listAvailableSeat("1", "01", type, "A", "B"));
        verifyNoInteractions(seats, tickets, assembler, cache);
    }

    @Test
    void invalidSeatCannotSilentlySkipBitmapLock() {
        SeatBitMapAssembler assembler = mock(SeatBitMapAssembler.class);
        when(assembler.listStationOrdered("1")).thenReturn(List.of("A", "B"));
        DistributedCache cache = mock(DistributedCache.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(cache.getInstance()).thenReturn(redis);
        SeatServiceImpl service = new SeatServiceImpl(mock(SeatMapper.class), mock(TicketMapper.class), assembler, cache);
        TrainPurchaseTicketRespDTO seat = new TrainPurchaseTicketRespDTO();
        seat.setSeatType(7);
        seat.setCarriageNumber("01");
        seat.setSeatNumber("01A");
        assertThrows(ServiceException.class, () -> service.lockSeat("1", "A", "B", List.of(seat)));
        verifyNoInteractions(redis);
    }

    private PurchaseTicketReqDTO request(int type) {
        PurchaseTicketPassengerDetailDTO passenger = new PurchaseTicketPassengerDetailDTO();
        passenger.setPassengerId("1");
        passenger.setSeatType(type);
        PurchaseTicketReqDTO request = new PurchaseTicketReqDTO();
        request.setTrainId("1");
        request.setDeparture("A");
        request.setArrival("B");
        request.setPassengers(List.of(passenger));
        return request;
    }
}
