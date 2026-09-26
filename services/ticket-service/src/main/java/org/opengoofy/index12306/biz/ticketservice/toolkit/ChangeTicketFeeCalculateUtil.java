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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.Objects;

/**
 * 改签费用计算工具
 * 改签费按较低票价核收；变更到站差额退款部分按退票费标准核收手续费，金额单位均为分
 *
 * <p>改签费规则：
 * 开车前 48 小时以上，改签至预售期内任意列车，免费；
 * 开车前不足 48 小时，改签至票面日期当天或之前的列车，免费；
 * 开车前 24-48 小时，改签至票面日期之后的列车，按较低票价 5% 核收；
 * 开车前不足 24 小时，改签至票面日期之后的列车，按较低票价 15% 核收；
 * 开车后当日 24 点前，改签至当日其他列车，免费；改签至次日及以后列车，按较低票价 40% 核收
 *
 * <p>退票费标准（用于变更到站差额退款手续费）：
 * 开车前 8 天以上免收；开车前 48 小时至 8 天按 5% 核收；24-48 小时按 10% 核收；不足 24 小时按 20% 核收
 *
 * 公众号：马丁玩编程，回复：加群，添加马哥微信（备注：12306）获取项目资料
 */
public final class ChangeTicketFeeCalculateUtil {

    /**
     * 改签费率：开车前不足 48 小时改签至票面日期之后的列车
     */
    private static final BigDecimal CHANGE_FEE_RATE_48 = new BigDecimal("0.05");

    /**
     * 改签费率：开车前不足 24 小时改签至票面日期之后的列车
     */
    private static final BigDecimal CHANGE_FEE_RATE_24 = new BigDecimal("0.15");

    /**
     * 改签费率：开车后当日 24 点前改签至次日及以后的列车
     */
    private static final BigDecimal CHANGE_FEE_RATE_AFTER_DEPARTURE = new BigDecimal("0.40");

    /**
     * 退票费率：开车前 48 小时至 8 天
     */
    private static final BigDecimal REFUND_FEE_RATE_48 = new BigDecimal("0.05");

    /**
     * 退票费率：开车前 24 至 48 小时
     */
    private static final BigDecimal REFUND_FEE_RATE_24 = new BigDecimal("0.10");

    /**
     * 退票费率：开车前不足 24 小时
     */
    private static final BigDecimal REFUND_FEE_RATE_IMMEDIATE = new BigDecimal("0.20");

    private static final int REFUND_FEE_FREE_HOURS = 8 * 24;

    private ChangeTicketFeeCalculateUtil() {
    }

    /**
     * 计算单张车票改签费
     *
     * @param departureTime 原票列车开车时间
     * @param oldRidingDate 原票票面乘车日期
     * @param newRidingDate 改签后乘车日期
     * @param oldAmount     原票金额（分）
     * @param newAmount     新票金额（分）
     * @return 改签费（分）
     */
    public static int calculateChangeTicketFee(LocalDateTime departureTime, Date oldRidingDate, Date newRidingDate, int oldAmount, int newAmount) {
        long hoursBefore = hoursBeforeDeparture(departureTime);
        boolean newDateAfterOld = isAfterDay(newRidingDate, oldRidingDate);
        BigDecimal rate;
        if (hoursBefore > 0) {
            if (hoursBefore >= 48) {
                rate = BigDecimal.ZERO;
            } else if (hoursBefore >= 24) {
                rate = newDateAfterOld ? CHANGE_FEE_RATE_48 : BigDecimal.ZERO;
            } else {
                rate = newDateAfterOld ? CHANGE_FEE_RATE_24 : BigDecimal.ZERO;
            }
        } else {
            // 开车后当日 24 点前办理：改签至当日其他列车免费，改签至次日及以后按较低票价 40% 核收
            rate = newDateAfterOld ? CHANGE_FEE_RATE_AFTER_DEPARTURE : BigDecimal.ZERO;
        }
        return multiplyFee(Math.min(oldAmount, newAmount), rate);
    }

    /**
     * 按退票费标准计算手续费（用于变更到站差额退款）
     *
     * @param departureTime 原票列车开车时间
     * @param amount        计费基数（分）
     * @return 手续费（分）
     */
    public static int calculateRefundFee(LocalDateTime departureTime, int amount) {
        if (amount <= 0) {
            return 0;
        }
        long hoursBefore = hoursBeforeDeparture(departureTime);
        BigDecimal rate;
        if (hoursBefore >= REFUND_FEE_FREE_HOURS) {
            rate = BigDecimal.ZERO;
        } else if (hoursBefore >= 48) {
            rate = REFUND_FEE_RATE_48;
        } else if (hoursBefore >= 24) {
            rate = REFUND_FEE_RATE_24;
        } else {
            rate = REFUND_FEE_RATE_IMMEDIATE;
        }
        return multiplyFee(amount, rate);
    }

    /**
     * 生成改签费规则说明文案
     */
    public static String buildChangeFeeRuleDesc(LocalDateTime departureTime, Date oldRidingDate, Date newRidingDate, int changeFee) {
        long hoursBefore = hoursBeforeDeparture(departureTime);
        boolean newDateAfterOld = isAfterDay(newRidingDate, oldRidingDate);
        if (hoursBefore > 0) {
            if (hoursBefore >= 48) {
                return "开车前48小时以上改签，免收改签费";
            }
            if (!newDateAfterOld) {
                return "开车前不足48小时改签至票面日期当天或之前的列车，免收改签费";
            }
            return hoursBefore >= 24
                    ? "开车前24-48小时改签至票面日期之后的列车，按较低票价5%核收改签费"
                    : "开车前不足24小时改签至票面日期之后的列车，按较低票价15%核收改签费";
        }
        return newDateAfterOld
                ? "开车后当日改签至次日及以后列车，按较低票价40%核收改签费"
                : "开车后当日改签至当日其他列车，免收改签费";
    }

    /**
     * 生成变更到站差额手续费规则说明文案
     */
    public static String buildRefundFeeRuleDesc(LocalDateTime departureTime) {
        long hoursBefore = hoursBeforeDeparture(departureTime);
        if (hoursBefore >= REFUND_FEE_FREE_HOURS) {
            return "变更到站差额退款，开车前8天以上免收手续费";
        }
        if (hoursBefore >= 48) {
            return "变更到站差额退款，退款部分按退票费标准5%核收手续费";
        }
        if (hoursBefore >= 24) {
            return "变更到站差额退款，退款部分按退票费标准10%核收手续费";
        }
        return "变更到站差额退款，退款部分按退票费标准20%核收手续费";
    }

    private static int multiplyFee(int baseAmount, BigDecimal rate) {
        if (baseAmount <= 0 || BigDecimal.ZERO.compareTo(rate) == 0) {
            return 0;
        }
        return new BigDecimal(baseAmount).multiply(rate).setScale(0, RoundingMode.CEILING).intValue();
    }

    private static long hoursBeforeDeparture(LocalDateTime departureTime) {
        return Duration.between(LocalDateTime.now(), departureTime).toHours();
    }

    private static boolean isAfterDay(Date source, Date target) {
        if (Objects.isNull(source) || Objects.isNull(target)) {
            return false;
        }
        LocalDate sourceDay = source.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        LocalDate targetDay = target.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        return sourceDay.isAfter(targetDay);
    }
}
