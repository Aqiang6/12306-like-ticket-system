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

package org.opengoofy.index12306.biz.ticketservice.mq.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.opengoofy.index12306.biz.ticketservice.dto.req.TicketPurchasePrepareDTO;

/**
 * 订单创建补偿任务事件
 * 公众号：马丁玩编程，回复：加群，添加马哥微信（备注：12306）获取项目资料
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderCreateConfirmEvent {

    /**
     * 购票幂等令牌（订单服务按令牌幂等创建订单）
     */
    private String purchaseToken;

    /**
     * 用户名（消费者重建用户上下文使用）
     */
    private String username;

    /**
     * 用户 ID
     */
    private String userId;

    /**
     * 购票临界区产物上下文
     */
    private TicketPurchasePrepareDTO prepare;
}
