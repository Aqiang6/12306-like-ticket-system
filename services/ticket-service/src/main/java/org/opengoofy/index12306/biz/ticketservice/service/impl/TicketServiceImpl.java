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
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.collect.Lists;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opengoofy.index12306.biz.ticketservice.common.enums.ChangeTicketTypeEnum;
import org.opengoofy.index12306.biz.ticketservice.common.enums.RefundTypeEnum;
import org.opengoofy.index12306.biz.ticketservice.common.enums.SourceEnum;
import org.opengoofy.index12306.biz.ticketservice.common.enums.TicketChainMarkEnum;
import org.opengoofy.index12306.biz.ticketservice.common.enums.TicketStatusEnum;
import org.opengoofy.index12306.biz.ticketservice.common.enums.VehicleTypeEnum;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.StationDO;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.TicketDO;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.TrainDO;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.TrainStationPriceDO;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.TrainStationRelationDO;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.StationMapper;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.TicketMapper;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.TrainMapper;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.TrainStationPriceMapper;
import org.opengoofy.index12306.biz.ticketservice.remote.UserRemoteService;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.TrainStationRelationMapper;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.ChangeTicketPassengerDetailDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.PurchaseTicketPassengerDetailDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.RouteDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.SeatClassDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.TicketListDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.req.CancelTicketOrderReqDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.req.ChangeTicketReqDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.req.PurchaseTicketReqDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.req.TicketPurchasePrepareDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.req.RefundTicketReqDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.req.TicketOrderItemQueryReqDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.req.TicketPageQueryReqDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.resp.ChangeTicketRespDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.resp.RefundTicketRespDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.resp.TicketOrderDetailRespDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.resp.TicketPageQueryRespDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.PassengerRespDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.resp.TicketPurchaseRespDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.PayRemoteService;
import org.opengoofy.index12306.biz.ticketservice.remote.TicketOrderRemoteService;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.PayInfoRespDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.RefundReqDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.RefundRespDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderCreateRemoteReqDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderItemCreateRemoteReqDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderPassengerDetailRespDTO;
import org.opengoofy.index12306.biz.ticketservice.service.SeatService;
import org.opengoofy.index12306.biz.ticketservice.service.TicketService;
import org.opengoofy.index12306.biz.ticketservice.service.TrainStationService;
import org.opengoofy.index12306.biz.ticketservice.service.cache.SeatMarginCacheLoader;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.dto.TrainPurchaseTicketRespDTO;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.ratelimit.TicketPurchaseRateLimiter;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.ratelimit.TicketStockReserver;
import org.opengoofy.index12306.biz.ticketservice.service.cache.TicketStockDisplayRefresher;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.select.TrainSeatTypeSelector;
import org.opengoofy.index12306.biz.ticketservice.toolkit.ChangeTicketFeeCalculateUtil;
import org.opengoofy.index12306.biz.ticketservice.toolkit.DateUtil;
import org.opengoofy.index12306.biz.ticketservice.toolkit.TimeStringComparator;
import org.opengoofy.index12306.framework.starter.bases.ApplicationContextHolder;
import org.opengoofy.index12306.framework.starter.cache.DistributedCache;
import org.opengoofy.index12306.framework.starter.cache.toolkit.CacheUtil;
import org.opengoofy.index12306.framework.starter.common.toolkit.BeanUtil;
import org.opengoofy.index12306.framework.starter.convention.exception.ClientException;
import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;
import org.opengoofy.index12306.framework.starter.convention.exception.RemoteException;
import org.opengoofy.index12306.framework.starter.convention.result.Result;
import org.opengoofy.index12306.framework.starter.designpattern.chain.AbstractChainContext;
import org.opengoofy.index12306.framework.starter.idempotent.annotation.Idempotent;
import org.opengoofy.index12306.framework.starter.idempotent.enums.IdempotentSceneEnum;
import org.opengoofy.index12306.framework.starter.idempotent.enums.IdempotentTypeEnum;
import org.opengoofy.index12306.framework.starter.log.annotation.ILog;
import org.opengoofy.index12306.frameworks.starter.user.core.UserContext;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import static org.opengoofy.index12306.biz.ticketservice.common.constant.Index12306Constant.ADVANCE_TICKET_DAY;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.LOCK_CHANGE_TICKETS;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.LOCK_PURCHASE_TICKETS_V2;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.LOCK_REGION_TRAIN_STATION;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.LOCK_REGION_TRAIN_STATION_MAPPING;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.REGION_TRAIN_STATION;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.REGION_TRAIN_STATION_MAPPING;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.TRAIN_INFO;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.TRAIN_STATION_PRICE;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.TRAIN_STATION_REMAINING_TICKET;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.TRAIN_STATION_REMAINING_DISPLAY;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.TRAIN_STATION_RELATION_DETAIL;
import static org.opengoofy.index12306.biz.ticketservice.toolkit.DateUtil.convertDateToLocalTime;

