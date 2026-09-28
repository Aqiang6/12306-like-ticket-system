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

package org.opengoofy.index12306.biz.payservice.service.impl;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opengoofy.index12306.biz.payservice.common.enums.PayChannelEnum;
import org.opengoofy.index12306.biz.payservice.common.enums.TradeStatusEnum;
import org.opengoofy.index12306.biz.payservice.convert.RefundRequestConvert;
import org.opengoofy.index12306.biz.payservice.dao.entity.PayDO;
import org.opengoofy.index12306.biz.payservice.dao.mapper.PayMapper;
import org.opengoofy.index12306.biz.payservice.dto.PayCallbackReqDTO;
import org.opengoofy.index12306.biz.payservice.dto.PayInfoRespDTO;
import org.opengoofy.index12306.biz.payservice.dto.PayRespDTO;
import org.opengoofy.index12306.biz.payservice.dto.RefundCommand;
import org.opengoofy.index12306.biz.payservice.dto.RefundReqDTO;
import org.opengoofy.index12306.biz.payservice.dto.RefundRespDTO;
import org.opengoofy.index12306.biz.payservice.dto.base.PayRequest;
import org.opengoofy.index12306.biz.payservice.dto.base.PayResponse;
import org.opengoofy.index12306.biz.payservice.dto.base.RefundRequest;
import org.opengoofy.index12306.biz.payservice.dto.base.RefundResponse;
import org.opengoofy.index12306.biz.payservice.handler.AliPayNativeHandler;
import org.opengoofy.index12306.biz.payservice.handler.AliRefundNativeHandler;
import org.opengoofy.index12306.biz.payservice.mq.event.PayResultCallbackOrderEvent;
import org.opengoofy.index12306.biz.payservice.service.PayService;
import org.opengoofy.index12306.biz.payservice.service.payid.PayIdGeneratorManager;
import org.opengoofy.index12306.framework.starter.cache.DistributedCache;
import org.opengoofy.index12306.framework.starter.common.toolkit.BeanUtil;
import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;
import org.opengoofy.index12306.framework.starter.designpattern.strategy.AbstractStrategyChoose;
import org.opengoofy.index12306.framework.starter.idempotent.annotation.Idempotent;
import org.opengoofy.index12306.framework.starter.idempotent.enums.IdempotentTypeEnum;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Date;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static org.opengoofy.index12306.biz.payservice.common.constant.RedisKeyConstant.ORDER_PAY_RESULT_INFO;

import org.opengoofy.index12306.biz.payservice.remote.TicketOrderRemoteService;
import org.opengoofy.index12306.biz.payservice.remote.dto.TicketOrderDetailRespDTO;
import org.opengoofy.index12306.framework.starter.convention.result.Result;

