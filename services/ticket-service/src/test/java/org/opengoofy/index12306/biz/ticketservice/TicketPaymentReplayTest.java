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
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.TicketMapper;
import org.opengoofy.index12306.biz.ticketservice.mq.consumer.PayResultCallbackTicketConsumer;
import org.opengoofy.index12306.biz.ticketservice.mq.domain.MessageWrapper;
import org.opengoofy.index12306.biz.ticketservice.mq.event.PayResultCallbackTicketEvent;
import org.opengoofy.index12306.biz.ticketservice.remote.TicketOrderRemoteService;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderDetailRespDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderPassengerDetailRespDTO;
import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;
import org.opengoofy.index12306.framework.starter.convention.result.Result;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TicketPaymentReplayTest {
    private final TicketOrderRemoteService orders = mock(TicketOrderRemoteService.class);
    private final TicketMapper tickets = mock(TicketMapper.class);
    private final PayResultCallbackTicketConsumer consumer = new PayResultCallbackTicketConsumer(orders, tickets);

    private MessageWrapper<PayResultCallbackTicketEvent> message() {
        PayResultCallbackTicketEvent event = new PayResultCallbackTicketEvent();
        event.setOrderSn("order-1");
        return new MessageWrapper<>("order-1", event);
    }

    @Test
    void waitsForOrderConsumerCommit() {
        TicketOrderDetailRespDTO order = new TicketOrderDetailRespDTO();
        order.setStatus(0);
        when(orders.queryTicketOrderByOrderSn("order-1"))
                .thenReturn(new Result<TicketOrderDetailRespDTO>().setCode("0").setData(order));
        assertThrows(ServiceException.class, () -> consumer.onMessage(message()));
        verifyNoInteractions(tickets);
    }

    @Test
    void oldPaymentDoesNotAffectReplacementTickets() {
        TicketOrderDetailRespDTO order = new TicketOrderDetailRespDTO();
        order.setStatus(12);
        order.setPassengerDetails(List.of(TicketOrderPassengerDetailRespDTO.builder().status(40).build(),
                TicketOrderPassengerDetailRespDTO.builder().status(50).build()));
        when(orders.queryTicketOrderByOrderSn("order-1"))
                .thenReturn(new Result<TicketOrderDetailRespDTO>().setCode("0").setData(order));
        assertDoesNotThrow(() -> consumer.onMessage(message()));
        verifyNoInteractions(tickets);
    }
}
