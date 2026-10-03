# STAGE-14E2-ADMIN-FX-AGG R7 验证报告

> 验证日期：2026-06-02
> 验证人：Claude Opus 4.8（在 R1 Commander 调度下作为 R7）
> 任务：`STAGE-14E2` 管理端 FX 实时汇率监控 page + 多币种聚合（exposure 按报价币 USD 等价汇总）+ admin WebSocket 双币 / quoteCurrency 字段最终 break（master §7.5 / §8.3 E「admin 同步无 legacy」）。E 阶段第二切片（管理端）。
> **本切片收口即 STAGE-14E（E0+E1+E2）整体完成 + STAGE-14（A-E）全阶段收官。**

---

## §0. 部署前置阻断项（最高优先级，必读）

> **🔴 本切片 E2 在 trading-core 后端 admin WS 层 + console 透传 + console-frontend 代码侧已实现并通过 UT/IT/vitest/build 验证，但存在 (1) 沿 14B 叠加的库 schema 修复阻断 + (2) admin WS 硬 break 部署协调（同属 E1 硬 break），部署前必须处理。本系统整体仍处 BBook 一期建设中，不得无条件表述为「生产可用」。**

### 阻断项 A：14B 遗留 schema 漂移（沿 14B/C1/C2/D1/D2/D3/E1 叠加，E2 仅新增干净 console V14）

- E2 新增 migration **仅 console V14**（`V14__seed_fx_monitor_permissions_and_menu.sql`，干净无 `USE`，作用于 `falconx_console` 库，`fx:view` 权限 ID 9800001 + 菜单 ID 9800010）；trading / market **无新 migration**。
- 沿 14B：V28（`t_ledger` 三列）/ V29（`t_position.entry_fx_rate`）在 root bug 期间误写 `USE falconx_trading;`（已由 `1031a9ce` 修复），致这两列在被污染库已物理存在但 `flyway_schema_history` 无 V28/V29 行。
- 风险：被污染的既有 trading 库下次 `flyway migrate` 仍会先撞 `Duplicate column` 失败。**E2 不引入新阻断，但不解除既有阻断。**
- 修复指引（部署前由 DBA / 部署执行，沿 E1/D3 §0）：先修 14B V28/V29 漂移（手动补 `success=1` 行用删 USE 后 checksum / `flyway repair` + 人工核对列已存在）→ 再正常 migrate（trading V30-V37 + console V13/V14）。**禁止生产/演示库裸跑 `flyway migrate`。** 干净全新库无此问题。

### 阻断项 B：🔴 admin WS 硬 break 部署协调（E2 本阶段特有，无 legacy 兼容，与 E1 客户端硬 break 同属一体）

- E2 把管理端实时推送字段**硬切**（无旧字段并存）：
  - `admin.position.update`（`AdminPositionPnlUpdatePayload.Item`）**删 `unrealizedPnl`**，加 `quoteCurrency / fxRate / unrealizedPnlInQuote / unrealizedPnlInAccount`；
  - `admin.exposure.update`（`AdminExposureUpdatePayload`）补 `quoteCurrency`；
  - `admin.position.summary` 平台 `totalUnrealizedPnl` 口径由报价币（QC）修正为账户币（AC，跨 symbol 求和才有意义）。
- 因此 **`trading-core-service`（推送方）与 `console-frontend`（消费方）必须同窗口部署**：先部署后端、旧管理端仍在线时，旧管理端解析新帧会丢失浮盈亏字段。
- 这与 E1 客户端硬 break（`trading-core` + `falconx-frontend` 同窗口）同属一体：整个 E 阶段 WS break 是对外硬切，**`trading-core-service` + `falconx-frontend` + `console-frontend` 三者须同一发布窗口上线**，灰度 / 蓝绿需保证同一会话内前后端版本一致。

---

## §1. 范围

本切片覆盖 [STAGE-14 多币种 + CROSS/ISOLATED 保证金总设计稿](../design/STAGE-14-MULTICURRENCY-AND-CROSS-MARGIN-MASTER-design.md) §9 E 阶段的**第二切片（管理端）**，落地 master §7.5（WebSocket 最终 break，admin 侧）+ §8.3 E（admin 同步无 legacy + 多币聚合 + admin FX 监控）：

