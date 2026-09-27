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

package org.opengoofy.index12306.biz.ticketservice.service.task;

import com.alibaba.fastjson2.JSON;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.OrderCreateTaskDO;
import org.opengoofy.index12306.biz.ticketservice.dto.req.TicketPurchasePrepareDTO;
import org.opengoofy.index12306.biz.ticketservice.mq.event.OrderCreateConfirmEvent;
import org.opengoofy.index12306.biz.ticketservice.mq.produce.OrderCreateConfirmSendProduce;
import org.opengoofy.index12306.biz.ticketservice.service.OrderCreateTaskService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 订单创建补偿任务扫描器：周期扫描发件箱（t_order_create_task）中未确认的任务并投递 MQ，
 * 兜底实例在"车票账本已落库、订单未创建"之间宕机后同步链路无法自愈的悬挂窗口。
 * 投递失败仅记录日志，下个周期重新扫描投递；重复投递由订单服务购票令牌幂等兜底。
 * 公众号：马丁玩编程，回复：加群，添加马哥微信（备注：12306）获取项目资料
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderCreateTaskScanner {

    private static final long FIXED_DELAY_MS = 15_000L;

    private static final int BATCH_SIZE = 50;

    private final OrderCreateTaskService orderCreateTaskService;
    private final OrderCreateConfirmSendProduce orderCreateConfirmSendProduce;

    private ScheduledExecutorService scheduler;

    @PostConstruct
    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "order-create-task-scanner");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::scanAndDispatch, FIXED_DELAY_MS, FIXED_DELAY_MS, TimeUnit.MILLISECONDS);
        log.info("订单创建补偿任务扫描器已启动，扫描周期 {}s", FIXED_DELAY_MS / 1000);
    }

    @PreDestroy
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    void scanAndDispatch() {
        try {
            List<OrderCreateTaskDO> taskList = orderCreateTaskService.listPendingTasks(BATCH_SIZE);
            for (OrderCreateTaskDO task : taskList) {
                try {
                    dispatch(task);
                } catch (Throwable ex) {
                    log.warn("[订单创建补偿] 补偿消息投递失败，令牌：{}，等待下个扫描周期重试", task.getPurchaseToken(), ex);
                }
            }
        } catch (Throwable ex) {
            log.error("[订单创建补偿] 补偿任务周期扫描异常", ex);
        }
    }

    private void dispatch(OrderCreateTaskDO task) {
        OrderCreateConfirmEvent event = OrderCreateConfirmEvent.builder()
                .purchaseToken(task.getPurchaseToken())
                .username(task.getUsername())
                .userId(task.getUserId())
                .prepare(JSON.parseObject(task.getTaskContent(), TicketPurchasePrepareDTO.class))
                .build();
        orderCreateConfirmSendProduce.sendMessage(event);
        orderCreateTaskService.markSent(task);
        log.info("[订单创建补偿] 已投递补偿消息，令牌：{}，车票：{}", task.getPurchaseToken(), event.getPrepare().getTicketIds());
    }
}
