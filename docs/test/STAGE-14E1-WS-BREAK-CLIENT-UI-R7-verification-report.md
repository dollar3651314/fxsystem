# STAGE-14E1-WS-BREAK-CLIENT-UI R7 验证报告

> 验证日期：2026-06-02
> 验证人：Claude Opus 4.8（在 R1 Commander 调度下作为 R7）
> 任务：`STAGE-14E0`（D1/D2 遗留 stale IT 修）+ `STAGE-14E1` WebSocket 双币 / MarginLevel 字段最终 break（master §7.5）+ 客户端实时 UI（mode toggle + MarginLevel 浮窗 + 双币 PnL）。E 阶段第一切片（客户端 + 后端 WS 层）；admin FX 监控 + 多币聚合留 STAGE-14E2。

---

## §0. 部署前置阻断项（最高优先级，必读）

> **🔴 本切片 E1 在 trading-core 后端 WS/REST 层 + 客户端代码侧已实现并通过 UT/IT/vitest/build 验证，但存在 (1) 沿 14B 叠加的库 schema 修复阻断 + (2) 本阶段特有的硬 break 部署协调，部署前必须处理。**

### 阻断项 A：14B 遗留 schema 漂移（沿 14B/C1/C2/D1/D2/D3 叠加，E1 未引入新 migration）

- E1 **无新 migration**（纯 WebSocket / REST DTO 字段 + 实时算 + 前端）。
- 沿 14B：V28（`t_ledger` 三列）/ V29（`t_position.entry_fx_rate`）在 root bug 期间误写 `USE falconx_trading;`（已由 `1031a9ce` 修复），致这两列在被污染库已物理存在但 `flyway_schema_history` 无 V28/V29 行。
- 风险：被污染的既有 trading 库下次 `flyway migrate` 仍会先撞 `Duplicate column` 失败。**E1 不引入新阻断，但不解除既有阻断。**
- 修复指引（部署前由 DBA / 部署执行，沿 D2/D3 §0）：先修 14B V28/V29 漂移（手动补 `success=1` 行用删 USE 后 checksum / `flyway repair` + 人工核对列已存在）→ 再正常 migrate（trading V30-V37 + console V13）。**禁止生产/演示库裸跑 `flyway migrate`。** 干净全新库无此问题。

### 阻断项 B：🔴 硬 break 部署协调（E1 本阶段特有，无 legacy 兼容）

- E1 把 WebSocket / REST 字段**硬切**：`position.update` / `position.pnl` / REST 持仓列表 **删 `unrealizedPnl`**，加 `quoteCurrency / fxRate / unrealizedPnlInQuote / unrealizedPnlInAccount / isolatedMargin`；`account.update` / `account.snapshot` / REST account 加 `equity / marginLevel / marginLevelStatus`，`openPositions` 同步双币。**无旧字段并存。**
- 因此 **`trading-core-service` 与 `falconx-frontend` 必须同窗口部署**：
  - 若先部署后端、旧前端仍在线 → 旧前端解析新帧时找不到 `unrealizedPnl`，浮盈亏列丢失。
  - 若先部署前端、旧后端仍在线 → 新前端找不到双币字段，浮盈亏列丢失。
- 部署须按发布窗口同时上线两端；灰度 / 蓝绿需保证同一用户会话内前后端版本一致。

---

## §1. 范围

本切片覆盖 [STAGE-14 多币种 + CROSS/ISOLATED 保证金总设计稿](../design/STAGE-14-MULTICURRENCY-AND-CROSS-MARGIN-MASTER-design.md) §9 E 阶段的**第一切片**（客户端 + 后端 WS 层），落地 master §7.5（WebSocket 最终 break）+ §3.2（双币 PnL / MarginLevel 实时算）：