| 子能力 | E2 交付 | 关键 commit |
|---|---|---|
| 实施计划 | admin FX 监控 + 多币聚合 + admin WS break，6 task；FX 用 REST 5s 轮询（`admin.fx.rate.update` 1Hz WS 推送 = defer refinement） | `c4a6401a` |
| admin WS 硬切双币（后端） | `admin.position.update` 删 `unrealizedPnl` 加 4 双币字段 + `admin.exposure.update` 补 `quoteCurrency` + 提取共享 `TradingRealtimeDualPnlSupport`（client + admin 单一实现）+ `AdminPositionSummary` QC→AC 修正 + UT | `4804f043` |
| exposure quoteCurrency（后端 + console） | exposure internal RPC（`AdminTradingExposureListResponse`）+ console 响应补 `quoteCurrency`（多币聚合数据基础）+ IT | `63b48f20` |
| admin FX 监控透传（console） | `/admin/market/fx/rates` → market RPC `/internal/v1/market/fx/rates`（`InternalRpcClient` 按 path 经 gateway 路由）+ 错误码 90940 ADMIN_FX_RATE_NOT_FOUND + V14 `fx:view` 权限/菜单 seed + IT | `9a2ee0ea` |
| console-frontend FX 监控页 | 8 FX Table + `eventTimeMillis` 判 stale badge + REST 5s 轮询 + `fx:view` RBAC + error Alert + sourceSymbol + vitest | `afbc7489` + `8c67f8be` |
| exposure 多币聚合 tab（console-frontend） | 按 `quoteCurrency` 汇总 `netExposureUsd` USD 等价 + 多/空 + 占比 + admin WS 双币/quoteCurrency 消费（删旧单币）+ vitest + build0 | `ff06bc12` |
| R8 文档 + R7 报告 + 计划录入 | 本 commit | 本 commit |

**不在 E2 范围（defer refinement，见 §5）**：`admin.fx.rate.update` 1Hz WS 推送 channel（本期 REST 轮询）；`admin.account.mode.changed` 模式变更专用推送流；marginLevel per-tick 实时化；`getPositionSummary` 用户侧多币聚合修正（E2 仅修 admin 侧 `AdminPositionSummary`）。

---

## §2. 角色

E2 为**后端 admin WS 层（trading-core）+ 管理端后端（console-service）+ 管理端前端（console-frontend）**三端切片（无客户端），按 [`AI工作模式 §2`](../process/AI工作模式.md) 角色路由：

- **R4 业务后端**：admin payload 工厂双币实时算（提取 `TradingRealtimeDualPnlSupport` 共享 client + admin）+ `AdminPositionSummary` QC→AC 修正 + exposure RPC quoteCurrency。
- **R9 管理端后端**：console FX 监控透传（`AdminMarketFxController` + `AdminMarketFxApplicationService` + `FxRateView`）+ 错误码 90940 + V14 RBAC/菜单 seed + exposure 透传 quoteCurrency。
- **R10 管理端前端**：`FxRateMonitorPage`（REST 5s 轮询 + stale 判定）+ `TradingExposureBoardPage` 按报价币聚合 tab + `useAdminTradingSocket` 双币/quoteCurrency 消费。
- **R6 Test**：trading-core UT 6（admin payload 3 + exposure quoteCurrency 3）+ console IT 5（AdminMarketFx 4 + exposure 透传 1）+ 前端 vitest（FX 页 7 + 聚合/WS 11）。
- **R7 QA**：本报告 + 浏览器 QA（WSL 受限标注）。
- **R8 Doc**：管理端接口规范 §20/§7.3 + WebSocket 规范 §5.5 + 统一接口文档 §3.34 + Kafka 规范 §12.14（console FX consumer 标注）+ 计划 + BBook §15J。

---

## §3. 各 Task 证据

