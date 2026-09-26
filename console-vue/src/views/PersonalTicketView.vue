<template>
  <div>
    <div class="page-head">
      <h1 class="page-title">本人车票</h1>
      <p class="page-subtitle">查询您名下的电子车票</p>
    </div>

    <div v-if="loading" class="spin-wrap" style="min-height: 240px">
      <span class="spinner spinner-lg" />
    </div>

    <template v-else>
      <div v-if="tickets.length" class="ticket-list">
        <div v-for="item in tickets" :key="item.id" class="ticket-card">
          <div class="ticket-ribbon">
            {{ item.ridingDate }} {{ getWeekNumber(dayjs(item.ridingDate).day()) }}
          </div>

          <div class="ticket-body">
            <div class="ticket-train">
              <div class="ticket-number">{{ item.trainNumber }}</div>
              <div class="text-secondary" style="font-size: 12px">车次当日有效</div>
            </div>

            <div class="ticket-route">
              <div class="route-point">
                <div class="route-city">{{ item.departure }}</div>
                <div class="route-time">{{ item.departureTime }} 开</div>
              </div>
              <AppIcon name="arrow-right" :size="20" style="color: var(--accent)" />
              <div class="route-point right">
                <div class="route-city">{{ item.arrival }}</div>
                <div class="route-time">{{ item.arrivalTime }} 到</div>
              </div>
            </div>

            <div class="ticket-seat">
              <div class="seat-name">
                {{ label(SEAT_CLASS_TYPE_LIST, item.seatType, 'code') }}
              </div>
              <div class="seat-no">
                {{ item.carriageNumber }}车 {{ item.seatNumber }}号
              </div>
            </div>

            <div class="ticket-type">
              <div class="text-secondary" style="font-size: 12px">
                {{ label(DISCOUNTS_TYPE, item.ticketType) }}票
              </div>
              <div class="price" style="font-size: 18px">
                {{ (item.amount / 100)?.toFixed(2) }}
              </div>
            </div>

            <div class="ticket-status">
              <span class="tag" :class="statusTag(item)">
                {{ statusLabel(item) }}
              </span>
            </div>
          </div>

          <hr class="divider" style="margin: 0" />

          <div class="ticket-actions">
            <div v-if="actionTip(item)" class="action-tip">{{ actionTip(item) }}</div>
            <div style="flex: 1" />
            <button
              class="btn btn-sm"
              :disabled="!canChange(item)"
              title="改签可变更乘车日期、车次、席别，开车后当日 24 点前也可办理"
              @click="goChange(item, 'change')"
            >
              改签
            </button>
            <button
              class="btn btn-sm"
              :disabled="!canChangeArrival(item)"
              title="变更到站需在开车前 48 小时以上办理，出发站不可变更"
              @click="goChange(item, 'arrival')"
            >
              变更车站
            </button>
            <button class="btn btn-danger btn-sm" :disabled="item.status !== 10" @click="openRefund(item)">
              退票
            </button>
          </div>
        </div>
      </div>

      <div v-else class="card">
        <div class="empty">
          <AppIcon name="ticket" :size="44" />
          <span class="empty-text">暂无本人车票</span>
        </div>
      </div>

      <AppPagination
        :current="state.current"
        :page-size="state.size"
        :total="state.total"
        @change="handlePage"
      />
    </template>

    <AppModal v-model="refundOpen" title="退票申请" width="440px">
      <div v-if="refundTarget" class="refund-info">
        <div class="refund-row">
          <span>车次</span>
          <b>{{ refundTarget.trainNumber }}</b>
        </div>
        <div class="refund-row">
          <span>乘车日期</span>
          <span>{{ refundTarget.ridingDate }} {{ getWeekNumber(dayjs(refundTarget.ridingDate).day()) }}</span>
        </div>
        <div class="refund-row">
          <span>区间</span>
          <span>{{ refundTarget.departure }} → {{ refundTarget.arrival }}</span>
        </div>
        <div class="refund-row">
          <span>席位</span>
          <span>{{ label(SEAT_CLASS_TYPE_LIST, refundTarget.seatType, 'code') }} {{ refundTarget.carriageNumber }}车 {{ refundTarget.seatNumber }}号</span>
        </div>
        <div class="refund-row">
          <span>应退票款</span>
          <span class="price">￥{{ (refundTarget.amount / 100)?.toFixed(2) }}</span>
        </div>
        <p class="refund-tip">实际核收退票费及应退票款将按最终交易时间计算。退票后车票将无法恢复。</p>
      </div>
      <template #footer>
        <button class="btn" @click="refundOpen = false">取消</button>
        <button class="btn btn-primary" :disabled="refunding" @click="handleRefund">
          <span v-if="refunding" class="spinner spinner-sm spinner-light" />
          确定退票
        </button>
      </template>
    </AppModal>
  </div>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import dayjs from 'dayjs'
import AppIcon from '@/components/AppIcon.vue'
import AppPagination from '@/components/AppPagination.vue'
import AppModal from '@/components/AppModal.vue'
import { myTicketPage, refundTicket } from '@/api'
import { toast } from '@/ui/toast'
import { getWeekNumber } from '@/utils'
import {
  SEAT_CLASS_TYPE_LIST,
  DISCOUNTS_TYPE,
  TICKET_STATUS_LIST,
  TICKET_STATUS_TAG
} from '@/constants'

