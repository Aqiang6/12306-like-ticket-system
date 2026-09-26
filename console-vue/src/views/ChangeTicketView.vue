<template>
  <div>
    <div class="page-head">
      <h1 class="page-title">{{ isArrival ? '变更到站' : '车票改签' }}</h1>
      <p class="page-subtitle">
        {{ isArrival ? '出发站不变，重新选择到达站、乘车日期、车次及席位' : '可变更乘车日期、车次、席别及到达站，开车后当日 24 点前也可办理' }}
      </p>
    </div>

    <div v-if="state.loading" class="spin-wrap" style="min-height: 240px">
      <span class="spinner spinner-lg" />
    </div>

    <template v-else>
      <!-- 原车票信息 -->
      <div class="card" v-if="state.item">
        <div class="card-head">
          <div class="card-title">原车票信息</div>
          <span class="tag" :class="state.item.status === 10 ? 'tag-green' : 'tag-gray'">
            {{ label(TICKET_STATUS_LIST, state.item.status) ?? '--' }}
          </span>
        </div>
        <div class="origin-route">
          <span class="origin-strong">{{ state.order.departure }}</span>站（{{ state.order.departureTime }}开）
          <AppIcon name="arrow-right" :size="14" />
          <span class="origin-strong">{{ state.order.arrival }}</span>站（{{ state.order.arrivalTime }}到）
        </div>
        <div class="origin-meta">
          {{ state.order.ridingDate }}（{{ getWeekNumber(dayjs(state.order.ridingDate).day()) }}） ·
          {{ state.order.trainNumber }}次 · {{ seatLabel(state.item.seatType) }}
          {{ state.item.carriageNumber }}车{{ state.item.seatNumber }}号 · {{ state.item.realName }}
        </div>
        <div class="origin-meta">
          票价 <span class="price">￥{{ (state.item.amount / 100).toFixed(2) }}</span>
        </div>
        <div class="rules-tip">
          <AppIcon name="info" :size="14" />
          每张车票仅可办理一次改签；{{ isArrival ? '变更到站需在开车前 48 小时以上办理' : '开车前不足 48 小时改签至之后日期列车，将按较低票价核收改签费' }}
        </div>
      </div>

      <!-- 改签行程 -->
      <div class="card">
        <div class="card-head">
          <div class="card-title">改签行程</div>
        </div>
        <div class="trip-form">
          <div class="field">
            <span class="field-label">出发站</span>
            <input class="input" :value="state.item?.departure" disabled />
          </div>
          <div class="field">
            <span class="field-label">到达站</span>
            <StationSelect v-model="form.toStation" :stations="stationOptions" placeholder="请选择到达站" />
          </div>
          <div class="field">
            <span class="field-label">乘车日期</span>
            <input type="date" class="input" v-model="form.departureDate" :min="minDate" :max="maxDate" />
          </div>
          <button class="btn btn-primary" :disabled="!canQuery || state.querying" @click="queryTrains">
            <span v-if="state.querying" class="spinner spinner-sm" />
            <AppIcon v-else name="search" :size="15" />
            查询余票
          </button>
        </div>

        <template v-if="state.trains.length">
          <hr class="divider" />
          <div class="train-list">
            <div
              v-for="train in state.trains"
              :key="trainKey(train)"
              class="train-item"
              :class="{ active: selectedTrain && trainKey(selectedTrain) === trainKey(train) }"
              @click="pickTrain(train)"
            >
              <div class="train-line">
                <span class="train-no">{{ train.trainNumber }}</span>
                <span class="train-time">{{ train.departureTime }} → {{ train.arrivalTime }}</span>
                <span class="train-duration">历时 {{ train.duration }}</span>
              </div>
              <div class="seat-chip-row">
                <button
                  v-for="seat in availableSeats(train)"
                  :key="seat.type"
                  class="seat-chip"
                  :class="{
                    active: selectedTrain && trainKey(selectedTrain) === trainKey(train) && selectedSeatType === seat.type
                  }"
                  @click.stop="pickSeat(train, seat)"
                >
                  {{ seatLabel(seat.type) }} ￥{{ seat.price }}
                </button>
              </div>
            </div>
          </div>
        </template>
        <div v-else-if="state.queried" class="empty" style="padding: 32px">
          <AppIcon name="train" :size="40" />
          <span class="empty-text">该日期暂无符合条件的车次，请更换日期或到达站</span>
        </div>
      </div>

      <!-- 费用明细 -->
      <div class="card" v-if="state.preview">
        <div class="card-head">
          <div class="card-title">费用明细</div>
        </div>
        <div class="fee-row">
          <span>原票金额</span>
          <span>￥{{ fee(state.preview.oldAmount) }}</span>
        </div>
        <div class="fee-row">
          <span>新票金额</span>
          <span>￥{{ fee(state.preview.newAmount) }}</span>
        </div>
        <div class="fee-row">
          <span>{{ isArrival ? '差额手续费（按退票费标准）' : '改签费' }}</span>
          <span>￥{{ fee(state.preview.changeFee) }}</span>
        </div>
        <div class="fee-row">
          <span>原票应退金额</span>
          <span class="text-accent" style="font-weight: 700">￥{{ fee(state.preview.refundAmount) }}</span>
        </div>
        <div class="fee-rule">
          <AppIcon name="info" :size="14" />
          {{ state.preview.ruleDesc }}
        </div>
        <p class="settle-tip">
          提交后原票款将按上述金额原路退回，新车票生成待支付订单，请尽快完成支付（多退少补）。
        </p>
        <div class="row" style="justify-content: flex-end; gap: 12px; margin-top: 16px">
          <button class="btn" @click="resetSelection">重新选择</button>
          <button class="btn btn-orange" :disabled="state.submitting" @click="submitChange">
            <span v-if="state.submitting" class="spinner spinner-sm spinner-light" />
            确认改签
          </button>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import dayjs from 'dayjs'
