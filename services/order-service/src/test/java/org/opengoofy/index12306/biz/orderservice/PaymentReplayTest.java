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

package org.opengoofy.index12306.biz.orderservice;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opengoofy.index12306.biz.orderservice.dao.entity.OrderDO;
import org.opengoofy.index12306.biz.orderservice.dao.entity.OrderItemDO;
import org.opengoofy.index12306.biz.orderservice.dao.mapper.OrderMapper;
import org.opengoofy.index12306.biz.orderservice.dao.mapper.OrderItemMapper;
import org.opengoofy.index12306.biz.orderservice.dto.domain.OrderStatusReversalDTO;
import org.opengoofy.index12306.biz.orderservice.service.impl.OrderServiceImpl;
import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentReplayTest {
    @Mock
    private OrderMapper orders;
    @Mock
    private OrderItemMapper items;
    @InjectMocks
    private OrderServiceImpl service;

    @BeforeEach
    void tableMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "test");
        TableInfoHelper.initTableInfo(assistant, OrderDO.class);
        TableInfoHelper.initTableInfo(assistant, OrderItemDO.class);
    }

    private OrderStatusReversalDTO payment() {
        return OrderStatusReversalDTO.builder().orderSn("order-1").orderStatus(10).orderItemStatus(10).build();
    }

    @ParameterizedTest
    @ValueSource(ints = {10, 11, 12, 20})
    void repeatedPaymentCannotResetPaidRefundedOrCompletedOrder(int status) {
        when(orders.selectOne(any())).thenReturn(OrderDO.builder().status(status).build());
        assertDoesNotThrow(() -> service.statusReversal(payment()));
        verify(orders, never()).update(any(), any());
        verifyNoInteractions(items);
    }

    @Test
    void firstPaymentUpdatesOrderAndItems() {
        when(orders.selectOne(any())).thenReturn(OrderDO.builder().status(0).build());
        when(orders.update(isNull(), any())).thenReturn(1);
        when(items.update(isNull(), any())).thenReturn(2);
        service.statusReversal(payment());
        verify(orders).update(isNull(), any());
        verify(items).update(isNull(), any());
    }

    @Test
    void closedOrderIsNotReopenedByPaymentReplay() {
        when(orders.selectOne(any())).thenReturn(OrderDO.builder().status(30).build());
        assertThrows(ServiceException.class, () -> service.statusReversal(payment()));
        verifyNoInteractions(items);
    }
}
