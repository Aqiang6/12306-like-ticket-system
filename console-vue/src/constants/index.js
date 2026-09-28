export const TICKET_TYPE_LIST = [
  { label: '成人票', value: 0 },
  { label: '学生票', value: 1 }
]

export const DISCOUNTS_TYPE = [
  { label: '成人', value: 0 },
  { label: '儿童', value: 1 },
  { label: '学生', value: 2 },
  { label: '残疾军人', value: 3 }
]

export const TRAIN_TAG = [
  { label: '复', value: '0', color: '#f29c58' },
  { label: '智', value: '1', color: '#7db08d' },
  { label: '静', value: '2', color: '#64a0f6' }
]

export const TICKET_STATUS_LIST = [
  { label: '待支付', value: 0 },
  { label: '已支付', value: 10 },
  { label: '已进站', value: 20 },
  { label: '已取消', value: 30 },
  { label: '已退票', value: 40 },
  { label: '已改签', value: 50 }
]

export const TICKET_STATUS_TAG = {
  0: 'tag-orange',
  10: 'tag-green',
  20: 'tag-blue',
  30: 'tag-gray',
  40: 'tag-gray',
  50: 'tag-purple'
}

export const ID_CARD_TYPE = [
  { label: '中国居民身份证', value: 0 }
]

export const SEAT_CLASS_TYPE_LIST = [
  { label: '商务座', code: 0 },
  { label: '一等座', code: 1 },
  { label: '二等座', code: 2 }
]

export const TRAIN_BRAND_LIST = [
  { code: 0, label: 'GC-高铁城际' },
  { code: 1, label: 'D-动车' },
  { code: 2, label: 'Z-直达' },
  { code: 3, label: 'T-特快' },
  { code: 4, label: 'K-快速' },
  { code: 5, label: '其他' },
  { code: 6, label: '复兴号' },
  { code: 7, label: '智能动车组' }
]

export const BANK_LIST = [
  { img: 'https://epay.12306.cn/pay/pages/web/images/bank_zfb.gif', name: '支付宝', value: 0 },
  { img: 'https://img.icons8.com/color/48/code.png', name: '开发者支付', value: 1 },
  { img: 'https://epay.12306.cn/pay/pages/web/images/bank_wx.gif', name: '微信', value: 2 },
  { img: 'https://epay.12306.cn/pay/pages/web/images/bank_gsyh2.gif', name: '工商银行', value: 10 },
  { img: 'https://epay.12306.cn/pay/pages/web/images/bank_nyyh2.gif', name: '农业银行', value: 9 },
  { img: 'https://epay.12306.cn/pay/pages/web/images/bank_zgyh2.gif', name: '中国银行', value: 8 },
  { img: 'https://epay.12306.cn/pay/pages/web/images/bank_jsyh2.gif', name: '建设银行', value: 7 },
  { img: 'https://epay.12306.cn/pay/pages/web/images/bank_zsyh2.gif', name: '招商银行', value: 6 },
  { img: 'https://epay.12306.cn/pay/pages/web/images/bank_ycyh.gif', name: '邮储银行', value: 5 },
  { img: 'https://epay.12306.cn/pay/pages/web/images/bank_zgyl.gif', name: '中国银联', value: 4 },
  { img: 'https://epay.12306.cn/pay/pages/web/images/bank_ztytk.gif', name: '中铁银通卡', value: 3 },
  { img: 'https://epay.12306.cn/pay/pages/web/images/bank_wk.gif', name: '国际卡', value: 12 },
  { img: 'https://epay.12306.cn/pay/pages/web/images/bank_jtyh.png', name: '交通银行', value: 13 }
]

export const REGIN_MAP = [
  { value: '0', label: '中国' }
]

export const CHECK_STATUS = [
  { value: 0, label: '通过' },
  { value: 1, label: '未通过' }
]

export const CAR_RANGE_TIME = [
  { value: 0, label: '00:00-24:00' },
  { value: 1, label: '00:00-06:00' },
  { value: 2, label: '06:00-12:00' },
  { value: 3, label: '12:00-18:00' },
  { value: 4, label: '18:00-24:00' }
]
