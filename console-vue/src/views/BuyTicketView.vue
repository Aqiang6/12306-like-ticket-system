<template>
  <div>
    <!-- 车次信息 -->
    <div class="card">
      <div class="card-head">
        <div class="card-title">
          列表信息<span class="card-sub">以下余票信息仅供参考</span>
        </div>
      </div>

      <div class="train-summary">
        <span class="summary-date">{{ query.departureDate }}</span>
        <span class="summary-week">（{{ getWeekNumber(dayjs(query.departureDate).day()) }}）</span>
        <span class="summary-strong">{{ query.trainNumber }}</span>次
        <span class="summary-strong">{{ state.currTrain?.departure }}</span>站
        （{{ state.currTrain?.departureTime }}开）
        <AppIcon name="arrow-right" :size="14" />
        <span class="summary-strong">{{ state.currTrain?.arrival }}</span>站
        （{{ state.currTrain?.arrivalTime }}到）
      </div>

      <hr class="divider" />

      <div class="seat-brief">
        <div v-for="item in state.currentSeat" :key="item.type" class="seat-brief-item">
          {{ seatLabel(item.type) }}（<span class="price">￥{{ item.price }}</span>）
          <span class="seat-qty">{{ item.quantity >= 1 ? '有票' : `${item.quantity} 张票` }}</span>
        </div>
      </div>
    </div>

    <!-- 乘客信息 -->
    <div class="card">
      <div class="card-head">
        <div class="card-title">乘客信息</div>
      </div>

      <div class="passenger-pick">
        <div class="pick-title">
          <AppIcon name="users" :size="16" />
          乘车人
        </div>
        <div v-if="state.currPassengerList.length" class="chip-row">
          <button
            v-for="item in state.currPassengerList"
            :key="item.id"
            class="chip"
            :class="{ active: selectedPassengerIds.includes(item.id) }"
            @click="togglePassenger(item.id)"
          >
            {{ item.realName }}
          </button>
        </div>
        <div v-else class="empty" style="padding: 24px">
          <span class="empty-text">还没有乘车人，</span>
          <router-link to="/passenger">去添加乘车人</router-link>
        </div>
      </div>

      <hr class="divider" />

      <div v-if="selectedPassengers.length" class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th>序号</th>
              <th>票种</th>
              <th>席别</th>
              <th>姓名</th>
              <th>证件类型</th>
              <th>证件号码</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(item, index) in selectedPassengers" :key="item.id">
              <td class="num">{{ index + 1 }}</td>
              <td>
                <select v-model.number="item.ticketType" class="select" style="width: 120px">
                  <option v-for="type in TICKET_TYPE_LIST" :key="type.value" :value="type.value">
                    {{ type.label }}
                  </option>
                </select>
              </td>
              <td>
                <select v-model.number="item.seatType" class="select" style="width: 170px">
                  <option v-for="seat in state.currentSeat" :key="seat.type" :value="seat.type">
                    {{ seatLabel(seat.type) }}（￥{{ seat.price }}）
                  </option>
                </select>
              </td>
              <td>{{ item.realName }}</td>
              <td>{{ idCardLabel(item.idType) }}</td>
              <td class="mono">{{ item.idCard }}</td>
              <td>
                <button class="btn btn-danger-text btn-sm" @click="removePassenger(item.id)">
                  <AppIcon name="close" :size="14" />
                  移除
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="row" style="justify-content: center; margin-top: 24px; gap: 14px">
        <button class="btn" @click="router.push('/ticketSearch')">上一步</button>
        <button class="btn btn-orange btn-lg" style="min-width: 180px" @click="handleSubmit">
          提交订单
        </button>
      </div>
    </div>

    <!-- 确认弹窗 -->
    <AppModal v-model="confirmOpen" title="请核对以下信息" width="640px">
      <div class="train-summary">
        <span class="summary-date">{{ query.departureDate }}</span>
        <span class="summary-week">（{{ getWeekNumber(dayjs(query.departureDate).day()) }}）</span>
        <span class="summary-strong">{{ query.trainNumber }}</span>次
        <span class="summary-strong">{{ state.currTrain?.departure }}</span>站
        （{{ state.currTrain?.departureTime }}开）
        <AppIcon name="arrow-right" :size="14" />
        <span class="summary-strong">{{ state.currTrain?.arrival }}</span>站
        （{{ state.currTrain?.arrivalTime }}到）
      </div>

      <div class="table-wrap" style="margin-top: 14px">
        <table class="table">
          <thead>
            <tr>
              <th>序号</th>
              <th>席别</th>
              <th>票种</th>
              <th>姓名</th>
              <th>证件类型</th>
              <th>证件号码</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(item, index) in selectedPassengers" :key="item.id">
              <td class="num">{{ index + 1 }}</td>
              <td>{{ seatLabel(item.seatType) }}</td>
              <td>{{ ticketTypeLabel(item.ticketType) }}</td>
              <td>{{ item.realName }}</td>
              <td>{{ idCardLabel(item.idType) }}</td>
              <td class="mono">{{ item.idCard }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- 选座 -->
      <template v-if="canChooseSeat">
        <hr class="divider" />
        <div class="seat-picker">
          <div class="pick-title" style="color: var(--orange)">
            <AppIcon name="seat" :size="16" />
            在线选座（已选 {{ chosenSeats.length }}/{{ selectedPassengers.length }}）
          </div>
          <div class="seat-rows">
            <div v-for="(row, rowIndex) in seatRows" :key="rowIndex" class="seat-row">
              <span class="seat-side">窗</span>
              <div class="seat-group">
                <button
                  v-for="cell in row.left"
                  :key="cell"
                  class="seat-box"
                  :class="{ chosen: chosenSeats.includes(cell) }"
                  @click="chooseSeat(cell)"
                >
                  {{ cell.slice(0, 1) }}
                </button>
              </div>
              <span class="seat-side aisle">过道</span>
              <div class="seat-group">
                <button
                  v-for="cell in row.right"
                  :key="cell"
                  class="seat-box"
                  :class="{ chosen: chosenSeats.includes(cell) }"
                  @click="chooseSeat(cell)"
                >
                  {{ cell.slice(0, 1) }}
                </button>
              </div>
              <span class="seat-side">窗</span>
            </div>
          </div>
          <p class="pick-tip">*如果本次列车剩余席位无法满足您的选座需求，系统将自动为您分配席位</p>
        </div>
      </template>
      <p v-else class="pick-tip" style="margin-top: 12px">
        *系统将随机为您申请席位，暂不支持自选席位
      </p>

      <template #footer>
        <button class="btn" @click="confirmOpen = false">返回修改</button>
        <button class="btn btn-orange" :disabled="submitting" @click="submitOrder">
          <span v-if="submitting" class="spinner spinner-sm spinner-light" />
          确认
        </button>
      </template>
    </AppModal>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import dayjs from 'dayjs'
import AppIcon from '@/components/AppIcon.vue'
import AppModal from '@/components/AppModal.vue'
import { ticketQuery, getPassengerList, buyTicket as apiBuyTicket } from '@/api'
import { getWeekNumber } from '@/utils'
import {
  SEAT_CLASS_TYPE_LIST,
  TICKET_TYPE_LIST,
  ID_CARD_TYPE
} from '@/constants'
import { getUsername } from '@/stores/auth'
import { toast } from '@/ui/toast'

const route = useRoute()
const router = useRouter()
const query = route.query
const username = getUsername()

const state = reactive({
  currTrain: null,
  currPassengerList: [],
  rawPassengers: [],
  currentSeat: []
})

const selectedPassengerIds = ref([])
const selectedPassengers = computed(() =>
  state.rawPassengers.filter((item) => selectedPassengerIds.value.includes(item.id))
)
const confirmOpen = ref(false)
const submitting = ref(false)
const chosenSeats = ref([])
const seatPosition = ref([])
const seatLeft = ref(3)
const seatNumber = ref(5)
const canChooseSeat = ref(true)

const seatLabel = (code) =>
  SEAT_CLASS_TYPE_LIST.find((item) => item.code === code)?.label ?? '--'
const ticketTypeLabel = (value) =>
  TICKET_TYPE_LIST.find((item) => item.value === value)?.label ?? '--'
const idCardLabel = (value) =>
  ID_CARD_TYPE.find((item) => item.value === value)?.label ?? '--'

onMounted(() => {
  ticketQuery({
    fromStation: query.fromStation,
    toStation: query.toStation,
    departureDate: query.departureDate
  }).then((res) => {
    if (!res.success) {
      toast.error(res.message)
      return
    }
    const train = res.data.trainList.find((item) => item.trainNumber === query.trainNumber)
    if (!train) {
      toast.error('未找到该车次信息')
      return
    }
    state.currTrain = train
    state.currentSeat = train.seatClassList ?? []
  })

  getPassengerList({ username }).then((res) => {
    if (!res.success) return
    const list = res.data ?? []
    state.currPassengerList = list
    state.rawPassengers = list.map((item) => ({
      ...item,
      ticketType: 0,
      seatType: undefined
    }))
  })
})

const togglePassenger = (id) => {
  const index = selectedPassengerIds.value.indexOf(id)
  if (index === -1) {
    selectedPassengerIds.value.push(id)
  } else {
    selectedPassengerIds.value.splice(index, 1)
  }
}

/* 席位布局（商务座 A/C/F、一等座 A/C/D/F、二等座 A/B/C/D/F） */
watch(
  selectedPassengers,
  (passengers) => {
    const firstSeat = passengers[0]?.seatType
    let positions = ['A', 'B', 'C', 'D', 'F']
    let left = 3
    if (firstSeat === 0) {
      positions = ['A', 'C', 'F']
      left = 2
    } else if (firstSeat === 1) {
      positions = ['A', 'C', 'D', 'F']
      left = 2
    }

    const count = Math.max(passengers.length, 1)
    const seatList = new Array(count * positions.length).fill('').map((_, index) => {
      if (index < positions.length - 1) {
        return `${positions[index]}0`
      }
      return `${positions[index % positions.length]}${Math.floor(index / positions.length)}`
    })

    seatPosition.value = seatList
    seatLeft.value = left
    seatNumber.value = positions.length
    chosenSeats.value = []

    canChooseSeat.value =
      passengers.length > 0 &&
      passengers.length <= positions.length &&
      passengers.every((item) => item.seatType === firstSeat)
  },
  { deep: true }
)

/* 初始席位：默认为每位乘客选择第一个有票席别 */
watch(state, (value) => {
  if (value.currentSeat.length && selectedPassengers.value.length) {
    const defaultSeat = value.currentSeat.find((item) => item.quantity)?.type
    selectedPassengers.value.forEach((item) => {
      if (item.seatType === undefined && defaultSeat !== undefined) {
        item.seatType = defaultSeat
      }
    })
  }
}, { deep: true })

const seatRows = computed(() => {
  const rows = []
  for (let rowIndex = 0; rowIndex < Math.max(selectedPassengers.value.length, 1); rowIndex++) {
    const cells = seatPosition.value.slice(
      rowIndex * seatNumber.value,
      (rowIndex + 1) * seatNumber.value
    )
    rows.push({
      left: cells.slice(0, seatLeft.value),
      right: cells.slice(seatLeft.value)
    })
  }
  return rows.filter((row) => row.left.length || row.right.length)
})

const chooseSeat = (code) => {
  const index = chosenSeats.value.indexOf(code)
  if (index !== -1) {
    chosenSeats.value.splice(index, 1)
    return
  }
  if (chosenSeats.value.length >= selectedPassengers.value.length) {
    chosenSeats.value.splice(0, 1)
  }
  chosenSeats.value.push(code)
}

const removePassenger = (id) => {
  const index = selectedPassengerIds.value.indexOf(id)
  if (index !== -1) selectedPassengerIds.value.splice(index, 1)
}

const handleSubmit = () => {
  if (!selectedPassengers.value.length) {
    toast.error('请先选择乘车人')
    return
  }
  if (selectedPassengers.value.some((item) => item.seatType === undefined)) {
    toast.error('请为乘车人选择席别')
    return
  }
  confirmOpen.value = true
}

const submitOrder = () => {
  submitting.value = true
  const passengers = selectedPassengers.value.map((item) => ({
    passengerId: item.id,
    seatType: item.seatType
  }))
  apiBuyTicket({
    trainId: query.trainId,
    passengers,
    chooseSeats: chosenSeats.value,
    departure: state.currTrain?.departure,
    arrival: state.currTrain?.arrival
  })
    .then((res) => {
      if (res.success) {
        toast.success('下单成功，正在跳转至订单')
        setTimeout(() => {
          router.push(`/order?sn=${res.data.orderSn}`)
        }, 500)
      } else {
        toast.error(res.message)
        submitting.value = false
      }
    })
    .catch((error) => {
      console.log(error)
      toast.error('下单失败，请稍后重试')
      submitting.value = false
    })
}
</script>

<style scoped>
.train-summary {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px;
  font-size: 14px;
  color: var(--text-2);
}

.summary-date,
.summary-strong {
  font-size: 16px;
  font-weight: 700;
  color: var(--text-1);
}

.seat-brief {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 24px;
}

.seat-brief-item {
  font-size: 13.5px;
  color: var(--text-1);
}

.seat-qty {
  color: var(--text-2);
  font-size: 12.5px;
}

.brief-tip {
  margin-top: 10px;
  font-size: 12.5px;
  color: var(--accent);
}

.pick-title {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 14px;
  font-weight: 600;
  color: var(--accent);
  margin-bottom: 12px;
}

.passenger-pick {
  padding: 4px 0;
}

/* 选座 */
.seat-picker {
  background: var(--bg);
  border-radius: var(--radius-md);
  padding: 16px 20px;
}

.seat-rows {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.seat-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.seat-side {
  font-size: 11px;
  color: var(--text-3);
}

.seat-group {
  display: flex;
  gap: 8px;
}

.seat-box {
  appearance: none;
  width: 34px;
  height: 32px;
  border: 1px solid var(--border-strong);
  border-radius: 9px 9px 4px 4px;
  background: var(--surface);
  color: var(--text-2);
  font-family: inherit;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.18s var(--ease);
}

.seat-box:hover {
  border-color: var(--accent);
  color: var(--accent);
}

.seat-box.chosen {
  background: var(--accent);
  border-color: var(--accent);
  color: #fff;
  box-shadow: 0 3px 10px rgba(0, 113, 227, 0.4);
}

.pick-tip {
  margin-top: 10px;
  font-size: 12.5px;
  color: var(--text-2);
}

.spinner-light {
  border-color: rgba(255, 255, 255, 0.4);
  border-top-color: #fff;
}
</style>
