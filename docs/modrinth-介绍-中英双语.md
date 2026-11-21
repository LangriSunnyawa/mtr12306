# Modrinth 上传文案 · v1.0.0 · 中英双语（详细版）

> 使用说明：Summary 栏只能填一段短文本（≤256 字符），建议主填中文；把 English Summary 放到正文最前面。
> 正文（Description）可整段复制下面 ```markdown 代码块里的内容。

---

## Summary（简介栏）

**中文：**
> 基于 MTR 的网页化铁路客票系统：浏览器查车次、选座购票、用 MTR fares 钱包扣款；游戏内用闸机右键检票、售票机购票、广播器播报、报销凭证打印，完整还原 12306 乘车流程。（v1.0.0 车站大屏暂不可用）

**English：**
> A web-based railway ticketing system for MTR: search services and buy seats in your browser, pay with the MTR fares wallet, then validate at ticket gates in-game. Ticket machines, station broadcaster, reimbursement receipts and depot timetables included. (Station board is unavailable in v1.0.0.)

---

## Description（正文）

```markdown
# 🚄 MTR-12306 · 铁路客票系统 / Railway Ticketing System

> ⚠️ **已知问题 / Known issue（v1.0.0）**
> **中文：** **车站大屏方块目前无法正常使用**（放置后显示 / 配置异常），请**暂时不要使用车站大屏**。其余所有功能（网页购票、闸机检票、售票机、报销凭证、广播器、排班表）均正常，修复会在后续版本提供。
> **English:** **The Station Board block is currently unusable in v1.0.0** (display/configuration is broken). **Please do not use it for now.** Everything else — web ticketing, ticket gates, ticket machine, receipts, broadcaster and depot timetables — works normally. A fix is coming in a later version.

---

## 🇨🇳 中文

### 这是什么
MTR-12306 把**中国铁路 12306 的完整乘车流程**搬进 Minecraft：你不再是"放个闸机就过"，而是要**先在网页上买票**，再**在游戏里用有效车票通过闸机**。它与 **MTR（Minecraft Transit Railway）** 深度配合——车次绑定 MTR 的车站与站台，票价按里程计费，扣款走 MTR 的 fares 钱包（`mtr_balance` 计分板）。

### 前置要求
| 项目 | 要求 |
| --- | --- |
| Minecraft | **1.20.1** |
| 加载器 | Fabric Loader ≥ 0.15 |
| 依赖 | **Fabric API**、**MTR ≥ 4.0.5**（强制） |
| Java | 17+ |
| 安装位置 | **客户端与服务端都要装**（网页功能在服务端运行） |

### 核心流程
1. **服务端启动** → 自动开启网页售票系统
   - 乘客端：`http://服务器IP:18127/`
   - 管理端：`http://服务器IP:18127/admin`（**默认密码 `mtr12306`，务必修改**）
2. **管理后台**建车站 → 建检票口 → 开车次（支持批量开行：号段 + 停站模板 + 车底 + 首班时刻 + 间隔）
3. **游戏内摆闸机** → 用 **MTR 原版「刷子」** 右键配置：选车站、多选检票口（留空 = 不限）、选模式
4. **乘客买票**：网页选车次 → 选坐席 → 选座位 → 扣款出票
5. **开检后** → 持票右键闸机 → 闸门打开 → 屏幕显示乘坐区间 → 出站闸机自动记录出站

### 主要功能

#### 🎫 网页购票（乘客端）
- 按车站 / 日期搜索车次，显示时刻、历时、余票、票价
- 坐席分类：二等座 / 一等座 / 商务座 / **城际自由席（不对号）**
- 可视化选座（`A B C|D F` 布局，靠窗/过道），**餐车车厢不可选座**
- 账号系统：昵称 + Minecraft 名称 + 密码注册（加盐哈希），支持登录、改密
- 我的车票：查看行程、区间、席位、票价；可**退票**（原路退款）
- **报销凭证**：在线查看国铁蓝票样式凭证，或打印（打印后不可退票）

#### 🚪 检票闸机（5 种模式）
| 模式 | 作用 |
| --- | --- |
| 进站 | 核销本站正在检票车次的车票，放行并显示乘坐区间 |
| 出站 | 只核销**最近一张**已进站未出闸的车票，支持**买短乘长** |
| 双向 | 进站/出站合一 |
| 换乘 | 一次完成"出闸 + 接续车次检票"（换乘窗口可配置） |
| 车票核验 | 大车站同款：**不检票**，只核验你名下有没有 N 小时（默认 12）内的有效车票 |

- 通行方式：**右键检票**（不再是走过去自动触发）
- 关门时**整格实心碰撞**并带**防翻越**：跳起来也会被推回原侧
- 模型/贴图复用 **Metropolis** 的 turnstile；相邻同朝向闸机会自动拼接成"并排"样式
- 开检 / 停止检票 / 提前量 / 迟到宽限 均可在配置里调整

