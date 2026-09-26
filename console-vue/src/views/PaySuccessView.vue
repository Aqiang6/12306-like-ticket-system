<template>
  <div>
    <!-- 成功横幅 -->
    <div class="card success-card">
      <div class="success-icon">
        <AppIcon name="check-circle" :size="34" />
      </div>
      <div class="success-info">
        <div class="success-title">交易已完成</div>
        <div class="success-sub">
          感谢您选择铁路出行！您的订单号是：<span class="order-sn">{{ state.orderDetail?.orderSn }}</span>
        </div>
        <div class="success-tip">
          {{ state.userInfo?.realName }} 先生/女士可持购票时所使用的中国居民身份证原件，于购票后、列车开车前到车站直接检票乘车。
        </div>
      </div>
    </div>

    <!-- 订单信息 -->
    <div class="card">
      <div class="card-head">
        <div class="card-title">订单信息</div>
      </div>

      <div class="train-summary">
        <span class="summary-strong">{{ state.orderDetail?.ridingDate }}</span>
        <span>（{{ getWeekNumber(dayjs(state.orderDetail?.ridingDate ?? new Date()).day()) }}）</span>
        <span class="summary-strong">{{ state.orderDetail?.trainNumber }}</span>次
        <span class="summary-strong">{{ state.orderDetail?.departure }}</span>站
        （{{ state.orderDetail?.departureTime }}开）
        <AppIcon name="arrow-right" :size="14" />
        <span class="summary-strong">{{ state.orderDetail?.arrival }}</span>站
        （{{ state.orderDetail?.arrivalTime }}到）
      </div>

      <div class="table-wrap" style="margin-top: 16px">
        <table class="table">
          <thead>
            <tr>
              <th>序号</th>
              <th>姓名</th>
              <th>证件类型</th>
              <th>证件号码</th>
              <th>票种</th>
              <th>席别</th>
              <th>车厢</th>
              <th>席位号</th>
              <th>票价(元)</th>
              <th>订单状态</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(item, index) in state.orderDetail?.passengerDetails ?? []" :key="index">
              <td class="num">{{ index + 1 }}</td>
              <td>{{ item.realName }}</td>
              <td>{{ label(ID_CARD_TYPE, item.idType) }}</td>
              <td class="mono">{{ item.idCard }}</td>
              <td>{{ label(TICKET_TYPE_LIST, item.ticketType) }}</td>
              <td>{{ label(SEAT_CLASS_TYPE_LIST, item.seatType, 'code') }}</td>
              <td class="num">{{ item.carriageNumber }}</td>
              <td class="num">{{ item.seatNumber }}</td>
              <td><span class="price">￥{{ (item.amount ?? 0) / 100 }}</span></td>
              <td>{{ label(TICKET_STATUS_LIST, item.status) }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <hr class="divider" />

      <div class="row" style="justify-content: center; gap: 12px">
        <button class="btn btn-primary" @click="router.push('/ticketSearch')">继续购票</button>
        <button class="btn" @click="router.push('/ticketList')">查询订单详情</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, reactive } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import dayjs from 'dayjs'
import AppIcon from '@/components/AppIcon.vue'
import { orderBySn, getUserInfo } from '@/api'
import { getWeekNumber } from '@/utils'
import {
  ID_CARD_TYPE,
  TICKET_TYPE_LIST,
  SEAT_CLASS_TYPE_LIST,
  TICKET_STATUS_LIST
} from '@/constants'
import { getUsername } from '@/stores/auth'

const route = useRoute()
const router = useRouter()

const state = reactive({
  orderDetail: null,
  userInfo: null
})

const label = (list, value, key = 'value') =>
  list.find((item) => item[key] === value)?.label ?? '--'

onMounted(() => {
  orderBySn({ orderSn: route.query?.orderSn }).then((res) => {
    state.orderDetail = res?.data
  })
  getUserInfo({ username: getUsername() }).then((res) => {
    state.userInfo = res?.data
  })
})
</script>

<style scoped>
.success-card {
  display: flex;
  align-items: flex-start;
  gap: 18px;
  background: linear-gradient(135deg, rgba(52, 199, 89, 0.08), rgba(0, 113, 227, 0.06));
  margin-bottom: 16px;
}

.success-icon {
  width: 56px;
  height: 56px;
  border-radius: 18px;
  background: var(--green-soft);
  color: #1d9d43;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.success-title {
  font-size: 19px;
  font-weight: 700;
  color: #1d9d43;
}

.success-sub {
  margin-top: 4px;
  font-size: 14px;
  color: var(--text-1);
}

.order-sn {
  font-weight: 700;
  color: var(--orange);
  font-variant-numeric: tabular-nums;
}

.success-tip {
  margin-top: 6px;
  font-size: 13px;
  color: var(--text-2);
  line-height: 1.7;
}

.train-summary {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px;
  font-size: 14px;
  color: var(--text-2);
}

.summary-strong {
  font-size: 16px;
  font-weight: 700;
  color: var(--text-1);
}
</style>