| 子能力 | E0/E1 交付 | 关键 commit |
|---|---|---|
| E0 stale IT 修 | `shouldReturnMarginModeNotSupportedWhenOrderRequestsCross`：marginMode=CROSS 现返 30088 非 40010，改名 + 改断言 + 补 cross_mode 开启镜像用例（restore 防污染） | `e52856c1` |
| E1 实施计划 | position/account 硬切双币 + marginLevel + mode toggle + MarginLevel 浮窗 + 双币 PnL，8 task | `c39fc5e3` |
| position 双币硬切（后端） | `position.update` / `position.pnl` / REST 持仓列表删 `unrealizedPnl`，加 5 双币字段 + 工厂 `calculatePositionPnlInAccount` 实时算（复用 D2，FX 降级 entryFxRate）+ UT | `772d513b` |
| account marginLevel（后端） | `account.update` / 快照 / REST account 加 `equity / marginLevel / marginLevelStatus`（`AccountEquityCalculator` + `MarginLevelMonitor`）+ `openPositions` 硬切双币 + 修循环依赖 `@Lazy` + UT | `ce7ffbd9` |
| 真 WS 端到端 IT + 全量回归 | position.pnl 双币 + account marginLevel 端到端 IT + 全量回归 586/1-fail | `7f8e1d47` |
| 客户端 WS hook + 类型硬切 | `useTradingSocket` / `tradingTypes` / `tradingApi` 删 unrealizedPnl 加双币 + equity/marginLevel/accountMarginMode + Terminal state + vitest + build0 | `e8c4a817` |
| 客户端 MarginModeToggle | 对接 `/me/margin-mode`（canSwitch/blockers 中文/确认 modal/30080-30088）+ vitest | `9b31d3b6` |
| 客户端 MarginLevel 浮窗 | `MarginLevelIndicator` 三态浮窗 + 百分比 + MARGIN_CALL/STOP_OUT 跃迁 critical toast + Dashboard 第 6 卡 + marginMode 展示 + vitest | `4a5333f3` |
| 客户端双币持仓行 | 持仓双币双行展示（账户币主 + 报价币副 + 币种标注，`pnlDisplay.ts` 共享助手）+ 清理 Task4 遗留 + vitest 162 + build0 | `a2105bba` + `20d25cbb` |
| R8 文档 + R7 报告 + 计划录入 | 本 commit | 本 commit |

**不在 E1 范围**（划 E2）：

- **STAGE-14E2 管理端**：admin FX rate 监控 page（console-frontend，承接 §15B 留项）+ admin 多币种聚合视图（账户/持仓跨币种汇总，解 `getPositionSummary` 单币聚合限制）+ admin 持仓多币种展示。

---

## §2. 角色

E1 为**后端 WS 层（trading-core）+ 客户端（falconx-frontend）**双端切片（无 console），按 [`AI工作模式 §2`](../process/AI工作模式.md) 角色路由：

- **R2 Contract Designer**：WebSocket / REST DTO 契约 break（删 `unrealizedPnl`、加双币 + equity/marginLevel/marginLevelStatus），master §7.5 冻结。
- **R4 业务后端**：position/account payload 工厂实时算（复用 D2 `calculatePositionPnlInAccount` + `AccountEquityCalculator` + `MarginLevelMonitor`）+ 循环依赖 `@Lazy` 修复 + E0 stale IT 修。
- **R5 客户端**：WS hook 类型硬切 + `MarginModeToggle` + `MarginLevelIndicator` 浮窗 + 双币持仓行 + `pnlDisplay.ts` 共享助手。
- **R6 Test**：后端 UT（`TradingUserRealtimePayloadFactoryTests` 4 + `TradingAccountSnapshotApplicationServiceTests` 3）+ 真 WS 端到端 IT 1；前端 vitest 162。
- **R7 QA**：本报告 + 浏览器 QA（WSL 受限标注）。
- **R8 Doc**：WebSocket 规范 §5.4 + 统一接口文档 §3.5/§3.15/§3.31.8 + 计划 + BBook §15I。

---

## §3. 各 Task 证据

| Task | commit | 证据 |
|---|---|---|
| E0 stale IT | `e52856c1` | `TradingControllerIntegrationTests` 改名 + 断言 marginMode=CROSS 返 30088 + cross_mode 开启镜像用例（restore） |
| E1-1 position 双币 | `772d513b` | `TradingPositionPnlUpdatePayload` / `TradingPositionItemResponse` 删 `unrealizedPnl` 加 5 双币字段；`TradingUserRealtimePayloadFactory.calculatePositionPnlInAccount` 实时算 + FX 降级；`toPositionResponse` 委托 factory |
| E1-2 account marginLevel | `ce7ffbd9` | `TradingAccountResponse` 加 equity/marginLevel/marginLevelStatus；`TradingAccountPositionResponse` 硬切双币；`@Lazy` 破循环 |
| E1-3 WS IT + 回归 | `7f8e1d47` | 真 WS 连接端到端 IT（position.pnl 双币 + account.update marginLevel）+ 全量回归 586/1-fail |
| E1-4 客户端 hook | `e8c4a817` | `useTradingSocket` / `tradingTypes` / `tradingApi` 删 unrealizedPnl 加双币 + equity/marginLevel/accountMarginMode；Terminal state；vitest；build0 |
| E1-5 MarginModeToggle | `9b31d3b6` | 对接 `/me/margin-mode`，canSwitch/blockers 中文、确认 modal、30080-30088 toast；vitest |
| E1-6 MarginLevel 浮窗 | `4a5333f3` | `MarginLevelIndicator` 三态 + 百分比 + 跃迁 critical toast；Dashboard 第 6 卡；vitest |
| E1-7 双币持仓行 | `a2105bba` + `20d25cbb` | 双行展示（账户币主 + 报价币副 + 币种标注）；`pnlDisplay.ts` 共享助手同币种省略；vitest 162；build0 |
| E1-8 收口 | 本 commit | 浏览器 QA + R8 + R7 + 计划录入 |

