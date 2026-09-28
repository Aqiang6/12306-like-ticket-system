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

package org.opengoofy.index12306.biz.ticketservice.common.constant;

/**
 * Redis Key 定义常量类
 * 公众号：马丁玩编程，回复：加群，添加马哥微信（备注：12306）获取项目资料
 */
public final class RedisKeyConstant {

    /**
     * 列车基本信息，Key Prefix + 列车ID
     */
    public static final String TRAIN_INFO = "index12306-ticket-service:train_info:";

    /**
     * 地区与站点映射查询
     */
    public static final String REGION_TRAIN_STATION_MAPPING = "index12306-ticket-service:region_train_station_mapping";

    /**
     * 站点查询分布式锁 Key
     */
    public static final String LOCK_REGION_TRAIN_STATION_MAPPING = "index12306-ticket-service:lock:region_train_station_mapping";

    /**
     * 站点查询，Key Prefix + 起始城市_终点城市_日期
     */
    public static final String REGION_TRAIN_STATION = "index12306-ticket-service:region_train_station:%s_%s";

    /**
     * 站点查询分布式锁 Key
     */
    public static final String LOCK_REGION_TRAIN_STATION = "index12306-ticket-service:lock:region_train_station";

    /**
     * 列车站点座位价格查询，Key Prefix + 列车ID_起始城市_终点城市
     */
    public static final String TRAIN_STATION_PRICE = "index12306-ticket-service:train_station_price:%s_%s_%s";

    /**
     * 车站关系缓存 Key（发到时刻等静态数据，购票下单路径读缓存）
     */
    public static final String TRAIN_STATION_RELATION_DETAIL = "index12306-ticket-service:train_station_relation_detail:";

    /**
     * 地区以及车站查询，Key Prefix + ( 车站名称 or 查询方式 )
     */
    public static final String REGION_STATION = "index12306-ticket-service:region-station:";

    /**
     * 站点余票查询，Key Prefix + 列车ID_起始站点_终点
     */
    public static final String TRAIN_STATION_REMAINING_TICKET = "index12306-ticket-service:train_station_remaining_ticket:";

    /**
     * 列车车厢查询，Key Prefix + 列车ID
     */
    public static final String TRAIN_CARRIAGE = "index12306-ticket-service:train_carriage:";

    /**
     * 站点详细信息查询，Key Prefix + 列车ID_起始站点_终点
     */
    public static final String TRAIN_STATION_DETAIL = "index12306-ticket-service:train_station_detail:";

    /**
     * 列车路线信息查询，Key Prefix + 列车ID
     */
    public static final String TRAIN_STATION_STOPOVER_DETAIL = "index12306-ticket-service:train_station_stopover_detail:";

    /**
     * 列车站点缓存
     */
    public static final String STATION_ALL = "index12306-ticket-service:all_station";

    /**
     * 车厢座位区间占用位图，Key Prefix + 列车 ID + 车厢编号
     * 每个座位占 站点数 个 bit：bit=1 表示该座位在对应相邻站段已售出占用，bit=0 表示空闲
     */
    public static final String TRAIN_CARRIAGE_SEAT_STATUS = "index12306-ticket-service:train_carriage_seat_status:";

    /**
     * 区间售罄广播，Key Prefix + 列车ID_出发站_到达站_席别：
     * 余票展示层刷新为 0 → 置位（15s 自愈 TTL），恢复 > 0 → 清除；
     * 购票路径在排队前与公平锁等待期间观测该标志，所需席别售完即逐出排队线程
     */
    public static final String TRAIN_INTERVAL_SOLD_OUT = "index12306-ticket-service:train_interval_sold_out:";

    /**
     * 车厢座位占用位图初始化分布式锁 Key
     */
    public static final String LOCK_SEAT_BIT_MAP = "index12306-ticket-service:lock:seat_bit_map_%s";

    /**
     * 用户购票分布式锁 Key v2
     */
    public static final String LOCK_PURCHASE_TICKETS_V2 = "${unique-name:}index12306-ticket-service:lock:purchase_tickets_%s_%d";

    /**
     * 用户改签分布式锁 Key
     */
    public static final String LOCK_CHANGE_TICKETS = "${unique-name:}index12306-ticket-service:lock:change_tickets_%s";

    /**
     * 获取全部地点集合 Key
     */
    public static final String QUERY_ALL_REGION_LIST = "index12306-ticket-service:query_all_region_list";

    /**
     * 获取全部地点集合分布式锁 Key
     */
    public static final String LOCK_QUERY_ALL_REGION_LIST = "index12306-ticket-service:lock:query_all_region_list";

    /**
     * 获取列车车厢数量集合分布式锁 Key
     */
    public static final String LOCK_QUERY_CARRIAGE_NUMBER_LIST = "index12306-ticket-service:lock:query_carriage_number_list_%s";

    /**
     * 获取地区以及站点集合分布式锁 Key
     */
    public static final String LOCK_QUERY_REGION_STATION_LIST = "index12306-ticket-service:lock:query_region_station_list_%s";

    /**
     * 获取相邻座位余票分布式锁 Key
     */
    public static final String LOCK_SAFE_LOAD_SEAT_MARGIN_GET = "index12306-ticket-service:lock:safe_load_seat_margin_%s";
}
