# 12306—高并发铁路购票平台

对标 12306 的全链路铁路购票系统，基于开源项目 [nageoffer/12306](https://github.com/nageoffer/12306) 二次开发。覆盖用户注册登录、余票查询、在线选座购票、支付、订单管理、改签、退票完整业务闭环，重点围绕**购票链路的分层并发设计**与**库存模型**演进。

> 本仓库在原版基础上的主要增强：**库存模型重构**（Redis 座位区间占用位图 = 热路径唯一准入，Lua 脚本整单原子锁座，`t_ticket` 售卖区间账本为持久事实，`t_seat` 退化为物理座位注册表）、余票缓存只读化（3 秒周期重写，售罄粘滞广播）、动态令牌桶准入、购票临界区瘦身（订单创建移出锁外）、位图对账自愈、**跨服务建单容灾**（事务性发件箱 + RocketMQ 补偿重建订单 + 购票令牌幂等，宕机不丢单不重单）、退票/关单/改签状态闭环、Windows 一键启动编排。

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
├── resources/db/                # 建库建表脚本（含 t_seat 注册表 / t_ticket 账本新模型）
├── test-run/                    # 压测与多实例联调运行资产（JMX、ShardingSphere 配置、准备脚本）
├── start.py / start.bat         # Windows 一键启动编排
└── test-apis.py                 # 核心接口冒烟脚本
```

## 快速启动

### 环境要求

- JDK 17+、Maven 3.8+、Node.js 16+、Python 3.8+
- 本机安装 MySQL、Redis、RocketMQ（4.x），并在 `start.py` 顶部"配置区"核对路径与端口：
  - MySQL `3306`、Redis `6379`、RocketMQ NameServer `9876` / Broker `10911`
  - JDK 17 运行 RocketMQ 4.9.5 需追加模块开放参数（见 `bin/broker_j17.bat` 示例）：
    `--add-opens java.base/java.nio=ALL-UNNAMED --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED --add-exports java.base/jdk.internal.ref=ALL-UNNAMED`

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
- `12306-springcloud-ticket.sql` → `12306_ticket`（含新库存模型：`t_seat` 注册表 + `t_ticket` 售卖区间账本）
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

多实例联调/压测时统一数据源：`test-run/*.yaml` 提供 4 个服务的 ShardingSphere 配置（全部指向单库 `12306`），启动时以命令行覆盖数据源即可，例如：

```bash
java -jar services/ticket-service/target/index12306-ticket-service-0.0.1-SNAPSHOT.jar \
  --spring.datasource.driver-class-name=org.apache.shardingsphere.driver.ShardingSphereDriver \
  "--spring.datasource.url=jdbc:shardingsphere:absolutepath:D:/12306-main/test-run/ticket-shardingsphere.yaml"
```

## 库存模型（三层职责）

| 层 | 载体 | 职责 | 一致性 |
|---|---|---|---|
| 热路径准入 | Redis 座位区间占用位图（`train_carriage_seat_status:车次_车厢`） | 可售判定与占位，**唯一准入裁决**；每个座位占 `相邻站段数` 个 bit，bit=1 已售 | 60s 周期对账任务修复漂移 |
| 持久账本 | `t_ticket`（`departure/arrival` 区间列） | 有效状态票（UNPAID/PAID/BOARDED）即"该座位在 [departure, arrival) 被占用"的事实；DB 兜底统计与位图重建的数据源 | 与购票/关单同事务写入 |
| 座位注册表 | `t_seat`（一座位一行，**无任何状态列**） | 描述"某车次某车厢有哪些物理座位、什么席别"，支撑位图组装与无位图席别兜底 | 静态数据，几乎只读 |

相对原版"座位 × 区间组合行"库存模型（每座位 C(N,2) 行、购票需按区间行数条件 UPDATE 对账），本模型：**行数除以 C(N,2)**、购票临界区零库存 UPDATE、语义从"对账式"变为直接的"区间重叠判断"。

### 位图关键设计

- **重建原子化**：位图缺失时由"注册表 + 账本"在内存组装完整 `byte[]`（布局内全部位置默认占用，仅注册表存在且无有效票覆盖的站段清 0），以 `SET key NX` 一次写入——无分布式锁（NX 天然裁决唯一写入者），消除逐位 pipeline 的"半成品位图"风险
- **锁座整单原子**：Lua 脚本内"预检全部座位目标站段 bit 空闲 → 统一置 1"，任一占用则整单失败且零变更；跨车厢多乘客一单一次 `EVALSHA`
- **席别覆盖**：商务/一等/二等有固定布局走位图；卧铺等无布局席别走注册表 + 账本重叠查询，由购票公平锁串行化保证不超卖
- **对账自愈**（`SeatBitMapReconciler`，60s 周期）：以账本重算期望位图与线上比对，漂移即告警并覆盖修复——接替旧模型中 DB 条件更新的兜底职责

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
      └─ 余票只读预判：读余票缓存 Hash（3s 周期重写），无票请求在此秒拒"已售完→候补"，
         并即时置位售罄广播（见下）
  → 令牌桶（Redis + Lua，按车次维度，准入闸门）
      容量动态 = 当前区间余票 × capacity-multiplier（临近售罄准入自动收紧），
      补充速率固定（refill-rate，与选座临界区实测排水能力匹配）；
      限流器自身故障降级放行，不阻断购票主流程
  → 两级公平锁 + 售罄广播
      ├─ 本地公平锁（Caffeine 缓存锁对象）：JVM 内排队
      ├─ Redisson 分布式公平锁（车次 + 席别）：跨 JVM 先到先得，保障出票顺序
      └─ 等待期间每 200ms 检查售罄广播：售罄即整队列退出，不空等锁位
  → 购票临界区（preparePurchaseTickets，只保留快操作）
      ├─ 售罄快速失败：拿锁后核验余票缓存，不足则即时置位售罄广播并退出
      ├─ 选座引擎（策略模式，hippo4j 动态线程池多席别并行分配）
      │    ├─ 按 车型×席别 路由到 7 个选座 Handler（商务/一等/二等/软卧/硬卧/硬座/无座）
      │    ├─ 二等座三级降级：意向座位 → 同车厢空闲座 → 跨车厢
      │    └─ 可用座位读位图（缺失自动组装，失败回退账本重叠查询）
      ├─ 位图 Lua CAS：整单"预检 + 置位"原子完成（all-or-nothing）
      └─ 车票账本落库（INSERT t_ticket，含售卖区间）；发件箱 `t_order_create_task` 同事务写入（见"容灾处理"）；落库失败补偿释放位图
  → 出临界区 → createTicketOrder（购票锁外执行）
      乘车人补全、票价与车站关系全部读缓存（不落库），Feign 创建订单（携带购票令牌，订单服务令牌幂等）；
      成功即确认发件箱任务；创建失败触发单点补偿：删除车票 + 释放位图 + 作废任务
  → RocketMQ 延迟消息（当前 1 分钟，delayLevel 5，按需调整）
      超时未支付自动关单：账本作废（CLOSED）+ 位图清位 + 回补余票缓存（幂等，MQ 重试兜底）
```

**分层准入的职责划分**：余票缓存回答"还有没有票"（3s 快照，售罄秒拒并引导候补），令牌桶回答"放多快进临界区"（容量随余票伸缩 + 固定放量），两级公平锁回答"谁先出票"（FIFO 公平），位图 Lua CAS 是防超卖的唯一准入裁决、账本是持久事实、对账任务负责收敛。

### 余票缓存与展示层

- **余票缓存只读化**（`train_station_remaining_ticket:车次_出发_到达`，field = 席别）：缓存不参与准入计数，购票路径对它只读——
  - 刷新器每 3 秒拉取全部车厢位图、本地聚合"覆盖站段全空闲"座位数**周期重写**该缓存
  - 活跃车次工作集：仅刷新首页/购票触达过的车次（10 分钟 TTL 退出，下次触达 3s 内恢复新鲜）
  - 卧铺等无固定布局坐席按"注册表 + 账本重叠"统计降频兜底；位图缺失自动组装预热
  - 首页/按钮读该缓存展示"有票/候补"，令牌桶容量按其余票总量伸缩
- **售罄广播（粘滞 + 零点即时）**：
  - 刷新器聚合计数为 0 → 置位广播标志；余票恢复需**连续 2 个刷新周期** > 0 才清除（防自动关单回补导致标志抖动，等待线程永远观察不到售罄）
  - 购票链路（余票预判链 / 临界区库存校验）观测到余票不足时**同步置位标志**——等待中的线程最迟 200ms 退出，无需等刷新周期或超时上限
- 5 类缓存结构：站点-城市映射、城市对车次列表、列车信息、区间票价、余票 Hash（`区间 × 席别`）
- 缓存三防（收敛在框架层 `DistributedCache.safeGet`，业务一行接入）：
  - **穿透** → Redisson 布隆过滤器前置拦截（注册查重过滤器容量 100 万 / 1% 误判率）
  - **击穿** → 分布式锁 + 双重判定回源，N 个并发重建压缩为 1 次 DB 查询
  - **雪崩** → 各调用方自定义过期时间错峰 + 定时预热任务分批写入
- 购票下单路径零落库查询：票价复用首页票价缓存（同键同装载）、车站关系走独立 safeGet、乘车人走 Feign

### 退票、关单与状态闭环

```
支付成功（PayResultCallbackTicketConsumer）
    车票账本 UNPAID → PAID（位图保持占用）
退票成功（RefundResultCallbackTicketConsumer）
    整单/部分退款按证件号匹配乘车人 → 账本 PAID → REFUNDED
    → unlock 释放座位（位图清位；账本作废因已流转为 REFUNDED 天然幂等跳过）
    → 按覆盖站段回补余票缓存
超时未支付（DelayCloseOrderConsumer，延迟消息 1 分钟）
    关单（幂等）→ unlock：有效票账本作废（CLOSED）+ 位图清位 → 余票缓存回补
    失败依赖 MQ 重试与对账任务兜底
改签（复用购票链路）
    新票走购票链路；原票账本流转 CHANGED 后释放旧座位资源；失败自动补偿
```

### 容灾处理（跨服务建单不丢单、不重单）

购票链路中最脆弱的一段是「车票账本已落库 → 订单服务建单」的跨服务窗口：实例在此间宕机，同步链路无法自愈——用户请求已丢失，却留下一条永远无人认领的 UNPAID 账本记录，座位被长期占用形成少卖。本项目以**事务性发件箱 + RocketMQ 补偿**闭环该窗口：

- **发件箱同事务落库**（`t_order_create_task`）：购票临界区内，账本 `t_ticket` 与发件箱记录在同一本地事务写入（含购票幂等令牌 `purchaseToken` 与临界区产物 JSON）——"有账本必有任务"由数据库事务保证，不依赖进程存活
- **同步成功即确认**：`createTicketOrder` 成功后任务置为 CONFIRMED，补偿链路不再介入；同步失败（用户可见的异常）走单点补偿并作废任务——不会给看到报错的用户冒出幽灵订单
- **扫描器 + MQ 补建**（`OrderCreateTaskScanner` 15s 周期 / `OrderCreateConfirmConsumer`）：实例宕机后任务滞留 CREATED，扫描器投递 RocketMQ，消费者先核验账本车票仍存活（防止为已回滚的购票建单），再重建用户上下文复用 `doCreateTicketOrder` 补建；消息丢失靠 MQ 重投，消费幂等用 `@Idempotent`（MQ 场景）
- **订单服务令牌幂等**：同一 `purchaseToken` 全局仅允许创建一单——Redis SETNX 认领订单号 + Redisson 锁**跨事务持有**（事务提交后才释放，防"已认领未提交"窗口被并发误判），覆盖同步与补偿并发建单、消息重复投递、上次尝试回滚后重试三类场景；发现"已认领但订单不存在"自动清理陈旧认领后重建
- **有界兜底**：补偿重试超上限（60 次，约 15 分钟）回滚购票事务释放座位并告警人工介入——少卖可由用户重试挽回，座位被无限期占住会拖垮运力

实测（`test-run/dr_verify.py`）：崩溃状态注入 **4s 内自动补建订单**；任务重发不产生重复订单；真实 `kill -9` 双实例之一，136 个在途请求全部收敛、**2 笔崩溃窗口购票被存活实例自动补建**、零重复零丢单。

> 配套一致性兜底：位图对账任务（60s）以账本为事实修复"置位成功但事务回滚"等漂移；MQ 全链路 at-least-once + 消费幂等，重复投递不产生副作用。

### 关键配置

```yaml
ticket:
  purchase:
    rate-limiter:
      enabled: true
      capacity-multiplier: 1.5   # 桶容量 = 当前区间余票缓存总量 × 该倍数（动态跟随票量）
      refill-rate: 10            # 每秒补充令牌数（与选座临界区实测排水能力匹配）

ticket:
  availability:
    cache-update:
      type: ""            # 已退役配置：t_seat 无状态变更语义，位图与账本一致性由对账任务保证
```

## 其他能力

- **改签/变更到站**：改签费阶梯计算；新旧车次多把公平锁按 Key 排序加锁防死锁；复用购票责任链与选座引擎；失败自动补偿释放新票资源
- **退票**：整单/部分退款，退款结果经 MQ 回调驱动账本流转、位图释放与余票回补
- **幂等组件**（frameworks/idempotent）：一套 `@Idempotent` 注解覆盖 Token 一次性校验、SpEL 业务键、MQ 消费状态机（Lua SETNX + CONSUMING/CONSUMED）
- **分库分表**：ShardingSphere 用户/订单/支付 2 库 × 32 表；雪花 ID 低 4 位嵌入用户基因位，订单号携带路由信息，按订单号/按用户查询均命中同一分片；敏感字段 AES 透明加密
- **支付**：支付宝沙箱 + 本地 Dev 模拟渠道（策略模式），支付结果经 MQ 广播驱动订单/车票服务异步流转

## 性能演进（JMeter 实测：1 万人抢 1000 张，购票双实例）

| 指标 | 基线（同步扣减+冲突清位） | 临界区瘦身+冲突自愈 | 当前（位图+账本模型，Lua CAS，售罄粘滞广播） |
|---|---|---|---|
| 总耗时 | 5 分 02 秒 | 1 分 30 秒 | **1 分 10 秒** |
| TPS | 33.1 | 111.2 | **142.7** |
| 成交 | 96 / 1000（严重少卖） | 996 / 1000 | **1000 / 1000 精确售罄（关单转卖不再虚增）** |
| 座位冲突 | 1115 次（风暴循环） | 0 | **0** |
| 售罄后长尾 | — | — | **售罄即广播退出（max 延迟 81.6s → 62.6s）** |
| 超卖 / 对账漂移 | — | 0 | **0 / 0（60s 对账零告警）** |

历史版本基线的少卖由三个叠加缺陷造成：混合状态座位引发"选座→冲突→清位→再选中"的风暴循环、冲突后余票缓存不回补、位图与 DB 口径不一致；当前版本的库存判定收敛到位图 Lua CAS 单点，DB 只承载账本事实。演进细节见各组件注释与 `test-run/` 压测报告。

## 测试与联调

```bash
# 冒烟测试：登录 → 余票查询 → 购票 → 订单/支付等核心接口
python test-apis.py

# 压测准备（test-run/loadtest_setup.py，1 万人抢 1000 票）
python test-run/loadtest_setup.py seats   # 重建压测车厢座位 + 账本预锁到可售 1000 + 清理缓存
python test-run/loadtest_setup.py users   # 刷新 1 万压测用户登录态（loadtest_users.csv）
python test-run/loadtest_setup.py status  # 查看当前压测状态

# JMeter 压测（双 ticket 实例 9002/9012）
D:/apache-jmeter-5.6.3/bin/jmeter.bat -n -t test-run/loadtest_10000_1000.jmx \
  -l test-run/loadtest_result.jtl -e -o test-run/jmeter_report

# 联调重置（作废该车次有效票账本 + 清余票缓存与座位位图）
POST /api/ticket-service/temp/seat/reset?trainId={车次ID}

# 容灾验证（崩溃恢复 + 令牌幂等 + 真实 kill 实例混沌，需先启动全部业务服务）
python test-run/dr_verify.py A   # 正常购票冒烟：发件箱任务同步确认
python test-run/dr_verify.py B   # 注入崩溃状态：扫描器+MQ 补建订单、重发幂等不重单
python test-run/dr_verify.py C   # 300 并发购票中 kill -9 双实例之一，验证补偿收敛
```

> 运行产物（HTML 报告、JTL、用户 token CSV、日志、本机启动脚本）已在 `.gitignore` 中排除；JMeter 计划、准备脚本与 ShardingSphere 运行配置纳入版本管理。

## 声明

- 本项目基于 [nageoffer/12306](https://github.com/nageoffer/12306) 学习二次开发，原版架构解析见其官方文档
- 仅用于学习交流，请勿用于生产环境
