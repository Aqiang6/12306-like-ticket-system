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

package org.opengoofy.index12306.biz.ticketservice.service.cache;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.CarriageDO;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.CarriageMapper;
import org.opengoofy.index12306.biz.ticketservice.toolkit.SeatBitMapAssembler;
import org.opengoofy.index12306.biz.ticketservice.toolkit.SeatBitMapUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 座位占用位图对账任务：以 t_ticket 售卖区间账本为持久事实，周期重算活跃车次各车厢的期望位图
 * 并与线上位图比对，发现漂移即告警并覆盖修复。
 *
 * <p>位图是热路径唯一准入、账本是持久账本，二者短暂不一致（如置位成功但事务回滚、
 * 释放位图失败、历史快照时点差异）表现为"少卖/幻影空闲"，由本任务统一收敛——
 * 接替旧模型中 t_seat 条件更新 UPDATE 的兜底职责。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeatBitMapReconciler {

    private static final long FIXED_DELAY_MS = 60_000L;

    private final SeatBitMapAssembler seatBitMapAssembler;
    private final CarriageMapper carriageMapper;
    private final TicketStockDisplayRefresher ticketStockDisplayRefresher;
    private final StringRedisTemplate stringRedisTemplate;

    private ScheduledExecutorService scheduler;

    @PostConstruct
    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "seat-bitmap-reconciler");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::reconcileAll, FIXED_DELAY_MS, FIXED_DELAY_MS, TimeUnit.MILLISECONDS);
        log.info("座位占用位图对账任务已启动，对账周期 {}s", FIXED_DELAY_MS / 1000);
    }

    @PreDestroy
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    void reconcileAll() {
        try {
            var activeTrains = ticketStockDisplayRefresher.activeTrainIds();
            if (CollUtil.isEmpty(activeTrains)) {
                return;
            }
            for (String trainId : activeTrains) {
                try {
                    reconcileTrain(trainId);
                } catch (Throwable ex) {
                    log.warn("座位位图对账失败，车次：{}", trainId, ex);
                }
            }
        } catch (Throwable ex) {
            log.error("座位位图对账周期执行异常", ex);
        }
    }

    private void reconcileTrain(String trainId) {
        List<CarriageDO> carriages = carriageMapper.selectList(Wrappers.lambdaQuery(CarriageDO.class)
                .eq(CarriageDO::getTrainId, Long.valueOf(trainId)));
        if (CollUtil.isEmpty(carriages)) {
            return;
        }
        for (CarriageDO carriage : carriages) {
            String bitMapKey = SeatBitMapUtil.buildKey(trainId, carriage.getCarriageNumber());
            byte[] actual = stringRedisTemplate.execute((RedisCallback<byte[]>) connection ->
                    connection.stringCommands().get(bitMapKey.getBytes(StandardCharsets.UTF_8)));
            if (actual == null) {
                // 位图缺失走按需组装路径，不属于漂移
                continue;
            }
            byte[] expected = seatBitMapAssembler.assemble(trainId, carriage.getCarriageNumber());
            if (expected == null) {
                continue;
            }
            if (!Arrays.equals(actual, expected)) {
                log.warn("[位图对账] 检测到位图与账本漂移，覆盖修复。车次：{}，车厢：{}", trainId, carriage.getCarriageNumber());
                stringRedisTemplate.execute((RedisCallback<Object>) connection ->
                        connection.stringCommands().set(bitMapKey.getBytes(StandardCharsets.UTF_8), expected));
            }
        }
    }
}
