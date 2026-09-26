<template>
  <div>
    <div class="row-between" style="margin-bottom: 16px">
      <div class="segmented">
        <button
          v-for="tab in tabs"
          :key="tab.value"
          class="segmented-item"
          :class="{ active: state.activeKey === tab.value }"
          @click="state.activeKey = tab.value"
        >
          {{ tab.label }}
        </button>
      </div>
      <button class="btn btn-ghost" @click="refresh">
        <AppIcon name="refresh" :size="15" />
        刷新
      </button>
    </div>

    <div class="card" style="padding: 8px 20px 20px">
      <div v-if="state.loading" class="spin-wrap" style="min-height: 240px">
        <span class="spinner spinner-lg" />
      </div>

      <template v-else>
        <div v-if="state.rows.length" class="table-wrap">
          <table class="table order-table">
            <thead>
              <tr>
                <th style="min-width: 250px">车次信息</th>
                <th>旅客信息</th>
                <th>席位信息</th>
                <th>票价</th>
                <th>车票状态</th>
                <th v-if="state.activeKey === 0">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(row, index) in state.rows" :key="`${row.orderSn}-${row.id}-${index}`">
                <td v-if="row.rowSpan" :rowspan="row.rowSpan" class="order-info-cell">
                  <div class="order-date">订票日期：{{ row.orderTime }}</div>
                  <div class="order-route">
                    {{ row.departure }} → {{ row.arrival }} {{ row.trainNumber }}
                  </div>
                  <div class="order-ride">
                    {{ row.ridingDate }} {{ row.departureTime }} 开
                  </div>
                </td>
                <td>
                  <div>{{ row.realName }}</div>
                  <div class="text-secondary" style="font-size: 12px">
                    {{ label(ID_CARD_TYPE, row.idType) }}
                  </div>
                </td>
                <td>
                  <div>{{ label(SEAT_CLASS_TYPE_LIST, row.seatType, 'code') }}</div>
                  <div class="text-secondary" style="font-size: 12px">
                    {{ row.carriageNumber }}车 {{ row.seatNumber }}号
                  </div>
                </td>
                <td>
                  <div>{{ label(TICKET_TYPE_LIST, row.ticketType) }}</div>
                  <div class="price">￥{{ (row.amount ?? 0) / 100 }}</div>
                </td>
                <td>
                  <span class="tag" :class="TICKET_STATUS_TAG[row.status] ?? 'tag-gray'">
                    {{ label(TICKET_STATUS_LIST, row.status) ?? '--' }}
                  </span>
                  <div v-if="row.status === 10" style="margin-top: 6px">
                    <button class="btn btn-text btn-sm" @click="goChange(row, 'change')">改签</button>
                    <button
                      class="btn btn-text btn-sm"
                      :disabled="!canChangeArrival(row)"
                      :title="canChangeArrival(row) ? '' : '变更到站需在开车前 48 小时以上办理'"
                      @click="goChange(row, 'arrival')"
                    >
                      变更车站
                    </button>
                    <button class="btn btn-text btn-sm" @click="openRefund(row)">退票</button>
                  </div>
                </td>
                <td v-if="state.activeKey === 0 && row.rowSpan" :rowspan="row.rowSpan">
                  <div class="row" style="flex-direction: column; align-items: stretch; gap: 6px">
                    <button class="btn btn-sm" @click="cancelOrder(row.orderSn)">取消订单</button>
                    <button class="btn btn-primary btn-sm" @click="payOrder(row.orderSn)">去支付</button>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <div v-else class="empty">
          <AppIcon name="list" :size="44" />
          <span class="empty-text">暂无相关订单</span>
        </div>

        <AppPagination
          :current="state.current"
          :page-size="state.size"
          :total="state.total"
          @change="handlePage"
        />
      </template>
    </div>

    <div class="notice" style="margin-top: 16px">
      <div>
        <p>1. 席位已锁定，请在指定时间内完成网上支付，逾期未支付，系统将取消本次交易。</p>
        <p>2. 在完成支付或取消订单之前，您将无法购买其他车票。</p>
        <p>3. 未尽事宜详见《国铁集团铁路旅客运输规程》等有关规定和车站公告。</p>
      </div>
    </div>

    <!-- 退票弹窗 -->
    <AppModal v-model="refundOpen" title="退票申请" width="480px">
      <div class="refund-section">
        <span class="field-label">请选择要退票的乘车人：</span>
        <div class="chip-row" style="margin-top: 8px">
          <button
            v-for="passenger in refundPassengers"
            :key="passenger.id"
            class="chip"
            :class="{ active: refundIds.includes(passenger.id) }"
            @click="toggleRefund(passenger.id)"
          >
            {{ passenger.realName }}
          </button>
        </div>
      </div>

      <hr class="divider" />

      <div class="refund-row">
        <span>车票票价：</span>
        <span class="text-accent" style="font-weight: 600">￥{{ refundTotal / 100 }}</span>
      </div>
      <div class="refund-row">
        <span>应退票款：</span>
        <span class="text-accent" style="font-weight: 700">￥{{ refundTotal / 100 }}</span>
      </div>

      <div class="refund-tip">
        <p>实际核收退票费及应退票款将按最终交易时间计算。</p>
        <p>如需办理该次列车前续、后续退票业务，请于退票车次票面开车时间前办理。</p>
      </div>

      <template #footer>
        <button class="btn" @click="refundOpen = false">取消</button>
        <button class="btn btn-primary" :disabled="!refundIds.length" @click="handleRefund">
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
import { ticketPage, cancelTicket, refundTicket } from '@/api'
import {
  ID_CARD_TYPE,
  SEAT_CLASS_TYPE_LIST,
  TICKET_TYPE_LIST,
  TICKET_STATUS_LIST,
  TICKET_STATUS_TAG
} from '@/constants'
import { getUserId } from '@/stores/auth'
import { toast } from '@/ui/toast'

