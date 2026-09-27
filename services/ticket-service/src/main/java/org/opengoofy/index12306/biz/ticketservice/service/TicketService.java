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
import org.opengoofy.index12306.biz.ticketservice.dao.entity.TicketDO;
import org.opengoofy.index12306.biz.ticketservice.dto.req.CancelTicketOrderReqDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.req.ChangeTicketReqDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.req.PurchaseTicketReqDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.req.TicketPurchasePrepareDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.req.RefundTicketReqDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.req.TicketPageQueryReqDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.resp.ChangeTicketRespDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.resp.RefundTicketRespDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.resp.TicketPageQueryRespDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.resp.TicketPurchaseRespDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.PayInfoRespDTO;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 车票接口
 * 公众号：马丁玩编程，回复：加群，添加马哥微信（备注：12306）获取项目资料
 */
public interface TicketService extends IService<TicketDO> {

    /**
     * 根据条件分页查询车票
     *
     * @param requestParam 分页查询车票请求参数
     * @return 查询车票返回结果
     */
    TicketPageQueryRespDTO pageListTicketQueryV1(TicketPageQueryReqDTO requestParam);

    /**
     * 根据条件分页查询车票V2高性能版本
     *
     * @param requestParam 分页查询车票请求参数
     * @return 查询车票返回结果
     */
    TicketPageQueryRespDTO pageListTicketQueryV2(TicketPageQueryReqDTO requestParam);

    /**
     * 购买车票V2高性能版本
     *
     * @param requestParam 车票购买请求参数
     * @return 订单号
     */
    TicketPurchaseRespDTO purchaseTicketsV2(@RequestBody PurchaseTicketReqDTO requestParam);

    /**
     * 购票临界区：锁定座位、保存车票并扣减余票缓存（调用方需持有对应购票锁）
     * 乘车人信息补全、票价查询与订单创建移至 {@link TicketService#createTicketOrder} 锁外执行
     *
     * @param requestParam 车票购买请求参数
     * @return 购票临界区产物上下文
     */
    TicketPurchasePrepareDTO preparePurchaseTickets(PurchaseTicketReqDTO requestParam);

    /**
     * 创建订单（购票锁外执行）
     * 创建失败时补偿释放已锁座位、删除车票记录、作废补偿任务并回补余票缓存
     *
     * @param prepare 购票临界区产物上下文
     * @return 订单号及车票详情
     */
    TicketPurchaseRespDTO createTicketOrder(TicketPurchasePrepareDTO prepare);

    /**
     * 订单创建（无补偿版本）：失败直接抛出异常，不回滚账本，供补偿消费者重试场景复用；
     * 成功后确认发件箱任务（t_order_create_task）为已完成
     *
     * @param prepare 购票临界区产物上下文
     * @return 订单号及车票详情
     */
    TicketPurchaseRespDTO doCreateTicketOrder(TicketPurchasePrepareDTO prepare);

    /**
     * 购票事务回滚：删除车票账本记录、释放已锁座位并作废补偿任务；
     * 供同步链路补偿与补偿消费者重试超限兜底复用
     *
     * @param prepare 购票临界区产物上下文
     */
    void rollbackPurchase(TicketPurchasePrepareDTO prepare);

    /**
     * 支付单详情查询
     *
     * @param orderSn 订单号
     * @return 支付单详情
     */
    PayInfoRespDTO getPayInfo(String orderSn);

    /**
     * 取消车票订单
     *
     * @param requestParam 取消车票订单入参
     */
    void cancelTicketOrder(CancelTicketOrderReqDTO requestParam);

    /**
     * 公共退款接口
     *
     * @param requestParam 退款请求参数
     * @return 退款返回详情
     */
    RefundTicketRespDTO commonTicketRefund(RefundTicketReqDTO requestParam);

    /**
     * 改签/变更到站费用预览
     * 改签可变更乘车日期、车次、席位、席别及到站，开车前及开车后当日均可办理；
     * 变更到站仅支持开车前 48 小时以上办理，出发站不可变更
     *
     * @param requestParam 改签请求参数
     * @return 改签费用明细
     */
    ChangeTicketRespDTO previewChangeTicket(ChangeTicketReqDTO requestParam);

    /**
     * 车票改签（含变更到站）
     * 每张车票仅可办理一次改签；原票退款金额 = 原票金额 - 手续费，新票生成新订单待支付
     *
     * @param requestParam 改签请求参数
     * @return 新订单号及费用明细
     */
    ChangeTicketRespDTO changeTicket(ChangeTicketReqDTO requestParam);
}
