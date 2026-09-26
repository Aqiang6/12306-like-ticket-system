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

package org.opengoofy.index12306.biz.ticketservice.toolkit;

import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.TRAIN_CARRIAGE_SEAT_STATUS;

import java.util.Map;

/**
 * 列车座位区间占用 BitMap 工具
 *
 * <p>位图设计（对齐 12306 项目余票 BitMap 方案解读）：以车厢为粒度维护一个 Redis BitMap，
 * Key 为 {@code train_carriage_seat_status:{trainId}_{carriageNumber}}；
 * 每个座位占 {@code 站点数} 个 bit，第 {@code seatIndex * 站点数 + 相邻站段序号} 位记录
 * 该座位在对应相邻站段（沿途第 i 站 → 第 i+1 站）的占用情况：
 * bit=1 表示该站段已售出占用，bit=0 表示空闲。
 * 判断 座位 在 [出发站, 到达站) 内所有相邻站段的 bit 是否全为 0，即可精确判断任意起止站组合是否可售。
 *
 * <p>座位索引：{@code (行号 - 1) * 6 + 列位}，列位按 A=0、B=1、C=2、D=3、E=4、F=5 固定步长映射，
 * 商务座（ACF）、一等座（ACDF）、二等座（ABCDF）的座位号在该映射下互不冲突。
 * 行数与列字母表与原项目购票组件、{@code BitMapCheckSeat} 位图类保持一致：
 * 商务座 2 行 × A/C/F、一等座 7 行 × A/C/D/F、二等座 18 行 × A/B/C/D/F。
 *
 * 公众号：马丁玩编程，回复：加群，添加马哥微信（备注：12306）获取项目资料
 */
public final class SeatBitMapUtil {

    /**
     * 通用列位映射，固定步长 6（A-F），保证各席别座位索引互不冲突
     */
    private static final String LETTERS = "ABCDEF";

    private static final int LETTER_STRIDE = LETTERS.length();

    /**
     * 各席别座位布局：行数（与原项目商务/一等/二等购票组件及 BitMapCheckSeat 一致）
     */
    private static final Map<Integer, Integer> SEAT_TYPE_ROWS = Map.of(0, 2, 1, 7, 2, 18);

    /**
     * 各席别物理列字母表（行内非字母表位置的 bit 恒为 1，视为不存在）
     */
    private static final Map<Integer, String> SEAT_TYPE_LETTERS = Map.of(0, "ACF", 1, "ACDF", 2, "ABCDF");

    private SeatBitMapUtil() {
    }

    /**
     * 席别是否支持位图（仅原项目定义了座位布局的商务/一等/二等座）
     */
    public static boolean supports(Integer seatType) {
        return seatType != null && SEAT_TYPE_ROWS.containsKey(seatType);
    }

    public static int getRows(Integer seatType) {
        return SEAT_TYPE_ROWS.get(seatType);
    }

    public static String getLetters(Integer seatType) {
        return SEAT_TYPE_LETTERS.get(seatType);
    }

    /**
     * 构建车厢座位占用位图缓存 Key
     */
    public static String buildKey(String trainId, String carriageNumber) {
        return TRAIN_CARRIAGE_SEAT_STATUS + trainId + "_" + carriageNumber;
    }

    /**
     * 座位号转座位索引
     *
     * @param seatType   席别类型
     * @param seatNumber 座位号（如 01A）
     * @return 座位索引；座位号不合法（超出布局行数或列字母不属于该席别）返回 -1
     */
    public static int seatIndexOf(Integer seatType, String seatNumber) {
        if (seatNumber == null || seatNumber.length() != 3 || !supports(seatType)) {
            return -1;
        }
        int row;
        try {
            row = Integer.parseInt(seatNumber.substring(0, 2));
        } catch (NumberFormatException ex) {
            return -1;
        }
        int letterBit = LETTERS.indexOf(seatNumber.charAt(2));
        if (row <= 0 || row > getRows(seatType) || letterBit < 0 || getLetters(seatType).indexOf(seatNumber.charAt(2)) < 0) {
            return -1;
        }
        return (row - 1) * LETTER_STRIDE + letterBit;
    }

    /**
     * 座位索引转座位号
     *
     * @param seatType  席别类型
     * @param seatIndex 座位索引
     * @return 座位号；索引位置不属于该席别物理座位（如商务座 B 列）返回 null
     */
    public static String seatNumberOf(Integer seatType, int seatIndex) {
        if (!supports(seatType) || seatIndex < 0) {
            return null;
        }
        int row = seatIndex / LETTER_STRIDE + 1;
        int letterBit = seatIndex % LETTER_STRIDE;
        if (row > getRows(seatType)) {
            return null;
        }
        char letter = LETTERS.charAt(letterBit);
        if (getLetters(seatType).indexOf(letter) < 0) {
            return null;
        }
        return String.format("%02d", row) + letter;
    }

    /**
     * 座位在指定相邻站段的 bit 偏移量
     *
     * @param seatIndex  座位索引
     * @param stationCount 列车沿途站点数
     * @param segmentIdx 相邻站段序号（0 起始，第 i 站 → 第 i+1 站）
     */
    public static long bitOffset(int seatIndex, int stationCount, int segmentIdx) {
        return (long) seatIndex * stationCount + segmentIdx;
    }

    /**
     * 读取位图中指定偏移的 bit（越界视为 0 = 空闲）
     */
    public static boolean getBit(byte[] bitmap, long offset) {
        int byteIndex = (int) (offset >> 3);
        if (bitmap == null || byteIndex >= bitmap.length) {
            return false;
        }
        return (bitmap[byteIndex] & (0x80 >> (offset & 7))) != 0;
    }
}
