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

package org.opengoofy.index12306.biz.ticketservice.service;

import com.baomidou.mybatisplus.extension.service.IService;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.OrderCreateTaskDO;
import org.opengoofy.index12306.biz.ticketservice.dto.req.TicketPurchasePrepareDTO;

import java.util.List;

/**
 * 订单创建补偿任务服务（事务性发件箱）
 * 公众号：马丁玩编程，回复：加群，添加马哥微信（备注：12306）获取项目资料
 */
public interface OrderCreateTaskService extends IService<OrderCreateTaskDO> {

    /**
     * 发件箱记录：与车票账本同一事务写入，实例宕机后由扫描任务补偿投递
     * 写入失败仅记录日志不阻断购票，避免补偿链路故障影响正常售卖
     *
     * @param prepare  购票临界区产物上下文（含购票幂等令牌）
     * @param username 用户名
     * @param userId   用户 ID
     */
    void record(TicketPurchasePrepareDTO prepare, String username, String userId);

    /**
     * 确认任务完成：订单创建成功后调用
     *
     * @param purchaseToken 购票幂等令牌
     */
    void confirmByToken(String purchaseToken);

    /**
     * 作废任务：同步链路补偿回滚后调用，防止补偿链路再为已回滚的购票建单
     *
     * @param purchaseToken 购票幂等令牌
     */
    void cancelByToken(String purchaseToken);

    /**
     * 核验车票账本是否全部存活且待支付（补偿消费前置校验）
     *
     * @param ticketIds 车票记录 ID 集合
     * @return 全部存活返回 true
     */
    boolean existsAliveTickets(List<Long> ticketIds);

    /**
     * 查询待补偿投递的任务
     *
     * @param limit 单次扫描上限
     * @return 待投递任务列表
     */
    List<OrderCreateTaskDO> listPendingTasks(int limit);

    /**
     * 标记任务已投递：累加投递次数并顺延下次投递时间
     *
     * @param task 补偿任务
     */
    void markSent(OrderCreateTaskDO task);

    /**
     * 累加并获取任务投递次数（补偿消费者用于判断重试上限）
     *
     * @param purchaseToken 购票幂等令牌
     * @return 累加后的投递次数
     */
    int incrementAndGetRetryCount(String purchaseToken);
}
