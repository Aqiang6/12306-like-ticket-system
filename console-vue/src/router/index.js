import { createRouter, createWebHistory } from 'vue-router'
import { toast } from '@/ui/toast'
import { isLoggedIn } from '@/stores/auth'

import LoginView from '@/views/LoginView.vue'
import TicketSearchView from '@/views/TicketSearchView.vue'
import UserInfoView from '@/views/UserInfoView.vue'
import PassengerView from '@/views/PassengerView.vue'
import PassengerEditView from '@/views/PassengerEditView.vue'
import OrderView from '@/views/OrderView.vue'
import BuyTicketView from '@/views/BuyTicketView.vue'
import AliPayView from '@/views/AliPayView.vue'
import OrderListView from '@/views/OrderListView.vue'
import PersonalTicketView from '@/views/PersonalTicketView.vue'
import PaySuccessView from '@/views/PaySuccessView.vue'
import ChangeTicketView from '@/views/ChangeTicketView.vue'

const routes = [
  { path: '/', redirect: '/ticketSearch' },
  { path: '/login', name: 'login', component: LoginView, meta: { bare: true } },
  {
    path: '/ticketSearch',
    name: 'ticketSearch',
    component: TicketSearchView,
    meta: { title: '车票查询' }
  },
  {
    path: '/userInfo',
    name: 'userInfo',
    component: UserInfoView,
    meta: { title: '个人信息', requiresAuth: true }
  },
  {
    path: '/passenger',
    name: 'passenger',
    component: PassengerView,
    meta: { title: '乘车人管理', requiresAuth: true }
  },
  {
    path: '/addPassenger',
    name: 'addPassenger',
    component: PassengerEditView,
    meta: { title: '添加乘车人', requiresAuth: true }
  },
  { path: '/myTicket', redirect: '/personalTicket' },
  {
    path: '/order',
    name: 'order',
    component: OrderView,
    meta: { title: '订单支付', requiresAuth: true }
  },
  {
    path: '/buyTicket',
    name: 'buyTicket',
    component: BuyTicketView,
    meta: { title: '购买车票', requiresAuth: true }
  },
  {
    path: '/aliPay',
    name: 'aliPay',
    component: AliPayView,
    meta: { title: '支付跳转', requiresAuth: true, bare: true }
  },
  {
    path: '/ticketList',
    name: 'ticketList',
    component: OrderListView,
    meta: { title: '车票订单', requiresAuth: true }
  },
  {
    path: '/personalTicket',
    name: 'personalTicket',
    component: PersonalTicketView,
    meta: { title: '本人车票' }
  },
  {
    path: '/changeTicket',
    name: 'changeTicket',
    component: ChangeTicketView,
    meta: { title: '车票改签', requiresAuth: true }
  },
  {
    path: '/paySuccess',
    name: 'paySuccess',
    component: PaySuccessView,
    meta: { title: '支付成功' }
  },
  { path: '/:pathMatch(.*)*', redirect: '/ticketSearch' }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to) => {
  if (to.meta?.requiresAuth && !isLoggedIn()) {
    toast.error('用户未登录或已过期！')
    return { name: 'login' }
  }
  document.title = to.meta?.title ? `${to.meta.title} · 12306` : '12306 · 铁路购票'
})

export default router
