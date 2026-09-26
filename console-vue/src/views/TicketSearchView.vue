<template>
  <div>
    <!-- 查询卡片 -->
    <div class="card search-card">
      <div class="search-row">
        <div class="search-field">
          <span class="search-label">出发地</span>
          <StationSelect v-model="form.fromStation" :stations="stationOptions" />
        </div>
        <button class="swap-btn" title="交换出发地与目的地" @click="swapStation">
          <AppIcon name="swap" :size="18" />
        </button>
        <div class="search-field">
          <span class="search-label">目的地</span>
          <StationSelect v-model="form.toStation" :stations="stationOptions" />
        </div>
        <div class="search-field">
          <span class="search-label">出发日期</span>
          <input
            v-model="form.departureDate"
            type="date"
            class="input"
            :min="today"
            @change="search"
          />
        </div>
        <button class="btn btn-primary btn-lg search-btn" :disabled="searching" @click="search">
          <span v-if="searching" class="spinner spinner-sm spinner-light" />
          <AppIcon v-else name="search" :size="16" />
          查询
        </button>
      </div>

      <!-- 15 天日期条 -->
      <div class="date-strip">
        <button
          v-for="day in days"
          :key="day.value"
          class="date-pill"
          :class="{ active: day.value === form.departureDate }"
          @click="pickDate(day.value)"
        >
          <span class="date-day">{{ day.label }}</span>
          <span class="date-week">{{ day.week }}</span>
        </button>
      </div>

      <!-- 快捷筛选 + 更多筛选入口 -->
      <div class="quick-row">
        <div class="quick-chips">
          <button
            class="chip"
            :class="{ active: quickHighspeed }"
            @click="toggleQuickBrand([0, 1, 6, 7])"
          >
            只看高铁/动车
          </button>
          <button
            class="chip"
            :class="{ active: quickPrspeed }"
            @click="toggleQuickBrand([2, 3, 4, 5])"
          >
            只看普速
          </button>
          <button
            class="chip"
            :class="{ active: form.onlyTicket }"
            @click="form.onlyTicket = !form.onlyTicket"
          >
            只看有票
          </button>
        </div>
        <button class="more-filter-btn" @click="filterOpen = !filterOpen">
          <AppIcon name="filter" :size="14" />
          更多筛选
          <span v-if="activeFilterCount" class="filter-count">{{ activeFilterCount }}</span>
          <AppIcon :name="filterOpen ? 'chevron-down' : 'chevron-right'" :size="13" />
        </button>
      </div>

      <!-- 可展开的详细筛选 -->
      <div v-show="filterOpen" class="filter-groups">
        <div class="filter-group">
          <span class="filter-label">车次类型</span>
          <div class="chip-row">
            <button
              class="chip chip-all"
              :class="{ active: isAllSelected(form.carType) }"
              @click="toggleAll('carType', brandCodes)"
            >
              全部
            </button>
            <button
              v-for="brand in activeBrands"
              :key="brand.code"
              class="chip"
              :class="{ active: form.carType.includes(brand.code) }"
              @click="toggleItem('carType', brand.code)"
            >
              {{ brand.label }}
            </button>
          </div>
        </div>
        <div class="filter-group">
          <span class="filter-label">出发车站</span>
          <div class="chip-row">
            <button
              class="chip chip-all"
              :class="{ active: isAllSelected(form.departure) }"
              @click="toggleAll('departure', departureStations)"
            >
              全部
            </button>
            <button
              v-for="station in departureStations"
              :key="station"
              class="chip"
              :class="{ active: form.departure.includes(station) }"
              @click="toggleItem('departure', station)"
            >
              {{ station }}
            </button>
          </div>
        </div>
        <div class="filter-group">
          <span class="filter-label">到达车站</span>
          <div class="chip-row">
            <button
              class="chip chip-all"
              :class="{ active: isAllSelected(form.arrival) }"
              @click="toggleAll('arrival', arrivalStations)"
            >
              全部
            </button>
            <button
              v-for="station in arrivalStations"
              :key="station"
              class="chip"
              :class="{ active: form.arrival.includes(station) }"
              @click="toggleItem('arrival', station)"
            >
              {{ station }}
            </button>
          </div>
        </div>
        <div class="filter-group">
          <span class="filter-label">车次席别</span>
          <div class="chip-row">
            <button
              class="chip chip-all"
              :class="{ active: isAllSelected(form.seat) }"
              @click="toggleAll('seat', seatCodes)"
            >
              全部
            </button>
            <button
              v-for="seat in activeSeatTypes"
              :key="seat.code"
              class="chip"
              :class="{ active: form.seat.includes(seat.code) }"
              @click="toggleItem('seat', seat.code)"
            >
              {{ seat.label }}
            </button>
          </div>
        </div>
        <div class="filter-group">
          <span class="filter-label">出发时间</span>
          <div class="chip-row">
            <button
              v-for="range in timeRanges"
              :key="`d-${range.value}`"
              class="chip"
              :class="{ active: form.departureRanges.includes(range.value) }"
              @click="toggleItem('departureRanges', range.value)"
            >
              {{ range.label }}
            </button>
          </div>
        </div>
        <div class="filter-group">
          <span class="filter-label">到达时间</span>
          <div class="chip-row">
            <button
              v-for="range in timeRanges"
              :key="`a-${range.value}`"
              class="chip"
              :class="{ active: form.arrivalRanges.includes(range.value) }"
              @click="toggleItem('arrivalRanges', range.value)"
            >
              {{ range.label }}
            </button>
          </div>
        </div>
      </div>
    </div>

    <!-- 待出行订单 -->
    <div v-if="loggedIn && upcomingOrders.length" class="card upcoming-card">
      <div class="card-head" style="margin-bottom: 12px">
        <div class="card-title">待出行的车票订单</div>
        <router-link to="/ticketList" class="btn btn-text btn-sm">查看全部订单</router-link>
      </div>

      <div class="car-wrap">
        <button
          v-if="upcomingOrders.length > 1"
          class="car-arrow"
          :disabled="upcomingIndex === 0"
          aria-label="上一张"
          @click="upcomingIndex--"
        >
          <AppIcon name="chevron-left" :size="16" />
        </button>

        <div
          class="car-viewport"
          @touchstart.passive="onTouchStart"
          @touchend.passive="onTouchEnd"
          @pointerdown="onPointerDown"
          @pointerup="onPointerUp"
        >
          <div
            class="car-track"
            :style="{ transform: `translateX(-${upcomingIndex * 100}%)` }"
          >
            <div
              v-for="order in upcomingOrders"
              :key="`${order.orderSn}-${order.firstSeatType}-${order.firstCarriageNumber}-${order.firstSeatNumber}`"
              class="car-slide"
              @click="goOrder(order)"
            >
              <div class="slide-top">
                <span class="tag" :class="order.orderStatus === 0 ? 'tag-orange' : 'tag-green'">
                  {{ order.orderStatus === 0 ? '待支付' : '已支付' }}
                </span>
                <span class="slide-date mono">
                  {{ order.ridingDate }} {{ getWeekNumber(dayjs(order.ridingDate).day()) }}
                </span>
                <span class="slide-train">{{ order.trainNumber }}</span>
              </div>
              <div class="slide-route">
                <div class="slide-point">
                  <div class="slide-time mono">{{ order.departureTime }}</div>
                  <div class="slide-city">{{ order.departure }}</div>
                </div>
                <div class="slide-mid">
                  <div class="slide-duration mono" v-if="order.duration">{{ order.duration }}</div>
                  <div class="slide-line" />
                </div>
                <div class="slide-point right">
                  <div class="slide-time mono">{{ order.arrivalTime }}</div>
                  <div class="slide-city">{{ order.arrival }}</div>
                </div>
              </div>
              <div class="slide-bottom">
                <span class="text-secondary">
                  {{ seatLabel(order.firstSeatType) }}
                  <template v-if="order.firstCarriageNumber">
                    · {{ order.firstCarriageNumber }}车{{ order.firstSeatNumber }}号
                  </template>
                  <template v-if="order.passengers?.length">
                    · {{ order.passengers.join('、') }}
                  </template>
                </span>
                <span class="slide-amount">
                  合计 <b class="price">￥{{ (order.totalAmount / 100).toFixed(2) }}</b>
                </span>
              </div>
            </div>
          </div>
        </div>

        <button
          v-if="upcomingOrders.length > 1"
          class="car-arrow"
          :disabled="upcomingIndex >= upcomingOrders.length - 1"
          aria-label="下一张"
          @click="upcomingIndex++"
        >
          <AppIcon name="chevron-right" :size="16" />
        </button>
      </div>

      <div v-if="upcomingOrders.length > 1" class="car-dots">
        <span
          v-for="(order, i) in upcomingOrders"
          :key="order.orderSn"
          class="car-dot"
          :class="{ active: i === upcomingIndex }"
          @click="upcomingIndex = i"
        />
      </div>
    </div>

    <!-- 结果标题 -->
    <div class="result-head">
      <div class="result-title">
        <span class="result-city">{{ stationName(form.fromStation) }}</span>
        <AppIcon name="arrow-right" :size="16" />
        <span class="result-city">{{ stationName(form.toStation) }}</span>
        <span class="result-date">{{ dateLabel }}</span>
      </div>
      <span class="result-count">
        共 <b>{{ filteredTrainList.length }}</b> 个车次
      </span>
    </div>

    <!-- 车次列表 -->
    <div v-if="pageLoading" class="spin-wrap" style="min-height: 320px">
      <span class="spinner spinner-lg" />
      正在加载车票信息...
    </div>

    <template v-else>
      <div v-if="filteredTrainList.length" class="train-list">
        <div v-for="train in filteredTrainList" :key="train.trainId" class="card train-card">
          <div class="train-main">
            <div class="train-left">
              <div class="train-number" @click="toggleStations(train)">
                {{ train.trainNumber }}
                <AppIcon
                  :name="expandedId === train.trainId ? 'chevron-down' : 'chevron-right'"
                  :size="13"
                />
              </div>
              <div v-if="train.trainTags?.length" class="row" style="gap: 4px; margin-top: 4px">
                <span
                  v-for="tag in train.trainTags"
                  :key="tag"
                  class="train-tag"
                  :style="tagStyle(tag)"
                >
                  {{ tagLabel(tag) }}
                </span>
              </div>
            </div>

            <div class="train-route">
              <div class="route-point">
                <div class="route-time">{{ train.departureTime }}</div>
                <div class="route-city">
                  <span class="route-flag depart">{{ train.departureFlag ? '始' : '过' }}</span>
                  {{ train.departure }}
                </div>
              </div>
              <div class="route-middle">
                <div class="route-duration">{{ train.duration }}</div>
                <div class="route-line" />
              </div>
              <div class="route-point right">
                <div class="route-time">{{ train.arrivalTime }}</div>
                <div class="route-city">
                  <span class="route-flag arrive">{{ train.arrivalFlag ? '终' : '过' }}</span>
                  {{ train.arrival }}
                </div>
              </div>
            </div>

            <div class="train-seats">
              <div v-for="seat in seatCells(train.seatClassList)" :key="seat.type" class="seat-cell">
                <span class="seat-name">{{ seat.label }}</span>
                <span v-if="seat.price" class="seat-price">￥{{ seat.price }}</span>
                <span class="seat-count" :class="getTicketNumber(seat.quantity).color">
                  {{ getTicketNumber(seat.quantity).label }}
                </span>
              </div>
            </div>

            <div class="train-action">
              <button
                v-if="hasTicket(train)"
                class="btn btn-primary"
                @click="book(train)"
              >
                购买
              </button>
              <button v-else class="btn soldout-btn" disabled>无票</button>
            </div>
          </div>

          <!-- 经停站 -->
          <div v-if="expandedId === train.trainId" class="train-stations">
            <div v-if="stationLoading" class="spin-wrap" style="padding: 20px 0">
              <span class="spinner" /> 加载经停信息...
            </div>
            <table v-else class="table" style="max-width: 720px">
              <thead>
                <tr>
                  <th>站序</th>
                  <th>站名</th>
                  <th>到站时间</th>
                  <th>出发时间</th>
                  <th>停留时间</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="station in stationList" :key="station.sequence">
                  <td class="num">{{ station.sequence }}</td>
                  <td>{{ station.departure }}</td>
                  <td class="num">{{ station.arrivalTime }}</td>
                  <td class="num">{{ station.departureTime }}</td>
                  <td class="num">{{ station.stopoverTime }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </div>

      <div v-else class="card">
        <div class="empty">
          <AppIcon name="train" :size="44" />
          <span class="empty-text">暂无符合条件的车次，试试调整筛选条件或更换日期</span>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import dayjs from 'dayjs'
import { useRouter } from 'vue-router'
import AppIcon from '@/components/AppIcon.vue'
import StationSelect from '@/components/StationSelect.vue'
import { ticketQuery, stationAll, trainStationQuery, myTicketPage } from '@/api'
import { getWeekNumber, getTicketNumber, findSeatType } from '@/utils'
import { SEAT_CLASS_TYPE_LIST, TRAIN_BRAND_LIST, TRAIN_TAG } from '@/constants'
import { getUsername, isLoggedIn } from '@/stores/auth'
import { toast } from '@/ui/toast'

const router = useRouter()

const today = dayjs().format('YYYY-MM-DD')

const HIGH_SPEED_CODES = [0, 1, 6, 7]
const PRSPEED_CODES = [2, 3, 4, 5]

const form = reactive({
  fromStation: 'BJP',
  toStation: 'HZH',
  departureDate: today,
  carType: [],
  departure: [],
  arrival: [],
  seat: [],
  departureRanges: [],
  arrivalRanges: [],
  onlyTicket: false
})

const state = reactive({
  stationList: [],
  trainList: [],
  rawTrainList: [],
  brandList: [],
  seatTypeList: [],
  departureStations: [],
  arrivalStations: []
})

const pageLoading = ref(false)
const searching = ref(false)
const filterOpen = ref(false)

const expandedId = ref(null)
const stationLoading = ref(false)
const stationList = ref([])

const upcomingOrders = ref([])

const days = Array.from({ length: 7 }, (_, index) => {
  const day = dayjs().add(index, 'day')
  return {
    value: day.format('YYYY-MM-DD'),
    label: index === 0 ? '今天' : index === 1 ? '明天' : day.format('MM-DD'),
    week: getWeekNumber(day.day())
  }
})

// 时间段（分钟）：凌晨/上午/下午/晚上
const timeRanges = [
  { value: 1, label: '00:00-06:00', min: 0, max: 360 },
  { value: 2, label: '06:00-12:00', min: 360, max: 720 },
  { value: 3, label: '12:00-18:00', min: 720, max: 1080 },
  { value: 4, label: '18:00-24:00', min: 1080, max: 1440 }
]

const stationOptions = computed(() => state.stationList)
const activeBrands = computed(() =>
  TRAIN_BRAND_LIST.filter((brand) => state.brandList.includes(brand.code))
)
const activeSeatTypes = computed(() =>
  SEAT_CLASS_TYPE_LIST.filter((seat) => state.seatTypeList.includes(seat.code))
)
const brandCodes = computed(() => activeBrands.value.map((item) => item.code))
const seatCodes = computed(() => activeSeatTypes.value.map((item) => item.code))
const departureStations = computed(() => state.departureStations)
const arrivalStations = computed(() => state.arrivalStations)

const stationName = (code) =>
  state.stationList.find((item) => item.code === code)?.name ?? code

const dateLabel = computed(() => {
  const day = dayjs(form.departureDate)
  return `${day.format('MM月DD日')} ${getWeekNumber(day.day())}`
})

const loggedIn = computed(() => isLoggedIn())

/* 快捷筛选状态 */
const sameSet = (list, codes) =>
  list.length === codes.length && codes.every((code) => list.includes(code))

const quickHighspeed = computed(() => sameSet(form.carType, HIGH_SPEED_CODES))
const quickPrspeed = computed(() => sameSet(form.carType, PRSPEED_CODES))

const toggleQuickBrand = (codes) => {
  form.carType = sameSet(form.carType, codes) ? [] : [...codes]
}

const activeFilterCount = computed(
  () =>
    form.carType.length +
    form.departure.length +
    form.arrival.length +
    form.seat.length +
    form.departureRanges.length +
    form.arrivalRanges.length +
    (form.onlyTicket ? 1 : 0)
)

const tagLabel = (value) => TRAIN_TAG.find((item) => item.value === value)?.label ?? value
const tagStyle = (value) => {
  const color = TRAIN_TAG.find((item) => item.value === value)?.color ?? 'var(--text-3)'
  return { color, borderColor: color }
}

const toMinutes = (time) => {
  const [h, m] = String(time ?? '').split(':').map(Number)
  if (Number.isNaN(h) || Number.isNaN(m)) return -1
  return h * 60 + m
}

const matchRanges = (time, ranges) => {
  if (!ranges.length) return true
  const minutes = toMinutes(time)
  if (minutes < 0) return false
  return timeRanges
    .filter((range) => ranges.includes(range.value))
    .some((range) => minutes >= range.min && minutes < range.max)
}

/* 客户端筛选 */
const filteredTrainList = computed(() => {
  let list = state.rawTrainList
  if (form.carType.length) {
    list = list.filter((item) =>
      (item.trainBrand ?? '')
        .split(',')
        .some((code) => form.carType.includes(Number(code)))
    )
  }
  if (form.departure.length) {
    list = list.filter((item) => form.departure.includes(item.departure))
  }
  if (form.arrival.length) {
    list = list.filter((item) => form.arrival.includes(item.arrival))
  }
  if (form.seat.length) {
    list = list.filter((item) =>
      item.seatClassList?.some((seat) => form.seat.includes(seat.type) && seat.quantity)
    )
  }
  if (form.departureRanges.length) {
    list = list.filter((item) => matchRanges(item.departureTime, form.departureRanges))
  }
  if (form.arrivalRanges.length) {
    list = list.filter((item) => matchRanges(item.arrivalTime, form.arrivalRanges))
  }
  if (form.onlyTicket) {
    list = list.filter((item) => item.seatClassList?.some((seat) => seat.quantity))
  }
  return list
})

watch(
  () => [
    form.carType,
    form.departure,
    form.arrival,
    form.seat,
    form.departureRanges,
    form.arrivalRanges,
    form.onlyTicket
  ],
  () => {
    expandedId.value = null
  },
  { deep: true }
)

const isAllSelected = (list) => list.length > 0

const toggleItem = (key, value) => {
  const list = form[key]
  const index = list.indexOf(value)
  if (index === -1) list.push(value)
  else list.splice(index, 1)
}

const toggleAll = (key, values) => {
  form[key] = form[key].length ? [] : [...values]
}

const pickDate = (value) => {
  if (form.departureDate === value) return
  form.departureDate = value
  search()
}

const swapStation = () => {
  const [from, to] = [form.fromStation, form.toStation]
  form.fromStation = to
  form.toStation = from
}

const applySearchResult = (res) => {
  if (!res.success) {
    toast.error(res.message)
    return false
  }
  const list = res.data.trainList ?? []
  state.trainList = list
  state.rawTrainList = list
  state.brandList = res.data.trainBrandList ?? []
  state.seatTypeList = res.data.seatClassTypeList ?? []
  state.departureStations = res.data.departureStationList ?? []
  state.arrivalStations = res.data.arrivalStationList ?? []
  return true
}

const search = () => {
  if (form.fromStation === form.toStation) {
    toast.error('出发地与目的地不能相同')
    return
  }
  searching.value = true
  expandedId.value = null
  ticketQuery({
    fromStation: form.fromStation,
    toStation: form.toStation,
    departureDate: form.departureDate
  })
    .then((res) => {
      applySearchResult(res)
    })
    .catch((error) => {
      console.log(error)
      toast.error('查询失败，请稍后重试')
    })
    .finally(() => {
      searching.value = false
    })
}

const toggleStations = (train) => {
  if (expandedId.value === train.trainId) {
    expandedId.value = null
    return
  }
  expandedId.value = train.trainId
  stationLoading.value = true
  trainStationQuery({ trainId: train.trainId })
    .then((res) => {
      stationList.value = res.data ?? []
    })
    .catch((error) => console.log(error))
    .finally(() => {
      stationLoading.value = false
    })
}

const SEAT_DISPLAY = [
  { label: '商务座', types: [0, 12] },
  { label: '一等座', types: [1] },
  { label: '二等座', types: [2, 3] },
  { label: '动卧', types: [10] },
  { label: '高级软卧', types: [9] },
  { label: '软卧/一等卧', types: [6, 4] },
  { label: '硬卧/二等卧', types: [7, 5] },
  { label: '软座', types: [11] },
  { label: '硬座', types: [8] },
  { label: '无座', types: [13] },
  { label: '其他', types: [14] }
]

const seatCells = (seatClassList) =>
  SEAT_DISPLAY.map(({ label, types }) => {
    const found = findSeatType(seatClassList, ...types)
    return found ? { type: found.type, label, price: found.price, quantity: found.quantity } : null
  }).filter(Boolean)

const hasTicket = (train) => Boolean(train.seatClassList?.some((item) => item.quantity))

const book = (train) => {
  const { href } = router.resolve({
    path: '/buyTicket',
    query: {
      trainId: train.trainId,
      trainNumber: train.trainNumber,
      fromStation: form.fromStation,
      toStation: form.toStation,
      departureDate: form.departureDate
    }
  })
  window.open(href, '_blank')
}

/* 待出行订单 */
const seatLabel = (code) =>
  SEAT_CLASS_TYPE_LIST.find((item) => item.code === code)?.label ?? '--'

const upcomingIndex = ref(0)
let touchStartX = 0
let pointerStartX = 0

const onTouchStart = (e) => { touchStartX = e.changedTouches[0]?.clientX ?? 0 }
const onTouchEnd = (e) => {
  const delta = (e.changedTouches[0]?.clientX ?? 0) - touchStartX
  if (Math.abs(delta) < 40) return
  if (delta < 0) upcomingIndex.value = Math.min(upcomingIndex.value + 1, upcomingOrders.value.length - 1)
  else upcomingIndex.value = Math.max(upcomingIndex.value - 1, 0)
}
const onPointerDown = (e) => { pointerStartX = e.clientX }
const onPointerUp = (e) => {
  const delta = e.clientX - pointerStartX
  if (Math.abs(delta) < 60) return
  if (delta < 0) upcomingIndex.value = Math.min(upcomingIndex.value + 1, upcomingOrders.value.length - 1)
  else upcomingIndex.value = Math.max(upcomingIndex.value - 1, 0)
}

const goOrder = (order) => {
  if (order.orderStatus === 0) {
    router.push(`/order?sn=${order.orderSn}`)
  } else {
    router.push('/ticketList')
  }
}

const loadUpcomingOrders = () => {
  if (!isLoggedIn()) return
  // 待出行只展示本人（证件号匹配）已支付的车票，不含乘车人、不含已改签
  myTicketPage({ current: 1, size: 10 })
    .then((res) => {
      if (!res.success) return
      const records = res.data?.records ?? []
      upcomingOrders.value = records
        .map((item) => ({
          orderSn: item.orderSn,
          ridingDate: item.ridingDate,
          trainNumber: item.trainNumber,
          departure: item.departure,
          arrival: item.arrival,
          departureTime: item.departureTime,
          arrivalTime: item.arrivalTime,
          duration: item.duration,
          orderStatus: item.status,
          firstSeatType: item.seatType,
          firstCarriageNumber: item.carriageNumber,
          firstSeatNumber: item.seatNumber,
          passengers: [item.realName],
          totalAmount: item.amount ?? 0
        }))
        .sort((a, b) => {
          const ta = dayjs(`${a.ridingDate} ${a.departureTime}`).valueOf()
          const tb = dayjs(`${b.ridingDate} ${b.departureTime}`).valueOf()
          return ta - tb
        })
      upcomingIndex.value = 0
    })
    .catch((error) => console.log(error))
}

const loadStations = () =>
  stationAll()
    .then((res) => {
      if (res.success) state.stationList = res.data ?? []
    })
    .catch((error) => console.log(error))

onMounted(() => {
  pageLoading.value = true
  Promise.all([
    ticketQuery({
      fromStation: form.fromStation,
      toStation: form.toStation,
      departureDate: form.departureDate
    }),
    loadStations()
  ])
    .then(([ticketRes]) => {
      applySearchResult(ticketRes)
    })
    .catch((error) => {
      console.log(error)
      toast.error('车票信息加载失败')
    })
    .finally(() => {
      pageLoading.value = false
    })
  loadUpcomingOrders()
})
</script>

<style scoped>
.search-card {
  padding: 20px 24px;
}

.search-row {
  display: flex;
  align-items: flex-end;
  gap: 12px;
}

.search-field {
  flex: 1;
  min-width: 0;
}

.search-label {
  display: block;
  font-size: 12px;
  font-weight: 600;
  color: var(--text-2);
  margin-bottom: 6px;
}

.swap-btn {
  appearance: none;
  border: 1px solid var(--border-strong);
  background: var(--surface);
  color: var(--accent);
  width: 36px;
  height: 38px;
  border-radius: 10px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  flex-shrink: 0;
  transition: all 0.2s var(--ease);
}

.swap-btn:hover {
  background: var(--accent-soft);
  border-color: var(--accent);
}

.search-btn {
  min-width: 108px;
}

.spinner-light {
  border-color: rgba(255, 255, 255, 0.4);
  border-top-color: #fff;
}

.date-strip {
  display: flex;
  gap: 6px;
  margin-top: 18px;
}

.date-pill {
  appearance: none;
  border: 1px solid transparent;
  background: var(--surface-2);
  border-radius: 12px;
  padding: 7px 4px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 1px;
  font-family: inherit;
  cursor: pointer;
  flex: 1;
  min-width: 0;
  transition: all 0.18s var(--ease);
}

.date-pill:hover {
  background: var(--surface-3);
}

.date-pill.active {
  background: var(--accent);
  border-color: var(--accent);
  box-shadow: 0 4px 14px rgba(0, 113, 227, 0.35);
}

.date-pill.active .date-day,
.date-pill.active .date-week {
  color: #fff;
}

.date-day {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-1);
  font-variant-numeric: tabular-nums;
}

