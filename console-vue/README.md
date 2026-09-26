# 12306 Console（Vue 3 + Vite）

12306 铁路购票系统前端控制台，基于 **Vue 3 + Vite** 构建，采用现代苹果风格（Apple-style）设计语言：磨砂玻璃导航、大圆角卡片、胶囊按钮、细腻的过渡动效。

## 技术栈

- Vue 3（`<script setup>` 组合式 API）
- Vite 6（开发服务器 / 构建工具，替代旧版 Vue CLI）
- Vue Router 4
- Axios / dayjs / js-cookie
- 无 UI 组件库，全部为自研设计系统（`src/styles/main.css` 设计令牌 + 基础组件）

## 快速开始

```bash
npm install     # 安装依赖
npm run dev     # 启动开发服务器（默认 http://localhost:8080）
npm run build   # 生产构建，输出到 dist/
npm run preview # 预览生产构建
```

开发服务器已配置代理：`/api` → `http://127.0.0.1:9000`（网关服务）。

## 页面结构

| 路由 | 说明 |
| --- | --- |
| `/login` | 登录 / 注册 |
| `/ticketSearch` | 车票查询（筛选、余票、经停站） |
| `/buyTicket` | 购买车票（乘车人、席别、在线选座） |
| `/order` | 订单支付（倒计时、支付渠道、状态轮询） |
| `/aliPay` | 支付宝跳转中转 |
| `/paySuccess` | 支付成功 |
| `/ticketList` | 车票订单（未完成 / 未出行 / 历史，退票） |
| `/personalTicket` | 本人车票 |
| `/userInfo` | 个人信息维护 |
| `/passenger` · `/addPassenger` | 乘车人管理 / 新增编辑 |

## 目录结构

```
src/
├── api/            # 接口层（axios 封装 + 各服务接口）
├── assets/         # 静态资源
├── components/     # 通用组件（导航、侧边栏、弹窗、分页、图标等）
├── constants/      # 业务常量（席别、车型、支付渠道等）
├── router/         # 路由与登录守卫
├── stores/         # 会话（Cookie）存取
├── styles/         # 设计系统（设计令牌 / 基础样式）
├── ui/             # 轻量全局状态（Toast）
├── utils/          # 工具函数
└── views/          # 页面
```
