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

package org.opengoofy.index12306.biz.ticketservice.canal;

import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import org.opengoofy.index12306.biz.ticketservice.common.enums.CanalExecuteStrategyMarkEnum;
import org.opengoofy.index12306.biz.ticketservice.mq.event.CanalBinlogEvent;
import org.opengoofy.index12306.framework.starter.designpattern.strategy.AbstractExecuteStrategy;
import org.springframework.stereotype.Component;

/**
 * 列车余票缓存更新组件（已退役）
 *
 * <p>库存模型重构后，{@code t_seat} 退化为物理座位注册表（一座位一行、无状态列），
 * 不再存在 seat_status/start_station/end_station 状态变更 binlog；
 * 座位占用的热路径由 Redis 位图 Lua CAS 维护，持久事实由 t_ticket 售卖区间账本承载，
 * 位图与账本的一致性由对账任务周期修复，本处理器保留注册仅作空实现。
 *
 * 公众号：马丁玩编程，回复：加群，添加马哥微信（备注：12306）获取项目资料
 */
@Slf4j
@Component
public class TicketAvailabilityCacheUpdateHandler implements AbstractExecuteStrategy<CanalBinlogEvent, Void> {

    @Override
    public void execute(CanalBinlogEvent message) {
        log.debug("[余票缓存更新] t_seat 注册表无状态变更语义，忽略 binlog：{}", JSON.toJSONString(message));
    }

    @Override
    public String mark() {
        return CanalExecuteStrategyMarkEnum.T_SEAT.getActualTable();
    }
}