/**
 * 车票接口实现
 * 公众号：马丁玩编程，回复：加群，添加马哥微信（备注：12306）获取项目资料
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TicketServiceImpl extends ServiceImpl<TicketMapper, TicketDO> implements TicketService, CommandLineRunner {

    private final TrainMapper trainMapper;
    private final TrainStationRelationMapper trainStationRelationMapper;
    private final TrainStationPriceMapper trainStationPriceMapper;
    private final DistributedCache distributedCache;
    private final TicketOrderRemoteService ticketOrderRemoteService;
    private final PayRemoteService payRemoteService;
    private final StationMapper stationMapper;
    private final SeatService seatService;
    private final TrainStationService trainStationService;
    private final TrainSeatTypeSelector trainSeatTypeSelector;
    private final UserRemoteService userRemoteService;
    private final SeatMarginCacheLoader seatMarginCacheLoader;
    private final AbstractChainContext<TicketPageQueryReqDTO> ticketPageQueryAbstractChainContext;
    private final AbstractChainContext<PurchaseTicketReqDTO> purchaseTicketAbstractChainContext;
    private final AbstractChainContext<RefundTicketReqDTO> refundReqDTOAbstractChainContext;
    private final RedissonClient redissonClient;
    private final ConfigurableEnvironment environment;
    private final TicketPurchaseRateLimiter ticketPurchaseRateLimiter;
    private final TicketStockReserver ticketStockReserver;
    private final TicketStockDisplayRefresher ticketStockDisplayRefresher;
    private TicketService ticketService;

    @Value("${ticket.availability.cache-update.type:}")
    private String ticketAvailabilityCacheUpdateType;
    @Value("${framework.cache.redis.prefix:}")
    private String cacheRedisPrefix;

    /**
     * 车票子订单已支付状态（{@code OrderItemStatusEnum.ALREADY_PAID}）
     */
    private static final int ORDER_ITEM_STATUS_ALREADY_PAID = 10;

    @Override
    public TicketPageQueryRespDTO pageListTicketQueryV1(TicketPageQueryReqDTO requestParam) {
        // 责任链模式 验证城市名称是否存在、不存在加载缓存以及出发日期不能小于当前日期等等
        ticketPageQueryAbstractChainContext.handler(TicketChainMarkEnum.TRAIN_QUERY_FILTER.name(), requestParam);
        StringRedisTemplate stringRedisTemplate = (StringRedisTemplate) distributedCache.getInstance();
        // 列车查询逻辑较为复杂，详细解析文章查看 https://nageoffer.com/12306/question
        // v1 版本存在严重的性能深渊问题，v2 版本完美的解决了该问题。通过 Jmeter 压测聚合报告得知，性能提升在 300% - 500%+
        List<Object> stationDetails = stringRedisTemplate.opsForHash()
                .multiGet(REGION_TRAIN_STATION_MAPPING, Lists.newArrayList(requestParam.getFromStation(), requestParam.getToStation()));
        long count = stationDetails.stream().filter(Objects::isNull).count();
        if (count > 0) {
            RLock lock = redissonClient.getLock(LOCK_REGION_TRAIN_STATION_MAPPING);
            lock.lock();
            try {
                stationDetails = stringRedisTemplate.opsForHash()
                        .multiGet(REGION_TRAIN_STATION_MAPPING, Lists.newArrayList(requestParam.getFromStation(), requestParam.getToStation()));
                count = stationDetails.stream().filter(Objects::isNull).count();
                if (count > 0) {
                    List<StationDO> stationDOList = stationMapper.selectList(Wrappers.emptyWrapper());
                    Map<String, String> regionTrainStationMap = new HashMap<>();
                    stationDOList.forEach(each -> regionTrainStationMap.put(each.getCode(), each.getRegionName()));
                    stringRedisTemplate.opsForHash().putAll(REGION_TRAIN_STATION_MAPPING, regionTrainStationMap);
                    stationDetails = new ArrayList<>();
                    stationDetails.add(regionTrainStationMap.get(requestParam.getFromStation()));
                    stationDetails.add(regionTrainStationMap.get(requestParam.getToStation()));
                }
            } finally {
                lock.unlock();
            }
        }
        List<TicketListDTO> seatResults = new ArrayList<>();
        String buildRegionTrainStationHashKey = String.format(REGION_TRAIN_STATION, stationDetails.get(0), stationDetails.get(1));
        Map<Object, Object> regionTrainStationAllMap = stringRedisTemplate.opsForHash().entries(buildRegionTrainStationHashKey);
        if (MapUtil.isEmpty(regionTrainStationAllMap)) {
            RLock lock = redissonClient.getLock(LOCK_REGION_TRAIN_STATION);
            lock.lock();
            try {
                regionTrainStationAllMap = stringRedisTemplate.opsForHash().entries(buildRegionTrainStationHashKey);
                if (MapUtil.isEmpty(regionTrainStationAllMap)) {
                    LambdaQueryWrapper<TrainStationRelationDO> queryWrapper = Wrappers.lambdaQuery(TrainStationRelationDO.class)
                            .eq(TrainStationRelationDO::getStartRegion, stationDetails.get(0))
                            .eq(TrainStationRelationDO::getEndRegion, stationDetails.get(1));
                    List<TrainStationRelationDO> trainStationRelationList = trainStationRelationMapper.selectList(queryWrapper);
                    for (TrainStationRelationDO each : trainStationRelationList) {
                        TrainDO trainDO = distributedCache.safeGet(
                                TRAIN_INFO + each.getTrainId(),
                                TrainDO.class,
                                () -> trainMapper.selectById(each.getTrainId()),
                                ADVANCE_TICKET_DAY,
                                TimeUnit.DAYS);
                        TicketListDTO result = new TicketListDTO();
                        result.setTrainId(String.valueOf(trainDO.getId()));
                        result.setTrainNumber(trainDO.getTrainNumber());
                        result.setDepartureTime(convertDateToLocalTime(each.getDepartureTime(), "HH:mm"));
                        result.setArrivalTime(convertDateToLocalTime(each.getArrivalTime(), "HH:mm"));
                        result.setDuration(DateUtil.calculateHourDifference(each.getDepartureTime(), each.getArrivalTime()));
                        result.setDeparture(each.getDeparture());
                        result.setArrival(each.getArrival());
                        result.setDepartureFlag(each.getDepartureFlag());
                        result.setArrivalFlag(each.getArrivalFlag());
                        result.setTrainType(trainDO.getTrainType());
                        result.setTrainBrand(trainDO.getTrainBrand());
                        if (StrUtil.isNotBlank(trainDO.getTrainTag())) {
                            result.setTrainTags(StrUtil.split(trainDO.getTrainTag(), ","));
                        }
                        long betweenDay = cn.hutool.core.date.DateUtil.betweenDay(each.getDepartureTime(), each.getArrivalTime(), false);
                        result.setDaysArrived((int) betweenDay);
                        result.setSaleStatus(new Date().after(trainDO.getSaleTime()) ? 0 : 1);
                        result.setSaleTime(convertDateToLocalTime(trainDO.getSaleTime(), "MM-dd HH:mm"));
                        seatResults.add(result);
                        regionTrainStationAllMap.put(CacheUtil.buildKey(String.valueOf(each.getTrainId()), each.getDeparture(), each.getArrival()), JSON.toJSONString(result));
                    }
                    stringRedisTemplate.opsForHash().putAll(buildRegionTrainStationHashKey, regionTrainStationAllMap);
                }
            } finally {
                lock.unlock();
            }
        }
        seatResults = CollUtil.isEmpty(seatResults)
                ? regionTrainStationAllMap.values().stream().map(each -> JSON.parseObject(each.toString(), TicketListDTO.class)).toList()
                : seatResults;
        seatResults = seatResults.stream().sorted(new TimeStringComparator()).toList();
        for (TicketListDTO each : seatResults) {
            String trainStationPriceStr = distributedCache.safeGet(
                    String.format(TRAIN_STATION_PRICE, each.getTrainId(), each.getDeparture(), each.getArrival()),
                    String.class,
                    () -> {
                        LambdaQueryWrapper<TrainStationPriceDO> trainStationPriceQueryWrapper = Wrappers.lambdaQuery(TrainStationPriceDO.class)
                                .eq(TrainStationPriceDO::getDeparture, each.getDeparture())
                                .eq(TrainStationPriceDO::getArrival, each.getArrival())
                                .eq(TrainStationPriceDO::getTrainId, each.getTrainId());
                        return JSON.toJSONString(trainStationPriceMapper.selectList(trainStationPriceQueryWrapper));
                    },
                    ADVANCE_TICKET_DAY,
                    TimeUnit.DAYS
            );
            List<TrainStationPriceDO> trainStationPriceDOList = JSON.parseArray(trainStationPriceStr, TrainStationPriceDO.class);
            List<SeatClassDTO> seatClassList = new ArrayList<>();
            trainStationPriceDOList.forEach(item -> {
                String seatType = String.valueOf(item.getSeatType());
                String keySuffix = StrUtil.join("_", each.getTrainId(), item.getDeparture(), item.getArrival());
                ticketStockDisplayRefresher.touch(String.valueOf(each.getTrainId()));
                // 首页/按钮读展示层（3s 周期刷新）；展示缓存未就绪时回退实时余票缓存
                Object quantityObj = stringRedisTemplate.opsForHash().get(TRAIN_STATION_REMAINING_DISPLAY + keySuffix, seatType);
                if (quantityObj == null) {
                    quantityObj = stringRedisTemplate.opsForHash().get(TRAIN_STATION_REMAINING_TICKET + keySuffix, seatType);
                }
                int quantity = Optional.ofNullable(quantityObj)
                        .map(Object::toString)
                        .map(Integer::parseInt)
                        .orElseGet(() -> {
                            Map<String, String> seatMarginMap = seatMarginCacheLoader.load(String.valueOf(each.getTrainId()), seatType, item.getDeparture(), item.getArrival());
                            return Optional.ofNullable(seatMarginMap.get(String.valueOf(item.getSeatType()))).map(Integer::parseInt).orElse(0);
                        });
                seatClassList.add(new SeatClassDTO(item.getSeatType(), quantity, new BigDecimal(item.getPrice()).divide(new BigDecimal("100"), 1, RoundingMode.HALF_UP), false));
            });
            each.setSeatClassList(seatClassList);
        }
        return TicketPageQueryRespDTO.builder()
                .trainList(seatResults)
                .departureStationList(buildDepartureStationList(seatResults))
                .arrivalStationList(buildArrivalStationList(seatResults))
                .trainBrandList(buildTrainBrandList(seatResults))
                .seatClassTypeList(buildSeatClassList(seatResults))
                .build();
    }

    @Override
    public TicketPageQueryRespDTO pageListTicketQueryV2(TicketPageQueryReqDTO requestParam) {
        // 责任链模式 验证城市名称是否存在、不存在加载缓存以及出发日期不能小于当前日期等等
        ticketPageQueryAbstractChainContext.handler(TicketChainMarkEnum.TRAIN_QUERY_FILTER.name(), requestParam);
        StringRedisTemplate stringRedisTemplate = (StringRedisTemplate) distributedCache.getInstance();
        // 列车查询逻辑较为复杂，详细解析文章查看 https://nageoffer.com/12306/question
        // v2 版本更符合企业级高并发真实场景解决方案，完美解决了 v1 版本性能深渊问题。通过 Jmeter 压测聚合报告得知，性能提升在 300% - 500%+
        // 其实还能有 v3 版本，性能估计在原基础上还能进一步提升一倍。不过 v3 版本太过于复杂，不易读且不易扩展，就不写具体的代码了。面试中 v2 版本已经够和面试官吹的了
        List<Object> stationDetails = stringRedisTemplate.opsForHash()
                .multiGet(REGION_TRAIN_STATION_MAPPING, Lists.newArrayList(requestParam.getFromStation(), requestParam.getToStation()));
        String buildRegionTrainStationHashKey = String.format(REGION_TRAIN_STATION, stationDetails.get(0), stationDetails.get(1));
        Map<Object, Object> regionTrainStationAllMap = stringRedisTemplate.opsForHash().entries(buildRegionTrainStationHashKey);
        List<TicketListDTO> seatResults = regionTrainStationAllMap.values().stream()
                .map(each -> JSON.parseObject(each.toString(), TicketListDTO.class))
                .sorted(new TimeStringComparator())
                .toList();
        List<String> trainStationPriceKeys = seatResults.stream()
                .map(each -> String.format(cacheRedisPrefix + TRAIN_STATION_PRICE, each.getTrainId(), each.getDeparture(), each.getArrival()))
                .toList();
        List<Object> trainStationPriceObjs = stringRedisTemplate.executePipelined((RedisCallback<String>) connection -> {
            trainStationPriceKeys.forEach(each -> connection.stringCommands().get(each.getBytes()));
            return null;
        });
        List<TrainStationPriceDO> trainStationPriceDOList = new ArrayList<>();
        List<String> trainStationRemainingKeyList = new ArrayList<>();
        for (Object each : trainStationPriceObjs) {
            List<TrainStationPriceDO> trainStationPriceList = JSON.parseArray(each.toString(), TrainStationPriceDO.class);
            trainStationPriceDOList.addAll(trainStationPriceList);
            for (TrainStationPriceDO item : trainStationPriceList) {
                String trainStationRemainingKey = cacheRedisPrefix + TRAIN_STATION_REMAINING_TICKET + StrUtil.join("_", item.getTrainId(), item.getDeparture(), item.getArrival());
                trainStationRemainingKeyList.add(trainStationRemainingKey);
            }
        }
        List<Object> trainStationRemainingObjs = stringRedisTemplate.executePipelined((RedisCallback<String>) connection -> {
            for (int i = 0; i < trainStationRemainingKeyList.size(); i++) {
                connection.hashCommands().hGet(trainStationRemainingKeyList.get(i).getBytes(), trainStationPriceDOList.get(i).getSeatType().toString().getBytes());
            }
            return null;
        });
        for (TicketListDTO each : seatResults) {
            List<Integer> seatTypesByCode = VehicleTypeEnum.findSeatTypesByCode(each.getTrainType());
            List<Object> remainingTicket = new ArrayList<>(trainStationRemainingObjs.subList(0, seatTypesByCode.size()));
            List<TrainStationPriceDO> trainStationPriceDOSub = new ArrayList<>(trainStationPriceDOList.subList(0, seatTypesByCode.size()));
            trainStationRemainingObjs.subList(0, seatTypesByCode.size()).clear();
            trainStationPriceDOList.subList(0, seatTypesByCode.size()).clear();
            List<SeatClassDTO> seatClassList = new ArrayList<>();
            for (int i = 0; i < trainStationPriceDOSub.size(); i++) {
                TrainStationPriceDO trainStationPriceDO = trainStationPriceDOSub.get(i);
                SeatClassDTO seatClassDTO = SeatClassDTO.builder()
                        .type(trainStationPriceDO.getSeatType())
                        .quantity(Integer.parseInt(remainingTicket.get(i).toString()))
                        .price(new BigDecimal(trainStationPriceDO.getPrice()).divide(new BigDecimal("100"), 1, RoundingMode.HALF_UP))
                        .candidate(false)
                        .build();
                seatClassList.add(seatClassDTO);
            }
            each.setSeatClassList(seatClassList);
        }
        return TicketPageQueryRespDTO.builder()
                .trainList(seatResults)
                .departureStationList(buildDepartureStationList(seatResults))
                .arrivalStationList(buildArrivalStationList(seatResults))
                .trainBrandList(buildTrainBrandList(seatResults))
                .seatClassTypeList(buildSeatClassList(seatResults))
                .build();
    }

    private final Cache<String, ReentrantLock> localLockMap = Caffeine.newBuilder()
            .expireAfterWrite(1, TimeUnit.DAYS)
            .build();

    @ILog
    @Idempotent(
            uniqueKeyPrefix = "index12306-ticket:lock_purchase-tickets:",
            key = "T(org.opengoofy.index12306.framework.starter.bases.ApplicationContextHolder).getBean('environment').getProperty('unique-name', '')"
                    + "+'_'+"
                    + "T(org.opengoofy.index12306.frameworks.starter.user.core.UserContext).getUsername()",
            message = "正在执行下单流程，请稍后...",
            scene = IdempotentSceneEnum.RESTAPI,
            type = IdempotentTypeEnum.SPEL
    )
    @Override
    public TicketPurchaseRespDTO purchaseTicketsV2(PurchaseTicketReqDTO requestParam) {
        // 责任链模式，验证 1：参数必填 2：参数正确性 3：乘客是否已买当前车次等...
        purchaseTicketAbstractChainContext.handler(TicketChainMarkEnum.TRAIN_PURCHASE_TICKET_FILTER.name(), requestParam);
        // 分层准入第一层——库存原子预占（与首页查询同源的余票缓存）：
        // 点击购买即原子扣减覆盖站段余票，任一站段不足直接返回已售完（自动触发阈值校准纠偏），
        // 无票请求不消耗令牌桶额度也不进入锁队列；预占成功才进入令牌桶排队
        Map<Integer, Integer> seatTypeNeed = requestParam.getPassengers().stream()
                .collect(Collectors.groupingBy(PurchaseTicketPassengerDetailDTO::getSeatType, Collectors.summingInt(e -> 1)));
        if (!ticketStockReserver.reserve(requestParam.getTrainId(), requestParam.getDeparture(), requestParam.getArrival(), seatTypeNeed)) {
            throw new ClientException("车票已售完，您可提交候补订单或选择其他车次");
        }
        // 分层准入第二层——令牌桶：容量随余票动态伸缩、按固定速率控制进入临界区的节奏，削峰填谷；
        // 超卖由锁内座位位图 + DB 校验兜底
        if (!ticketPurchaseRateLimiter.tryAcquire(requestParam.getTrainId(), requestParam.getDeparture(), requestParam.getArrival())) {
            throw new ServiceException("当前购票请求较多，请稍后重试");
        }
        List<ReentrantLock> localLockList = new ArrayList<>();
        List<RLock> distributedLockList = new ArrayList<>();
        Map<Integer, List<PurchaseTicketPassengerDetailDTO>> seatTypeMap = requestParam.getPassengers().stream()
                .collect(Collectors.groupingBy(PurchaseTicketPassengerDetailDTO::getSeatType));
        seatTypeMap.forEach((searType, count) -> {
            String lockKey = environment.resolvePlaceholders(String.format(LOCK_PURCHASE_TICKETS_V2, requestParam.getTrainId(), searType));
            ReentrantLock localLock = localLockMap.getIfPresent(lockKey);
            if (localLock == null) {
                synchronized (TicketService.class) {
                    if ((localLock = localLockMap.getIfPresent(lockKey)) == null) {
                        localLock = new ReentrantLock(true);
                        localLockMap.put(lockKey, localLock);
                    }
                }
            }
            localLockList.add(localLock);
            RLock distributedLock = redissonClient.getFairLock(lockKey);
            distributedLockList.add(distributedLock);
        });
        TicketPurchasePrepareDTO prepare;
        try {
            localLockList.forEach(ReentrantLock::lock);
            distributedLockList.forEach(RLock::lock);
            // 临界区仅保留：选座 + 锁座 + 车票落库（余票已在预占层扣减），订单创建移至锁外
            prepare = ticketService.preparePurchaseTickets(requestParam);
        } catch (Throwable ex) {
            // 预占库存的唯一回补点：临界区（选座/锁座/落票）任一失败，按预占口径整单回补一次
            log.error("购票临界区执行失败，回补预占库存，请求参数：{}", JSON.toJSONString(requestParam), ex);
            compensateRemainingTicket(requestParam.getTrainId(), requestParam.getDeparture(), requestParam.getArrival(), seatTypeNeed);
            throw ex;
        } finally {
            localLockList.forEach(localLock -> {
                try {
                    localLock.unlock();
                } catch (Throwable ignored) {
                }
            });
            distributedLockList.forEach(distributedLock -> {
                try {
                    distributedLock.unlock();
                } catch (Throwable ignored) {
                }
            });
        }
        return ticketService.createTicketOrder(prepare);
    }

    @Override
    @Transactional(rollbackFor = Throwable.class)
    public TicketPurchasePrepareDTO preparePurchaseTickets(PurchaseTicketReqDTO requestParam) {
        String trainId = requestParam.getTrainId();
        try {
            return doPreparePurchaseTickets(requestParam, trainId);
        } catch (Throwable ex) {
            // 预占库存的回补统一由 purchaseTicketsV2 的 catch 单点执行，此处仅记录日志，避免多重回补造成缓存膨胀
            log.error("购票临界区执行失败，请求参数：{}", JSON.toJSONString(requestParam), ex);
            throw ex;
        }
    }

    private TicketPurchasePrepareDTO doPreparePurchaseTickets(PurchaseTicketReqDTO requestParam, String trainId) {
        // 节假日高并发购票Redis能扛得住么？详情查看：https://nageoffer.com/12306/question
        TrainDO trainDO = distributedCache.safeGet(
                TRAIN_INFO + trainId,
                TrainDO.class,
                () -> trainMapper.selectById(trainId),
                ADVANCE_TICKET_DAY,
                TimeUnit.DAYS);
        // 临界区仅保留：选座 + 锁座 + 车票落库（余票已在预占层扣减）；乘车人/票价补全与订单创建移至锁外
        List<TrainPurchaseTicketRespDTO> trainPurchaseTicketResults = trainSeatTypeSelector.selectSeatsAndLock(trainDO.getTrainType(), requestParam);
        List<TicketDO> ticketDOList = trainPurchaseTicketResults.stream()
                .map(each -> TicketDO.builder()
                        .username(UserContext.getUsername())
                        .trainId(Long.parseLong(trainId))
                        .carriageNumber(each.getCarriageNumber())
                        .seatNumber(each.getSeatNumber())
                        .passengerId(each.getPassengerId())
                        .ticketStatus(TicketStatusEnum.UNPAID.getCode())
                        .build())
                .toList();
        saveBatch(ticketDOList);
        return TicketPurchasePrepareDTO.builder()
                .trainId(trainId)
                .departure(requestParam.getDeparture())
                .arrival(requestParam.getArrival())
                .trainNumber(trainDO.getTrainNumber())
                .ticketIds(ticketDOList.stream().map(TicketDO::getId).toList())
                .seatResults(trainPurchaseTicketResults)
                .build();
    }

    @Override
    public TicketPurchaseRespDTO createTicketOrder(TicketPurchasePrepareDTO prepare) {
        List<TrainPurchaseTicketRespDTO> trainPurchaseTicketResults = prepare.getSeatResults();
        List<TicketOrderDetailRespDTO> ticketOrderDetailResults = new ArrayList<>();
        Result<String> ticketOrderResult;
        try {
            // 乘车人信息补全（远程调用已移出购票临界区）
            List<String> passengerIds = trainPurchaseTicketResults.stream()
                    .map(TrainPurchaseTicketRespDTO::getPassengerId)
                    .collect(Collectors.toList());
            Result<List<PassengerRespDTO>> passengerRemoteResult;
            List<PassengerRespDTO> passengerRemoteResultList;
            try {
                passengerRemoteResult = userRemoteService.listPassengerQueryByIds(UserContext.getUsername(), passengerIds);
                if (!passengerRemoteResult.isSuccess() || CollUtil.isEmpty(passengerRemoteResultList = passengerRemoteResult.getData())) {
                    throw new RemoteException("用户服务远程调用查询乘车人相关信息错误");
                }
            } catch (Throwable ex) {
                if (!(ex instanceof RemoteException)) {
                    log.error("用户服务远程调用查询乘车人相关信息错误，当前用户：{}，请求参数：{}", UserContext.getUsername(), passengerIds, ex);
                }
                throw ex;
            }
            trainPurchaseTicketResults.forEach(each -> passengerRemoteResultList.stream()
                    .filter(item -> Objects.equals(item.getId(), each.getPassengerId()))
                    .findFirst()
                    .ifPresent(passenger -> {
                        each.setIdCard(passenger.getIdCard());
                        each.setPhone(passenger.getPhone());
                        each.setUserType(passenger.getDiscountType());
                        each.setIdType(passenger.getIdType());
                        each.setRealName(passenger.getRealName());
                    }));
            // 票价补全：复用首页票价缓存（同键同装载），进程内按坐席过滤，购票路径不落库
            List<Integer> seatTypes = trainPurchaseTicketResults.stream().map(TrainPurchaseTicketRespDTO::getSeatType).distinct().toList();
            String trainStationPriceStr = distributedCache.safeGet(
                    String.format(TRAIN_STATION_PRICE, prepare.getTrainId(), prepare.getDeparture(), prepare.getArrival()),
                    String.class,
                    () -> JSON.toJSONString(trainStationPriceMapper.selectList(Wrappers.lambdaQuery(TrainStationPriceDO.class)
                            .eq(TrainStationPriceDO::getTrainId, prepare.getTrainId())
                            .eq(TrainStationPriceDO::getDeparture, prepare.getDeparture())
                            .eq(TrainStationPriceDO::getArrival, prepare.getArrival()))),
                    ADVANCE_TICKET_DAY, TimeUnit.DAYS);
            Map<Integer, Integer> priceMap = JSON.parseArray(trainStationPriceStr, TrainStationPriceDO.class).stream()
                    .filter(each -> seatTypes.contains(each.getSeatType()))
                    .collect(Collectors.toMap(TrainStationPriceDO::getSeatType, TrainStationPriceDO::getPrice, (a, b) -> a));
            trainPurchaseTicketResults.forEach(each -> each.setAmount(priceMap.get(each.getSeatType())));
            List<TicketOrderItemCreateRemoteReqDTO> orderItemCreateRemoteReqDTOList = new ArrayList<>();
            trainPurchaseTicketResults.forEach(each -> {
                TicketOrderItemCreateRemoteReqDTO orderItemCreateRemoteReqDTO = TicketOrderItemCreateRemoteReqDTO.builder()
                        .amount(each.getAmount())
                        .carriageNumber(each.getCarriageNumber())
                        .seatNumber(each.getSeatNumber())
                        .idCard(each.getIdCard())
                        .idType(each.getIdType())
                        .phone(each.getPhone())
                        .seatType(each.getSeatType())
                        .ticketType(each.getUserType())
                        .realName(each.getRealName())
                        .build();
                TicketOrderDetailRespDTO ticketOrderDetailRespDTO = TicketOrderDetailRespDTO.builder()
                        .amount(each.getAmount())
                        .carriageNumber(each.getCarriageNumber())
                        .seatNumber(each.getSeatNumber())
                        .idCard(each.getIdCard())
                        .idType(each.getIdType())
                        .seatType(each.getSeatType())
                        .ticketType(each.getUserType())
                        .realName(each.getRealName())
                        .build();
                orderItemCreateRemoteReqDTOList.add(orderItemCreateRemoteReqDTO);
                ticketOrderDetailResults.add(ticketOrderDetailRespDTO);
            });
            // 车站关系（发到时刻）：静态数据走缓存，购票路径不落库
            TrainStationRelationDO trainStationRelationDO = distributedCache.safeGet(
                    TRAIN_STATION_RELATION_DETAIL + StrUtil.join("_", prepare.getTrainId(), prepare.getDeparture(), prepare.getArrival()),
                    TrainStationRelationDO.class,
                    () -> trainStationRelationMapper.selectOne(Wrappers.lambdaQuery(TrainStationRelationDO.class)
                            .eq(TrainStationRelationDO::getTrainId, prepare.getTrainId())
                            .eq(TrainStationRelationDO::getDeparture, prepare.getDeparture())
                            .eq(TrainStationRelationDO::getArrival, prepare.getArrival())),
                    ADVANCE_TICKET_DAY, TimeUnit.DAYS);
            TicketOrderCreateRemoteReqDTO orderCreateRemoteReqDTO = TicketOrderCreateRemoteReqDTO.builder()
                    .departure(prepare.getDeparture())
                    .arrival(prepare.getArrival())
                    .orderTime(new Date())
                    .source(SourceEnum.INTERNET.getCode())
                    .trainNumber(prepare.getTrainNumber())
                    .departureTime(trainStationRelationDO.getDepartureTime())
                    .arrivalTime(trainStationRelationDO.getArrivalTime())
                    .ridingDate(trainStationRelationDO.getDepartureTime())
                    .userId(UserContext.getUserId())
                    .username(UserContext.getUsername())
                    .trainId(Long.parseLong(prepare.getTrainId()))
                    .ticketOrderItems(orderItemCreateRemoteReqDTOList)
                    .build();
            ticketOrderResult = ticketOrderRemoteService.createTicketOrder(orderCreateRemoteReqDTO);
            if (!ticketOrderResult.isSuccess() || StrUtil.isBlank(ticketOrderResult.getData())) {
                log.error("订单服务调用失败，返回结果：{}", ticketOrderResult.getMessage());
                throw new ServiceException("订单服务调用失败");
            }
        } catch (Throwable ex) {
            log.error("订单创建失败，补偿释放已锁座位并回补余票缓存，请求参数：{}", JSON.toJSONString(prepare), ex);
            compensateLockFailure(prepare);
            throw ex;
        }
        return new TicketPurchaseRespDTO(ticketOrderResult.getData(), ticketOrderDetailResults);
    }

    private void compensateRemainingTicket(String trainId, String departure, String arrival, Map<Integer, Integer> seatTypeNeed) {
        try {
            StringRedisTemplate stringRedisTemplate = (StringRedisTemplate) distributedCache.getInstance();
            List<RouteDTO> routeDTOList = trainStationService.listTakeoutTrainStationRoute(trainId, departure, arrival);
            routeDTOList.forEach(item -> seatTypeNeed.forEach((seatType, count) -> stringRedisTemplate.opsForHash().increment(
                    TRAIN_STATION_REMAINING_TICKET + StrUtil.join("_", trainId, item.getStartStation(), item.getEndStation()),
                    String.valueOf(seatType), count)));
        } catch (Throwable ex) {
            log.error("[购票补偿] 回补余票缓存失败，车次：{}", trainId, ex);
        }
    }

    private void compensateLockFailure(TicketPurchasePrepareDTO prepare) {
        try {
            removeByIds(prepare.getTicketIds());
        } catch (Throwable ex) {
            log.error("[购票补偿] 删除车票记录失败，车票：{}", prepare.getTicketIds(), ex);
        }
        try {
            seatService.unlock(prepare.getTrainId(), prepare.getDeparture(), prepare.getArrival(), prepare.getSeatResults());
        } catch (Throwable ex) {
            log.error("[购票补偿] 释放座位失败，车次：{}", prepare.getTrainId(), ex);
        }
        try {
            StringRedisTemplate stringRedisTemplate = (StringRedisTemplate) distributedCache.getInstance();
            List<RouteDTO> routeDTOList = trainStationService.listTakeoutTrainStationRoute(prepare.getTrainId(), prepare.getDeparture(), prepare.getArrival());
            prepare.getSeatResults().stream()
                    .collect(Collectors.groupingBy(TrainPurchaseTicketRespDTO::getSeatType, Collectors.counting()))
                    .forEach((seatType, count) -> routeDTOList.forEach(item -> stringRedisTemplate.opsForHash().increment(
                            TRAIN_STATION_REMAINING_TICKET + StrUtil.join("_", prepare.getTrainId(), item.getStartStation(), item.getEndStation()),
                            String.valueOf(seatType), count)));
        } catch (Throwable ex) {
            log.error("[购票补偿] 回补余票缓存失败，车次：{}", prepare.getTrainId(), ex);
        }
    }

    @Override
    public PayInfoRespDTO getPayInfo(String orderSn) {
        return payRemoteService.getPayInfo(orderSn).getData();
    }

    @ILog
    @Override
    public void cancelTicketOrder(CancelTicketOrderReqDTO requestParam) {
        Result<Void> cancelOrderResult = ticketOrderRemoteService.cancelTicketOrder(requestParam);
        if (cancelOrderResult.isSuccess() && !StrUtil.equals(ticketAvailabilityCacheUpdateType, "binlog")) {
            Result<org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderDetailRespDTO> ticketOrderDetailResult = ticketOrderRemoteService.queryTicketOrderByOrderSn(requestParam.getOrderSn());
            org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderDetailRespDTO ticketOrderDetail = ticketOrderDetailResult.getData();
            String trainId = String.valueOf(ticketOrderDetail.getTrainId());
            String departure = ticketOrderDetail.getDeparture();
            String arrival = ticketOrderDetail.getArrival();
            List<TicketOrderPassengerDetailRespDTO> trainPurchaseTicketResults = ticketOrderDetail.getPassengerDetails();
            try {
                seatService.unlock(trainId, departure, arrival, BeanUtil.convert(trainPurchaseTicketResults, TrainPurchaseTicketRespDTO.class));
            } catch (Throwable ex) {
                log.error("[取消订单] 订单号：{} 回滚列车DB座位状态失败", requestParam.getOrderSn(), ex);
                throw ex;
            }
            try {
                StringRedisTemplate stringRedisTemplate = (StringRedisTemplate) distributedCache.getInstance();
                Map<Integer, List<TicketOrderPassengerDetailRespDTO>> seatTypeMap = trainPurchaseTicketResults.stream()
                        .collect(Collectors.groupingBy(TicketOrderPassengerDetailRespDTO::getSeatType));
                List<RouteDTO> routeDTOList = trainStationService.listTakeoutTrainStationRoute(trainId, departure, arrival);
                routeDTOList.forEach(each -> {
                    String keySuffix = StrUtil.join("_", trainId, each.getStartStation(), each.getEndStation());
                    seatTypeMap.forEach((seatType, ticketOrderPassengerDetailRespDTOList) -> {
                        stringRedisTemplate.opsForHash()
                                .increment(TRAIN_STATION_REMAINING_TICKET + keySuffix, String.valueOf(seatType), ticketOrderPassengerDetailRespDTOList.size());
                    });
                });
            } catch (Throwable ex) {
                log.error("[取消关闭订单] 订单号：{} 回滚列车Cache余票失败", requestParam.getOrderSn(), ex);
                throw ex;
            }
        }
    }

    @Override
    public RefundTicketRespDTO commonTicketRefund(RefundTicketReqDTO requestParam) {
        // 责任链模式，验证 1：参数必填
        refundReqDTOAbstractChainContext.handler(TicketChainMarkEnum.TRAIN_REFUND_TICKET_FILTER.name(), requestParam);
        Result<org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderDetailRespDTO> orderDetailRespDTOResult = ticketOrderRemoteService.queryTicketOrderByOrderSn(requestParam.getOrderSn());
        if (!orderDetailRespDTOResult.isSuccess() && Objects.isNull(orderDetailRespDTOResult.getData())) {
            throw new ServiceException("车票订单不存在");
        }
        org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderDetailRespDTO ticketOrderDetailRespDTO = orderDetailRespDTOResult.getData();
        List<TicketOrderPassengerDetailRespDTO> passengerDetails = ticketOrderDetailRespDTO.getPassengerDetails();
        if (CollectionUtil.isEmpty(passengerDetails)) {
            throw new ServiceException("车票子订单不存在");
        }
        RefundReqDTO refundReqDTO = new RefundReqDTO();
        if (RefundTypeEnum.PARTIAL_REFUND.getType().equals(requestParam.getType())) {
            TicketOrderItemQueryReqDTO ticketOrderItemQueryReqDTO = new TicketOrderItemQueryReqDTO();
            ticketOrderItemQueryReqDTO.setOrderSn(requestParam.getOrderSn());
            ticketOrderItemQueryReqDTO.setOrderItemRecordIds(requestParam.getSubOrderRecordIdReqList());
            Result<List<TicketOrderPassengerDetailRespDTO>> queryTicketItemOrderById = ticketOrderRemoteService.queryTicketItemOrderById(ticketOrderItemQueryReqDTO);
            List<TicketOrderPassengerDetailRespDTO> partialRefundPassengerDetails = passengerDetails.stream()
                    .filter(item -> queryTicketItemOrderById.getData().contains(item))
                    .collect(Collectors.toList());
            refundReqDTO.setRefundDetailReqDTOList(partialRefundPassengerDetails);
            // 全部子单都退款时按整单退票处理，订单状态流转为已退票
            if (partialRefundPassengerDetails.size() >= passengerDetails.size()) {
                refundReqDTO.setRefundTypeEnum(RefundTypeEnum.FULL_REFUND);
            } else {
                refundReqDTO.setRefundTypeEnum(RefundTypeEnum.PARTIAL_REFUND);
            }
        } else if (RefundTypeEnum.FULL_REFUND.getType().equals(requestParam.getType())) {
            refundReqDTO.setRefundTypeEnum(RefundTypeEnum.FULL_REFUND);
            refundReqDTO.setRefundDetailReqDTOList(passengerDetails);
        }
        if (CollectionUtil.isNotEmpty(refundReqDTO.getRefundDetailReqDTOList())) {
            // 部分退款时仅累计所选乘车人的退款金额
            Integer partialRefundAmount = refundReqDTO.getRefundDetailReqDTOList().stream()
                    .mapToInt(TicketOrderPassengerDetailRespDTO::getAmount)
                    .sum();
            refundReqDTO.setRefundAmount(partialRefundAmount);
        }
        refundReqDTO.setOrderSn(requestParam.getOrderSn());
        Result<RefundRespDTO> refundRespDTOResult = payRemoteService.commonRefund(refundReqDTO);
        if (!refundRespDTOResult.isSuccess() && Objects.isNull(refundRespDTOResult.getData())) {
            throw new ServiceException("车票订单退款失败");
        }
        return null; // 暂时返回空实体
    }

    @Override
    public ChangeTicketRespDTO previewChangeTicket(ChangeTicketReqDTO requestParam) {
        return executeChangeTicket(requestParam, true);
    }

    @ILog
    @Idempotent(
            uniqueKeyPrefix = "index12306-ticket:lock_change-tickets:",
            key = "T(org.opengoofy.index12306.framework.starter.bases.ApplicationContextHolder).getBean('environment').getProperty('unique-name', '')"
                    + "+'_'+"
                    + "T(org.opengoofy.index12306.frameworks.starter.user.core.UserContext).getUsername()"
                    + "+'_'+"
                    + "#requestParam.orderSn",
            message = "正在执行改签流程，请稍后...",
            scene = IdempotentSceneEnum.RESTAPI,
            type = IdempotentTypeEnum.SPEL
    )
    @Override
    public ChangeTicketRespDTO changeTicket(ChangeTicketReqDTO requestParam) {
        // 分布式锁防止同一订单并发改签
        String lockKey = environment.resolvePlaceholders(String.format(LOCK_CHANGE_TICKETS, requestParam.getOrderSn()));
        RLock lock = redissonClient.getLock(lockKey);
        lock.lock();
        try {
            return executeChangeTicket(requestParam, false);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 执行改签/变更到站
     * 改签与变更到站共用出票与结算链路，差异点：
     * 1. 变更到站仅可在开车前 48 小时以上办理，且必须变更到达站；改签开车前、开车后当日均可办理
     * 2. 改签按较低票价核收改签费；变更到站差额退款部分按退票费标准核收手续费
     * 结算遵循"多退少补"：原票按 原票金额-手续费 退款（子订单状态经退款回调流转为已改签），新票按全价生成新订单待支付
     */
    private ChangeTicketRespDTO executeChangeTicket(ChangeTicketReqDTO requestParam, boolean previewOnly) {
        ChangeTicketTypeEnum changeType = ChangeTicketTypeEnum.findNameByCode(requestParam.getChangeType());
        if (Objects.isNull(changeType)) {
            throw new ServiceException("改签业务类型不合法");
        }
        org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderDetailRespDTO orderDetail = queryTicketOrderDetail(requestParam.getOrderSn());
        Map<String, Integer> requestSeatTypeMap = requestParam.getPassengers().stream()
                .collect(Collectors.toMap(ChangeTicketPassengerDetailDTO::getOrderItemRecordId, ChangeTicketPassengerDetailDTO::getSeatType, (a, b) -> a));
        List<TicketOrderPassengerDetailRespDTO> selectedPassengerDetails = orderDetail.getPassengerDetails().stream()
                .filter(each -> requestSeatTypeMap.containsKey(each.getId()))
                .toList();
        if (selectedPassengerDetails.size() != requestSeatTypeMap.size()) {
            throw new ServiceException("待改签车票不存在，请核对后重试");
        }
        // 每张车票仅可办理一次改签：非已支付状态的子订单（已改签、已退票等）不可再次办理
        for (TicketOrderPassengerDetailRespDTO each : selectedPassengerDetails) {
            if (!Objects.equals(each.getStatus(), ORDER_ITEM_STATUS_ALREADY_PAID)) {
                throw new ServiceException("乘车人 " + each.getRealName() + " 的车票当前状态不支持改签，每张车票仅可办理一次改签");
            }
        }
        if (!StrUtil.equals(requestParam.getNewDeparture(), orderDetail.getDeparture())) {
            throw new ServiceException("改签不支持变更出发站，如需变更出发站请退票后重新购买");
        }
        LocalDateTime oldDepartureDateTime = buildDepartureDateTime(orderDetail.getRidingDate(), orderDetail.getDepartureTime());
        LocalDateTime now = LocalDateTime.now();
        if (ChangeTicketTypeEnum.CHANGE_ARRIVAL.equals(changeType)) {
            long minutesBefore = Duration.between(now, oldDepartureDateTime).toMinutes();
            if (minutesBefore < 48 * 60) {
                throw new ServiceException("变更到站需在开车前48小时以上办理，不足48小时请前往车站窗口办理退票改签业务");
            }
            if (StrUtil.equals(requestParam.getNewArrival(), orderDetail.getArrival())) {
                throw new ServiceException("变更到站需选择与原票不同的到达站");
            }
        } else if (!now.isBefore(oldDepartureDateTime) && !now.isBefore(buildRidingDateEnd(orderDetail.getRidingDate()))) {
            throw new ServiceException("开车后仅可在当日24点前办理改签");
        }
        TrainDO newTrainDO = queryChangeTrainInfo(requestParam.getNewTrainId());
        TrainStationRelationDO newRelation = queryChangeTrainStationRelation(requestParam.getNewTrainId(), requestParam.getNewDeparture(), requestParam.getNewArrival());
        if (!convertDateToLocalDateTime(newRelation.getDepartureTime()).isAfter(now)) {
            throw new ServiceException("所选车次已发车，请重新选择改签车次");
        }
        if (Objects.equals(orderDetail.getTrainId(), Long.parseLong(requestParam.getNewTrainId())) && StrUtil.equals(requestParam.getNewArrival(), orderDetail.getArrival())) {
            throw new ServiceException("请选择不同的车次或到达站办理改签");
        }
        // 费用计算：改签费按较低票价逐票核收；变更到站对差额退款部分按退票费标准逐票核收手续费
        Map<Integer, Integer> newSeatTypePriceMap = loadNewSeatTypePrices(requestParam);
        int oldAmountTotal = 0;
        int newAmountTotal = 0;
        int changeFeeTotal = 0;
        Map<String, Integer> itemFeeMap = new HashMap<>();
        for (TicketOrderPassengerDetailRespDTO each : selectedPassengerDetails) {
            Integer newSeatType = requestSeatTypeMap.get(each.getId());
            Integer newPrice = newSeatTypePriceMap.get(newSeatType);
            if (Objects.isNull(newPrice)) {
                throw new ServiceException("改签车次暂不支持所选席别，请重新选择");
            }
            int changeFee = ChangeTicketTypeEnum.CHANGE_ARRIVAL.equals(changeType)
                    ? ChangeTicketFeeCalculateUtil.calculateRefundFee(oldDepartureDateTime, Math.max(each.getAmount() - newPrice, 0))
                    : ChangeTicketFeeCalculateUtil.calculateChangeTicketFee(oldDepartureDateTime, orderDetail.getRidingDate(), newRelation.getDepartureTime(), each.getAmount(), newPrice);
            itemFeeMap.put(each.getId(), changeFee);
            oldAmountTotal += each.getAmount();
            newAmountTotal += newPrice;
            changeFeeTotal += changeFee;
        }
        String ruleDesc = ChangeTicketTypeEnum.CHANGE_ARRIVAL.equals(changeType)
                ? ChangeTicketFeeCalculateUtil.buildRefundFeeRuleDesc(oldDepartureDateTime)
                : ChangeTicketFeeCalculateUtil.buildChangeFeeRuleDesc(oldDepartureDateTime, orderDetail.getRidingDate(), newRelation.getDepartureTime(), changeFeeTotal);
        if (previewOnly) {
            return ChangeTicketRespDTO.builder()
                    .changeType(requestParam.getChangeType())
                    .oldAmount(oldAmountTotal)
                    .newAmount(newAmountTotal)
                    .changeFee(changeFeeTotal)
                    .refundAmount(oldAmountTotal - changeFeeTotal)
                    .ruleDesc(ruleDesc)
                    .build();
        }
        // 通过原票 t_ticket 记录回溯乘车人 ID，选座链路据此从用户服务补全实名信息
        Map<String, String> passengerIdMap = new HashMap<>();
        for (TicketOrderPassengerDetailRespDTO each : selectedPassengerDetails) {
            String passengerId = recoverPassengerId(orderDetail, each);
            if (StrUtil.isBlank(passengerId)) {
                throw new ServiceException("原票乘车人信息缺失，请前往车站窗口办理改签");
            }
            passengerIdMap.put(each.getId(), passengerId);
        }
        Map<String, Integer> ticketTypeMap = selectedPassengerDetails.stream()
                .collect(Collectors.toMap(each -> passengerIdMap.get(each.getId()), TicketOrderPassengerDetailRespDTO::getTicketType, (a, b) -> a));
        List<PurchaseTicketPassengerDetailDTO> newPassengers = new ArrayList<>();
        selectedPassengerDetails.forEach(each -> {
            PurchaseTicketPassengerDetailDTO newPassenger = new PurchaseTicketPassengerDetailDTO();
            newPassenger.setPassengerId(passengerIdMap.get(each.getId()));
            newPassenger.setSeatType(requestSeatTypeMap.get(each.getId()));
            newPassengers.add(newPassenger);
        });
        PurchaseTicketReqDTO newPurchaseReq = new PurchaseTicketReqDTO();
        newPurchaseReq.setTrainId(requestParam.getNewTrainId());
        newPurchaseReq.setDeparture(requestParam.getNewDeparture());
        newPurchaseReq.setArrival(requestParam.getNewArrival());
        newPurchaseReq.setPassengers(newPassengers);
        newPurchaseReq.setChooseSeats(requestParam.getChooseSeats());
        // 标记改签来源订单：复用购票责任链时跳过重复购买校验（改签本身即旧票置换）
        newPurchaseReq.setRescheduleSourceOrderSn(requestParam.getOrderSn());
        // 改签的选座/释放与购票竞争同一座位，按 新车次+席别、原车次+席别 获取购票公平锁串行化；
        // 多把锁按 Key 排序加锁，避免两次改签交叉持锁死锁
        List<RLock> purchaseLocks = new ArrayList<>();
        for (String lockKey : buildPurchaseLockKeys(requestParam, orderDetail, selectedPassengerDetails, requestSeatTypeMap)) {
            RLock purchaseLock = redissonClient.getFairLock(lockKey);
            purchaseLock.lock();
            purchaseLocks.add(purchaseLock);
        }
        try {
            // 改签复用购票责任链：基于余票缓存只读预判新车次余量，再经限流令牌桶控制准入
            purchaseTicketAbstractChainContext.handler(TicketChainMarkEnum.TRAIN_PURCHASE_TICKET_FILTER.name(), newPurchaseReq);
            if (!ticketPurchaseRateLimiter.tryAcquire(newPurchaseReq.getTrainId(), newPurchaseReq.getDeparture(), newPurchaseReq.getArrival())) {
                throw new ServiceException("当前改签请求较多，请稍后重试");
            }
            Map<Integer, Integer> changeSeatTypeNeed = newPurchaseReq.getPassengers().stream()
                    .collect(Collectors.groupingBy(PurchaseTicketPassengerDetailDTO::getSeatType, Collectors.summingInt(e -> 1)));
            if (!ticketStockReserver.reserve(newPurchaseReq.getTrainId(), newPurchaseReq.getDeparture(), newPurchaseReq.getArrival(), changeSeatTypeNeed)) {
                throw new ServiceException("车票已售完，您可提交候补订单或选择其他车次");
            }
            int refundAmount = oldAmountTotal - changeFeeTotal;
            String newOrderSn;
            List<TrainPurchaseTicketRespDTO> newSeatResults = null;
            try {
                // 复用原项目选座组件（含商务座位图校验等席位分配策略），选座成功后锁定新座位并扣减沿途余票缓存
                newSeatResults = trainSeatTypeSelector.select(newTrainDO.getTrainType(), newPurchaseReq);
                List<TicketDO> newTicketDOList = newSeatResults.stream()
                        .map(each -> TicketDO.builder()
                                .username(UserContext.getUsername())
                                .trainId(Long.parseLong(requestParam.getNewTrainId()))
                                .carriageNumber(each.getCarriageNumber())
                                .seatNumber(each.getSeatNumber())
                                .passengerId(each.getPassengerId())
                                .ticketStatus(TicketStatusEnum.UNPAID.getCode())
                                .build())
                        .toList();
                saveBatch(newTicketDOList);
                newOrderSn = createChangeTicketOrder(requestParam, newRelation, newTrainDO, newSeatResults, ticketTypeMap);
                // 原票按 原票金额-手续费 退款，退款回调将原票子订单流转为已改签
                refundChangeTickets(requestParam.getOrderSn(), selectedPassengerDetails, itemFeeMap, refundAmount);
                markOldTicketsChanged(orderDetail, selectedPassengerDetails);
            } catch (Throwable ex) {
                if (CollUtil.isNotEmpty(newSeatResults)) {
                    compensateNewSeatResources(newPurchaseReq, newSeatResults);
                } else {
                    // 预占已扣减但尚未产生座位分配，按预占口径回补，避免改签失败造成缓存泄漏
                    compensateRemainingTicket(newPurchaseReq.getTrainId(), newPurchaseReq.getDeparture(), newPurchaseReq.getArrival(),
                            newPurchaseReq.getPassengers().stream()
                                    .collect(Collectors.groupingBy(PurchaseTicketPassengerDetailDTO::getSeatType, Collectors.summingInt(e -> 1))));
                }
                throw ex;
            }
            // 释放原票座位、清理座位占用位图、回滚余票缓存与令牌桶，尽力而为不阻塞改签结果
            releaseOldTicketResources(orderDetail, selectedPassengerDetails);
            return ChangeTicketRespDTO.builder()
                    .newOrderSn(newOrderSn)
                    .changeType(requestParam.getChangeType())
                    .oldAmount(oldAmountTotal)
                    .newAmount(newAmountTotal)
                    .changeFee(changeFeeTotal)
                    .refundAmount(refundAmount)
                    .ruleDesc(ruleDesc)
                    .build();
        } finally {
            for (int i = purchaseLocks.size() - 1; i >= 0; i--) {
                try {
                    purchaseLocks.get(i).unlock();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private List<String> buildPurchaseLockKeys(ChangeTicketReqDTO requestParam, org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderDetailRespDTO orderDetail, List<TicketOrderPassengerDetailRespDTO> selectedPassengerDetails, Map<String, Integer> requestSeatTypeMap) {
        Set<String> lockKeys = new HashSet<>();
        requestSeatTypeMap.values().stream().distinct().forEach(each ->
                lockKeys.add(environment.resolvePlaceholders(String.format(LOCK_PURCHASE_TICKETS_V2, requestParam.getNewTrainId(), each))));
        selectedPassengerDetails.stream().map(TicketOrderPassengerDetailRespDTO::getSeatType).distinct().forEach(each ->
                lockKeys.add(environment.resolvePlaceholders(String.format(LOCK_PURCHASE_TICKETS_V2, String.valueOf(orderDetail.getTrainId()), each))));
        List<String> result = new ArrayList<>(lockKeys);
        Collections.sort(result);
        return result;
    }

    private org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderDetailRespDTO queryTicketOrderDetail(String orderSn) {
        Result<org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderDetailRespDTO> orderDetailResult = ticketOrderRemoteService.queryTicketOrderByOrderSn(orderSn);
        if (!orderDetailResult.isSuccess() || Objects.isNull(orderDetailResult.getData())) {
            throw new ServiceException("车票订单不存在");
        }
        org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderDetailRespDTO orderDetail = orderDetailResult.getData();
        if (CollectionUtil.isEmpty(orderDetail.getPassengerDetails())) {
            throw new ServiceException("车票子订单不存在");
        }
        return orderDetail;
    }

    private TrainDO queryChangeTrainInfo(String trainId) {
        TrainDO trainDO = distributedCache.safeGet(
                TRAIN_INFO + trainId,
                TrainDO.class,
                () -> trainMapper.selectById(trainId),
                ADVANCE_TICKET_DAY,
                TimeUnit.DAYS);
        if (Objects.isNull(trainDO)) {
            throw new ServiceException("改签车次不存在，请重新选择");
        }
        return trainDO;
    }

    private TrainStationRelationDO queryChangeTrainStationRelation(String trainId, String departure, String arrival) {
        LambdaQueryWrapper<TrainStationRelationDO> queryWrapper = Wrappers.lambdaQuery(TrainStationRelationDO.class)
                .eq(TrainStationRelationDO::getTrainId, trainId)
                .eq(TrainStationRelationDO::getDeparture, departure)
                .eq(TrainStationRelationDO::getArrival, arrival);
        TrainStationRelationDO trainStationRelationDO = trainStationRelationMapper.selectOne(queryWrapper);
        if (Objects.isNull(trainStationRelationDO)) {
            throw new ServiceException("改签车次不包含所选乘车站点，请重新选择");
        }
        return trainStationRelationDO;
    }

    private Map<Integer, Integer> loadNewSeatTypePrices(ChangeTicketReqDTO requestParam) {
        LambdaQueryWrapper<TrainStationPriceDO> priceQueryWrapper = Wrappers.lambdaQuery(TrainStationPriceDO.class)
                .eq(TrainStationPriceDO::getTrainId, Long.parseLong(requestParam.getNewTrainId()))
                .eq(TrainStationPriceDO::getDeparture, requestParam.getNewDeparture())
                .eq(TrainStationPriceDO::getArrival, requestParam.getNewArrival());
        return trainStationPriceMapper.selectList(priceQueryWrapper).stream()
                .collect(Collectors.toMap(TrainStationPriceDO::getSeatType, TrainStationPriceDO::getPrice, (a, b) -> a));
    }

    private String recoverPassengerId(org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderDetailRespDTO orderDetail, TicketOrderPassengerDetailRespDTO orderItem) {
        // t_ticket 在支付后不会流转状态（支付回调仅更新座位表），这里匹配未被改签/退票/关闭的有效购票记录
        LambdaQueryWrapper<TicketDO> queryWrapper = Wrappers.lambdaQuery(TicketDO.class)
                .eq(TicketDO::getUsername, UserContext.getUsername())
                .eq(TicketDO::getTrainId, orderDetail.getTrainId())
                .eq(TicketDO::getCarriageNumber, orderItem.getCarriageNumber())
                .eq(TicketDO::getSeatNumber, orderItem.getSeatNumber())
                .in(TicketDO::getTicketStatus, TicketStatusEnum.UNPAID.getCode(), TicketStatusEnum.PAID.getCode(), TicketStatusEnum.BOARDED.getCode())
                .orderByDesc(TicketDO::getCreateTime)
                .last("LIMIT 1");
        TicketDO oldTicketDO = getBaseMapper().selectOne(queryWrapper);
        return Objects.isNull(oldTicketDO) ? null : oldTicketDO.getPassengerId();
    }

    private String createChangeTicketOrder(ChangeTicketReqDTO requestParam, TrainStationRelationDO newRelation, TrainDO newTrainDO, List<TrainPurchaseTicketRespDTO> newSeatResults, Map<String, Integer> ticketTypeMap) {
        List<TicketOrderItemCreateRemoteReqDTO> orderItemCreateRemoteReqDTOList = new ArrayList<>();
        newSeatResults.forEach(each -> orderItemCreateRemoteReqDTOList.add(TicketOrderItemCreateRemoteReqDTO.builder()
                .amount(each.getAmount())
                .carriageNumber(each.getCarriageNumber())
                .seatNumber(each.getSeatNumber())
                .idCard(each.getIdCard())
                .idType(each.getIdType())
                .phone(each.getPhone())
                .seatType(each.getSeatType())
                .ticketType(ticketTypeMap.getOrDefault(each.getPassengerId(), each.getUserType()))
                .realName(each.getRealName())
                .build()));
        TicketOrderCreateRemoteReqDTO orderCreateRemoteReqDTO = TicketOrderCreateRemoteReqDTO.builder()
                .departure(requestParam.getNewDeparture())
                .arrival(requestParam.getNewArrival())
                .orderTime(new Date())
                .source(SourceEnum.INTERNET.getCode())
                .trainNumber(newTrainDO.getTrainNumber())
                .departureTime(newRelation.getDepartureTime())
                .arrivalTime(newRelation.getArrivalTime())
                .ridingDate(newRelation.getDepartureTime())
                .userId(UserContext.getUserId())
                .username(UserContext.getUsername())
                .trainId(Long.parseLong(requestParam.getNewTrainId()))
                .ticketOrderItems(orderItemCreateRemoteReqDTOList)
                .build();
        Result<String> ticketOrderResult = ticketOrderRemoteService.createTicketOrder(orderCreateRemoteReqDTO);
        if (!ticketOrderResult.isSuccess() || StrUtil.isBlank(ticketOrderResult.getData())) {
            log.error("改签创建新订单失败，原订单号：{}，返回结果：{}", requestParam.getOrderSn(), ticketOrderResult.getMessage());
            throw new ServiceException("改签创建新订单失败");
        }
        return ticketOrderResult.getData();
    }

    private void refundChangeTickets(String orderSn, List<TicketOrderPassengerDetailRespDTO> selectedPassengerDetails, Map<String, Integer> itemFeeMap, int refundAmount) {
        RefundReqDTO refundReqDTO = new RefundReqDTO();
        refundReqDTO.setOrderSn(orderSn);
        refundReqDTO.setRefundTypeEnum(RefundTypeEnum.RESCHEDULED);
        refundReqDTO.setRefundAmount(refundAmount);
        List<TicketOrderPassengerDetailRespDTO> refundDetailList = selectedPassengerDetails.stream()
                .map(each -> {
                    TicketOrderPassengerDetailRespDTO detail = BeanUtil.convert(each, TicketOrderPassengerDetailRespDTO.class);
                    detail.setAmount(each.getAmount() - itemFeeMap.getOrDefault(each.getId(), 0));
                    return detail;
                })
                .toList();
        refundReqDTO.setRefundDetailReqDTOList(refundDetailList);
        Result<RefundRespDTO> refundRespDTOResult = payRemoteService.commonRefund(refundReqDTO);
        if (!refundRespDTOResult.isSuccess()) {
            log.error("改签原票退款失败，订单号：{}，返回结果：{}", orderSn, refundRespDTOResult.getMessage());
            throw new ServiceException("改签原票退款失败，请稍后重试");
        }
    }

    private void markOldTicketsChanged(org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderDetailRespDTO orderDetail, List<TicketOrderPassengerDetailRespDTO> selectedPassengerDetails) {
        selectedPassengerDetails.forEach(each -> {
            LambdaUpdateWrapper<TicketDO> updateWrapper = Wrappers.lambdaUpdate(TicketDO.class)
                    .eq(TicketDO::getUsername, UserContext.getUsername())
                    .eq(TicketDO::getTrainId, orderDetail.getTrainId())
                    .eq(TicketDO::getCarriageNumber, each.getCarriageNumber())
                    .eq(TicketDO::getSeatNumber, each.getSeatNumber())
                    .in(TicketDO::getTicketStatus, TicketStatusEnum.UNPAID.getCode(), TicketStatusEnum.PAID.getCode(), TicketStatusEnum.BOARDED.getCode());
            TicketDO updateTicketDO = TicketDO.builder()
                    .ticketStatus(TicketStatusEnum.CHANGED.getCode())
                    .build();
            getBaseMapper().update(updateTicketDO, updateWrapper);
        });
    }

    private void compensateNewSeatResources(PurchaseTicketReqDTO newPurchaseReq, List<TrainPurchaseTicketRespDTO> newSeatResults) {
        try {
            if (CollUtil.isNotEmpty(newSeatResults)) {
                seatService.unlock(newPurchaseReq.getTrainId(), newPurchaseReq.getDeparture(), newPurchaseReq.getArrival(), newSeatResults);
                if (!StrUtil.equals(ticketAvailabilityCacheUpdateType, "binlog")) {
                    StringRedisTemplate stringRedisTemplate = (StringRedisTemplate) distributedCache.getInstance();
                    Map<Integer, List<TrainPurchaseTicketRespDTO>> seatTypeMap = newSeatResults.stream()
                            .collect(Collectors.groupingBy(TrainPurchaseTicketRespDTO::getSeatType));
                    List<RouteDTO> routeDTOList = trainStationService.listTakeoutTrainStationRoute(newPurchaseReq.getTrainId(), newPurchaseReq.getDeparture(), newPurchaseReq.getArrival());
                    routeDTOList.forEach(each -> {
                        String keySuffix = StrUtil.join("_", newPurchaseReq.getTrainId(), each.getStartStation(), each.getEndStation());
                        seatTypeMap.forEach((seatType, results) ->
                                stringRedisTemplate.opsForHash().increment(TRAIN_STATION_REMAINING_TICKET + keySuffix, String.valueOf(seatType), results.size()));
                    });
                }
            }
        } catch (Throwable ex) {
            log.error("[改签补偿] 释放新票座位与缓存失败，请求参数：{}", JSON.toJSONString(newPurchaseReq), ex);
        }
    }

    private void releaseOldTicketResources(org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderDetailRespDTO orderDetail, List<TicketOrderPassengerDetailRespDTO> selectedPassengerDetails) {
        if (StrUtil.equals(ticketAvailabilityCacheUpdateType, "binlog")) {
            return;
        }
        try {
            String oldTrainId = String.valueOf(orderDetail.getTrainId());
            List<TrainPurchaseTicketRespDTO> oldSeatResults = BeanUtil.convert(selectedPassengerDetails, TrainPurchaseTicketRespDTO.class);
            seatService.unlock(oldTrainId, orderDetail.getDeparture(), orderDetail.getArrival(), oldSeatResults);
            StringRedisTemplate stringRedisTemplate = (StringRedisTemplate) distributedCache.getInstance();
            Map<Integer, List<TicketOrderPassengerDetailRespDTO>> seatTypeMap = selectedPassengerDetails.stream()
                    .collect(Collectors.groupingBy(TicketOrderPassengerDetailRespDTO::getSeatType));
            List<RouteDTO> routeDTOList = trainStationService.listTakeoutTrainStationRoute(oldTrainId, orderDetail.getDeparture(), orderDetail.getArrival());
            routeDTOList.forEach(each -> {
                String keySuffix = StrUtil.join("_", oldTrainId, each.getStartStation(), each.getEndStation());
                seatTypeMap.forEach((seatType, details) ->
                        stringRedisTemplate.opsForHash().increment(TRAIN_STATION_REMAINING_TICKET + keySuffix, String.valueOf(seatType), details.size()));
            });
        } catch (Throwable ex) {
            log.error("[改签] 订单号：{} 释放原票座位与缓存失败", orderDetail.getOrderSn(), ex);
        }
    }

    private LocalDateTime buildDepartureDateTime(Date ridingDate, Date departureTime) {
        LocalDate ridingDay = convertDateToLocalDateTime(ridingDate).toLocalDate();
        LocalTime departureLocalTime = convertDateToLocalDateTime(departureTime).toLocalTime();
        return LocalDateTime.of(ridingDay, departureLocalTime);
    }

    private LocalDateTime buildRidingDateEnd(Date ridingDate) {
        LocalDate ridingDay = convertDateToLocalDateTime(ridingDate).toLocalDate();
        return ridingDay.plusDays(1).atStartOfDay();
    }

    private LocalDateTime convertDateToLocalDateTime(Date date) {
        return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
    }

    private List<String> buildDepartureStationList(List<TicketListDTO> seatResults) {
        return seatResults.stream().map(TicketListDTO::getDeparture).distinct().collect(Collectors.toList());
    }

    private List<String> buildArrivalStationList(List<TicketListDTO> seatResults) {
        return seatResults.stream().map(TicketListDTO::getArrival).distinct().collect(Collectors.toList());
    }

    private List<Integer> buildSeatClassList(List<TicketListDTO> seatResults) {
        Set<Integer> resultSeatClassList = new HashSet<>();
        for (TicketListDTO each : seatResults) {
            for (SeatClassDTO item : each.getSeatClassList()) {
                resultSeatClassList.add(item.getType());
            }
        }
        return resultSeatClassList.stream().toList();
    }

    private List<Integer> buildTrainBrandList(List<TicketListDTO> seatResults) {
        Set<Integer> trainBrandSet = new HashSet<>();
        for (TicketListDTO each : seatResults) {
            if (StrUtil.isNotBlank(each.getTrainBrand())) {
                trainBrandSet.addAll(StrUtil.split(each.getTrainBrand(), ",").stream().map(Integer::parseInt).toList());
            }
        }
        return trainBrandSet.stream().toList();
    }

    @Override
    public void run(String... args) throws Exception {
        ticketService = ApplicationContextHolder.getBean(TicketService.class);
    }
}
