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

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.opengoofy.index12306.biz.ticketservice.common.constant.TicketRocketMQConstant;
import org.opengoofy.index12306.biz.ticketservice.common.enums.TicketStatusEnum;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.TicketDO;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.TicketMapper;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.RouteDTO;
import org.opengoofy.index12306.biz.ticketservice.mq.domain.MessageWrapper;
import org.opengoofy.index12306.biz.ticketservice.mq.event.RefundResultCallbackTicketEvent;
import org.opengoofy.index12306.biz.ticketservice.remote.TicketOrderRemoteService;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderDetailRespDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderPassengerDetailRespDTO;
import org.opengoofy.index12306.biz.ticketservice.service.SeatService;
import org.opengoofy.index12306.biz.ticketservice.service.TrainStationService;
import org.opengoofy.index12306.framework.starter.cache.DistributedCache;
import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;
import org.opengoofy.index12306.framework.starter.convention.result.Result;
import org.opengoofy.index12306.framework.starter.idempotent.annotation.Idempotent;
import org.opengoofy.index12306.framework.starter.idempotent.enums.IdempotentSceneEnum;
import org.opengoofy.index12306.framework.starter.idempotent.enums.IdempotentTypeEnum;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.dto.TrainPurchaseTicketRespDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.TRAIN_STATION_REMAINING_TICKET;

/**
 * 退票结果回调消费者：已支付订单退票成功后释放座位、回补余票缓存并流转车票状态
 * 补齐"支付退款成功 → 座位可再售 → 余票缓存回加"的回退链路，防止退票座位永久占用造成少卖
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = TicketRocketMQConstant.PAY_GLOBAL_TOPIC_KEY,
        selectorExpression = TicketRocketMQConstant.REFUND_RESULT_CALLBACK_TAG_KEY,
        consumerGroup = TicketRocketMQConstant.REFUND_RESULT_CALLBACK_TICKET_CG_KEY
)
public class RefundResultCallbackTicketConsumer implements RocketMQListener<MessageWrapper<RefundResultCallbackTicketEvent>> {

    private final TicketOrderRemoteService ticketOrderRemoteService;
    private final SeatService seatService;
    private final TicketMapper ticketMapper;
    private final TrainStationService trainStationService;
    private final DistributedCache distributedCache;
    private final org.springframework.core.env.ConfigurableEnvironment environment;

    @Idempotent(
            uniqueKeyPrefix = "index12306-ticket:refund_result_callback:",
            key = "#message.getKeys()+'_'+#message.hashCode()",
            type = IdempotentTypeEnum.SPEL,
            scene = IdempotentSceneEnum.MQ,
            keyTimeout = 7200L
    )
    @Transactional(rollbackFor = Exception.class)
    @Override
    public void onMessage(MessageWrapper<RefundResultCallbackTicketEvent> message) {
        RefundResultCallbackTicketEvent event = message.getMessage();
        Result<TicketOrderDetailRespDTO> orderDetailResult = ticketOrderRemoteService.queryTicketOrderByOrderSn(event.getOrderSn());
        if (!orderDetailResult.isSuccess() || Objects.isNull(orderDetailResult.getData())) {
            throw new ServiceException("退票结果回调查询订单失败");
        }
        TicketOrderDetailRespDTO orderDetail = orderDetailResult.getData();
        boolean fullRefund = "FULL_REFUND".equals(event.getRefundTypeEnum());
        Set<String> refundedIdCards = fullRefund || CollUtil.isEmpty(event.getPartialRefundTicketDetailList())
                ? Collections.emptySet()
                : event.getPartialRefundTicketDetailList().stream()
                        .map(RefundResultCallbackTicketEvent.RefundPassengerDetailDTO::getIdCard)
                        .filter(StrUtil::isNotEmpty)
                        .collect(Collectors.toSet());
        List<TicketOrderPassengerDetailRespDTO> refundedDetails = orderDetail.getPassengerDetails().stream()
                .filter(each -> fullRefund || refundedIdCards.contains(each.getIdCard()))
                .collect(Collectors.toList());
        List<TrainPurchaseTicketRespDTO> refundSeatResults = refundedDetails.stream()
                .map(each -> {
                    TrainPurchaseTicketRespDTO result = new TrainPurchaseTicketRespDTO();
                    result.setCarriageNumber(each.getCarriageNumber());
                    result.setSeatNumber(each.getSeatNumber());
                    result.setSeatType(each.getSeatType());
                    result.setPassengerId(each.getId());
                    return result;
                })
                .collect(Collectors.toList());
        if (CollUtil.isEmpty(refundSeatResults)) {
            log.warn("[退票回调] 订单 {} 未匹配到退票乘车人座位，跳过释放", event.getOrderSn());
            return;
        }
        String trainId = String.valueOf(orderDetail.getTrainId());
        // 车票账本先流转为已退票（仅已支付状态可流转，天然幂等）：
        // 必须先于 unlock 执行，否则 unlock 会把有效票作废为 CLOSED，覆盖退票状态
        refundedDetails.forEach(each -> ticketMapper.update(null, Wrappers.lambdaUpdate(TicketDO.class)
                .eq(TicketDO::getTrainId, Long.valueOf(trainId))
                .eq(TicketDO::getCarriageNumber, each.getCarriageNumber())
                .eq(TicketDO::getSeatNumber, each.getSeatNumber())
                .eq(TicketDO::getUsername, each.getUsername())
                .eq(TicketDO::getTicketStatus, TicketStatusEnum.PAID.getCode())
                .set(TicketDO::getTicketStatus, TicketStatusEnum.REFUNDED.getCode())));
        // 释放座位：清理座位占用位图（unlock 内的账本作废因已流转为 REFUNDED 而不再匹配）
        seatService.unlock(trainId, orderDetail.getDeparture(), orderDetail.getArrival(), refundSeatResults);
        // 回补余票缓存：按退票坐席数量沿覆盖站段回加
        if (!StrUtil.equals(environment.getProperty("ticket.availability.cache-update.type", ""), "binlog")) {
            StringRedisTemplate stringRedisTemplate = (StringRedisTemplate) distributedCache.getInstance();
            Map<Integer, Long> seatTypeCountMap = refundSeatResults.stream()
                    .collect(Collectors.groupingBy(TrainPurchaseTicketRespDTO::getSeatType, Collectors.counting()));
            List<RouteDTO> routeDTOList = trainStationService.listTakeoutTrainStationRoute(trainId, orderDetail.getDeparture(), orderDetail.getArrival());
            routeDTOList.forEach(each -> {
                String keySuffix = StrUtil.join("_", trainId, each.getStartStation(), each.getEndStation());
                seatTypeCountMap.forEach((seatType, count) ->
                        stringRedisTemplate.opsForHash().increment(TRAIN_STATION_REMAINING_TICKET + keySuffix, String.valueOf(seatType), count));
            });
        }
        log.info("[退票回调] 订单 {} 退票账本流转、座位释放与余票缓存回补完成，共 {} 张", event.getOrderSn(), refundSeatResults.size());
    }

}