/**
 * 支付接口层实现
 * 公众号：马丁玩编程，回复：加群，添加马哥微信（备注：12306）获取项目资料
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PayServiceImpl implements PayService {

    private final PayMapper payMapper;
    private final AbstractStrategyChoose abstractStrategyChoose;
    private final TicketOrderRemoteService ticketOrderRemoteService;
    private final DistributedCache distributedCache;

    @Idempotent(
            type = IdempotentTypeEnum.SPEL,
            uniqueKeyPrefix = "index12306-pay:lock_create_pay:",
            key = "#requestParam.getOutOrderSn()"
    )
    @Transactional(rollbackFor = Exception.class)
    @Override
    public PayRespDTO commonPay(PayRequest requestParam) {
        PayRespDTO cacheResult = distributedCache.get(ORDER_PAY_RESULT_INFO + requestParam.getOrderSn(), PayRespDTO.class);
        if (cacheResult != null) {
            return cacheResult;
        }
        // 支付前校验订单状态：仅待支付订单可收款，防止对已关闭订单继续收款
        Result<TicketOrderDetailRespDTO> orderResult = ticketOrderRemoteService.queryTicketOrderByOrderSn(requestParam.getOrderSn());
        if (orderResult == null || !orderResult.isSuccess() || Objects.isNull(orderResult.getData())) {
            throw new ServiceException("订单不存在");
        }
        Integer orderStatus = orderResult.getData().getStatus();
        if (!Objects.equals(orderStatus, 0)) {
            if (Objects.equals(orderStatus, 10)) {
                throw new ServiceException("订单已支付，请勿重复支付");
            }
            throw new ServiceException("订单已超时关闭，请重新购票");
        }
        /**
         * {@link AliPayNativeHandler}
         */
        // 策略模式：通过策略模式封装支付渠道和支付场景，用户支付时动态选择对应的支付组件
        PayResponse result = abstractStrategyChoose.chooseAndExecuteResp(requestParam.buildMark(), requestParam);
        PayDO insertPay = BeanUtil.convert(requestParam, PayDO.class);
        String paySn = PayIdGeneratorManager.generateId(requestParam.getOrderSn());
        insertPay.setPaySn(paySn);
        insertPay.setStatus(TradeStatusEnum.WAIT_BUYER_PAY.tradeCode());
        insertPay.setTotalAmount(requestParam.getTotalAmount().multiply(new BigDecimal("100")).setScale(0, BigDecimal.ROUND_HALF_UP).intValue());
        int insert = payMapper.insert(insertPay);
        if (insert <= 0) {
            log.error("支付单创建失败，支付聚合根：{}", JSON.toJSONString(requestParam));
            throw new ServiceException("支付单创建失败");
        }
        distributedCache.put(ORDER_PAY_RESULT_INFO + requestParam.getOrderSn(), JSON.toJSONString(result), 10, TimeUnit.MINUTES);
        // 开发者支付：直接触发支付成功回调，无需等待第三方支付
        if (Objects.equals(requestParam.getChannel(), PayChannelEnum.DEV_PAY.getCode())) {
            PayCallbackReqDTO devCallback = PayCallbackReqDTO.builder()
                    .orderSn(requestParam.getOrderSn())
                    .status(TradeStatusEnum.TRADE_SUCCESS.tradeCode())
                    .payAmount(requestParam.getTotalAmount().multiply(new BigDecimal("100")).setScale(0, BigDecimal.ROUND_HALF_UP).intValue())
                    .tradeNo("DEV_PAY_" + System.currentTimeMillis())
                    .gmtPayment(new Date())
                    .build();
            callbackPay(devCallback);
        }
        return BeanUtil.convert(result, PayRespDTO.class);
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void callbackPay(PayCallbackReqDTO requestParam) {
        LambdaQueryWrapper<PayDO> queryWrapper = Wrappers.lambdaQuery(PayDO.class)
                .eq(PayDO::getOrderSn, requestParam.getOrderSn());
        PayDO payDO = payMapper.selectOne(queryWrapper);
        if (Objects.isNull(payDO)) {
            log.error("支付单不存在，orderRequestId：{}", requestParam.getOrderRequestId());
            throw new ServiceException("支付单不存在");
        }
        // 已记录成功回调时不重建消息，也不允许迟到回调覆盖支付/退款状态。
        if (payDO.getNotificationStatus() != null && payDO.getNotificationStatus() != 0) {
            return;
        }
        Integer previousStatus = payDO.getStatus();
        payDO.setTradeNo(requestParam.getTradeNo());
        payDO.setStatus(requestParam.getStatus());
        payDO.setPayAmount(requestParam.getPayAmount());
        payDO.setGmtPayment(requestParam.getGmtPayment());
        if (Objects.equals(requestParam.getStatus(), TradeStatusEnum.TRADE_SUCCESS.tradeCode())) {
            payDO.setNotificationStatus(1);
            payDO.setNotificationPayload(JSON.toJSONString(BeanUtil.convert(payDO, PayResultCallbackOrderEvent.class)));
            payDO.setNotificationNextRetry(new Date());
        }
        LambdaUpdateWrapper<PayDO> updateWrapper = Wrappers.lambdaUpdate(PayDO.class)
                .eq(PayDO::getOrderSn, requestParam.getOrderSn())
                .eq(PayDO::getStatus, previousStatus)
                .eq(PayDO::getNotificationStatus, 0);
        int result = payMapper.update(payDO, updateWrapper);
        if (result <= 0) {
            PayDO latest = payMapper.selectOne(Wrappers.lambdaQuery(PayDO.class)
                    .eq(PayDO::getOrderSn, requestParam.getOrderSn()).last("FOR UPDATE"));
            if (latest != null && latest.getNotificationStatus() != null && latest.getNotificationStatus() != 0) {
                return;
            }
            log.error("修改支付单支付结果失败，支付单信息：{}", JSON.toJSONString(payDO));
            throw new ServiceException("修改支付单支付结果失败");
        }
        // 此处只落库。独立扫描器读取已提交的发件箱并发送，事务失败时不会发出消息。
    }

    @Override
    public PayInfoRespDTO getPayInfoByOrderSn(String orderSn) {
        LambdaQueryWrapper<PayDO> queryWrapper = Wrappers.lambdaQuery(PayDO.class)
                .eq(PayDO::getOrderSn, orderSn);
        PayDO payDO = payMapper.selectOne(queryWrapper);
        return BeanUtil.convert(payDO, PayInfoRespDTO.class);
    }

    @Override
    public PayInfoRespDTO getPayInfoByPaySn(String paySn) {
        LambdaQueryWrapper<PayDO> queryWrapper = Wrappers.lambdaQuery(PayDO.class)
                .eq(PayDO::getPaySn, paySn);
        PayDO payDO = payMapper.selectOne(queryWrapper);
        return BeanUtil.convert(payDO, PayInfoRespDTO.class);
    }

    @Override
    public RefundRespDTO commonRefund(RefundReqDTO requestParam) {
        LambdaQueryWrapper<PayDO> queryWrapper = Wrappers.lambdaQuery(PayDO.class)
                .eq(PayDO::getOrderSn, requestParam.getOrderSn());
        PayDO payDO = payMapper.selectOne(queryWrapper);
        if (Objects.isNull(payDO)) {
            log.error("支付单不存在，orderSn：{}", requestParam.getOrderSn());
            throw new ServiceException("支付单不存在");
        }
        /**
         * {@link AliRefundNativeHandler}
         */
        // 策略模式：通过策略模式封装退款渠道和退款场景，用户退款时动态选择对应的退款组件
        RefundCommand refundCommand = BeanUtil.convert(payDO, RefundCommand.class);
        RefundRequest refundRequest = RefundRequestConvert.command2RefundRequest(refundCommand);
        RefundResponse result = abstractStrategyChoose.chooseAndExecuteResp(refundRequest.buildMark(), refundRequest);
        payDO.setStatus(result.getStatus());
        LambdaUpdateWrapper<PayDO> updateWrapper = Wrappers.lambdaUpdate(PayDO.class)
                .eq(PayDO::getOrderSn, requestParam.getOrderSn());
        int updateResult = payMapper.update(null, updateWrapper.set(PayDO::getStatus, payDO.getStatus()));
        if (updateResult <= 0) {
            log.error("修改支付单退款结果失败，支付单信息：{}", JSON.toJSONString(payDO));
            throw new ServiceException("修改支付单退款结果失败");
        }
        return null;
    }
}