.date-week {
  font-size: 11px;
  color: var(--text-2);
}

/* 快捷筛选行 */
.quick-row {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid var(--separator);
}

.quick-chips {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.more-filter-btn {
  margin-left: auto;
  appearance: none;
  border: none;
  background: transparent;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-family: inherit;
  font-size: 13px;
  font-weight: 600;
  color: var(--text-2);
  cursor: pointer;
  padding: 6px 10px;
  border-radius: 8px;
  transition: all 0.15s var(--ease);
}

.more-filter-btn:hover {
  color: var(--accent);
  background: var(--accent-soft);
}

.filter-count {
  min-width: 17px;
  height: 17px;
  border-radius: 9px;
  background: var(--accent);
  color: #fff;
  font-size: 11px;
  font-weight: 600;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 0 5px;
}

/* 详细筛选 */
.filter-groups {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-top: 14px;
  padding-top: 14px;
  border-top: 1px dashed var(--border);
}

.filter-group {
  display: flex;
  align-items: flex-start;
  gap: 14px;
}

.filter-label {
  flex-shrink: 0;
  width: 62px;
  text-align: right;
  font-size: 13px;
  font-weight: 500;
  color: var(--text-2);
  line-height: 30px;
}

/* 待出行订单 */
.upcoming-card {
  margin-top: 16px;
  padding: 18px 22px;
}

.car-wrap {
  display: flex;
  align-items: center;
  gap: 10px;
}

.car-arrow {
  appearance: none;
  border: 1px solid var(--border-strong);
  background: var(--surface);
  color: var(--text-2);
  width: 32px;
  height: 32px;
  border-radius: 50%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  flex-shrink: 0;
  transition: all 0.18s var(--ease);
}

.car-arrow:hover:not(:disabled) {
  color: var(--accent);
  border-color: var(--accent);
  background: var(--accent-soft);
}

.car-arrow:disabled {
  opacity: 0.35;
  cursor: not-allowed;
}

.car-viewport {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  border-radius: var(--radius-md);
  cursor: grab;
}

.car-track {
  display: flex;
  transition: transform 0.32s var(--ease);
}

.car-slide {
  flex: 0 0 100%;
  min-width: 0;
  background: linear-gradient(135deg, rgba(0, 113, 227, 0.05), rgba(94, 92, 230, 0.05));
  border: 1px solid var(--separator);
  border-radius: var(--radius-md);
  padding: 14px 20px;
  user-select: none;
}

.slide-top {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}

.slide-date {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-2);
}

