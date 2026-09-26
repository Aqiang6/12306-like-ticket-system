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

package org.opengoofy.index12306.biz.ticketservice.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

/**
 * 车票改签业务类型枚举
 * 改签与变更到站是两种不同的业务：改签适用范围更广，可同时变更乘车日期、车次和席位，开车前及开车后当日均可办理；
 * 变更到站仅可在开车前 48 小时以上办理，出发站不变，重新选择到站、乘车日期、车次和席位
 *
 * 公众号：马丁玩编程，回复：加群，添加马哥微信（备注：12306）获取项目资料
 */
@RequiredArgsConstructor
public enum ChangeTicketTypeEnum {

    /**
     * 改签
     */
    CHANGE(0, "改签"),

    /**
     * 变更到站
     */
    CHANGE_ARRIVAL(1, "变更到站");

    @Getter
    private final Integer code;

    @Getter
    private final String name;

    public static ChangeTicketTypeEnum findNameByCode(Integer code) {
        return Arrays.stream(values()).filter(each -> each.getCode().equals(code)).findFirst().orElse(null);
    }
}