const router = useRouter()
const userId = getUserId()

const tabs = [
  { label: '未完成订单', value: 0 },
  { label: '历史订单', value: 2 }
]

const state = reactive({
  activeKey: 0,
  rows: [],
  records: [],
  total: 0,
  current: 1,
  size: 10,
  loading: false
})

const refundOpen = ref(false)
const currentOrder = ref(null)
const refundIds = ref([])

const label = (list, value, key = 'value') =>
  list.find((item) => item[key] === value)?.label ?? '--'

const getTicketList = (current, size, statusType) => {
  state.loading = true
  ticketPage({ userId, current, size, statusType })
    .then((res) => {
      const records = res.data?.records ?? []
      state.records = records
      state.total = res.data?.total ?? 0
      state.rows = []
      records.forEach((info) => {
        info.passengerDetails?.forEach((item, index) => {
          state.rows.push({
            ...info,
            ...item,
            rowSpan: index === 0 ? info.passengerDetails.length : 0
          })
        })
      })
    })
    .catch((error) => {
      console.log(error)
      toast.error('订单加载失败')
    })
    .finally(() => {
      state.loading = false
    })
}

watch(
  () => [state.activeKey, state.current, state.size],
  ([statusType, current, size]) => {
    getTicketList(current, size, statusType)
  },
  { immediate: true }
)

const handlePage = (page, pageSize) => {
  state.current = page
  state.size = pageSize
}

const refresh = () => getTicketList(state.current, state.size, state.activeKey)

const cancelOrder = (sn) => {
  cancelTicket({ orderSn: sn }).then((res) => {
    if (res.success) {
      toast.success('订单取消成功')
      refresh()
    } else {
      toast.error(res.message)
    }
  })
}

const payOrder = (sn) => {
  router.push(`/order?sn=${sn}`)
}

/* 改签：开车前及开车后当日 24 点前均可办理；变更到站：仅开车前 48 小时以上 */
const departureDateTime = (row) => dayjs(`${row.ridingDate} ${row.departureTime}`)

const canChangeArrival = (row) =>
  row.status === 10 && dayjs().isBefore(departureDateTime(row).subtract(48, 'hour'))

const goChange = (row, mode) => {
  router.push({
    path: '/changeTicket',
    query: { orderSn: row.orderSn, itemId: row.id, mode }
  })
}

/* 退票 */
const refundPassengers = computed(() => {
  const order = state.records.find((item) => item.orderSn === currentOrder.value)
  return order?.passengerDetails ?? []
})

const refundTotal = computed(() =>
  refundPassengers.value
    .filter((item) => refundIds.value.includes(item.id))
    .reduce((sum, item) => sum + (item.amount ?? 0), 0)
)

const openRefund = (row) => {
  currentOrder.value = row.orderSn
  refundIds.value = []
  refundOpen.value = true
}

const toggleRefund = (id) => {
  const index = refundIds.value.indexOf(id)
  if (index === -1) refundIds.value.push(id)
  else refundIds.value.splice(index, 1)
}

const handleRefund = () => {
  refundTicket({
    orderSn: currentOrder.value,
    type: 0,
    subOrderRecordIdReqList: refundIds.value
  })
    .then((res) => {
      if (res.success) {
        refundOpen.value = false
        toast.success('退票成功')
        refresh()
      } else {
        toast.error(res.message)
      }
    })
    .catch((error) => {
      console.log(error)
      toast.error('退票失败，请稍后重试')
    })
}
</script>

<style scoped>
.order-table td {
  vertical-align: top;
}

.order-info-cell {
  background: rgba(0, 0, 0, 0.015);
}

.order-date {
  font-size: 12px;
  color: var(--text-3);
  margin-bottom: 6px;
}

.order-route {
  font-size: 15px;
  font-weight: 700;
}

.order-ride {
  margin-top: 4px;
  font-size: 13px;
  color: var(--text-2);
  font-variant-numeric: tabular-nums;
}

/* 退票 */
.refund-row {
  display: flex;
  justify-content: space-between;
  padding: 6px 0;
  color: var(--text-2);
}

.refund-tip {
  margin-top: 12px;
  font-size: 12px;
  color: var(--text-3);
  line-height: 1.8;
}
</style>