| Task | commit | 证据 |
|---|---|---|
| E2 计划 | `c4a6401a` | 6 task 实施计划（FX REST 轮询，admin.fx.rate.update WS 推送 defer） |
| E2-1 admin WS 双币 | `4804f043` | `AdminPositionPnlUpdatePayload.Item` 删 `unrealizedPnl` 加 4 双币字段；`AdminExposureUpdatePayload` 补 `quoteCurrency`；`TradingRealtimeDualPnlSupport` 共享（`TradingUserRealtimePayloadFactory` 委托）；`TradingAdminRealtimePushService` summary 按 `unrealizedPnlInAccount` 累计（AC，缺失移除条目）；`TradingAdminRealtimePushServicePayloadTests` 3 |
| E2-2 exposure quoteCurrency | `63b48f20` | trading-core + console `AdminTradingExposureListResponse` 加 `quoteCurrency`；`TradingMonitorAdminApplicationService` 填充；`TradingMonitorAdminExposureQuoteCurrencyTests` 3 + console `AdminTradingExposureQuoteCurrencyPassThroughTests` 1 |
| E2-3 FX 监控透传 | `9a2ee0ea` | `AdminMarketFxController` GET `/admin/market/fx/rates`（`fx:view`）+ `AdminMarketFxApplicationService` 透传 market RPC + `FxRateView`（无 stale 字段）+ `AdminErrorCode` 90940 + `V14` 权限 9800001/菜单 9800010 + `AdminMarketFxEndpointIntegrationTests` 4 |
| E2-4 FX 监控页 | `afbc7489` + `8c67f8be` | `FxRateMonitorPage`（8 FX Table + eventTimeMillis stale badge + REST 5s 轮询 + fx:view RBAC + error Alert + sourceSymbol）+ `fxRateApi` + 路由 + vitest 7 |
| E2-5 多币聚合 + WS 消费 | `ff06bc12` | `TradingExposureBoardPage` 按报价币聚合 tab + `exposureAggregate` 助手 + `useAdminTradingSocket` 双币/quoteCurrency 消费（删旧单币）+ `TradingPositionListPage` merge + vitest 11 + build0 |
| E2-6 收口 | 本 commit | 全量验证 + R8 + R7 + 计划录入 |

---

## §4. 测试统计

### 后端（trading-core）

- UT：`TradingAdminRealtimePushServicePayloadTests` 3（admin.position.update 双币 + admin.exposure.update quoteCurrency + admin.position.summary AC 汇总 / 缺失移除）+ `TradingMonitorAdminExposureQuoteCurrencyTests` 3（exposure quoteCurrency 填充）。
- 全量回归：`mvn -pl falconx-market-contract -am install -DskipTests -q && mvn -pl falconx-trading-core-service test` → **592 tests / 2 failures**。2 失败逐条定性：
  - `TradingKafkaWalletDepositIntegrationTests.shouldNotCreditTwiceWhenDuplicateConfirmedMessageArrivesViaKafka` —— `AssertionFailedError expected:<1> but was:<4>`，**既有 Kafka consumer group 异步计数竞态 flake**（pre-existing，非本阶段）。
  - `TradingKafkaWalletDepositIntegrationTests.shouldCreditDepositWhenConfirmedMessageArrivesViaKafka` —— `DeadlockLoserDataAccessException`（MySQL Deadlock），**瞬态死锁 flake**（pre-existing，非确定性，非本阶段）。
  - 两失败同属一个既有 flake 类，**零 E2 新增失败**；E0 已消除 D3a R7 §5 #2 残留的 `shouldReturnMarginModeNotSupportedWhenOrderRequestsCross` stale IT，本轮不复现。

### 管理端后端（console-service）

- IT 22 全过：`AdminMarketFxEndpointIntegrationTests` 4（FX 监控透传 + 90940 + RBAC）+ `AdminTradingExposureQuoteCurrencyPassThroughTests` 1（exposure quoteCurrency 透传）+ 回归 `AdminPlatformConfigEndpointIntegrationTests` 10 + `AdminFxPauseBehaviorEndpointIntegrationTests` 7。**BUILD SUCCESS**。

### 管理端前端（console-frontend）

- vitest 全量 **115 passed + 3 skipped**（19 test files），其中 E2 新增：FX 页 `FxRateMonitorPage` 7 + 聚合 `TradingExposureBoardPage` / `useAdminTradingSocket` 11。
- build 退出 0。

### 客户端（falconx-frontend，E1 不回归确认）

- build 退出 0（E1 客户端硬 break 切片无回归）。

---

## §5. 已知不阻断项 / 边界 / defer refinement

