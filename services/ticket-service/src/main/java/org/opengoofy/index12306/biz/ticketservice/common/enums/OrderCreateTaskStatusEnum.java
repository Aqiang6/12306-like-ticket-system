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

/**
 * 订单创建补偿任务状态枚举
 * 公众号：马丁玩编程，回复：加群，添加马哥微信（备注：12306）获取项目资料
 */
@Getter
@RequiredArgsConstructor
public enum OrderCreateTaskStatusEnum {

    /**
     * 待确认：账本已落库，订单尚未确认创建成功
     */
    CREATED(0),

    /**
     * 已完成：订单创建成功
     */
    CONFIRMED(1),

    /**
     * 已作废：同步链路已补偿回滚，或补偿任务核验后无需建单
     */
    CANCELLED(2);

    private final Integer code;
}
