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

package org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.filter.purchase;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.PurchaseTicketPassengerDetailDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.req.PurchaseTicketReqDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.TicketOrderRemoteService;
import org.opengoofy.index12306.biz.ticketservice.remote.UserRemoteService;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.PassengerRespDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderActivePurchaseQueryReqDTO;
import org.opengoofy.index12306.framework.starter.convention.exception.ClientException;
import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;
import org.opengoofy.index12306.framework.starter.convention.result.Result;
import org.opengoofy.index12306.frameworks.starter.user.core.UserContext;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 购票流程过滤器之验证乘客是否重复购买
 * 以订单数据为准（待支付/已支付/已进站视为持票），已关闭、已退票、已改签订单自动放行；
 * 校验维度为身份证号，跨账号为同一乘车人购票同样拦截
 * 公众号：马丁玩编程，回复：加群，添加马哥微信（备注：12306）获取项目资料
 */
@Slf4j
@Component
@RequiredArgsConstructor
public final class TrainPurchaseTicketRepeatChainHandler implements TrainPurchaseTicketChainFilter<PurchaseTicketReqDTO> {

    private final UserRemoteService userRemoteService;
    private final TicketOrderRemoteService ticketOrderRemoteService;

    @Override
    public void handler(PurchaseTicketReqDTO requestParam) {
        // 改签/变更到站复用购票责任链，其语义即旧票置换（同车次变更到站合法），跳过重复购买校验
        if (StrUtil.isNotBlank(requestParam.getRescheduleSourceOrderSn())) {
            return;
        }
        List<String> passengerIds = requestParam.getPassengers().stream()
                .map(PurchaseTicketPassengerDetailDTO::getPassengerId)
                .filter(StrUtil::isNotBlank)
                .distinct()
                .toList();
        if (CollUtil.isEmpty(passengerIds)) {
            return;
        }
        // 乘车人 ID 解析身份证号，仅校验当前账号名下乘车人；解析不出的交给后续选座链路报错
        Result<List<PassengerRespDTO>> passengerResult;
        try {
            passengerResult = userRemoteService.listPassengerQueryByIds(UserContext.getUsername(), passengerIds);
        } catch (Throwable ex) {
            log.error("乘车人信息查询失败，乘车人 ID：{}", passengerIds, ex);
            throw new ServiceException("乘车人信息校验失败，请稍后重试");
        }
        if (passengerResult == null || !passengerResult.isSuccess() || CollUtil.isEmpty(passengerResult.getData())) {
            throw new ServiceException("乘车人信息校验失败，请稍后重试");
        }
        Map<String, String> idCardRealNameMap = new HashMap<>();
        passengerResult.getData().forEach(each -> {
            if (StrUtil.isNotBlank(each.getIdCard())) {
                idCardRealNameMap.put(each.getIdCard(), StrUtil.blankToDefault(each.getRealName(), "乘车人"));
            }
        });
        if (idCardRealNameMap.isEmpty()) {
            return;
        }
        TicketOrderActivePurchaseQueryReqDTO activePurchaseQuery = new TicketOrderActivePurchaseQueryReqDTO();
        activePurchaseQuery.setTrainId(requestParam.getTrainId());
        activePurchaseQuery.setIdCards(List.copyOf(idCardRealNameMap.keySet()));
        Result<List<String>> purchasedResult;
        try {
            purchasedResult = ticketOrderRemoteService.listActivePurchasedIdCards(activePurchaseQuery);
        } catch (Throwable ex) {
            log.error("重复购买校验查询订单失败，乘车人身份证：{}", idCardRealNameMap.keySet(), ex);
            throw new ServiceException("重复购票校验失败，请稍后重试");
        }
        if (purchasedResult == null || !purchasedResult.isSuccess() || purchasedResult.getData() == null) {
            throw new ServiceException("重复购票校验失败，请稍后重试");
        }
        List<String> purchasedIdCards = purchasedResult.getData().stream()
                .filter(idCardRealNameMap::containsKey)
                .distinct()
                .toList();
        if (CollUtil.isEmpty(purchasedIdCards)) {
            return;
        }
        String purchasedRealNames = purchasedIdCards.stream()
                .map(idCardRealNameMap::get)
                .distinct()
                .collect(Collectors.joining("、"));
        throw new ClientException("乘车人 " + purchasedRealNames + " 已购买当前车次，请勿重复购买");
    }

    @Override
    public int getOrder() {
        return 30;
    }
}