| # | 项 | 性质 | 说明 |
|---|---|---|---|
| ① | `admin.fx.rate.update` 1Hz WS 推送 channel | defer refinement | master §7.5 设想的 admin FX 专用推送帧本期未实现；FX 用 REST 5s 轮询（`FxRateMonitorPage`）。console 当前不消费 `falconx.market.fx.rate.update` topic（见 [Kafka 规范 §12.14](../event/Kafka事件规范.md)）。 |
| ② | `admin.account.mode.changed` 模式变更专用推送流 | defer | D1 已发 `falconx.trading.account.mode.changed` Kafka（additive）；admin WS 专用流后续按需接入。 |
| ③ | marginLevel per-tick 实时化 + `MarginLevelMonitor` 事件化解耦 | defer refinement / tech-debt | marginLevel 在 fill/close 推送（非 per-tick，沿 E1）；`MarginLevelMonitor`→Notification→Push→Registry 循环用 `@Lazy` 打破（E1 tech-debt），建议事件化解耦。 |
| ④ | `getPositionSummary` / `UserPositionSummaryAggregator` 用户侧多币聚合 | defer | B 阶段既有 mixed-currency 已知问题。E2 已修 **admin 侧** `AdminPositionSummary`（QC→AC），**user 侧未动**。 |
| ⑤ | 真机浏览器 QA 6 截图（客户端 + admin） + `MarginLevelIndicator` 移动端 tap | WSL 受限手动项 / 待核 | 见 §7。vitest 115 + build 0 覆盖组件行为，不伪造截图。 |
| ⑥ | `TradingPositionListPage` admin WS 消费侧 merge 无专用 vitest | 边界（trivial） | WS 解析层（`useAdminTradingSocket`）已测，merge 为 trivial `??` 兜底。 |
| ⑦ | 既有 Kafka flake + 瞬态死锁 flake | pre-existing | `TradingKafkaWalletDepositIntegrationTests`（consumer race + Deadlock），非本阶段引入。 |

---

## §6. WebSocket / REST 字段无 legacy 残留证据

- `AdminPositionPnlUpdatePayload.Item`：record 定义中**已删 `unrealizedPnl`**，仅 `quoteCurrency / fxRate / unrealizedPnlInQuote / unrealizedPnlInAccount`（commit `4804f043`）。
- `AdminExposureUpdatePayload`：新增 `quoteCurrency`（commit `4804f043`）。
- `TradingAdminRealtimePushService`：summary 按 `pnl.inAccount()` 累计（AC，跨 symbol 可加），全缺 quote/AC 时 `summaryAggregator.updateSymbol(symbol, null)` 移除条目避免陈旧值串入平台 sum（commit `4804f043`）。
- 双币算法提取共享 `TradingRealtimeDualPnlSupport`，client（`TradingUserRealtimePayloadFactory`）+ admin（`TradingAdminRealtimePushService`）**单一实现来源**，无两套口径漂移。
- console-frontend `useAdminTradingSocket` / `types`：消费侧删旧单币字段，加双币 + quoteCurrency（commit `ff06bc12`），与后端 break 同切片同步，无 legacy 并存。

---

## §7. master §8.3 E 阶段验收（含 STAGE-14 全收官口径）

| 验收项 | 状态 | 证据 |
|---|---|---|
| WS break 客户端 + admin 同步无 legacy 残留 | ✅ | E1 客户端（`position.update`/`account.update` 删 `unrealizedPnl`）+ E2 admin（`admin.position.update` 删 `unrealizedPnl`）双端共用 `TradingRealtimeDualPnlSupport`，§6 |
| admin 多币种聚合 | ✅ | exposure RPC/console 补 `quoteCurrency`（IT）+ 前端「按报价币聚合」tab（vitest 11） |
| admin FX 实时汇率监控 | ✅ | console 透传 `/admin/market/fx/rates`（IT 4）+ `FxRateMonitorPage`（vitest 7） |
| 客户端 + admin 桌面 / 移动浏览器视觉 QA（6 截图） | ⚠️ WSL 受限手动项 | 见下 |

### 浏览器 QA 结论（WSL 受限，不伪造）

- 环境：本会话 WSL 无 chromium 系统包 / 无后端栈（gateway / identity / trading-core / market）/ 无 admin 登录会话。启动 `console-frontend` dev server（监听 `:5300`），用 Playwright MCP 导航 `/admin/market/fx-rates` 成功（HTTP serve build 正常），被前端鉴权守卫**重定向到 `/admin/login`**（无登录态），页面渲染空白（无后端业务数据），**控制台 0 error**（无渲染崩溃）。
- 结论：**console-frontend build 可 serve、路由 + 鉴权守卫正常、构建产物健康（vitest 115 + build 0），但 FX 监控页 + exposure 多币聚合 tab + 客户端实时 UI 的桌面 + 移动 6 截图视觉 QA 需真机 / CI（有后端栈 + admin 认证态 + 真 WS/RPC）补**。与前阶段（E1 / D3b）口径一致：视觉 QA 标注为 WSL 受限手动项，由 vitest + build 覆盖组件行为，**不伪造截图**。

---

## §8. 文档同步清单（R8）

