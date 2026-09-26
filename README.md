# 12306—高并发铁路购票平台

对标 12306 的全链路铁路购票系统，基于开源项目 [nageoffer/12306](https://github.com/nageoffer/12306) 二次开发。覆盖用户注册登录、余票查询、在线选座购票、支付、订单管理、改签、退票完整业务闭环，重点围绕**购票链路的分层并发设计**演进。

> 本仓库在原版基础上的主要增强：库存原子预占硬闸门 + 动态令牌桶的分层准入（替代原版同步扣减）、购票临界区瘦身（订单创建移出锁外）、位图统一初始化语义与"相信 DB"冲突自愈、余票展示层（按钮状态与准入计数分离）、退票结果闭环（释放座位/回补缓存/状态流转）、重复购票校验、改签/变更到站、Windows 一键启动编排。

## 项目结构

```
├── services/                    # 微服务
│   ├── gateway-service/         # 网关            9000（JWT 鉴权、路由）
│   ├── user-service/            # 用户服务        9001（注册/登录/乘车人）
│   ├── ticket-service/          # 购票服务        9002（余票查询/购票选座/改签/退票）★核心
│   ├── order-service/           # 订单服务        9003
│   ├── pay-service/             # 支付服务        9004（支付宝沙箱/本地模拟渠道）
│   └── aggregation-service/     # 聚合服务        9005（四服务单进程合并，本地演示用）
├── frameworks/                  # 公共框架层（Spring Boot 3 Starter）
│   ├── cache/                   # 缓存三防（穿透/击穿/雪崩）+ safeGet 封装
│   ├── idempotent/              # 幂等组件（@Idempotent：Token/SpEL/MQ 消费）
│   ├── designpattern/           # 责任链 / 策略模式组件
│   ├── distributedid/           # 雪花 ID（含用户基因位）
│   ├── database/                # ShardingSphere 分片算法 + MyBatis-Plus 增强
│   ├── bizs/user/               # 用户上下文透传 + JWT 工具
│   └── web / common / convention / log / base / bizs
├── console-vue/                 # Vue 3 前端      8080
├── resources/db/                # 建库建表脚本
├── test-run/                    # 压测与多实例联调运行资产（JMX、报告、ShardingSphere 配置、日志）
├── start.py / start.bat         # Windows 一键启动编排
└── test-apis.py                 # 核心接口冒烟脚本
```

## 快速启动

### 环境要求

- JDK 17+、Maven 3.8+、Node.js 16+、Python 3.8+
- 本机安装 MySQL、Redis、RocketMQ（4.x），并在 `start.py` 顶部"配置区"核对路径与端口：
  - MySQL `3306`、Redis `6379`、RocketMQ NameServer `9876` / Broker `10911`

### 一键启动（推荐）

```bash
# Windows 双击或命令行执行
start.bat
```

`start.py` 会按序完成：

1. 启动 MySQL / Redis / RocketMQ（已运行则跳过）
2. 构建后端 jar（已构建则跳过）、安装前端依赖（已安装则跳过）
3. 启动聚合服务 `9005` → 网关 `9000` → 前端 `8080`，各自探活等待就绪
4. 自动打开浏览器 `http://localhost:8080`

> 注意：聚合服务 jar 需要重新构建才会包含最新购票实现（`mvn -pl services/aggregation-service -am package`）。

### 首次启动前：初始化数据库

执行 `resources/db/` 下 4 个脚本，创建 4 个业务库：

- `12306-springcloud-user.sql` → `12306_user`
- `12306-springcloud-ticket.sql` → `12306_ticket`
- `12306-springcloud-order.sql` → `12306_order`
- `12306-springcloud-pay.sql` → `12306_pay`

演示账号：`admin / admin123456`。

### 手动启动（两种模式）

```bash
# 模式一：聚合（四业务服务单进程，无需 Nacos，本地演示）
mvn -pl services/aggregation-service,services/gateway-service -am package -DskipTests
java -jar services/aggregation-service/target/index12306-aggregation-service.jar
java -jar services/gateway-service/target/index12306-gateway-service.jar

# 模式二：微服务（Nacos 服务发现 + 网关 lb:// 负载均衡，支持购票服务多实例水平扩展）
# 先启动 Nacos（standalone）与 RocketMQ，再依次拉起业务服务（例：购票服务双实例 9002/9012）
java -jar services/user-service/target/index12306-user-service.jar
java -jar services/order-service/target/index12306-order-service.jar
java -jar services/ticket-service/target/index12306-ticket-service.jar          # 9002
java -jar services/ticket-service/target/index12306-ticket-service.jar --server.port=9012
java -jar services/gateway-service/target/index12306-gateway-service.jar --spring.profiles.active=dev
```

购票服务多实例压测与联调的运行配置（统一数据源 ShardingSphere 配置、JMeter 计划、报告）见 `test-run/`。

## 购票核心实现

购票入口 `POST /api/ticket-service/ticket/purchase/v2`，完整链路：

```
前端请求
  → 网关 JWT 鉴权（TokenValidateGatewayFilterFactory）
      黑名单路径校验 Token，解析后把 userId/username 写入请求头向下游透传
  → 幂等防重（@Idempotent，SpEL + Redisson）
      锁键 = 用户名，同一用户并发重复下单直接拦截
  → 重复购票校验（责任链，以订单数据为准）
      待支付/已支付/已进站视为持票，已关闭/已退票/已改签自动放行；
      校验维度为身份证号，跨账号为同一乘车人购票同样拦截
  → 责任链校验（TicketChainMarkEnum.TRAIN_PURCHASE_TICKET_FILTER）
      ├─ 参数非空校验
      ├─ 车次存在/未发车 + 站点顺序校验（防跳站、反向、非法区间）
      └─ 余票只读预判：读余票缓存 Hash，无票请求在此秒拒（零成本、不消耗准入额度）
  → 库存原子预占（第一道硬闸门，Redis Lua）
      购买区间覆盖的全部站段余票一次原子"检查 + 扣减"，
      任一站段不足直接返回"已售完，可提交候补"，请求不进入令牌桶与锁队列；
      预占失败的请求若命中 0/初始票量 10% 阈值，自动以 DB 统计校准缓存后重试一次
  → 令牌桶（Redis + Lua，按车次维度）
      容量动态 = 当前区间余票 × capacity-multiplier（临近售罄准入自动收紧），
      补充速率固定（refill-rate，按选座临界区实测排水能力配置）；
      限流器自身故障降级放行，不阻断购票主流程
  → 两级公平锁
      ├─ 本地公平锁（Caffeine 缓存锁对象）：JVM 内排队，把锁竞争从线程级降到实例级
      └─ Redisson 分布式公平锁（车次 + 席别）：跨 JVM 先到先得，保障出票顺序
  → 购票临界区（preparePurchaseTickets，只保留快操作）
      ├─ 选座引擎（策略模式，hippo4j 动态线程池多席别并行分配）
      │    ├─ 按 车型×席别 路由到 7 个选座 Handler（商务/一等/二等/软卧/硬卧/硬座/无座）
      │    ├─ 二等座三级降级：意向座位 → 同车厢空闲座 → 跨车厢（意向座位即偏好，被占自动改配）
      │    └─ Redis 位图占座：Key = 车次+车厢，bit = 座位 × 相邻站段
      ├─ DB 座位行同步落库（与位图互为对方的事务边界，超卖最终硬保证）
      └─ 车票记录落库；失败由外层单点回补预占库存（冲突座位"相信 DB"置占用，幻影自愈）
  → 出临界区 → createTicketOrder（购票锁外执行）
      乘车人补全、票价与车站关系全部读缓存（不落库），Feign 创建订单；
      创建失败触发单点补偿：删除车票 + 释放座位（DB+位图）+ 回补预占库存
  → RocketMQ 延迟消息（10 分钟）
      超时未支付自动关单：回滚 DB 座位 + 位图清位 + 按沿途子区间回补余票缓存（幂等，失败重试兜底）
```

**四层准入的职责划分**：余票预判与库存预占回答"还有没有票"（无票秒拒，候补引导），令牌桶回答"放多快进临界区"（削峰填谷），两级公平锁回答"谁先出票"（FIFO 公平），位图 + DB 是防超卖的最终真相。上三层允许有界误差，最底层零容忍。

### 余票缓存与展示层

- **实时余票 Hash**（`train_station_remaining_ticket:车次_出发_到达`，field = 席别）：准入计数器，预占扣减 / 退票与关单回补 / 阈值校准，责任链预检与预占共用
- **展示层快照**（`train_station_remaining_display:...`）：余票缓存降级为"按钮亮不亮"的展示面，与准入计数分离——
  - 刷新器每 3 秒 pipeline 拉取全部车厢位图、本地聚合"覆盖站段全空闲"座位数写入快照（不落库、不与准入计数互写）
  - 活跃车次工作集：仅刷新首页/购票触达过的车次（10 分钟 TTL 退出）
  - 卧铺等无固定布局坐席（不可位图化）按 DB 统计降频兜底；位图缺失自动预热构建
  - 首页/按钮只读展示层，未就绪自动回退实时余票缓存
- **位图统一初始化语义**：站段占用按"任一覆盖组合行非可售即占用"聚合（修正混合状态座位误判）；仅"布局物理位且 DB 存在座位行"的位才清 0，非物理位/缺行位恒为占用（宁可少卖）
- 5 类缓存结构：站点-城市映射、城市对车次列表、列车信息、区间票价、余票 Hash（`区间 × 席别`）
- 缓存三防（收敛在框架层 `DistributedCache.safeGet`，业务一行接入）：
  - **穿透** → Redisson 布隆过滤器前置拦截（注册查重过滤器容量 100 万 / 1% 误判率）
  - **击穿** → 分布式锁 + 双重判定回源，N 个并发重建压缩为 1 次 DB 查询
  - **雪崩** → 各调用方自定义过期时间错峰 + 定时预热任务分批写入
- 购票下单路径零落库查询：票价复用首页票价缓存（同键同装载）、车站关系走独立 safeGet、乘车人走 Feign
- 一致性双模式：默认购票/关单**同步双写**缓存；`ticket.availability.cache-update.type=binlog` 时业务只写 DB，由 Canal 监听 binlog → RocketMQ → 策略模式分发更新缓存

### 退票与状态闭环

```
支付成功（PayResultCallbackTicketConsumer）
    车票 UNPAID → PAID，座位 LOCKED → SOLD
退票成功（RefundResultCallbackTicketConsumer，新增）
    整单/部分退款按证件号匹配乘车人 → 释放座位（DB 站段行 + 位图清位）
    → 按覆盖站段回补余票缓存 → 车票 PAID → REFUNDED
超时未支付（DelayCloseOrderConsumer，延迟消息）
    关单（幂等）→ 同上全链路回滚，失败依赖 MQ 重试兜底
```

### 关键配置

```yaml
ticket:
  purchase:
    rate-limiter:
      enabled: true
      capacity-multiplier: 1.5   # 桶容量 = 当前区间余票缓存总量 × 该倍数（动态跟随票量）
      refill-rate: 25            # 每秒补充令牌数（按选座临界区实测排水能力配置）

ticket:
  availability:
    cache-update:
      type: binlog        # 余票缓存一致性：默认同步双写，binlog 由 Canal 驱动
```

## 其他能力

- **改签/变更到站**：改签费阶梯计算；新旧车次多把公平锁按 Key 排序加锁防死锁；复用购票责任链与选座引擎；失败自动补偿释放新票资源（座位 + 预占库存）
- **退票**：整单/部分退款，退款结果经 MQ 回调驱动座位释放、余票回补与车票状态流转
- **幂等组件**（frameworks/idempotent）：一套 `@Idempotent` 注解覆盖 Token 一次性校验、SpEL 业务键、MQ 消费状态机（Lua SETNX + CONSUMING/CONSUMED）
- **分库分表**：ShardingSphere 用户/订单/支付 2 库 × 32 表；雪花 ID 低 4 位嵌入用户基因位，订单号携带路由信息，按订单号/按用户查询均命中同一分片；敏感字段 AES 透明加密
- **支付**：支付宝沙箱 + 本地 Dev 模拟渠道（策略模式），支付结果经 MQ 广播驱动订单/车票服务异步流转

## 性能演进（JMeter 实测：1 万人抢 1000 张，购票双实例）

| 指标 | 基线（同步扣减+冲突清位） | 临界区瘦身+冲突自愈后 | 当前（+预占闸门/动态桶/展示层） |
|---|---|---|---|
| 总耗时 | 5 分 02 秒 | 1 分 30 秒 | **1 分 28 秒** |
| TPS | 33.1 | 111.2 | **113.5** |
| 成交 | 96 / 1000（严重少卖） | 996 / 1000 | **1000 / 1000 精确售罄** |
| 座位冲突 | 1115 次（风暴循环） | 0 | **0** |
| 无票误拒 | 5675 次（缓存泄漏污染） | 0 | **0（全部为售罄后预占层秒拒）** |
| 余票缓存终态 | 泄漏至负数 | = DB | **= DB，且展示层独立快照** |

基线的少卖由三个叠加缺陷造成：混合状态座位引发"选座→冲突→清位→再选中"的风暴循环、冲突后余票缓存不回补、位图与 DB 口径不一致。修复次序与细节见各组件注释与 `test-run/` 压测报告。

## 测试与联调

```bash
# 冒烟测试：登录 → 余票查询 → 购票 → 订单/支付等核心接口
python test-apis.py

# 联调重置（清余票缓存、座位位图、重置 DB 座位状态）
POST /api/ticket-service/temp/seat/reset?trainId={车次ID}

# 压测资产（1 万人抢 1000 票：JMeter 计划、HTML 报告、多实例联调配置）
# 见 test-run/（loadtest_10000_1000.jmx / jmeter_report/）
```

## 声明

- 本项目基于 [nageoffer/12306](https://github.com/nageoffer/12306) 学习二次开发，原版架构解析见其官方文档
- 仅用于学习交流，请勿用于生产环境
