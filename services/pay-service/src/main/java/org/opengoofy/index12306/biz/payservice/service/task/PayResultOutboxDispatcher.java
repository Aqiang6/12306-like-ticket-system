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

package org.opengoofy.index12306.biz.payservice.service.task;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.opengoofy.index12306.biz.payservice.dao.entity.PayDO;
import org.opengoofy.index12306.biz.payservice.dao.mapper.PayMapper;
import org.opengoofy.index12306.biz.payservice.mq.event.PayResultCallbackOrderEvent;
import org.opengoofy.index12306.biz.payservice.mq.produce.PayResultCallbackOrderSendProduce;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 支付结果发件箱：只读取已提交的支付行，发送成功后确认；失败或宕机后重试。
 * 消息至少发送一次，消费者以订单号幂等。扫描不持有数据库事务或行锁执行网络调用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PayResultOutboxDispatcher {

    private final PayMapper payMapper;
    private final PayResultCallbackOrderSendProduce producer;
    private ScheduledExecutorService scheduler;

    @PostConstruct
    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "pay-result-outbox");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::dispatchPending, 1, 1, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    public void dispatchPending() {
        try {
            Date now = new Date();
            var pending = payMapper.selectList(Wrappers.lambdaQuery(PayDO.class)
                    .eq(PayDO::getNotificationStatus, 1)
                    .le(PayDO::getNotificationNextRetry, now)
                    .orderByAsc(PayDO::getNotificationNextRetry)
                    .last("LIMIT 100"));
            for (PayDO pay : pending) {
                dispatch(pay);
            }
        } catch (Exception ex) {
            log.error("支付结果发件箱扫描失败，下一周期重试", ex);
        }
    }

    private void dispatch(PayDO pay) {
        try {
            Date now = new Date();
            // 条件更新认领，orderSn 将写入精确路由至支付单所在分片。
            int claimed = payMapper.update(null, Wrappers.lambdaUpdate(PayDO.class)
                    .eq(PayDO::getOrderSn, pay.getOrderSn())
                    .eq(PayDO::getId, pay.getId())
                    .eq(PayDO::getNotificationStatus, 1)
                    .le(PayDO::getNotificationNextRetry, now)
                    .set(PayDO::getNotificationNextRetry, new Date(now.getTime() + 30_000L)));
            if (claimed == 0) {
                return;
            }
            PayResultCallbackOrderEvent event = JSON.parseObject(pay.getNotificationPayload(), PayResultCallbackOrderEvent.class);
            SendResult result = producer.sendMessage(event);
            if (result == null || result.getSendStatus() != SendStatus.SEND_OK) {
                throw new IllegalStateException("MQ 未确认支付结果消息发送成功");
            }
            payMapper.update(null, Wrappers.lambdaUpdate(PayDO.class)
                    .eq(PayDO::getOrderSn, pay.getOrderSn())
                    .eq(PayDO::getId, pay.getId())
                    .eq(PayDO::getNotificationStatus, 1)
                    .set(PayDO::getNotificationStatus, 2));
        } catch (Exception ex) {
            log.warn("支付结果消息待重试，orderSn：{}", pay.getOrderSn(), ex);
        }
    }
}
