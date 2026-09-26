import Axios from 'axios'
import { getToken, clearSession } from '@/stores/auth'
import { toast } from '@/ui/toast'

const http = Axios.create({
  timeout: 1800000
})

http.interceptors.request.use((config) => {
  const token = getToken()
  if (token) config.headers.Authorization = token
  return config
})

http.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401) {
      toast.error('用户未登录或已过期！')
      clearSession()
      window.location.href = '/login'
    }
    return Promise.reject(error)
  }
)

const request = async ({ method = 'GET', url, data, params } = {}) => {
  const { data: body } = await http({ method, url, data, params })
  return body
}

/* ---------- 用户服务 ---------- */

export const login = (body) =>
  request({ method: 'POST', url: '/api/user-service/v1/login', data: body })

export const register = (body) =>
  request({ method: 'POST', url: '/api/user-service/register', data: body })

export const logout = () => request({ method: 'GET', url: '/api/user-service/logout' })

export const getUserInfo = (params) =>
  request({ url: '/api/user-service/query', params })

export const updateUserInfo = (body) =>
  request({ method: 'POST', url: '/api/user-service/update', data: body })

/* ---------- 乘车人 ---------- */

export const getPassengerList = (params) =>
  request({ url: '/api/user-service/passenger/query', params })

export const addPassenger = (body) =>
  request({ method: 'POST', url: '/api/user-service/passenger/save', data: body })

export const updatePassenger = (body) =>
  request({ method: 'POST', url: '/api/user-service/passenger/update', data: body })

export const removePassenger = (body) =>
  request({ method: 'POST', url: '/api/user-service/passenger/remove', data: body })

/* ---------- 购票 ---------- */

export const ticketQuery = (params) =>
  request({ url: '/api/ticket-service/ticket/query', params })

export const regionStationQuery = (params) =>
  request({ url: '/api/ticket-service/region-station/query', params })

export const stationAll = () => request({ url: '/api/ticket-service/station/all' })

export const trainStationQuery = (params) =>
  request({ url: '/api/ticket-service/train-station/query', params })

export const buyTicket = (body) =>
  request({ method: 'POST', url: '/api/ticket-service/ticket/purchase/v2', data: body })

export const cancelTicket = (body) =>
  request({ method: 'POST', url: '/api/ticket-service/ticket/cancel', data: body })

export const refundTicket = (body) =>
  request({ method: 'POST', url: '/api/ticket-service/ticket/refund', data: body })

export const changeTicketPreview = (body) =>
  request({ method: 'POST', url: '/api/ticket-service/ticket/change/preview', data: body })

export const changeTicket = (body) =>
  request({ method: 'POST', url: '/api/ticket-service/ticket/change', data: body })

/* ---------- 订单 ---------- */

export const orderBySn = (params) =>
  request({ url: '/api/order-service/order/ticket/query', params })

export const ticketPage = (params) =>
  request({ url: '/api/order-service/order/ticket/page', params })

export const myTicketPage = (params) =>
  request({ url: '/api/order-service/order/ticket/self/page', params })

/* ---------- 支付 ---------- */

export const createPay = (body) =>
  request({ method: 'POST', url: '/api/pay-service/pay/create', data: body })

export const payOrderStatus = (params) =>
  request({ url: '/api/pay-service/pay/query/order-sn', params })