import AppIcon from '@/components/AppIcon.vue'
import StationSelect from '@/components/StationSelect.vue'
import { orderBySn, stationAll, ticketQuery, changeTicketPreview, changeTicket } from '@/api'
import { getWeekNumber } from '@/utils'
import { SEAT_CLASS_TYPE_LIST, TICKET_STATUS_LIST } from '@/constants'
import { toast } from '@/ui/toast'

const route = useRoute()
const router = useRouter()

const isArrival = route.query.mode === 'arrival'
const orderSn = route.query.orderSn
const itemId = route.query.itemId

const state = reactive({
  loading: true,
  order: null,
  item: null,
  stationList: [],
  trains: [],
  queried: false,
  querying: false,
  preview: null,
  submitting: false
})

const form = reactive({
  toStation: '',
  departureDate: dayjs().format('YYYY-MM-DD')
})

const selectedTrain = ref(null)
const selectedSeatType = ref(null)

const minDate = dayjs().format('YYYY-MM-DD')
const maxDate = dayjs().add(14, 'day').format('YYYY-MM-DD')

const label = (list, value, key = 'value') =>
  list.find((item) => item[key] === value)?.label ?? '--'

const seatLabel = (code) =>
  SEAT_CLASS_TYPE_LIST.find((item) => item.code === code)?.label ?? '--'

const fee = (cents) => ((cents ?? 0) / 100).toFixed(2)

const trainKey = (train) => `${train.trainId}_${train.departure}_${train.arrival}`

const availableSeats = (train) => (train.seatClassList ?? []).filter((seat) => seat.quantity)

const stationOptions = computed(() => state.stationList)

/* 车票查询接口按车站编码查询，订单里存的是车站名称，这里做一次转换 */
const stationCodeByName = (name) =>
  state.stationList.find((station) => station.name === name)?.code ?? name

const stationNameByCode = (code) =>
  state.stationList.find((station) => station.code === code)?.name ?? code

const canQuery = computed(() => {
  if (!form.toStation || !form.departureDate) return false
  if (isArrival && state.item && form.toStation === stationCodeByName(state.order.arrival)) return false
  return true
})

onMounted(() => {
  if (!orderSn || !itemId) {
    toast.error('缺少改签参数')
    router.replace('/personalTicket')
    return
  }
  orderBySn({ orderSn }).then((res) => {
    if (!res.success) {
      toast.error(res.message)
      state.loading = false
      return
    }
    state.order = res.data
    state.item = res.data?.passengerDetails?.find((item) => item.id === itemId)
    if (!state.item) {
      toast.error('未找到原车票信息')
      router.replace('/personalTicket')
      return
    }
    // 改签模式默认到达站为原票到达站（转为编码），可修改；变更到站必须选择不同到达站
    if (!isArrival) form.toStation = stationCodeByName(state.order.arrival)
    state.loading = false
  })

  stationAll()
    .then((res) => {
      if (res.success) state.stationList = res.data ?? []
    })
    .catch((error) => console.log(error))
})

const queryTrains = () => {
  state.querying = true
  resetSelection()
  ticketQuery({
    fromStation: stationCodeByName(state.order.departure),
    toStation: stationCodeByName(form.toStation),
    departureDate: form.departureDate
  })
    .then((res) => {
      if (!res.success) {
        toast.error(res.message)
        return
      }
      const arrivalName = stationNameByCode(form.toStation)
      state.trains = (res.data?.trainList ?? []).filter(
        (train) => train.departure === state.order.departure && train.arrival === arrivalName
      )
      state.queried = true
    })
    .catch((error) => {
      console.log(error)
      toast.error('余票查询失败，请稍后重试')
    })
    .finally(() => {
      state.querying = false
    })
}

