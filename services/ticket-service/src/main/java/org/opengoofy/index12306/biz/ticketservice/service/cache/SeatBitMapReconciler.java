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
import org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.CarriageDO;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.CarriageMapper;
import org.opengoofy.index12306.biz.ticketservice.toolkit.SeatBitMapAssembler;
import org.opengoofy.index12306.biz.ticketservice.toolkit.SeatBitMapUtil;
import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

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

    /**
     * 单把购票公平锁的获取超时：拿不到说明购票风暴进行中，本轮跳过（在途购票正是不能对账的时刻）
     */
    private static final long PURCHASE_LOCK_TRYLOCK_MS = 2_000L;

    private final SeatBitMapAssembler seatBitMapAssembler;
    private final CarriageMapper carriageMapper;
    private final TicketStockDisplayRefresher ticketStockDisplayRefresher;
    private final StringRedisTemplate stringRedisTemplate;
    private final RedissonClient redissonClient;
    private final ConfigurableEnvironment environment;

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
        // 与购票临界区互斥：位图置位发生在账本事务提交前，若不持购票公平锁就对账，
        // 会把"已置位、账本未提交"的在途座位误判为漂移清位，随后同座被二次售出（超卖）。
        // 锁按席别升序获取（与改签加锁顺序一致），获取失败说明购票风暴进行中，本轮跳过。
        List<Integer> seatTypes = carriages.stream()
                .map(CarriageDO::getCarriageType)
                .filter(SeatBitMapUtil::supports)
                .distinct()
                .sorted()
                .collect(java.util.stream.Collectors.toList());
        List<RLock> purchaseLocks = new ArrayList<>();
        try {
            for (Integer seatType : seatTypes) {
                String lockKey = environment.resolvePlaceholders(
                        String.format(RedisKeyConstant.LOCK_PURCHASE_TICKETS_V2, trainId, seatType));
                RLock lock = redissonClient.getFairLock(lockKey);
                try {
                    if (!lock.tryLock(PURCHASE_LOCK_TRYLOCK_MS, TimeUnit.MILLISECONDS)) {
                        log.debug("[位图对账] 车次：{} 席别：{} 购票锁竞争激烈，本轮跳过", trainId, seatType);
                        return;
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new ServiceException("位图对账获取购票锁被中断");
                }
                purchaseLocks.add(lock);
            }
            for (CarriageDO carriage : carriages) {
                reconcileCarriage(trainId, carriage);
            }
        } finally {
            for (int i = purchaseLocks.size() - 1; i >= 0; i--) {
                try {
                    purchaseLocks.get(i).unlock();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void reconcileCarriage(String trainId, CarriageDO carriage) {
        String bitMapKey = SeatBitMapUtil.buildKey(trainId, carriage.getCarriageNumber());
        byte[] actual = stringRedisTemplate.execute((RedisCallback<byte[]>) connection ->
                connection.stringCommands().get(bitMapKey.getBytes(StandardCharsets.UTF_8)));
        if (actual == null) {
            // 位图缺失走按需组装路径，不属于漂移
            return;
        }
        byte[] expected = seatBitMapAssembler.assemble(trainId, carriage.getCarriageNumber());
        if (expected == null) {
            return;
        }
        if (!Arrays.equals(actual, expected)) {
            log.warn("[位图对账] 检测到位图与账本漂移，覆盖修复。车次：{}，车厢：{}", trainId, carriage.getCarriageNumber());
            stringRedisTemplate.execute((RedisCallback<Object>) connection ->
                    connection.stringCommands().set(bitMapKey.getBytes(StandardCharsets.UTF_8), expected));
        }
    }
}
