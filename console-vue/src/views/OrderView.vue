<template>
  <div>
    <!-- 倒计时提示 -->
    <div class="card countdown-card">
      <div class="countdown-icon">
        <AppIcon name="clock" :size="22" />
      </div>
      <div>
        <div class="countdown-text">席位已锁定，请在规定时间内完成网上支付</div>
        <div class="countdown-sub">
          支付剩余时间：<span class="countdown-time">{{ countdownText }}</span>
        </div>
      </div>
    </div>

    <!-- 订单信息 -->
    <div class="card">
      <div class="card-head">
        <div class="card-title">订单信息</div>
        <span v-if="state.currentInfo?.orderSn" class="tag tag-gray mono">
          {{ state.currentInfo.orderSn }}
        </span>
      </div>

      <div class="train-summary">
        <span class="summary-date">{{ state.currentInfo?.ridingDate }}</span>
        <span>（{{ getWeekNumber(dayjs(state.currentInfo?.ridingDate ?? new Date()).day()) }}）</span>
        <span class="summary-strong">{{ state.currentInfo?.trainNumber }}</span>次
        <span class="summary-strong">{{ state.currentInfo?.departure }}</span>站
        （{{ state.currentInfo?.departureTime }}开）
        <AppIcon name="arrow-right" :size="14" />
        <span class="summary-strong">{{ state.currentInfo?.arrival }}</span>站
        （{{ state.currentInfo?.arrivalTime }}到）
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
            </tr>
          </thead>
          <tbody>
            <tr v-for="(item, index) in state.currentInfo?.passengerDetails ?? []" :key="index">
              <td class="num">{{ index + 1 }}</td>
              <td>{{ item.realName }}</td>
              <td>{{ idCardLabel(item.idType) }}</td>
              <td class="mono">{{ item.idCard }}</td>
              <td>{{ ticketTypeLabel(item.ticketType) }}</td>
              <td>{{ seatLabel(item.seatType) }}</td>
              <td class="num">{{ item.carriageNumber }}</td>
              <td class="num">{{ item.seatNumber }}</td>
              <td><span class="price">￥{{ (item.amount ?? 0) / 100 }}</span></td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="total-row">
        总票价：<span class="price price-lg">￥{{ totalAmount }} 元</span>
      </div>

      <hr class="divider" />

      <div class="row" style="justify-content: center; gap: 14px">
        <button class="btn" @click="handleCancel">取消订单</button>
        <button class="btn btn-orange btn-lg" style="min-width: 160px" @click="payOpen = true">
          <AppIcon name="wallet" :size="16" />
          网上支付
        </button>
      </div>
    </div>

    <!-- 温馨提示 -->
    <div class="notice" style="margin-top: 16px">
      <div>
        <p>1. 一张有效身份证件同一乘车日期同一车次只能购买一张车票。</p>
        <p>2. 逾期未支付，系统将取消本次交易。一天内3次申请车票成功后取消订单，当日将不能继续购票。</p>
        <p>3. 未尽事宜详见《铁路旅客运输规程》等有关规定和车站公告。</p>
      </div>
    </div>

    <!-- 支付渠道弹窗 -->
    <AppModal v-model="payOpen" title="请选择支付方式" width="600px">
      <div class="pay-amount">
        应付金额：<span class="price price-lg">￥{{ totalAmount }}</span>
      </div>
      <hr class="divider" />
      <div class="bank-grid">
        <button v-for="bank in BANK_LIST" :key="bank.value" class="bank-item" @click="handlePay(bank.value)">
          <img :src="bank.img" :alt="bank.name" class="bank-logo" />
          <span class="bank-name">{{ bank.name }}</span>
        </button>
      </div>
    </AppModal>

    <!-- 支付等待提示 -->
    <AppModal v-model="payingOpen" title="网上支付提示" width="440px" :mask-closable="false">
      <div class="paying-body">
        <span class="spinner spinner-lg" />
        <div>
          <p>支付完成后，请不要关闭此支付验证窗口</p>
          <p class="text-secondary">支付完成后请根据您的支付情况点击下面按钮。</p>
        </div>
      </div>
      <template #footer>
        <button class="btn" @click="router.push('/ticketList')">支付遇到问题</button>
        <button class="btn btn-primary" @click="router.push('/ticketList')">支付完成</button>
      </template>
    </AppModal>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import dayjs from 'dayjs'
import AppIcon from '@/components/AppIcon.vue'
import AppModal from '@/components/AppModal.vue'
import { orderBySn, createPay, cancelTicket, payOrderStatus } from '@/api'
import { getWeekNumber, formatDuration } from '@/utils'
import { TICKET_TYPE_LIST, ID_CARD_TYPE, BANK_LIST, SEAT_CLASS_TYPE_LIST } from '@/constants'
import { toast } from '@/ui/toast'

const route = useRoute()
const router = useRouter()
const query = route.query

const COUNTDOWN_MS = 600000

const state = reactive({
  count: COUNTDOWN_MS,
  currentInfo: null
})

