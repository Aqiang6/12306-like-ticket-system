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

package org.opengoofy.index12306.biz.ticketservice.mq.consumer;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.opengoofy.index12306.biz.ticketservice.common.constant.TicketRocketMQConstant;
import org.opengoofy.index12306.biz.ticketservice.common.enums.TicketStatusEnum;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.TicketDO;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.TicketMapper;
import org.opengoofy.index12306.biz.ticketservice.mq.domain.MessageWrapper;
import org.opengoofy.index12306.biz.ticketservice.mq.event.PayResultCallbackTicketEvent;
import org.opengoofy.index12306.biz.ticketservice.remote.TicketOrderRemoteService;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderDetailRespDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderPassengerDetailRespDTO;
import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;
import org.opengoofy.index12306.framework.starter.convention.result.Result;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * 支付结果回调购票消费者
 * 公众号：马丁玩编程，回复：加群，添加马哥微信（备注：12306）获取项目资料
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = TicketRocketMQConstant.PAY_GLOBAL_TOPIC_KEY,
        selectorExpression = TicketRocketMQConstant.PAY_RESULT_CALLBACK_TAG_KEY,
        consumerGroup = TicketRocketMQConstant.PAY_RESULT_CALLBACK_TICKET_CG_KEY
)
public class PayResultCallbackTicketConsumer implements RocketMQListener<MessageWrapper<PayResultCallbackTicketEvent>> {

    private final TicketOrderRemoteService ticketOrderRemoteService;
    private final TicketMapper ticketMapper;

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void onMessage(MessageWrapper<PayResultCallbackTicketEvent> message) {
        Result<TicketOrderDetailRespDTO> ticketOrderDetailResult;
        try {
            ticketOrderDetailResult = ticketOrderRemoteService.queryTicketOrderByOrderSn(message.getMessage().getOrderSn());
            if (ticketOrderDetailResult == null || !ticketOrderDetailResult.isSuccess() || ticketOrderDetailResult.getData() == null) {
                throw new ServiceException("支付结果回调查询订单失败");
            }
        } catch (Throwable ex) {
            log.error("支付结果回调查询订单失败", ex);
            throw ex;
        }
        TicketOrderDetailRespDTO ticketOrderDetail = ticketOrderDetailResult.getData();
        // 两个消费组独立推进；订单尚未提交支付状态时让 MQ 重试。
        if (Objects.equals(ticketOrderDetail.getStatus(), 0)) {
            throw new ServiceException("订单支付状态尚未提交，请重试");
        }
        for (TicketOrderPassengerDetailRespDTO each : ticketOrderDetail.getPassengerDetails()) {
            // 订单明细 10=已支付，20=已进站。旧支付消息不能影响已退票/改签后重新购买的席位。
            if (!Objects.equals(each.getStatus(), 10) && !Objects.equals(each.getStatus(), 20)) {
                continue;
            }
            // 支付成功同步流转车票账本状态 UNPAID -> PAID（座位占用由位图与账本表达，注册表无状态流转）；
            // 退票链路依赖该状态做 REFUNDED 流转
            ticketMapper.update(null, Wrappers.lambdaUpdate(TicketDO.class)
                    .eq(TicketDO::getTrainId, ticketOrderDetail.getTrainId())
                    .eq(TicketDO::getCarriageNumber, each.getCarriageNumber())
                    .eq(TicketDO::getSeatNumber, each.getSeatNumber())
                    .eq(TicketDO::getUsername, each.getUsername())
                    .eq(TicketDO::getDeparture, ticketOrderDetail.getDeparture())
                    .eq(TicketDO::getArrival, ticketOrderDetail.getArrival())
                    .eq(TicketDO::getTicketStatus, TicketStatusEnum.UNPAID.getCode())
                    .set(TicketDO::getTicketStatus, TicketStatusEnum.PAID.getCode()));
        }
    }
}