---

## §4. 测试统计

### 后端（trading-core）

- UT：`TradingUserRealtimePayloadFactoryTests` 4（position 双币实时算 + FX 降级 entryFxRate）+ `TradingAccountSnapshotApplicationServiceTests` 3（account equity/marginLevel/marginLevelStatus 实时算 + openPositions 双币）。
- IT：真 WS 连接端到端 1（position.pnl 双币字段 + account.update marginLevel）。
- 全量回归：**586 tests / 1 failure**。唯一失败 = 既有 `TradingKafkaWalletDepositIntegrationTests`（Kafka consumer group 异步计数竞态，pre-existing flake，与本阶段无关）。**零 E1 新增失败**；E0 已消除 D3a 残留的 `shouldReturnMarginModeNotSupportedWhenOrderRequestsCross` stale IT（现返 30088 断言通过）。

### 前端（falconx-frontend）

- vitest 全量 **162 绿**（含 `useTradingSocket` 双币/marginLevel 解析、`MarginModeToggle` canSwitch/blockers/确认 modal/拒单码 toast、`MarginLevelIndicator` 三态 + 跃迁 toast、双币持仓行 `pnlDisplay` 同币种省略）。
- lint 0（改动文件）；build 退出 0。

---

## §5. 已知不阻断项 / 边界 / refinement

| # | 项 | 性质 | 说明 |
|---|---|---|---|
| ① | marginLevel 推送在 fill/close 非 per-tick | refinement | 浮窗显示最近一次 fill/close 后的 MarginLevel 值；per-tick 实时刷新属后续 refinement。`account.snapshot`（连接/重连）携当前快照值。 |
| ② | account 级 FX 降级 equity/marginLevel=null，而 per-position 行降级 entryFxRate→非空 | 口径不对称 / tech-debt | 账户级聚合 FX 不可用时保守置 null（不抛），per-position 行用开仓冻结 entryFxRate 优雅降级。两侧方向不同（账户级保守、行级优雅）。建议给 `TradingAccountResponse.equity` 加 doc 注释说明。 |
| ③ | `MarginLevelMonitor`→Notification→Push→Registry 循环用 `@Lazy` 打破 | tech-debt | 当前用 `@Lazy` 解循环依赖；建议后续事件化解耦 MarginCall 通知链路。 |
| ④ | `MarginLevelIndicator` 移动端 tap 开浮窗 | 待核 | button focus on tap，多数移动浏览器 OK；真机 QA 待人工核验。 |
| ⑤ | `getPositionSummary` 聚合仍单币 | 范围外 | B 阶段既有 mixed-currency 已知问题，非 E1 范围；多币聚合留 E2。 |
| ⑥ | 浏览器视觉 QA WSL 受限 | WSL 受限手动项 | 见 §7。 |
| ⑦ | 既有 Kafka flake + 瞬态死锁 flake | pre-existing | `TradingKafkaWalletDepositIntegrationTests` + `cleanOwnerTables` 瞬态死锁，非本阶段引入。 |

---

## §6. WebSocket / REST 字段无 legacy 残留证据

- `TradingPositionPnlUpdatePayload` / `TradingPositionItemResponse` / `TradingAccountPositionResponse`：record 定义中**已无 `unrealizedPnl` 字段**，仅 `unrealizedPnlInQuote` / `unrealizedPnlInAccount` + `quoteCurrency` / `fxRate` / `isolatedMargin`（commits `772d513b` / `ce7ffbd9`）。
- `TradingAccountResponse`：在 `marginMode` 后新增 `equity` / `marginLevel` / `marginLevelStatus`（commit `ce7ffbd9`）。
- 客户端 `tradingTypes` / `useTradingSocket` / `tradingApi`：类型定义删 `unrealizedPnl`，加双币 + equity/marginLevel/accountMarginMode（commit `e8c4a817`），与后端 break 同切片同步，无 legacy 并存。

---

## §7. master §8.3 E 阶段验收（客户端 + 后端 WS 层可证部分）