#### 🎟️ 售票机
- 模型直接复用 **MTR 售票机（两格高）**，与原版 MTR 车站风格一致
- 右键打开界面：**购买本站车次车票**（需要已注册的 mtr12306 账号）
- 支持**打印报销凭证**：生成一个物品，手持右键打开**国铁蓝票样式**界面，内容按车票数据**即时渲染**

#### 📢 车站广播器
- 绑定方式与闸机一致（车站 + 多选检票口）
- 自动播报"正在检票"的车次，格式贴近**京张高铁广播**：
  `各位旅客请注意，G1234次列车开始检票，请到3站台检票上车。感谢您的配合。`
- 前缀 / 后缀可在 `config/mtr12306.json` 里改；半径 64 格内玩家收到广播

#### 🗓️ 车场排班表（后台）
- 按车场配置：是否启用、**交路里有几条线路**
- 每班填写 `车次1|车次2`（几个线路填几个车次）
- 自动校验：**数量不符 / 时刻格式错 / 车次重复** 都会标红并拒绝保存

#### 📋 贴近 12306 的规则
- **禁止时间冲突购票**：与本人已有有效票的乘车时段重叠即拒绝，并提示冲突车次
- **允许买短乘长**：票面到站早于实际下车站也能出闸
- **里程计价**：统一 `0.1 元/公里`（`farePerKm` 可改），按坐席系数与全局倍率计算
- **跨日车次**：支持 `dayOffset`，跨零点车次时刻正确

### 常用命令（需要 OP）
```
/mtr12306 station add <名称>            建车站
/mtr12306 checkpoint add <车站> <名称>   建检票口
/mtr12306 service add ...              建车次
/mtr12306 gate bind <检票口id> [模式]    给准星所指闸机绑定
/mtr12306 gate simulate <x> <y> <z> <玩家> <in|out> [consume] [apply]   闸机判定自检
/mtr12306 gate mockpass <x> <y> <z> <玩家> <in|out>                     用临时玩家真实走一遍
/mtr12306 boarding open <车次> <车站> [分钟]    手动开检
```

### 主要配置（`config/mtr12306.json`）
| 键 | 说明 |
| --- | --- |
| `webPort` | 网页端口（默认 18127，被占用会自动 +1 重试） |
| `adminPassword` | 管理端密码（**务必修改**） |
| `timezone` | 时区，默认 `Asia/Shanghai` |
| `farePerKm` | 里程单价，默认 0.1 元/公里 |
| `boardingOpenMinutes` | 提前多少分钟开检 |
| `lateGraceMinutes` | 停止检票后的宽限分钟 |
| `transferWindowMinutes` | 换乘接续窗口（分钟） |
| `validateHours` | 核验闸机的有效票时间窗（小时） |
| `gateOpenSeconds` | 闸门开启时长（秒） |
| `broadcastPrefix/Suffix` | 广播前后缀文案 |
| `requirePayment` / `requireAuth` | 是否强制扣款 / 强制登录 |

### 已知问题与计划
- ❌ **车站大屏（Station Board）在 v1.0.0 不可用**：方块本体无法正常显示与配置，请勿使用；后续版本会重做
- 🚧 多版本适配（1.20.4 / 1.21.1）尚未开始，目前**仅支持 1.20.1 + MTR 4.0.5**
- 🚧 车票改签、学生票/儿童票等票种暂未实现

### 致谢
- **MTR（Minecraft Transit Railway）** —— 本模组的基础设施与 fares 钱包
- **Metropolis** —— 闸机模型与贴图
- 本项目以 **MIT** 许可开源

---

## 🇬🇧 English

### What is this
MTR-12306 brings the **full Chinese 12306 ticketing flow** into Minecraft. You can't just walk through a gate — you **buy a ticket in the browser first**, then **validate it in-game**. It integrates deeply with **MTR (Minecraft Transit Railway)**: services are bound to MTR stations and platforms, fares are mileage-based, and payment goes through MTR's fares wallet (the `mtr_balance` scoreboard).

### Requirements
| Item | Requirement |
| --- | --- |
| Minecraft | **1.20.1** |
| Loader | Fabric Loader ≥ 0.15 |
| Dependencies | **Fabric API**, **MTR ≥ 4.0.5** (required) |
| Java | 17+ |
| Where | **Install on both client and server** |

### The flow
1. **Start the server** — the web ticketing system starts automatically
   - Passengers: `http://SERVER-IP:18127/`
   - Admin: `http://SERVER-IP:18127/admin` (**default password `mtr12306` — change it!**)
2. **In the admin panel**: create stations → checkpoints → services (batch creation supported)
3. **Place gates in-game** → right-click with **MTR's Brush** to bind station, checkpoints (multiple or none = any) and mode
4. **Passengers buy tickets** in the browser (service → seat class → seat → pay)
5. **When boarding opens**, right-click a gate with a valid ticket → gate opens, your travel segment is shown on screen; exit gates record your exit automatically

