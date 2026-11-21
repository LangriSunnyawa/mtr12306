# Modrinth 上传文案（中英双语 · v1.0.0 · 含大屏已知问题声明）

## Summary（简介栏 · 256 字符以内）

**中文：**
> 基于 MTR 的网页化铁路客票系统：浏览器查车次、选座购票，用 MTR fares 钱包扣款；游戏内闸机右键检票、车站大屏、广播器、售票机与国铁样式报销凭证，完整还原 12306 乘车流程。（注意：车站大屏多格拼接存在问题，v1.0.0 建议先用 1×1）

**English：**
> A web-based railway ticketing system for MTR: browse trains and buy seats in your browser, pay with the MTR fares wallet, then validate at ticket gates in-game. Includes departure boards, station broadcasters, ticket machines and China-railway-style receipts. (Note: multi-block departure boards are buggy in v1.0.0 — use 1×1 only.)

---

## Description（正文 · 可直接整段粘贴）

```markdown
> ⚠️ **已知问题 / Known issue**
> **中文：** 车站大屏目前存在 bug：拼成多格（大于 1×1）时，非锚点格不会绘制画面内容，文字也可能偏移。**v1.0.0 请只使用 1×1 大屏**，其余功能不受影响，后续版本修复。
> **English:** The station departure board is currently buggy: when tiled larger than 1×1, only the anchor block renders content and text may be misaligned. **Please use 1×1 boards only in v1.0.0.** Everything else works; a fix is planned.

---

## 中文

在 Minecraft 里还原 12306：**网页端买票，游戏里检票坐车**。

### 前置要求
- Minecraft **1.20.1** + Fabric API
- **MTR（Minecraft Transit Railway）4.0.5+**（强制依赖）
- 客户端与服务端都需要安装本模组

### 主要功能
- **网页购票**：查车次 → 选坐席（二等 / 一等 / 商务 / 城际自由席）→ 选座 → 用 MTR fares 钱包扣款出票；支持注册登录、退票、查看与打印报销凭证
- **检票闸机**：进站 / 出站 / 双向 / 换乘 / 车票核验 五种模式；右键检票（用 **MTR 的刷子** 配置），关门时整格实心且防翻越；可绑定一个或多个检票口，也可留空表示不限
- **车站大屏**：显示本站车次 / 开点 / 终到 / 站台 / 检票状态，可按检票口筛选；**v1.0.0 仅建议 1×1 使用（见上方已知问题）**
- **车站广播器**：自动播报正在检票的车次（京张广播格式文案，可在配置中修改）
- **售票机**：复用 MTR 售票机模型（两格高），游戏内购票，并可打印**国铁蓝票样式的报销凭证**（物品，右键即时渲染查看）
- **车场排班表**：按车场设置交路线路数，每班填写 `车次1|车次2`，数量 / 格式 / 重复自动校验
- **规则贴近 12306**：禁止购买时间冲突的车票、允许买短乘长、0.1 元 / 公里的里程计价

### 快速上手
1. 服务端启动后访问 `http://服务器IP:18127/` 购票，`/admin` 为管理后台（**默认密码请务必修改**）
2. 后台依次创建车站、检票口、车次（支持批量开行）
3. 游戏内放置闸机 / 大屏 / 广播器，用 **MTR 的「刷子」** 右键配置所属车站与检票口
4. 车次进入检票状态后，持有效车票右键闸机即可进站

> 提示：车次需要配合 MTR 的车站与线路使用；更多配置项见仓库 README。

---

## English

A faithful 12306-style workflow in Minecraft: **buy in the browser, ride in-game**.

### Requirements
- Minecraft **1.20.1** + Fabric API
- **MTR (Minecraft Transit Railway) 4.0.5+** (required)
- Install on both client and server

### Features
- **Web ticketing**: browse services → choose a seat class (2nd / 1st / business / intercity non-reserved) → pick a seat → pay with the MTR fares wallet. Includes registration/login, refunds and reimbursements.
- **Ticket gates**: entry / exit / two-way / transfer / validation modes. Right-click to validate (configure with **MTR's Brush**); the gate is fully solid when closed and cannot be vaulted. Bind one or more checkpoints, or leave empty for "any".
- **Station departure boards**: shows service, departure time, destination, platform and boarding status, filterable by checkpoint. **v1.0.0: use 1×1 only (see known issue above).**
- **Station broadcaster**: automatically announces boarding services in a China-railway announcement style (text configurable).
- **Ticket machine**: reuses MTR's two-block ticket machine model. Buy tickets in-game and print **China-railway-style (blue) reimbursement receipts** as items — right-click to view, rendered live from ticket data.
- **Depot timetable board**: set the number of routes per depot, then enter `trainA|trainB` per departure with automatic count / format / duplicate validation.
- **12306-like rules**: overlapping-trip purchases are blocked, "buy short, ride long" is allowed, and fares use a flat 0.1 CNY per km mileage pricing.

### Quick start
1. After the server starts, open `http://SERVER-IP:18127/` to buy tickets; `/admin` is the admin panel (**change the default password**).
2. In the admin panel create stations, checkpoints and services (batch creation supported).
3. Place gates / boards / broadcasters and right-click them with **MTR's Brush** to bind the station and checkpoints.
4. Once a service starts boarding, right-click a gate with a valid ticket to enter.

> Note: services are tied to MTR stations and routes. See the repository README for all configuration options.

---

## Changelog note（可选填在 Version changelog）

**中文：** v1.0.0 首个正式版本。**已知问题：车站大屏多格拼接异常（请用 1×1）。**
**English:** v1.0.0 first stable release. **Known issue: multi-block station boards are broken — use 1×1.**
```