| 验收项 | 状态 | 证据 |
|---|---|---|
| WS break 字段无 legacy 残留 | ✅ | §6：DTO record 已删 `unrealizedPnl`，前后端同步切换 |
| 客户端 mode toggle | ✅（vitest）| `MarginModeToggle` 对接 `/me/margin-mode`，canSwitch/blockers/确认 modal/30080-30088 toast 用例覆盖 |
| 客户端 MarginLevel 浮窗 | ✅（vitest）| `MarginLevelIndicator` 三态 + 百分比 + MARGIN_CALL/STOP_OUT 跃迁 critical toast 用例覆盖 |
| 客户端双币持仓行 | ✅（vitest）| 双行展示（账户币主 + 报价币副 + 币种标注）+ `pnlDisplay.ts` 同币种省略用例覆盖 |
| 桌面 + 移动浏览器视觉 QA（6 截图） | ⚠️ WSL 受限手动项 | 见下 |

### 浏览器 QA 结论（WSL 受限，不伪造）

- 环境：本会话 WSL 无 chromium 系统包；启动 `falconx-frontend` dev server（实际监听 `:5200`）后用 Playwright MCP 导航成功（HTTP serve build 正常），但**页面渲染空白**——客户端实时 UI（mode toggle / MarginLevel 浮窗 / 双币持仓行）依赖后端栈（gateway / identity / trading-core）+ 认证态 + 真 WS 推送，本环境无后端栈与登录会话，应用进入终端路由后无业务数据渲染。控制台唯一 error 为 Zustand persist 迁移告警（`State loaded from storage couldn't be migrated`，benign，非渲染崩溃）。
- 结论：**客户端 build 可 serve、构建产物健康（vitest 162 + build 0），但桌面 + 移动 6 截图视觉 QA 需真机 / CI（有后端栈 + 认证态 + 真 WS）补**。与前阶段（D3b 等）口径一致：视觉 QA 标注为 WSL 受限手动项，由 vitest + build 覆盖组件行为，**不伪造截图**。

---

## §8. 文档同步清单（R8）

| 文档 | 状态 | 内容 |
|---|---|---|
| `docs/api/WebSocket接口规范.md` | ✅ | §5.1 account.snapshot 示例加 marginMode/equity/marginLevel/marginLevelStatus；§5.3 表加 `position.pnl` 行；新增 §5.4 STAGE-14E1 双币 / MarginLevel 字段最终切换（硬 break 声明 + position.update/position.pnl data 字段表删 unrealizedPnl 加 5 双币字段 + account.update/snapshot data 加 equity/marginLevel/marginLevelStatus + marginLevel 在 fill/close 推送语义） |
| `docs/api/FalconX统一接口文档.md` | ✅ | §3.5 accounts/me 响应说明 + JSON 示例加 equity/marginLevel/marginLevelStatus + openPositions 双币；§3.15 持仓列表说明 + JSON 示例硬切双币；WS 帧表加 `position.pnl` + E1 硬 break 过渡说明（取代 14B 值口径过渡）；§3.31.8 客户端 `/me/margin-mode` 对接登记 |
| `docs/setup/当前开发计划.md` §1 | ✅ | STAGE-14E0 + STAGE-14E1 收口条目（范围 + commits + 测试 586/1 + vitest 162 + 硬 break 部署须同窗口 + 已知不阻断/refinement + 下一步 E2）；D3b 下一步指向更新 |
| `docs/process/BBook一期完成执行路径.md` | ✅ | §15I STAGE-14E0 + E1 收口条目（E0 stale IT 修 + E1 8 task + 测试 + 部署阻断 + 硬 break 部署协调 + refinement + 下一步 E2） |

---

## §9. 结论

按 [AGENTS.md §8.1.2](../../AGENTS.md) 生产可用判定：

**E1（WebSocket 双币 / MarginLevel 字段最终 break + 客户端实时 UI）在 trading-core 后端 WS/REST 层 + 客户端 `falconx-frontend` 代码侧已完整实现**，并通过后端 UT 7（`TradingUserRealtimePayloadFactoryTests` 4 + `TradingAccountSnapshotApplicationServiceTests` 3）+ 真 WS 端到端 IT 1 + 全量回归 586/1-fail（唯一失败 = 既有 Kafka flake，零 E1 新增失败）+ 前端 vitest 162 绿 + lint 0 + build 0 验证。落地 master §7.5 WebSocket 最终 break：`position.update` / `position.pnl` / REST 持仓列表删 `unrealizedPnl` 硬切双币（`quoteCurrency / fxRate / unrealizedPnlInQuote / unrealizedPnlInAccount / isolatedMargin`，实时算复用 D2 `calculatePositionPnlInAccount` + FX 降级 entryFxRate）；`account.update` / `account.snapshot` / REST account 加 `equity / marginLevel / marginLevelStatus`（`AccountEquityCalculator` + `MarginLevelMonitor` 实时算）+ `openPositions` 同步双币。客户端 `MarginModeToggle`（对接 D1 `/me/margin-mode`，30080-30088）+ `MarginLevelIndicator` 三态浮窗（跃迁 critical toast）+ 持仓双币双行展示三组件均 vitest 覆盖。E0 已消除 D1/D2 遗留 stale IT。