### Features

#### 🎫 Web ticketing (passenger side)
- Search services by station/date with times, duration, availability and fares
- Seat classes: 2nd / 1st / business / **intercity non-reserved (free seating)**
- Visual seat picker (`A B C|D F` layout, window/aisle); **dining cars are not selectable**
- Accounts: nickname + Minecraft name + password (salted hash), login and password change
- "My tickets": itinerary, segment, seat, fare and **refunds**
- **Reimbursement receipts**: view a China-railway-style (blue) receipt online, or print it (printing makes the ticket non-refundable)

#### 🚪 Ticket gates (5 modes)
| Mode | Behaviour |
| --- | --- |
| Entry | Validates a ticket for a service boarding at this station, opens and shows your segment |
| Exit | Consumes only the **most recent** boarded ticket; supports **buy-short-ride-long** |
| Two-way | Entry + exit in one gate |
| Transfer | Does exit + check-in for the connecting service in one action (window configurable) |
| Validation | Big-station style: **no check-in**, just verifies you own a valid ticket departing within N hours (default 12) |

- Access is **right-click to validate** (no more walk-through triggering)
- When closed the gate is **fully solid** and **anti-vault** — jumping over pushes you back
- Model and textures reuse **Metropolis** turnstiles; adjacent gates with the same facing merge into the "in-line" variant automatically

#### 🎟️ Ticket machine
- Reuses **MTR's two-block ticket machine** model, so it matches MTR stations
- Right-click to **buy tickets for services at this station** (requires a registered mtr12306 account)
- **Prints reimbursement receipts** as items — right-click to open a China-railway blue-ticket screen rendered live from the ticket data

#### 📢 Station broadcaster
- Bound like gates (station + multiple checkpoints)
- Automatically announces services that are now boarding, China-railway style:
  `Attention please, train G1234 is now boarding at platform 3. Thank you.`
- Prefix/suffix configurable in `config/mtr12306.json`; players within 64 blocks hear it

#### 🗓️ Depot timetable board (admin panel)
- Per depot: enable/disable and **how many routes the diagram has**
- Each departure takes `trainA|trainB` (one per route)
- Automatic validation of **count mismatch / bad time format / duplicate train numbers**

#### 📋 12306-like rules
- **No overlapping trips**: a purchase that overlaps an existing valid ticket is rejected with the conflicting service named
- **Buy short, ride long** allowed
- **Mileage pricing**: flat `0.1 CNY/km` (`farePerKm`), scaled by seat class and a global multiplier
- **Cross-midnight services** supported via `dayOffset`

### Useful commands (OP)
```
/mtr12306 station add <name>
/mtr12306 checkpoint add <station> <name>
/mtr12306 service add ...
/mtr12306 gate bind <checkpointId> [mode]
/mtr12306 gate simulate <x> <y> <z> <player> <in|out> [consume] [apply]
/mtr12306 gate mockpass <x> <y> <z> <player> <in|out>
/mtr12306 boarding open <service> <station> [minutes]
```

### Configuration (`config/mtr12306.json`)
| Key | Description |
| --- | --- |
| `webPort` | Web port (default 18127; auto-increments if busy) |
| `adminPassword` | Admin password (**change it**) |
| `timezone` | Defaults to `Asia/Shanghai` |
| `farePerKm` | Price per km, default 0.1 |
| `boardingOpenMinutes` | How early boarding opens |
| `lateGraceMinutes` | Grace period after boarding closes |
| `transferWindowMinutes` | Transfer connection window |
| `validateHours` | Validation gate time window (hours) |
| `gateOpenSeconds` | How long the gate stays open |
| `broadcastPrefix/Suffix` | Announcement text |
| `requirePayment` / `requireAuth` | Enforce payment / login |

### Known issues & roadmap
- ❌ **The Station Board block is unusable in v1.0.0** — it does not display or configure correctly. Please don't use it; it will be reworked in a later version.
- 🚧 Multi-version support (1.20.4 / 1.21.1) not started yet — **1.20.1 + MTR 4.0.5 only**
- 🚧 Ticket changes (rebooking), student/child fares not implemented

### Credits
- **MTR (Minecraft Transit Railway)** — infrastructure and the fares wallet
- **Metropolis** — turnstile model and textures
- Licensed under **MIT**

---

## Changelog（Version changelog 可填）

**中文：** v1.0.0 首个正式版本：网页购票、五种闸机、售票机与国铁样式报销凭证、车站广播器、车场排班表。**已知问题：车站大屏不可用。**
**English:** v1.0.0 first stable release: web ticketing, five gate modes, ticket machine with blue-ticket receipts, station broadcaster, depot timetables. **Known issue: the Station Board is unusable.**
```