.slide-train {
  margin-left: auto;
  font-size: 17px;
  font-weight: 700;
  letter-spacing: 0.01em;
}

.slide-route {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.slide-point {
  min-width: 90px;
}

.slide-point.right {
  text-align: right;
}

.slide-time {
  font-size: 22px;
  font-weight: 700;
  letter-spacing: -0.01em;
  font-variant-numeric: tabular-nums;
}

.slide-city {
  margin-top: 2px;
  font-size: 13.5px;
  font-weight: 500;
}

.slide-mid {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 5px;
}

.slide-duration {
  font-size: 12px;
  color: var(--text-2);
  font-variant-numeric: tabular-nums;
}

.slide-line {
  width: 100%;
  height: 2px;
  border-radius: 2px;
  background: linear-gradient(90deg, rgba(0, 113, 227, 0.5), rgba(52, 199, 89, 0.5));
  position: relative;
}

.slide-line::after {
  content: '';
  position: absolute;
  right: -1px;
  top: -2.5px;
  border: 3.5px solid transparent;
  border-left: 6px solid rgba(52, 199, 89, 0.9);
}

.slide-bottom {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 12px;
  font-size: 13px;
}

.slide-amount {
  font-size: 13px;
  color: var(--text-2);
}

.slide-amount .price {
  font-size: 15px;
}

.car-dots {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  margin-top: 12px;
}

.car-dot {
  width: 7px;
  height: 7px;
  border-radius: 4px;
  background: var(--surface-3);
  cursor: pointer;
  transition: all 0.2s var(--ease);
}

.car-dot.active {
  width: 20px;
  background: var(--accent);
}

/* 结果 */
.result-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 22px 2px 12px;
}