**🔴 但当前不满足无条件"生产可用"：**

- **剩余阻断项 A（部署前必须处理，沿 14B/C1/C2/D1/D2/D3 叠加）**：生产/演示库 `falconx_trading` 因 14B root bug `USE` 污染存在 V28/V29 schema 漂移，下次 `flyway migrate` 将先撞 `Duplicate column`。E1 无新 migration，不引入新阻断但不解除既有阻断。部署前须先按 §0 修 14B V28/V29 再 migrate（trading V30-V37 + console V13），禁止裸跑 migrate。
- **剩余阻断项 B（E1 本阶段特有）**：E1 为对外硬 break、**无 legacy 兼容**，`trading-core-service` 与 `falconx-frontend` 必须同窗口部署；先后端、旧前端在线时旧前端解析新帧会丢失浮盈亏字段（见 §0-B）。
- **范围边界（非阻断，按 §5）**：marginLevel 推送在 fill/close 非 per-tick（per-tick refinement）；account 级 FX 降级口径不对称 / `@Lazy` 破循环 / MarginCall 通知链路均为 tech-debt；`getPositionSummary` 单币聚合留 E2；桌面 + 移动视觉 QA 为 WSL 受限手动项（vitest + build 覆盖）；移动端 tap 开浮窗真机待核。
- **不满足生产可用的其他原因**：本系统整体仍处 BBook 一期建设中，按 [当前开发计划 §1](../setup/当前开发计划.md)，当前系统不得表述为"生产可用"或"可安全对外公测"。

**使用说明（按 §8.1.3）：**

- **使用入口（客户端）**：
  - **margin mode 切换**：交易终端 `MarginModeToggle` 组件 → 读 `GET /me/margin-mode`（currentMode/canSwitch/blockers），切换 `POST /me/margin-mode`（targetMode）。
  - **MarginLevel 浮窗**：Dashboard 第 6 卡 / `MarginLevelIndicator` 浮窗 → 来自 `account.update` 的 `marginLevel` + `marginLevelStatus`，三态颜色 + 百分比 + MARGIN_CALL/STOP_OUT 跃迁 critical toast。
  - **双币持仓**：持仓表双行展示（账户币主 + 报价币副 + 币种标注），来自 `position.update` / `position.pnl` / REST 持仓列表的 `unrealizedPnlInAccount` + `unrealizedPnlInQuote` + `quoteCurrency`。
- **前置条件**：gateway / identity / trading-core / market 全栈在线 + 用户登录态 + `/ws/v1/trading` 真 WS 推送；目标库已按 §0-A 完成 14B 漂移修复 + migrate；**trading-core 与 falconx-frontend 同窗口部署（§0-B 硬 break）**。
- **执行步骤**：登录 → 进交易终端 → 订阅 `account` / `positions` 频道 → 触发开仓/平仓产生 fill/close 事件 → 观察 `position.pnl` 双币浮盈亏 + `account.update` marginLevel 浮窗刷新；点 `MarginModeToggle` 切换模式（受 D1 闸门 30080-30088）。
- **预期结果**：持仓行显示账户币 + 报价币双行浮盈亏；浮窗按 marginLevel 显示三态；mode 切换成功/被闸门拒（中文 toast）；MARGIN_CALL/STOP_OUT 跃迁弹 critical toast。
- **已知限制 / 禁用场景**：marginLevel 非 per-tick（fill/close 推送）；FX 不可用时账户级 equity/marginLevel 显示 null（行级降级 entryFxRate 仍显值）；旧前端 + 新后端混跑会丢浮盈亏字段（硬 break，禁止跨版本混跑）；CROSS 相关行为受 `cross_mode.enabled` 全局开关（默认 false，沿 D2）。

**下一步**：

- **STAGE-14E2 管理端**：admin FX rate 监控 page（console-frontend，承接 §15B 留项）+ admin 多币种聚合视图（账户 / 持仓跨币种汇总，解 `getPositionSummary` 单币聚合限制）+ admin 持仓多币种展示。
