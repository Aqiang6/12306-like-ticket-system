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

package org.opengoofy.index12306.biz.ticketservice.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.opengoofy.index12306.framework.starter.database.base.BaseDO;

import java.util.Date;

/**
 * 订单创建补偿任务实体（事务性发件箱）
 *
 * <p>与车票账本（t_ticket）在同一本地事务中写入：实例在"账本已提交、订单未创建"之间宕机时，
 * 扫描任务将本表未确认记录投递 RocketMQ，由消费者幂等地重建订单，消除跨服务调用的悬挂窗口。
 * 公众号：马丁玩编程，回复：加群，添加马哥微信（备注：12306）获取项目资料
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_order_create_task")
public class OrderCreateTaskDO extends BaseDO {

    /**
     * id
     */
    private Long id;

    /**
     * 购票幂等令牌（订单服务侧按令牌幂等创建订单）
     */
    private String purchaseToken;

    /**
     * 用户名（补偿消费时重建用户上下文）
     */
    private String username;

    /**
     * 用户 ID
     */
    private String userId;

    /**
     * 车次 ID
     */
    private Long trainId;

    /**
     * 任务状态：{@link org.opengoofy.index12306.biz.ticketservice.common.enums.OrderCreateTaskStatusEnum}
     */
    private Integer taskStatus;

    /**
     * 补偿投递次数
     */
    private Integer retryCount;

    /**
     * 下次补偿投递时间（写入时预置，给同步建单链路留出先行的窗口）
     */
    private Date nextRetryTime;

    /**
     * 任务内容：{@link org.opengoofy.index12306.biz.ticketservice.dto.req.TicketPurchasePrepareDTO} JSON
     */
    private String taskContent;
}