| 文档 | 状态 | 内容 |
|---|---|---|
| `docs/api/管理端接口规范.md` | ✅ | §7.3 净敞口看板响应加 `quoteCurrency` + 多币聚合说明；新增 §20 STAGE-14E2 admin FX 监控（端点 `/admin/market/fx/rates` + `fx:view` + 90940 + V14 ID 9800001/9800010 + FxRateView 无 stale 字段 + admin WS break + 测试结论） |
| `docs/api/WebSocket接口规范.md` | ✅ | 新增 §5.5 STAGE-14E2 admin WS 双币 / quoteCurrency 最终切换（硬 break 声明 + admin.position.update data 字段表删 unrealizedPnl 加 4 双币字段 + admin.exposure.update 补 quoteCurrency + admin.position.summary QC→AC 修正 + 部署协调） |
| `docs/event/Kafka事件规范.md` | ✅ | §12.14 console FX consumer 标注：E2 按 REST 5s 轮询实现，不消费本 topic；`admin.fx.rate.update` 1Hz WS 推送 = defer refinement |
| `docs/api/FalconX统一接口文档.md` | ✅ | 新增 §3.34 console FX 监控 admin REST（端点 + 90940 + V14 权限 ID 9800001/菜单 9800010 登记 + admin WS break + exposure quoteCurrency + 测试结论 592/2 + console IT 22 + vitest 115） |
| `docs/setup/当前开发计划.md` §1 | ✅ | STAGE-14E2 收口条目（范围 + commits + 测试 592/2 + console IT 22 + vitest 115 + admin WS 硬 break 部署须同窗口 + defer refinement + STAGE-14E 整体完成 + **STAGE-14 A-E 全阶段收官** + 下一步 BBook 剩余）；E1 下一步指向更新 |
| `docs/process/BBook一期完成执行路径.md` | ✅ | §15J STAGE-14E2 收口条目（6 task + 测试 + 部署阻断 + admin WS 硬 break 部署协调 + defer refinement + STAGE-14E 整体完成 + STAGE-14 A-E 全收官标注 + 下一步） |

---

## §9. 结论

按 [AGENTS.md §8.1.2](../../AGENTS.md) 生产可用判定：

**E2（管理端 FX 实时汇率监控 + 多币种聚合 + admin WebSocket 双币 / quoteCurrency 字段最终 break）在 trading-core 后端 admin WS 层 + console-service 透传 + console-frontend 代码侧已完整实现**，并通过 trading-core UT 6（admin payload 3 + exposure quoteCurrency 3）随全量回归 592 tests / 2-fail（2 失败均为既有 `TradingKafkaWalletDepositIntegrationTests` flake——consumer race + 瞬态死锁，零 E2 新增失败）+ console IT 22 全过 + console-frontend vitest 115（+3 skip）+ build 0 + 客户端 `falconx-frontend` build 0（E1 无回归）验证。落地 master §8.3 E：admin WS break 客户端 + admin 同步无 legacy 残留（双端共用 `TradingRealtimeDualPnlSupport`）+ admin 多币种聚合（exposure quoteCurrency + 前端聚合 tab）+ admin FX 实时汇率监控（console 透传 + 监控页）。

**🔴 STAGE-14（A-E）全阶段收官——但当前不满足无条件「生产可用」：**

- **STAGE-14 全收官口径（重大里程碑，但据实）**：A（market FX 数据源）/ B（trading-core 货币转换 + 账本三列）/ C（杠杆 Tier + MarginLevel 三态 + StopOut 强平 + console UI）/ D（CROSS·ISOLATED 切换 + 实时 MM + CROSS 账户级强平 + 运营可配 + console UI）/ E（客户端 + admin 三端展示 UI + WebSocket 最终 break）**代码层完整、测试闭环**。这是 STAGE-14 多币种 + CROSS/ISOLATED 保证金体系的代码层收官，**非无条件生产可用**。
- **剩余阻断项 A（部署前必须处理，沿 14B/C1/C2/D1/D2/D3/E1 叠加）**：生产/演示库 `falconx_trading` 因 14B root bug `USE` 污染存在 V28/V29 schema 漂移，下次 `flyway migrate` 将先撞 `Duplicate column`。E2 仅新增干净 console V14，不引入新阻断但不解除既有阻断。部署前须先按 §0-A 修 14B V28/V29 再 migrate（trading V30-V37 + console V13/V14），禁止裸跑 migrate。
- **剩余阻断项 B（E2 本阶段特有，与 E1 一体）**：admin WS 为对外硬 break、无 legacy 兼容，`trading-core-service` + `falconx-frontend` + `console-frontend` 三者须同一发布窗口部署（见 §0-B）。
- **范围边界（非阻断，按 §5）**：`admin.fx.rate.update` 1Hz WS 推送（本期 REST 轮询）/ `admin.account.mode.changed` 专用流 / marginLevel per-tick 实时化 + `MarginLevelMonitor` 事件化解耦 / `getPositionSummary` 用户侧多币聚合修正均为 defer refinement；真机 6 截图视觉 QA 为 WSL 受限手动项（vitest + build 覆盖）；`MarginLevelIndicator` 移动端 tap 待核。
- **不满足生产可用的其他原因**：本系统整体仍处 BBook 一期建设中，按 [当前开发计划 §1](../setup/当前开发计划.md)，当前系统不得表述为「生产可用」或「可安全对外公测」。

