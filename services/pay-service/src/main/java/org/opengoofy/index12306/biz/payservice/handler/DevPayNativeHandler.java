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

package org.opengoofy.index12306.biz.payservice.handler;

import cn.hutool.core.text.StrBuilder;
import lombok.extern.slf4j.Slf4j;
import org.opengoofy.index12306.biz.payservice.common.enums.PayChannelEnum;
import org.opengoofy.index12306.biz.payservice.common.enums.PayTradeTypeEnum;
import org.opengoofy.index12306.biz.payservice.dto.base.PayRequest;
import org.opengoofy.index12306.biz.payservice.dto.base.PayResponse;
import org.opengoofy.index12306.biz.payservice.handler.base.AbstractPayHandler;
import org.opengoofy.index12306.framework.starter.designpattern.strategy.AbstractExecuteStrategy;
import org.springframework.stereotype.Service;

/**
 * 开发者支付组件（仅开发环境使用，直接支付成功）
 */
@Slf4j
@Service
public class DevPayNativeHandler extends AbstractPayHandler implements AbstractExecuteStrategy<PayRequest, PayResponse> {

    @Override
    public PayResponse pay(PayRequest payRequest) {
        log.info("[开发者支付] 订单号：{}，直接支付成功", payRequest.getOrderSn());
        return new PayResponse("开发者支付成功");
    }

    @Override
    public String mark() {
        return PayChannelEnum.DEV_PAY.name();
    }

    @Override
    public PayResponse executeResp(PayRequest requestParam) {
        return pay(requestParam);
    }
}
