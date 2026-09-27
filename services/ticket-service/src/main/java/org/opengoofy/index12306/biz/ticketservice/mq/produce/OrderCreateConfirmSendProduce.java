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

package org.opengoofy.index12306.biz.ticketservice.mq.produce;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.MessageConst;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.opengoofy.index12306.biz.ticketservice.common.constant.TicketRocketMQConstant;
import org.opengoofy.index12306.biz.ticketservice.mq.domain.MessageWrapper;
import org.opengoofy.index12306.biz.ticketservice.mq.event.OrderCreateConfirmEvent;
import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 订单创建补偿消息生产者：由发件箱扫描任务调用，投递未确认的购票任务
 * 公众号：马丁玩编程，回复：加群，添加马哥微信（备注：12306）获取项目资料
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderCreateConfirmSendProduce {

    private final RocketMQTemplate rocketMQTemplate;
    private final ConfigurableEnvironment environment;

    /**
     * 同步投递订单创建补偿消息，投递失败抛出异常由扫描任务按周期重试
     *
     * @param event 订单创建补偿任务事件
     */
    public void sendMessage(OrderCreateConfirmEvent event) {
        String topic = environment.resolvePlaceholders(TicketRocketMQConstant.ORDER_CREATE_CONFIRM_TOPIC_KEY);
        String tag = environment.resolvePlaceholders(TicketRocketMQConstant.ORDER_CREATE_CONFIRM_TAG_KEY);
        Message<?> message = MessageBuilder
                .withPayload(new MessageWrapper<>(event.getPurchaseToken(), event))
                .setHeader(MessageConst.PROPERTY_KEYS, event.getPurchaseToken())
                .setHeader(MessageConst.PROPERTY_TAGS, tag)
                .build();
        SendResult sendResult = rocketMQTemplate.syncSend(StrUtil.format("{}:{}", topic, tag), message, 2000L);
        if (!Objects.equals(sendResult.getSendStatus(), SendStatus.SEND_OK)) {
            throw new ServiceException(StrUtil.format("订单创建补偿消息投递失败，令牌：{}", event.getPurchaseToken()));
        }
    }
}
