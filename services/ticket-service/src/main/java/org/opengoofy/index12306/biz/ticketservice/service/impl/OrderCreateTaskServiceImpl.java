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

package org.opengoofy.index12306.biz.ticketservice.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opengoofy.index12306.biz.ticketservice.common.enums.OrderCreateTaskStatusEnum;
import org.opengoofy.index12306.biz.ticketservice.common.enums.TicketStatusEnum;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.OrderCreateTaskDO;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.TicketDO;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.OrderCreateTaskMapper;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.TicketMapper;
import org.opengoofy.index12306.biz.ticketservice.dto.req.TicketPurchasePrepareDTO;
import org.opengoofy.index12306.biz.ticketservice.service.OrderCreateTaskService;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

/**
 * 订单创建补偿任务服务实现
 * 公众号：马丁玩编程，回复：加群，添加马哥微信（备注：12306）获取项目资料
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderCreateTaskServiceImpl extends ServiceImpl<OrderCreateTaskMapper, OrderCreateTaskDO> implements OrderCreateTaskService {

    /**
     * 发件箱写入时预置的首次投递延迟：给同步建单链路留出先行窗口，正常路径不会与补偿消费者并发
     */
    private static final long FIRST_DISPATCH_DELAY_SECONDS = 15L;

    /**
     * 投递失败或消费未确认后的重投间隔
     */
    private static final long RESEND_INTERVAL_SECONDS = 30L;

    private final TicketMapper ticketMapper;

    @Override
    public void record(TicketPurchasePrepareDTO prepare, String username, String userId) {
        try {
            OrderCreateTaskDO task = OrderCreateTaskDO.builder()
                    .purchaseToken(prepare.getPurchaseToken())
                    .username(username)
                    .userId(userId)
                    .trainId(Long.parseLong(prepare.getTrainId()))
                    .taskStatus(OrderCreateTaskStatusEnum.CREATED.getCode())
                    .retryCount(0)
                    .nextRetryTime(new Date(System.currentTimeMillis() + FIRST_DISPATCH_DELAY_SECONDS * 1000))
                    .taskContent(JSON.toJSONString(prepare))
                    .build();
            getBaseMapper().insert(task);
        } catch (Throwable ex) {
            // 发件箱写入失败不阻断购票：同步链路正常时无需补偿，仅实例宕机窗口失去保护，对账任务可兜底座位一致性
            log.error("[订单创建补偿] 发件箱记录写入失败，令牌：{}", prepare.getPurchaseToken(), ex);
        }
    }

    @Override
    public void confirmByToken(String purchaseToken) {
        updateStatusByToken(purchaseToken, OrderCreateTaskStatusEnum.CONFIRMED);
    }

    @Override
    public void cancelByToken(String purchaseToken) {
        updateStatusByToken(purchaseToken, OrderCreateTaskStatusEnum.CANCELLED);
    }

    private void updateStatusByToken(String purchaseToken, OrderCreateTaskStatusEnum targetStatus) {
        if (StrUtil.isBlank(purchaseToken)) {
            return;
        }
        getBaseMapper().update(null, Wrappers.lambdaUpdate(OrderCreateTaskDO.class)
                .eq(OrderCreateTaskDO::getPurchaseToken, purchaseToken)
                .eq(OrderCreateTaskDO::getTaskStatus, OrderCreateTaskStatusEnum.CREATED.getCode())
                .set(OrderCreateTaskDO::getTaskStatus, targetStatus.getCode()));
    }

    @Override
    public boolean existsAliveTickets(List<Long> ticketIds) {
        if (CollUtil.isEmpty(ticketIds)) {
            return false;
        }
        Long count = ticketMapper.selectCount(Wrappers.lambdaQuery(TicketDO.class)
                .in(TicketDO::getId, ticketIds)
                .eq(TicketDO::getTicketStatus, TicketStatusEnum.UNPAID.getCode()));
        return count != null && count >= ticketIds.size();
    }

    @Override
    public List<OrderCreateTaskDO> listPendingTasks(int limit) {
        return getBaseMapper().selectList(Wrappers.lambdaQuery(OrderCreateTaskDO.class)
                .eq(OrderCreateTaskDO::getTaskStatus, OrderCreateTaskStatusEnum.CREATED.getCode())
                .le(OrderCreateTaskDO::getNextRetryTime, new Date())
                .last("LIMIT " + limit));
    }

    @Override
    public void markSent(OrderCreateTaskDO task) {
        getBaseMapper().update(null, Wrappers.lambdaUpdate(OrderCreateTaskDO.class)
                .eq(OrderCreateTaskDO::getId, task.getId())
                .setSql("retry_count = retry_count + 1")
                .set(OrderCreateTaskDO::getNextRetryTime,
                        new Date(System.currentTimeMillis() + RESEND_INTERVAL_SECONDS * 1000)));
    }

    @Override
    public int incrementAndGetRetryCount(String purchaseToken) {
        if (StrUtil.isBlank(purchaseToken)) {
            return 0;
        }
        getBaseMapper().update(null, Wrappers.lambdaUpdate(OrderCreateTaskDO.class)
                .eq(OrderCreateTaskDO::getPurchaseToken, purchaseToken)
                .setSql("retry_count = retry_count + 1"));
        OrderCreateTaskDO task = getBaseMapper().selectOne(Wrappers.lambdaQuery(OrderCreateTaskDO.class)
                .eq(OrderCreateTaskDO::getPurchaseToken, purchaseToken));
        return task == null || task.getRetryCount() == null ? 0 : task.getRetryCount();
    }
}