const payOpen = ref(false)
const payingOpen = ref(false)
const isInitiatePayment = ref(false)

let timer = undefined

const idCardLabel = (value) => ID_CARD_TYPE.find((item) => item.value === value)?.label ?? '--'
const ticketTypeLabel = (value) =>
  TICKET_TYPE_LIST.find((item) => item.value === value)?.label ?? '--'
const seatLabel = (value) =>
  SEAT_CLASS_TYPE_LIST.find((item) => item.code === value)?.label ?? '--'

const totalAmount = computed(() => {
  const amount = (state.currentInfo?.passengerDetails ?? []).reduce(
    (sum, item) => sum + (item?.amount ?? 0),
    0
  )
  return amount / 100
})

const countdownText = computed(() => formatDuration(state.count))

const getOrder = () => {
  orderBySn({ orderSn: query?.sn }).then((res) => {
    if (res.success) state.currentInfo = res.data
  })
}

const getOrderStatus = () => {
  if (!isInitiatePayment.value) return
  payOrderStatus({ orderSn: query?.sn })
    .then((res) => {
      payingOpen.value = res.data.status === 0
      if (res.data.status === 20) {
        router.push(`/paySuccess?orderSn=${res.data.orderSn}`)
      }
    })
    .catch((error) => console.log(error))
}

const handleCancel = () => {
  cancelTicket({ orderSn: query?.sn }).then((res) => {
    if (res.success) {
      toast.success('订单取消成功')
      router.push('/ticketSearch')
    } else {
      toast.error(res.message)
    }
  })
}

const handlePay = (channel) => {
  if (channel !== 0 && channel !== 1) {
    toast.error('该支付方式暂未对接，请稍候...')
    return
  }
  payOpen.value = false
  payingOpen.value = true
  isInitiatePayment.value = true
  const body = {
    channel,
    tradeType: 0,
    orderSn: query.sn,
    totalAmount: totalAmount.value,
    outOrderSn: query.orderSn,
    subject: `${state.currentInfo.departure}-${state.currentInfo.arrival}`
  }
  createPay(body)
    .then((res) => {
      if (!res.success) {
        // 订单已关闭/已支付等业务错误：不提示支付成功，停留在当前页
        toast.error(res.message ?? '发起支付失败')
        payingOpen.value = false
        isInitiatePayment.value = false
        return
      }
      if (channel === 1) {
        // 开发者支付：后端直接支付成功，跳转到支付成功页
        toast.success('支付成功')
        router.push(`/paySuccess?orderSn=${query.sn}`)
        return
      }
      const html = res.data?.body ?? ''
      setTimeout(() => {
        window.open(`/aliPay?body=${encodeURIComponent(html)}`)
      }, 300)
    })
    .catch((error) => {
      console.log(error)
      toast.error('发起支付失败，请稍后重试')
      payingOpen.value = false
      isInitiatePayment.value = false
    })
}

onMounted(() => {
  getOrder()
  timer = setInterval(() => {
    state.count -= 1000
    if (state.count <= 0) {
      clearInterval(timer)
      state.count = 0
      toast.error('支付超时，订单已释放')
    }
    getOrderStatus()
  }, 1000)
})

onUnmounted(() => {
  clearInterval(timer)
})
</script>

<style scoped>
.countdown-card {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 18px 24px;
  background: linear-gradient(135deg, rgba(0, 113, 227, 0.06), rgba(94, 92, 230, 0.06));
}

.countdown-icon {
  width: 46px;
  height: 46px;
  border-radius: 14px;
  background: var(--accent-soft);
  color: var(--accent);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.countdown-text {
  font-size: 15px;
  font-weight: 600;
}

.countdown-sub {
  margin-top: 2px;
  font-size: 13px;
  color: var(--text-2);
}

.countdown-time {
  font-weight: 700;
  color: var(--orange);
  font-variant-numeric: tabular-nums;
  font-size: 15px;
}

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

.total-row {
  text-align: right;
  padding: 14px 4px 0;
  color: var(--text-2);
}

.pay-amount {
  font-size: 14px;
  color: var(--text-2);
}

.bank-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
}

.bank-item {
  appearance: none;
  border: 1px solid var(--border);
  background: var(--surface);
  border-radius: var(--radius-md);
  padding: 14px 8px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  font-family: inherit;
  transition: all 0.2s var(--ease);
}

.bank-item:hover {
  border-color: var(--accent);
  box-shadow: 0 4px 16px rgba(0, 113, 227, 0.15);
  transform: translateY(-2px);
}

.bank-logo {
  width: 40px;
  height: 40px;
  object-fit: contain;
  border-radius: 8px;
}

.bank-name {
  font-size: 12.5px;
  color: var(--text-1);
}

.paying-body {
  display: flex;
  align-items: center;
  gap: 18px;
  padding: 8px 0;
  font-size: 13.5px;
  line-height: 1.8;
}

@media (max-width: 720px) {
  .bank-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>