**使用说明（按 §8.1.3）：**

- **使用入口（管理端 FX 监控）**：console-frontend 侧栏「交易监控」→「FX 汇率监控」（path `/admin/market/fx-rates`，权限 `fx:view`）→ `FxRateMonitorPage` 调 `GET /admin/market/fx/rates`（console 透传 market `GET /internal/v1/market/fx/rates`），REST 每 5s 轮询，前端依 `eventTimeMillis` 与当前时间差判 stale badge。
- **使用入口（admin 多币聚合）**：console-frontend「交易监控」→ 净敞口看板 `TradingExposureBoardPage` →「按报价币聚合」tab，按 `quoteCurrency` 分组对 `netExposureUsd` 求 USD 等价汇总（多/空 + 占比）；数据来自 `GET /admin/trading/exposures` 响应补的 `quoteCurrency`。
- **使用入口（admin 实时双币持仓 / 敞口）**：`/ws/v1/admin` 订阅，`admin.position.update`（双币浮盈亏）+ `admin.exposure.update`（quoteCurrency）+ `admin.position.summary`（平台 AC 汇总）。
- **前置条件**：gateway / identity / trading-core / market 全栈在线 + admin 登录态（持 `fx:view` 角色或 SUPER_ADMIN）+ market 已有 FX tick（Redis FX 快照非空）；目标库已按 §0-A 完成 14B 漂移修复 + migrate（含 console V14）；**trading-core + falconx-frontend + console-frontend 同窗口部署（§0-B 硬 break）**。
- **执行步骤**：admin 登录 → 进「FX 汇率监控」观察 8 FX rate + stale badge（停止 market FX tick 超 30s 后该行变 stale）；进净敞口看板「按报价币聚合」tab 观察按 QC 分组的 USD 等价汇总；订阅 admin WS 观察 `admin.position.update` 双币浮盈亏 + `admin.exposure.update` quoteCurrency。
- **预期结果**：FX 监控页展示全量 FX rate（rate / eventTimeMillis / sourceLpCode / sourceSymbol），陈旧行标 stale；聚合 tab 按报价币显示 USD 等价净敞口汇总；admin WS 帧含双币浮盈亏 + quoteCurrency，平台 summary 用账户币汇总。
- **已知限制 / 禁用场景**：FX 监控为 REST 5s 轮询（非 1Hz WS 推送，defer refinement）；旧管理端 + 新后端混跑会丢浮盈亏字段（硬 break，禁止跨版本混跑）；`getPositionSummary` 用户侧多币聚合仍单币（E2 仅修 admin 侧）；CROSS 相关行为受 `cross_mode.enabled` 全局开关（默认 false，沿 D2）。

**下一步：**

- **部署前**：先修 14B V28/V29 库 schema 漂移（DBA 授权 repair）+ E1/E2 硬 break 三端同窗口部署协调。
- **defer refinement 清单**（独立 task，非阻断）：`admin.fx.rate.update` 1Hz WS 推送 channel / `admin.account.mode.changed` 模式变更流 / marginLevel per-tick 实时化 + `MarginLevelMonitor` 事件化解耦 / `getPositionSummary` 用户侧多币聚合修正 / 真机浏览器 QA 6 截图。
- **BBook 一期剩余阶段**：按 [当前开发计划 §1](../setup/当前开发计划.md) 与 [BBook 一期完成执行路径](../process/BBook一期完成执行路径.md) 推进剩余阶段（`STAGE-12-GROUP-MARKUP` 剩余切片 + 组合/跨品种异常行情保护 + 生产级监控告警与部署回滚证据等一期完成阻断项），不留计划真空。