.result-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 17px;
  font-weight: 700;
}

.result-city {
  letter-spacing: 0.01em;
}

.result-date {
  margin-left: 6px;
  font-size: 13px;
  font-weight: 500;
  color: var(--text-2);
}

.result-count {
  font-size: 13px;
  color: var(--text-2);
}

.result-count b {
  color: var(--text-1);
  font-size: 15px;
}

/* 车次卡片 */
.train-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.train-card {
  padding: 18px 22px;
  transition: box-shadow 0.2s var(--ease);
}

.train-card:hover {
  box-shadow: 0 6px 28px rgba(0, 0, 0, 0.1);
}

.train-main {
  display: flex;
  align-items: center;
  gap: 26px;
}

.train-left {
  flex-shrink: 0;
  width: 96px;
}

.train-number {
  font-size: 19px;
  font-weight: 700;
  letter-spacing: 0.01em;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  cursor: pointer;
  color: var(--text-1);
  transition: color 0.15s var(--ease);
}

.train-number:hover {
  color: var(--accent);
}

.train-route {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  min-width: 250px;
}

.route-point {
  min-width: 84px;
}

.route-point.right {
  text-align: right;
}

.route-time {
  font-size: 21px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  letter-spacing: -0.01em;
}

.route-city {
  margin-top: 3px;
  font-size: 13px;
  color: var(--text-1);
  display: flex;
  align-items: center;
  gap: 5px;
}

