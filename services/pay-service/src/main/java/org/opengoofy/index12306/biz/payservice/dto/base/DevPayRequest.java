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

package org.opengoofy.index12306.biz.payservice.dto.base;

import lombok.Data;
import lombok.experimental.Accessors;
import org.opengoofy.index12306.biz.payservice.common.enums.PayChannelEnum;
import org.opengoofy.index12306.biz.payservice.common.enums.PayTradeTypeEnum;

import java.math.BigDecimal;

/**
 * 开发者支付请求入参（仅开发环境使用，直接支付成功）
 */
@Data
@Accessors(chain = true)
public final class DevPayRequest extends AbstractPayRequest {

    /**
     * 子订单号
     */
    private String outOrderSn;

    /**
     * 订单总金额
     */
    private BigDecimal totalAmount;

    /**
     * 订单标题
     */
    private String subject;

    @Override
    public String buildMark() {
        return PayChannelEnum.DEV_PAY.name();
    }
}
