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

package org.opengoofy.index12306.biz.ticketservice.dto.resp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 车票改签返回参数实体
 * 结算遵循"多退少补"原则：原票退款金额 = 原票金额 - 手续费；新票按全价生成新订单待支付
 * 公众号：马丁玩编程，回复：加群，添加马哥微信（备注：12306）获取项目资料
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChangeTicketRespDTO {

    /**
     * 改签后新订单号（费用预览时为空）
     */
    private String newOrderSn;

    /**
     * 改签业务类型 0：改签 1：变更到站
     */
    private Integer changeType;

    /**
     * 原票金额合计（分）
     */
    private Integer oldAmount;

    /**
     * 新票金额合计（分）
     */
    private Integer newAmount;

    /**
     * 手续费合计（分）：改签时为改签费，变更到站差额退款时按退票费标准核收
     */
    private Integer changeFee;

    /**
     * 原票应退金额（分） = 原票金额合计 - 手续费合计
     */
    private Integer refundAmount;

    /**
     * 费用规则说明文案
     */
    private String ruleDesc;
}