.route-point.right .route-city {
  justify-content: flex-end;
}

.route-flag {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 17px;
  height: 17px;
  border-radius: 4px;
  color: #fff;
  font-size: 10px;
  font-weight: 600;
}

.route-flag.depart { background: #0a84ff; }
.route-flag.arrive { background: #34c759; }

.route-middle {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 5px;
  min-width: 90px;
}

.route-duration {
  font-size: 12px;
  color: var(--text-2);
  font-variant-numeric: tabular-nums;
}

.route-line {
  width: 100%;
  height: 2px;
  border-radius: 2px;
  background: linear-gradient(90deg, rgba(0, 113, 227, 0.5), rgba(52, 199, 89, 0.5));
  position: relative;
}

.route-line::after {
  content: '';
  position: absolute;
  right: -1px;
  top: -2.5px;
  border: 3.5px solid transparent;
  border-left: 6px solid rgba(52, 199, 89, 0.9);
}

/* 席别 */
.train-seats {
  flex: 1.2;
  display: flex;
  flex-wrap: wrap;
  gap: 4px 18px;
  max-width: 460px;
}

.seat-cell {
  display: flex;
  align-items: baseline;
  gap: 6px;
  min-width: 108px;
}

.seat-name {
  font-size: 12.5px;
  color: var(--text-2);
  white-space: nowrap;
}

.seat-price {
  font-size: 12.5px;
  color: var(--orange);
  font-variant-numeric: tabular-nums;
}

.seat-count {
  font-size: 13.5px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.train-action {
  flex-shrink: 0;
}

.soldout-btn {
  background: transparent;
  color: var(--text-3);
  cursor: not-allowed;
  opacity: 0.85;
}

.soldout-btn:hover {
  background: transparent;
  color: var(--text-3);
}

/* 经停站 */
.train-stations {
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px solid var(--separator);
  animation: expand 0.25s var(--ease);
}

@keyframes expand {
  from {
    opacity: 0;
    transform: translateY(-6px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

@media (max-width: 1080px) {
  .train-main {
    flex-wrap: wrap;
  }

  .search-row {
    flex-wrap: wrap;
  }
}
</style>
