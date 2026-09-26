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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 支付服务退款结果回调车票服务事件
 * 消费方据此释放退票座位、回补余票缓存并流转车票状态
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RefundResultCallbackTicketEvent {

    /**
     * 订单号
     */
    private String orderSn;

    /**
     * 退款类型：FULL_REFUND 全部退款 / PARTIAL_REFUND 部分退款
     */
    private String refundTypeEnum;

    /**
     * 本次退款乘车人明细（部分退款时为退票子集）
     */
    private List<RefundPassengerDetailDTO> partialRefundTicketDetailList;

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RefundPassengerDetailDTO {

        private Long userId;

        private String username;

        private Integer seatType;

        private String carriageNumber;

        private String seatNumber;

        private String realName;

        private Integer idType;

        private String idCard;

        private Integer ticketType;

        private Integer amount;

        private Integer status;
    }
}