const pickTrain = (train) => {
  if (!availableSeats(train).length) {
    toast.error('该车次暂无可预订席别')
    return
  }
  selectedTrain.value = train
  selectedSeatType.value = null
  state.preview = null
}

const pickSeat = (train, seat) => {
  selectedTrain.value = train
  selectedSeatType.value = seat.type
  doPreview()
}

const resetSelection = () => {
  selectedTrain.value = null
  selectedSeatType.value = null
  state.preview = null
}

const buildRequest = () => ({
  orderSn,
  changeType: isArrival ? 1 : 0,
  passengers: [{ orderItemRecordId: itemId, seatType: selectedSeatType.value }],
  newTrainId: selectedTrain.value.trainId,
  newDeparture: selectedTrain.value.departure,
  newArrival: selectedTrain.value.arrival
})

const doPreview = () => {
  state.preview = null
  changeTicketPreview(buildRequest())
    .then((res) => {
      if (!res.success) {
        toast.error(res.message)
        return
      }
      state.preview = res.data
    })
    .catch((error) => {
      console.log(error)
      toast.error('改签费用计算失败，请稍后重试')
    })
}

const submitChange = () => {
  state.submitting = true
  changeTicket(buildRequest())
    .then((res) => {
      if (!res.success) {
        toast.error(res.message)
        state.submitting = false
        return
      }
      toast.success('改签成功，请完成新车票支付')
      setTimeout(() => {
        router.push(`/order?sn=${res.data.newOrderSn}`)
      }, 500)
    })
    .catch((error) => {
      console.log(error)
      toast.error('改签失败，请稍后重试')
      state.submitting = false
    })
}
</script>

<style scoped>
.origin-route {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  font-size: 14px;
  color: var(--text-2);
}

.origin-strong {
  font-size: 17px;
  font-weight: 700;
  color: var(--text-1);
}

.origin-meta {
  margin-top: 6px;
  font-size: 13px;
  color: var(--text-2);
}

.rules-tip {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 14px;
  padding: 10px 14px;
  border-radius: var(--radius-md);
  background: var(--bg);
  font-size: 12.5px;
  color: var(--text-2);
}

.trip-form {
  display: flex;
  align-items: flex-end;
  flex-wrap: wrap;
  gap: 14px;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.field-label {
  font-size: 12px;
  color: var(--text-3);
}

.form-tip {
  margin-top: 10px;
  font-size: 12.5px;
  color: var(--accent);
}

.train-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.train-item {
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  padding: 14px 18px;
  cursor: pointer;
  transition: all 0.18s var(--ease);
}

.train-item:hover {
  border-color: var(--accent);
}

.train-item.active {
  border-color: var(--accent);
  box-shadow: 0 0 0 3px rgba(0, 113, 227, 0.12);
}

.train-line {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 14px;
}

.train-no {
  font-size: 16px;
  font-weight: 700;
}

.train-time {
  font-variant-numeric: tabular-nums;
  color: var(--text-1);
  font-weight: 600;
}

.train-duration {
  font-size: 12.5px;
  color: var(--text-2);
}

.seat-chip-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 12px;
}

.seat-chip {
  appearance: none;
  font-family: inherit;
  font-size: 12.5px;
  padding: 6px 14px;
  border-radius: 999px;
  border: 1px solid var(--border-strong);
  background: var(--surface);
  color: var(--text-2);
  cursor: pointer;
  transition: all 0.18s var(--ease);
}

.seat-chip:hover {
  border-color: var(--accent);
  color: var(--accent);
}

.seat-chip.active {
  background: var(--accent);
  border-color: var(--accent);
  color: #fff;
  box-shadow: 0 3px 10px rgba(0, 113, 227, 0.35);
}

.fee-row {
  display: flex;
  justify-content: space-between;
  padding: 8px 0;
  color: var(--text-2);
  font-size: 13.5px;
}

.fee-rule {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 8px;
  padding: 10px 14px;
  border-radius: var(--radius-md);
  background: var(--bg);
  font-size: 12.5px;
  color: var(--text-2);
}

.settle-tip {
  margin-top: 12px;
  font-size: 12.5px;
  color: var(--text-3);
  line-height: 1.8;
}

.spinner-light {
  border-color: rgba(255, 255, 255, 0.4);
  border-top-color: #fff;
}
</style>
