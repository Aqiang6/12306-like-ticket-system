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

import com.alibaba.fastjson2.JSON;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.opengoofy.index12306.biz.ticketservice.common.constant.TicketRocketMQConstant;
import org.opengoofy.index12306.biz.ticketservice.mq.domain.MessageWrapper;
import org.opengoofy.index12306.biz.ticketservice.mq.event.OrderCreateConfirmEvent;
import org.opengoofy.index12306.biz.ticketservice.service.OrderCreateTaskService;
import org.opengoofy.index12306.biz.ticketservice.service.TicketService;
import org.opengoofy.index12306.framework.starter.idempotent.annotation.Idempotent;
import org.opengoofy.index12306.framework.starter.idempotent.enums.IdempotentSceneEnum;
import org.opengoofy.index12306.framework.starter.idempotent.enums.IdempotentTypeEnum;
import org.opengoofy.index12306.frameworks.starter.user.core.UserContext;
import org.opengoofy.index12306.frameworks.starter.user.core.UserInfoDTO;
import org.springframework.stereotype.Component;

/**
 * 订单创建补偿任务消费者
 *
 * <p>实例在"车票账本已落库、订单未创建"之间宕机时，同步建单链路中断且无法自愈；
 * 本消费者基于发件箱任务重建订单，消除跨服务调用的悬挂窗口。订单服务按购票令牌幂等，
 * 与同步链路并发或消息重复投递均不会重复建单。
 * 公众号：马丁玩编程，回复：加群，添加马哥微信（备注：12306）获取项目资料
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = TicketRocketMQConstant.ORDER_CREATE_CONFIRM_TOPIC_KEY,
        selectorExpression = TicketRocketMQConstant.ORDER_CREATE_CONFIRM_TAG_KEY,
        consumerGroup = TicketRocketMQConstant.ORDER_CREATE_CONFIRM_CG_KEY
)
public class OrderCreateConfirmConsumer implements RocketMQListener<MessageWrapper<OrderCreateConfirmEvent>> {

    /**
     * 补偿重试上限：超限说明订单服务持续不可用等系统性故障，
     * 回滚购票事务释放座位（少卖可由用户重试挽回，长期占座会拖垮运力），并告警人工介入
     */
    private static final int MAX_RETRY_COUNT = 60;

    private final TicketService ticketService;
    private final OrderCreateTaskService orderCreateTaskService;

    @Idempotent(
            uniqueKeyPrefix = "index12306-ticket:order_create_confirm:",
            key = "#messageWrapper.getKeys()+'_'+#messageWrapper.getUuid()",
            type = IdempotentTypeEnum.SPEL,
            scene = IdempotentSceneEnum.MQ,
            keyTimeout = 7200L
    )
    @Override
    public void onMessage(MessageWrapper<OrderCreateConfirmEvent> messageWrapper) {
        OrderCreateConfirmEvent event = messageWrapper.getMessage();
        log.info("[订单创建补偿] 开始消费：{}", JSON.toJSONString(messageWrapper));
        // 账本核验：车票已被同步链路补偿删除（如订单服务调失败回滚）则任务直接作废，不再补建订单
        if (!orderCreateTaskService.existsAliveTickets(event.getPrepare().getTicketIds())) {
            log.warn("[订单创建补偿] 车票账本已不存在，作废补偿任务，令牌：{}", event.getPurchaseToken());
            orderCreateTaskService.cancelByToken(event.getPurchaseToken());
            return;
        }
        int retryCount = orderCreateTaskService.incrementAndGetRetryCount(event.getPurchaseToken());
        if (retryCount > MAX_RETRY_COUNT) {
            log.error("[订单创建补偿] 重试超过上限 {} 次，回滚购票并作废任务，令牌：{}，请人工介入排查",
                    MAX_RETRY_COUNT, event.getPurchaseToken());
            ticketService.rollbackPurchase(event.getPrepare());
            return;
        }
        UserContext.setUser(UserInfoDTO.builder()
                .userId(event.getUserId())
                .username(event.getUsername())
                .build());
        try {
            // 补建订单失败不回滚账本：抛出异常触发 MQ 重投，座位保持占用，直至建单成功或达到重试上限
            ticketService.doCreateTicketOrder(event.getPrepare());
        } catch (Throwable ex) {
            log.error("[订单创建补偿] 订单创建失败，等待消息重投，令牌：{}", event.getPurchaseToken(), ex);
            throw ex;
        } finally {
            UserContext.removeUser();
        }
    }
}
