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

package org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.ratelimit;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 购票限流令牌桶配置
 * 桶容量随余票动态伸缩（余票总量 × 容量倍数），补充速率按下游选座临界区
 * （本地锁 + 分布式公平锁 + 位图选座）的实测排水能力配置并留有余量
 */
@Data
@Component
@ConfigurationProperties(prefix = "ticket.purchase.rate-limiter")
public class TicketPurchaseRateLimiterProperties {

    /**
     * 是否启用购票限流
     */
    private Boolean enabled = Boolean.TRUE;

    /**
     * 令牌桶容量倍数：桶容量 = 当前区间余票缓存总量 × 该倍数（动态跟随票量，售罄前准入自动收紧）
     */
    private Double capacityMultiplier = 1.5D;

    /**
     * 每秒补充令牌数：持续准入吞吐
     */
    private Integer refillRate = 25;
}
