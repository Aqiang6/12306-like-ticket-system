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

package org.opengoofy.index12306.biz.orderservice.service.impl;

import cn.crane4j.annotation.AutoOperate;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.collection.ListUtil;
import cn.hutool.core.text.StrBuilder;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.opengoofy.index12306.biz.orderservice.common.enums.OrderCanalErrorCodeEnum;
import org.opengoofy.index12306.biz.orderservice.common.enums.OrderItemStatusEnum;
import org.opengoofy.index12306.biz.orderservice.common.enums.OrderStatusEnum;
import org.opengoofy.index12306.biz.orderservice.dao.entity.OrderDO;
import org.opengoofy.index12306.biz.orderservice.dao.entity.OrderItemDO;
import org.opengoofy.index12306.biz.orderservice.dao.entity.OrderItemPassengerDO;
import org.opengoofy.index12306.biz.orderservice.dao.mapper.OrderItemMapper;
import org.opengoofy.index12306.biz.orderservice.dao.mapper.OrderItemPassengerMapper;
import org.opengoofy.index12306.biz.orderservice.dao.mapper.OrderMapper;
import org.opengoofy.index12306.biz.orderservice.dto.domain.OrderStatusReversalDTO;
import org.opengoofy.index12306.biz.orderservice.dto.req.CancelTicketOrderReqDTO;
import org.opengoofy.index12306.biz.orderservice.dto.req.TicketOrderActivePurchaseQueryReqDTO;
import org.opengoofy.index12306.biz.orderservice.dto.req.TicketOrderCreateReqDTO;
import org.opengoofy.index12306.biz.orderservice.dto.req.TicketOrderItemCreateReqDTO;
import org.opengoofy.index12306.biz.orderservice.dto.req.TicketOrderPageQueryReqDTO;
import org.opengoofy.index12306.biz.orderservice.dto.req.TicketOrderSelfPageQueryReqDTO;
import org.opengoofy.index12306.biz.orderservice.dto.resp.TicketOrderDetailRespDTO;
import org.opengoofy.index12306.biz.orderservice.dto.resp.TicketOrderDetailSelfRespDTO;
import org.opengoofy.index12306.biz.orderservice.dto.resp.TicketOrderPassengerDetailRespDTO;
import org.opengoofy.index12306.biz.orderservice.mq.event.DelayCloseOrderEvent;
import org.opengoofy.index12306.biz.orderservice.mq.event.PayResultCallbackOrderEvent;
import org.opengoofy.index12306.biz.orderservice.mq.produce.DelayCloseOrderSendProduce;
import org.opengoofy.index12306.biz.orderservice.remote.UserRemoteService;
import org.opengoofy.index12306.biz.orderservice.remote.dto.UserQueryActualRespDTO;
import org.opengoofy.index12306.biz.orderservice.service.OrderItemService;
import org.opengoofy.index12306.biz.orderservice.service.OrderPassengerRelationService;
import org.opengoofy.index12306.biz.orderservice.service.OrderService;
import cn.hutool.core.util.StrUtil;
import org.opengoofy.index12306.biz.orderservice.service.orderid.OrderIdGeneratorManager;
import org.opengoofy.index12306.framework.starter.common.toolkit.BeanUtil;
import org.opengoofy.index12306.framework.starter.common.toolkit.EnvironmentUtil;
import org.opengoofy.index12306.framework.starter.convention.exception.ClientException;
import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;
import org.opengoofy.index12306.framework.starter.convention.page.PageResponse;
import org.opengoofy.index12306.framework.starter.convention.result.Result;
import org.opengoofy.index12306.framework.starter.database.toolkit.PageUtil;
import org.opengoofy.index12306.frameworks.starter.user.core.UserContext;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 订单服务接口层实现
 * 公众号：马丁玩编程，回复：加群，添加马哥微信（备注：12306）获取项目资料
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final OrderItemPassengerMapper orderItemPassengerMapper;
    private final OrderItemService orderItemService;
    private final OrderPassengerRelationService orderPassengerRelationService;
    private final RedissonClient redissonClient;
    private final DelayCloseOrderSendProduce delayCloseOrderSendProduce;
    private final UserRemoteService userRemoteService;
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 购票幂等令牌 Redis Key 前缀（Value = 令牌对应订单号）
     */
    private static final String ORDER_PURCHASE_TOKEN_PREFIX = "index12306-order:purchase_token:";

    /**
     * 购票幂等令牌并发互斥锁前缀
     */
    private static final String ORDER_PURCHASE_TOKEN_LOCK_PREFIX = "index12306-order:purchase_token_lock:";

    /**
     * 购票幂等令牌有效期：覆盖延迟关单等订单生命周期即可
     */
    private static final long PURCHASE_TOKEN_EXPIRE_DAYS = 7L;

    @Override
    public TicketOrderDetailRespDTO queryTicketOrderByOrderSn(String orderSn) {
        LambdaQueryWrapper<OrderDO> queryWrapper = Wrappers.lambdaQuery(OrderDO.class)
                .eq(OrderDO::getOrderSn, orderSn);
        OrderDO orderDO = orderMapper.selectOne(queryWrapper);
        TicketOrderDetailRespDTO result = BeanUtil.convert(orderDO, TicketOrderDetailRespDTO.class);
        LambdaQueryWrapper<OrderItemDO> orderItemQueryWrapper = Wrappers.lambdaQuery(OrderItemDO.class)
                .eq(OrderItemDO::getOrderSn, orderSn);
        List<OrderItemDO> orderItemDOList = orderItemMapper.selectList(orderItemQueryWrapper);
        result.setPassengerDetails(BeanUtil.convert(orderItemDOList, TicketOrderPassengerDetailRespDTO.class));
        return result;
    }

    @Override
    public List<String> listActivePurchasedIdCards(TicketOrderActivePurchaseQueryReqDTO requestParam) {
        List<String> idCards = requestParam.getIdCards();
        if (CollUtil.isEmpty(idCards)) {
            return Collections.emptyList();
        }
        // 乘车人关联表按身份证分片路由，汇总身份证关联的全部订单号
        List<OrderItemPassengerDO> orderItemPassengerList = orderItemPassengerMapper.selectList(
                Wrappers.lambdaQuery(OrderItemPassengerDO.class).in(OrderItemPassengerDO::getIdCard, idCards));
        if (CollUtil.isEmpty(orderItemPassengerList)) {
            return Collections.emptyList();
        }
        List<String> relatedOrderSnList = orderItemPassengerList.stream()
                .map(OrderItemPassengerDO::getOrderSn)
                .distinct()
                .toList();
        // 订单明细按订单号分片路由，过滤当前车次 + 有效持票状态（待支付/已支付/已进站）
        List<OrderItemDO> orderItemList = orderItemMapper.selectList(Wrappers.lambdaQuery(OrderItemDO.class)
                .in(OrderItemDO::getOrderSn, relatedOrderSnList)
                .eq(OrderItemDO::getTrainId, Long.parseLong(requestParam.getTrainId()))
                .in(OrderItemDO::getStatus,
                        OrderItemStatusEnum.PENDING_PAYMENT.getStatus(),
                        OrderItemStatusEnum.ALREADY_PAID.getStatus(),
                        OrderItemStatusEnum.ALREADY_PULL_IN.getStatus()));
        Set<String> requestedIdCards = new HashSet<>(idCards);
        return orderItemList.stream()
                .map(OrderItemDO::getIdCard)
                .filter(requestedIdCards::contains)
                .distinct()
                .toList();
    }

    @AutoOperate(type = TicketOrderDetailRespDTO.class, on = "data.records")
    @Override
    public PageResponse<TicketOrderDetailRespDTO> pageTicketOrder(TicketOrderPageQueryReqDTO requestParam) {
        LambdaQueryWrapper<OrderDO> queryWrapper = Wrappers.lambdaQuery(OrderDO.class)
                .eq(OrderDO::getUserId, requestParam.getUserId())
                .orderByDesc(OrderDO::getOrderTime);
        List<Integer> orderStatusList = buildOrderStatusList(requestParam);
        if (!orderStatusList.isEmpty()) {
            queryWrapper.in(OrderDO::getStatus, orderStatusList);
        }
        IPage<OrderDO> orderPage = orderMapper.selectPage(PageUtil.convert(requestParam), queryWrapper);
        return PageUtil.convert(orderPage, each -> {
            TicketOrderDetailRespDTO result = BeanUtil.convert(each, TicketOrderDetailRespDTO.class);
            LambdaQueryWrapper<OrderItemDO> orderItemQueryWrapper = Wrappers.lambdaQuery(OrderItemDO.class)
                    .eq(OrderItemDO::getOrderSn, each.getOrderSn());
            List<OrderItemDO> orderItemDOList = orderItemMapper.selectList(orderItemQueryWrapper);
            result.setPassengerDetails(BeanUtil.convert(orderItemDOList, TicketOrderPassengerDetailRespDTO.class));
            return result;
        });
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public String createTicketOrder(TicketOrderCreateReqDTO requestParam) {
        String purchaseToken = requestParam.getPurchaseToken();
        RLock tokenLock = null;
        if (StrUtil.isNotBlank(purchaseToken)) {
            // 购票令牌幂等：同一令牌仅允许创建一单，覆盖"同步建单与宕机补偿并发、补偿消息重复投递、
            // 上次尝试回滚后重试"等场景；互斥锁跨事务持锁（提交后释放），防止并发互查窗口内重复建单
            tokenLock = redissonClient.getLock(ORDER_PURCHASE_TOKEN_LOCK_PREFIX + purchaseToken);
            tokenLock.lock(10, TimeUnit.SECONDS);
            registerUnlockAfterTxCompletion(tokenLock);
            String existedOrderSn = resolveClaimedOrderSn(purchaseToken);
            if (StrUtil.isNotBlank(existedOrderSn)) {
                log.warn("[订单幂等] 购票令牌已创建过订单，直接返回既有订单号，令牌：{}，订单号：{}", purchaseToken, existedOrderSn);
                return existedOrderSn;
            }
        }
        // 通过基因法将用户 ID 融入到订单号
        String orderSn = OrderIdGeneratorManager.generateId(requestParam.getUserId());
        if (StrUtil.isNotBlank(purchaseToken)) {
            stringRedisTemplate.opsForValue().set(ORDER_PURCHASE_TOKEN_PREFIX + purchaseToken, orderSn,
                    PURCHASE_TOKEN_EXPIRE_DAYS, TimeUnit.DAYS);
        }
        OrderDO orderDO = OrderDO.builder().orderSn(orderSn)
                .orderTime(requestParam.getOrderTime())
                .departure(requestParam.getDeparture())
                .departureTime(requestParam.getDepartureTime())
                .ridingDate(requestParam.getRidingDate())
                .arrivalTime(requestParam.getArrivalTime())
                .trainNumber(requestParam.getTrainNumber())
                .arrival(requestParam.getArrival())
                .trainId(requestParam.getTrainId())
                .source(requestParam.getSource())
                .status(OrderStatusEnum.PENDING_PAYMENT.getStatus())
                .username(requestParam.getUsername())
                .userId(String.valueOf(requestParam.getUserId()))
                .build();
        orderMapper.insert(orderDO);
        List<TicketOrderItemCreateReqDTO> ticketOrderItems = requestParam.getTicketOrderItems();
        List<OrderItemDO> orderItemDOList = new ArrayList<>();
        List<OrderItemPassengerDO> orderPassengerRelationDOList = new ArrayList<>();
        ticketOrderItems.forEach(each -> {
            OrderItemDO orderItemDO = OrderItemDO.builder()
                    .trainId(requestParam.getTrainId())
                    .seatNumber(each.getSeatNumber())
                    .carriageNumber(each.getCarriageNumber())
                    .realName(each.getRealName())
                    .orderSn(orderSn)
                    .phone(each.getPhone())
                    .seatType(each.getSeatType())
                    .username(requestParam.getUsername()).amount(each.getAmount()).carriageNumber(each.getCarriageNumber())
                    .idCard(each.getIdCard())
                    .ticketType(each.getTicketType())
                    .idType(each.getIdType())
                    .userId(String.valueOf(requestParam.getUserId()))
                    .status(0)
                    .build();
            orderItemDOList.add(orderItemDO);
            OrderItemPassengerDO orderPassengerRelationDO = OrderItemPassengerDO.builder()
                    .idType(each.getIdType())
                    .idCard(each.getIdCard())
                    .orderSn(orderSn)
                    .build();
            orderPassengerRelationDOList.add(orderPassengerRelationDO);
        });
        orderItemService.saveBatch(orderItemDOList);
        orderPassengerRelationService.saveBatch(orderPassengerRelationDOList);
        try {
            // 发送 RocketMQ 延时消息，指定时间后取消订单
            DelayCloseOrderEvent delayCloseOrderEvent = DelayCloseOrderEvent.builder()
                    .trainId(String.valueOf(requestParam.getTrainId()))
                    .departure(requestParam.getDeparture())
                    .arrival(requestParam.getArrival())
                    .orderSn(orderSn)
                    .trainPurchaseTicketResults(requestParam.getTicketOrderItems())
                    .build();
            // 创建订单并支付后延时关闭订单消息怎么办？详情查看：https://nageoffer.com/12306/question
            SendResult sendResult = delayCloseOrderSendProduce.sendMessage(delayCloseOrderEvent);
            if (!Objects.equals(sendResult.getSendStatus(), SendStatus.SEND_OK)) {
                throw new ServiceException("投递延迟关闭订单消息队列失败");
            }
        } catch (Throwable ex) {
            log.error("延迟关闭订单消息队列发送错误，请求参数：{}", JSON.toJSONString(requestParam), ex);
            if (EnvironmentUtil.isDevEnvironment()) {
                log.warn("[开发环境] RocketMQ 未启动或消息发送失败，跳过延迟关闭订单消息，订单号：{}", orderSn);
            } else {
                throw ex;
            }
        }
        return orderSn;
    }

    /**
     * 解析令牌的既有认领：已认领且订单真实落库则返回订单号（幂等命中）；
     * 已认领但订单不存在，说明上次尝试已回滚或宕机未落库，清理陈旧认领后允许重新创建
     */
    private String resolveClaimedOrderSn(String purchaseToken) {
        String claimedOrderSn = stringRedisTemplate.opsForValue().get(ORDER_PURCHASE_TOKEN_PREFIX + purchaseToken);
        if (StrUtil.isBlank(claimedOrderSn)) {
            return null;
        }
        if (orderExists(claimedOrderSn)) {
            return claimedOrderSn;
        }
        stringRedisTemplate.delete(ORDER_PURCHASE_TOKEN_PREFIX + purchaseToken);
        return null;
    }

    private boolean orderExists(String orderSn) {
        Long count = orderMapper.selectCount(Wrappers.lambdaQuery(OrderDO.class).eq(OrderDO::getOrderSn, orderSn));
        return count != null && count > 0;
    }

    /**
     * 事务提交/回滚后释放购票令牌互斥锁：锁必须覆盖到订单落库提交，
     * 若在事务内直接 unlock，并发请求会在"已认领但未提交"的窗口内误判订单不存在而重复建单
     */
    private void registerUnlockAfterTxCompletion(RLock lock) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    try {
                        lock.unlock();
                    } catch (Throwable ignored) {
                        // 租期（10s）兜底自动释放，此处忽略解锁异常
                    }
                }
            });
        }
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public boolean closeTickOrder(CancelTicketOrderReqDTO requestParam) {
        String orderSn = requestParam.getOrderSn();
        LambdaQueryWrapper<OrderDO> queryWrapper = Wrappers.lambdaQuery(OrderDO.class)
                .eq(OrderDO::getOrderSn, orderSn)
                .select(OrderDO::getStatus);
        OrderDO orderDO = orderMapper.selectOne(queryWrapper);
        if (Objects.isNull(orderDO) || orderDO.getStatus() != OrderStatusEnum.PENDING_PAYMENT.getStatus()) {
            return false;
        }
        // 原则上订单关闭和订单取消这两个方法可以复用，为了区分未来考虑到的场景，这里对方法进行拆分但复用逻辑
        return cancelTickOrder(requestParam);
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public boolean cancelTickOrder(CancelTicketOrderReqDTO requestParam) {
        String orderSn = requestParam.getOrderSn();
        LambdaQueryWrapper<OrderDO> queryWrapper = Wrappers.lambdaQuery(OrderDO.class)
                .eq(OrderDO::getOrderSn, orderSn);
        OrderDO orderDO = orderMapper.selectOne(queryWrapper);
        if (orderDO == null) {
            throw new ServiceException(OrderCanalErrorCodeEnum.ORDER_CANAL_UNKNOWN_ERROR);
        } else if (orderDO.getStatus() != OrderStatusEnum.PENDING_PAYMENT.getStatus()) {
            throw new ServiceException(OrderCanalErrorCodeEnum.ORDER_CANAL_STATUS_ERROR);
        }
        RLock lock = redissonClient.getLock(StrBuilder.create("order:canal:order_sn_").append(orderSn).toString());
        if (!lock.tryLock()) {
            throw new ClientException(OrderCanalErrorCodeEnum.ORDER_CANAL_REPETITION_ERROR);
        }
        try {
            OrderDO updateOrderDO = new OrderDO();
            updateOrderDO.setStatus(OrderStatusEnum.CLOSED.getStatus());
            LambdaUpdateWrapper<OrderDO> updateWrapper = Wrappers.lambdaUpdate(OrderDO.class)
                    .eq(OrderDO::getOrderSn, orderSn);
            int updateResult = orderMapper.update(updateOrderDO, updateWrapper);
            if (updateResult <= 0) {
                throw new ServiceException(OrderCanalErrorCodeEnum.ORDER_CANAL_ERROR);
            }
            OrderItemDO updateOrderItemDO = new OrderItemDO();
            updateOrderItemDO.setStatus(OrderItemStatusEnum.CLOSED.getStatus());
            LambdaUpdateWrapper<OrderItemDO> updateItemWrapper = Wrappers.lambdaUpdate(OrderItemDO.class)
                    .eq(OrderItemDO::getOrderSn, orderSn);
            int updateItemResult = orderItemMapper.update(updateOrderItemDO, updateItemWrapper);
            if (updateItemResult <= 0) {
                throw new ServiceException(OrderCanalErrorCodeEnum.ORDER_CANAL_ERROR);
            }
        } finally {
            lock.unlock();
        }
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void statusReversal(OrderStatusReversalDTO requestParam) {
        // 行锁覆盖消费者的整个事务，重复投递只确认已完成状态，不回退退款/完成状态。
        OrderDO order = orderMapper.selectOne(Wrappers.lambdaQuery(OrderDO.class)
                .eq(OrderDO::getOrderSn, requestParam.getOrderSn()).last("FOR UPDATE"));
        if (order == null) {
            throw new ServiceException(OrderCanalErrorCodeEnum.ORDER_CANAL_UNKNOWN_ERROR);
        }
        if (Objects.equals(requestParam.getOrderStatus(), OrderStatusEnum.ALREADY_PAID.getStatus())
                && List.of(OrderStatusEnum.ALREADY_PAID.getStatus(), OrderStatusEnum.PARTIAL_REFUND.getStatus(),
                        OrderStatusEnum.FULL_REFUND.getStatus(), OrderStatusEnum.COMPLETED.getStatus()).contains(order.getStatus())) {
            return;
        }
        if (!Objects.equals(order.getStatus(), OrderStatusEnum.PENDING_PAYMENT.getStatus())) {
            throw new ServiceException(OrderCanalErrorCodeEnum.ORDER_CANAL_STATUS_ERROR);
        }
        int updated = orderMapper.update(null, Wrappers.lambdaUpdate(OrderDO.class)
                .eq(OrderDO::getOrderSn, requestParam.getOrderSn())
                .eq(OrderDO::getStatus, OrderStatusEnum.PENDING_PAYMENT.getStatus())
                .set(OrderDO::getStatus, requestParam.getOrderStatus()));
        int itemsUpdated = orderItemMapper.update(null, Wrappers.lambdaUpdate(OrderItemDO.class)
                .eq(OrderItemDO::getOrderSn, requestParam.getOrderSn())
                .eq(OrderItemDO::getStatus, OrderItemStatusEnum.PENDING_PAYMENT.getStatus())
                .set(OrderItemDO::getStatus, requestParam.getOrderItemStatus()));
        if (updated <= 0 || itemsUpdated <= 0) {
            throw new ServiceException(OrderCanalErrorCodeEnum.ORDER_STATUS_REVERSAL_ERROR);
        }
    }

    @Override
    public void payCallbackOrder(PayResultCallbackOrderEvent requestParam) {
        OrderDO updateOrderDO = new OrderDO();
        updateOrderDO.setPayTime(requestParam.getGmtPayment());
        updateOrderDO.setPayType(requestParam.getChannel());
        LambdaUpdateWrapper<OrderDO> updateWrapper = Wrappers.lambdaUpdate(OrderDO.class)
                .eq(OrderDO::getOrderSn, requestParam.getOrderSn());
        int updateResult = orderMapper.update(updateOrderDO, updateWrapper);
        if (updateResult <= 0) {
            throw new ServiceException(OrderCanalErrorCodeEnum.ORDER_STATUS_REVERSAL_ERROR);
        }
    }

    @Override
    public PageResponse<TicketOrderDetailSelfRespDTO> pageSelfTicketOrder(TicketOrderSelfPageQueryReqDTO requestParam) {
        // 本人车票仅展示本人（按证件号）待出行（已支付）的车票，已改签/已取消/已退票的不再展示
        Result<UserQueryActualRespDTO> userActualResp = userRemoteService.queryActualUserByUsername(UserContext.getUsername());
        LambdaQueryWrapper<OrderItemDO> queryWrapper = Wrappers.lambdaQuery(OrderItemDO.class)
                .eq(OrderItemDO::getIdCard, userActualResp.getData().getIdCard())
                .eq(OrderItemDO::getStatus, OrderItemStatusEnum.ALREADY_PAID.getStatus())
                .orderByDesc(OrderItemDO::getCreateTime);
        IPage<OrderItemDO> orderItemPage = orderItemMapper.selectPage(PageUtil.convert(requestParam), queryWrapper);
        return PageUtil.convert(orderItemPage, each -> {
            LambdaQueryWrapper<OrderDO> orderQueryWrapper = Wrappers.lambdaQuery(OrderDO.class)
                    .eq(OrderDO::getOrderSn, each.getOrderSn());
            OrderDO orderDO = orderMapper.selectOne(orderQueryWrapper);
            TicketOrderDetailSelfRespDTO actualResult = BeanUtil.convert(orderDO, TicketOrderDetailSelfRespDTO.class);
            BeanUtil.convertIgnoreNullAndBlank(each, actualResult);
            return actualResult;
        });
    }

    private List<Integer> buildOrderStatusList(TicketOrderPageQueryReqDTO requestParam) {
        List<Integer> result = new ArrayList<>();
        switch (requestParam.getStatusType()) {
            case 0 -> result = ListUtil.of(
                    OrderStatusEnum.PENDING_PAYMENT.getStatus()
            );
            // 待出行：已支付、尚未乘车的订单
            case 1 -> result = ListUtil.of(
                    OrderStatusEnum.ALREADY_PAID.getStatus()
            );
            // 历史订单：不限制订单状态，返回全部车票订单
            case 2 -> result = new ArrayList<>();
        }
        return result;
    }
}