const state = reactive({
  data: [],
  total: 0,
  size: 10,
  current: 1
})

const loading = ref(false)

const router = useRouter()

const tickets = computed(() => state.data)

const label = (list, value, key = 'value') =>
  list.find((item) => item[key] === value)?.label ?? '--'

// 已支付的车票按乘车日期区分待出行/已出站，其余直接显示真实状态
const statusLabel = (item) => {
  if (item.status === 10) {
    return dayjs(item.ridingDate).isAfter(dayjs()) ? '待出行' : '已出站'
  }
  return label(TICKET_STATUS_LIST, item.status)
}

const statusTag = (item) => {
  if (item.status === 10) {
    return dayjs(item.ridingDate).isAfter(dayjs()) ? 'tag-green' : 'tag-gray'
  }
  return TICKET_STATUS_TAG[item.status] ?? 'tag-gray'
}

/* 改签：开车前及开车后当日 24 点前均可办理；变更到站：仅开车前 48 小时以上 */
const departureDateTime = (item) => dayjs(`${item.ridingDate} ${item.departureTime}`)

const canChange = (item) =>
  item.status === 10 && dayjs().isBefore(departureDateTime(item).startOf('day').add(1, 'day'))

const canChangeArrival = (item) =>
  item.status === 10 && dayjs().isBefore(departureDateTime(item).subtract(48, 'hour'))

const actionTip = (item) => {
  if (item.status === 50) return '该票已办理过改签，每张车票仅可办理一次'
  if (item.status !== 10) return ''
  if (!canChange(item)) return '已超过当日改签办理时间'
  if (!canChangeArrival(item)) return '变更到站需在开车前 48 小时以上办理'
  return ''
}

const goChange = (item, mode) => {
  router.push({
    path: '/changeTicket',
    query: { orderSn: item.orderSn, itemId: item.id, mode }
  })
}

/* 退票 */
const refundOpen = ref(false)
const refunding = ref(false)
const refundTarget = ref(null)

const openRefund = (item) => {
  refundTarget.value = item
  refundOpen.value = true
}

const handleRefund = () => {
  if (!refundTarget.value) return
  refunding.value = true
  refundTicket({
    orderSn: refundTarget.value.orderSn,
    type: 0,
    subOrderRecordIdReqList: [refundTarget.value.id]
  })
    .then((res) => {
      if (res.success) {
        refundOpen.value = false
        toast.success('退票成功')
        loadTickets(state.current, state.size)
      } else {
        toast.error(res.message ?? '退票失败')
      }
    })
    .catch((error) => {
      console.log(error)
      toast.error('退票失败，请稍后重试')
    })
    .finally(() => {
      refunding.value = false
    })
}

const loadTickets = (current, size) => {
  loading.value = true
  myTicketPage({ current, size })
    .then((res) => {
      state.data = res.data?.records ?? []
      state.total = res.data?.total ?? 0
    })
    .catch((error) => {
      console.log(error)
    })
    .finally(() => {
      loading.value = false
    })
}

watch(
  () => [state.current, state.size],
  ([current, size]) => {
    loadTickets(current, size)
  },
  { immediate: true }
)

const handlePage = (page, pageSize) => {
  state.current = page
  state.size = pageSize
}
</script>

<style scoped>
.ticket-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.ticket-card {
  background: var(--surface);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
  overflow: hidden;
}

.ticket-ribbon {
  background: linear-gradient(135deg, #0071e3, #5e5ce6);
  color: #fff;
  font-size: 12.5px;
  font-weight: 600;
  padding: 7px 20px;
}

.ticket-body {
  display: flex;
  align-items: center;
  gap: 22px;
  padding: 18px 22px;
}

.ticket-train {
  flex-shrink: 0;
  width: 90px;
}

.ticket-number {
  font-size: 19px;
  font-weight: 700;
}

.ticket-route {
  flex: 1.6;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  min-width: 220px;
}

.route-point {
  min-width: 80px;
}

.route-point.right {
  text-align: right;
}

.route-city {
  font-size: 17px;
  font-weight: 700;
}

.route-time {
  margin-top: 2px;
  font-size: 12.5px;
  color: var(--text-2);
  font-variant-numeric: tabular-nums;
}

.ticket-seat {
  flex: 1;
}

.seat-name {
  font-weight: 600;
}

.seat-no {
  margin-top: 2px;
  font-size: 12.5px;
  color: var(--text-2);
}

.ticket-type {
  flex: 1;
  text-align: right;
}

.ticket-status {
  flex-shrink: 0;
  width: 72px;
  text-align: right;
}

.ticket-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 22px;
}

.action-tip {
  font-size: 12px;
  color: var(--text-3);
}

.spinner-light {
  border-color: rgba(255, 255, 255, 0.4);
  border-top-color: #fff;
}

.refund-row {
  display: flex;
  justify-content: space-between;
  padding: 7px 0;
  color: var(--text-2);
  font-size: 14px;
}

.refund-row b {
  color: var(--text-1);
}

.refund-tip {
  margin-top: 12px;
  font-size: 12px;
  color: var(--text-3);
  line-height: 1.7;
}

@media (max-width: 900px) {
  .ticket-body {
    flex-wrap: wrap;
    gap: 14px;
  }
}
</style>
