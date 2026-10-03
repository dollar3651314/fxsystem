# STAGE-14E1 WebSocket break + 客户端实时 UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 完成 WebSocket 字段最终 break 切换（master §7.5，无 legacy 残留）+ 客户端落地三件实时 UI（margin mode toggle / MarginLevel 浮窗 / 双币 PnL），把 STAGE-14B/C/D 的多币种+CROSS+MarginLevel 能力首次呈现给客户端用户。

**Architecture:** trading-core WS payload 工厂注入 `FxRateService`+`MarketSymbolSpecRepository` 把 `position.update`/`position.pnl` 的单币 `unrealizedPnl` **硬切**为 `unrealizedPnlInQuote`+`unrealizedPnlInAccount`+`quoteCurrency`+`fxRate`+`isolatedMargin`（FX 内存查，per-tick 廉价）；`account.update`/account snapshot 注入 `AccountEquityCalculator`+`MarginLevelMonitor` 实时算 `equity`/`marginLevel`/`marginLevelStatus`（在 fill/close 事件推送，非 per-tick）。客户端 `useTradingSocket` 同步切字段，新增 `MarginModeToggle`（对接 D1 `/me/margin-mode`）、`MarginLevelIndicator` 浮窗、双币 PnL 展示。**硬 break：删除旧 `unrealizedPnl` 字段，后端+客户端同切片同步切换。**

**Tech Stack:** trading-core: Java 25 / Spring Boot 4 / JUnit5。falconx-frontend: Vite + React + TS + Zustand/React Query + Vitest。

**关键约束（务必带入）：**
- **硬 break 协调**：客户端已上线演示环境（app-falconx.lifebyteapp.dev）。本切片后端+客户端同步切字段，**部署时必须 trading-core + falconx-frontend 同一窗口一起部署**（R7 写明）。
- 跑 trading IT 前先 `mvn -pl falconx-market-contract -am install -DskipTests`。
- FxRateService 是内存（Kafka `falconx.market.fx.rate.update` 喂），WS 推送查 FX 是内存查（廉价），但 FX 不可用时降级 `entry_fx_rate`（沿 D2 实时 MM 口径）。
- `isolatedMargin` 不加 DB 列（D1/D2/D3a 已定论）：position.update 的 `isolatedMargin` = marginMode==ISOLATED 时取 `position.margin()`，CROSS 时 null。
- `marginLevel` 推送语义：master §7.5 在 `account.update`（fill/close 事件），非 per-tick；浮窗显示最近一次推送值（per-tick 实时化属后续 refinement，R7 标注）。
- 前端字段名陷阱（explorer 沉淀）：`accountMarginMode`（后端账户级）≠ `defaultMarginMode`（本地下单偏好 localStorage）；position 级 `quoteCurrency`（结算币）≠ MarketSymbol.quoteCurrency（品种标的币）；`unrealizedPnl`(旧/删) / `unrealizedPnlInQuote` / `unrealizedPnlInAccount` 三口径。
- 默认 main，每 task 一个 commit（不 amend）+ 中文 message + 末尾 `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`。
- 仅 git add 本 task 文件，禁止混入 untracked（docker-compose.override.yml / qa-ind-*.png）。
- D1 `/api/v1/me/margin-mode` 已上线（GET 返回 mode + cooling_until + can_switch + blockers；POST {targetMode}，错误 30080-30083/30088）。

---

## File Structure

**trading-core（R4）后端：**
- `dto/TradingPositionItemResponse.java`（改：删 `unrealizedPnl`，加 `quoteCurrency`/`fxRate`/`unrealizedPnlInQuote`/`unrealizedPnlInAccount`/`isolatedMargin`；`liquidationPrice` 已有）
- `websocket/TradingPositionPnlUpdatePayload.java`（改：同步双币字段，删旧 `unrealizedPnl`）
- `websocket/TradingUserRealtimePayloadFactory.java`（改：注入 FxRateService+MarketSymbolSpecRepository，toPositionPayload/toPnlPayload 算双币+isolatedMargin）
- `dto/TradingAccountResponse.java`（改：加 `equity`/`marginLevel`/`marginLevelStatus`；`marginMode` 已有）
- `application/TradingAccountSnapshotApplicationService.java`（改：注入 AccountEquityCalculator+MarginLevelMonitor+OpenPositionSnapshotStore，toResponse 实时算）
- 测试：`TradingUserRealtimePayloadFactoryTests`、`TradingAccountSnapshotApplicationServiceTests`（UT）+ WS 推送 IT 扩字段断言

**falconx-frontend（R5）客户端：**
- `features/trading/tradingTypes.ts`（改：PositionItem 双币字段）
- `features/trading/tradingApi.ts`（改：TradingAccountResponse 加 equity/marginLevel/marginLevelStatus；新增 marginMode REST client）
- `features/trading/useTradingSocket.ts`（改：position.pnl/account.update 消费新字段，删旧 unrealizedPnl 引用）
- `features/terminal/TradingTerminal.tsx`（改：持有实时 marginLevel/双币聚合 state）
- `features/trading/MarginModeToggle.tsx`（新）+ `features/trading/MarginLevelIndicator.tsx`（新）
- `features/trading/PositionsTable.tsx`/`PositionItemCard.tsx`（改：双币 PnL）
- `features/dashboard/DashboardPage.tsx`（改：MarginLevel 卡 + marginMode 展示）
- 对应 `.test.tsx`

**文档（R8）：** `docs/api/WebSocket接口规范.md`（§5.3 break 字段）、`docs/api/FalconX统一接口文档.md`、`docs/setup/当前开发计划.md`、`docs/process/BBook一期完成执行路径.md`、`docs/test/STAGE-14E1-WS-BREAK-CLIENT-UI-R7-verification-report.md`

---

## Task 1: position.update/position.pnl 双币字段 + payload 工厂实时算

**Files:**
- Modify: `falconx-trading-core-service/src/main/java/com/falconx/trading/dto/TradingPositionItemResponse.java`
- Modify: `falconx-trading-core-service/src/main/java/com/falconx/trading/websocket/TradingPositionPnlUpdatePayload.java`
- Modify: `falconx-trading-core-service/src/main/java/com/falconx/trading/websocket/TradingUserRealtimePayloadFactory.java`
- Test: `falconx-trading-core-service/src/test/java/com/falconx/trading/websocket/TradingUserRealtimePayloadFactoryTests.java`

**先读**：`TradingUserRealtimePayloadFactory`（toPositionPayload 行 ~73-119 + toPnlPayload；当前调 `TradingPricingSupport.calculatePositionPnl` 仅 QC）、`TradingPricingSupport.calculatePositionPnlInAccount`（D2 双币算法，返回 PnlResult{inQuote,inAccount,fxRate,quoteCurrency}）、`PnlResult`、`MarketSymbolSpecRepository.findByPlatformSymbol`（取 quoteCurrency）、`FxRateService`（trading 侧，内存查 + 降级）、`DefaultAccountEquityCalculator`/D2 实时 MM 怎么取 fx 降级 entryFxRate。

- [ ] **Step 1: 写失败 UT**

`TradingUserRealtimePayloadFactoryTests`（mock FxRateService/SymbolSpecRepository/QuoteSnapshotRepository）：
- toPositionPayload(EURAUD ISOLATED 仓)：断言 payload 含 quoteCurrency="AUD"、fxRate（AUD→账户币）、unrealizedPnlInQuote、unrealizedPnlInAccount、isolatedMargin=margin、liquidationPrice。
- CROSS 仓：isolatedMargin=null、liquidationPrice=null。
- FX 不可用：fxRate 降级 entryFxRate，unrealizedPnlInAccount 用降级 fx 算（不抛）。
- 旧 `unrealizedPnl` 字段不再存在（编译期保证 + 断言无该字段）。

- [ ] **Step 2: Run 失败**

Run: `mvn -pl falconx-market-contract -am install -DskipTests -q && mvn -pl falconx-trading-core-service test -Dtest=TradingUserRealtimePayloadFactoryTests 2>&1 | grep -E "Tests run:|BUILD"`
Expected: FAIL/编译错误（字段未加）。

- [ ] **Step 3: 改 DTO（硬 break）**

`TradingPositionItemResponse`：删 `unrealizedPnl` 字段，加 `quoteCurrency`(String)、`fxRate`(BigDecimal)、`unrealizedPnlInQuote`(BigDecimal)、`unrealizedPnlInAccount`(BigDecimal)、`isolatedMargin`(BigDecimal nullable)。`TradingPositionPnlUpdatePayload` 同步（删 `unrealizedPnl`，加同名双币字段 + quoteCurrency + fxRate + isolatedMargin）。

- [ ] **Step 4: 工厂实时算**

`TradingUserRealtimePayloadFactory`：注入 `FxRateService`+`MarketSymbolSpecRepository`。toPositionPayload/toPnlPayload 改调 `calculatePositionPnlInAccount`（得 inQuote/inAccount/fxRate）；quoteCurrency 从 SymbolSpec 取；isolatedMargin = `position.marginMode()==ISOLATED ? position.margin() : null`；FX 不可用降级 entryFxRate（沿 D2）。修所有因删字段而编译失败的引用。

- [ ] **Step 5: Run 通过**

Run: `mvn -pl falconx-trading-core-service test -Dtest=TradingUserRealtimePayloadFactoryTests 2>&1 | grep -E "Tests run:|BUILD"`
Expected: 全绿。

- [ ] **Step 6: Commit**

```bash
git add falconx-trading-core-service/src/main/java/com/falconx/trading/dto/TradingPositionItemResponse.java falconx-trading-core-service/src/main/java/com/falconx/trading/websocket/TradingPositionPnlUpdatePayload.java falconx-trading-core-service/src/main/java/com/falconx/trading/websocket/TradingUserRealtimePayloadFactory.java falconx-trading-core-service/src/test/java/com/falconx/trading/websocket/TradingUserRealtimePayloadFactoryTests.java
git commit -m "feat(trading): STAGE-14E1 Task1 position.update/pnl 硬切双币(quoteCurrency/fxRate/unrealizedPnlInQuote/InAccount/isolatedMargin，删旧 unrealizedPnl)+工厂实时算+FX降级+UT

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: account.update equity/marginLevel/marginLevelStatus 实时算

**Files:**
- Modify: `falconx-trading-core-service/src/main/java/com/falconx/trading/dto/TradingAccountResponse.java`
- Modify: `falconx-trading-core-service/src/main/java/com/falconx/trading/application/TradingAccountSnapshotApplicationService.java`
- Test: `falconx-trading-core-service/src/test/java/com/falconx/trading/application/TradingAccountSnapshotApplicationServiceTests.java`

**先读**：`TradingAccountResponse`（record 字段，已有 marginMode）、`TradingAccountSnapshotApplicationService.toResponse`（行 ~74-90，当前只取 DB 快照）、`AccountEquityCalculator`（computeAccountMarginLevel — D2/C1）、`MarginLevelMonitor`（三态判定 HEALTHY/MARGIN_CALL/STOP_OUT + 读 t_risk_config 阈值）、`OpenPositionSnapshotStore`（取账户 OPEN 持仓算 equity）、`MarginLevelStatus` 枚举。

- [ ] **Step 1: 写失败 UT**

`TradingAccountSnapshotApplicationServiceTests`：
- toResponse(账户 + OPEN 持仓 + fx)：断言含 equity、marginLevel（=equity/totalMM*100 或与 calculator 一致口径）、marginLevelStatus（HEALTHY/MARGIN_CALL/STOP_OUT）、marginMode。
- 无持仓：marginLevel=null（或 calculator 约定值），status 合理。
- FX 降级：不抛，equity 用降级 fx。

- [ ] **Step 2: Run 失败**

Run: `mvn -pl falconx-trading-core-service test -Dtest=TradingAccountSnapshotApplicationServiceTests 2>&1 | grep -E "Tests run:|BUILD"`
Expected: FAIL/编译错误。

- [ ] **Step 3: 改 DTO + toResponse**

`TradingAccountResponse` 加 `equity`(BigDecimal)、`marginLevel`(BigDecimal nullable)、`marginLevelStatus`(String nullable)。`toResponse` 注入 `AccountEquityCalculator`+`MarginLevelMonitor`+`OpenPositionSnapshotStore`：取账户 OPEN 持仓 → computeAccountMarginLevel → equity/marginLevel；MarginLevelMonitor 判 status。无持仓/FX 不可用按 calculator 约定降级。**保持 toResponse 既有调用方不破坏**（REST GET account 也走它 → 顺带让 REST 初始加载也带这些字段，客户端首屏可用）。

- [ ] **Step 4: Run 通过 + 既有 account/REST 测试不回归**

Run: `mvn -pl falconx-trading-core-service test -Dtest='TradingAccountSnapshotApplicationServiceTests,TradingControllerIntegrationTests' 2>&1 | grep -E "Tests run:|BUILD"`
Expected: 全绿。

- [ ] **Step 5: Commit**

```bash
git add falconx-trading-core-service/src/main/java/com/falconx/trading/dto/TradingAccountResponse.java falconx-trading-core-service/src/main/java/com/falconx/trading/application/TradingAccountSnapshotApplicationService.java falconx-trading-core-service/src/test/java/com/falconx/trading/application/TradingAccountSnapshotApplicationServiceTests.java
git commit -m "feat(trading): STAGE-14E1 Task2 account.update/快照 加 equity/marginLevel/marginLevelStatus 实时算(AccountEquityCalculator+MarginLevelMonitor)+FX降级+UT

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: WS 推送链路 IT（新字段端到端）+ 全量回归

**Files:**
- Test: `falconx-trading-core-service/src/test/java/com/falconx/trading/` 既有 WS 推送 IT（找 `TradingUserRealtime*IntegrationTests` 或类似；无则新建 `TradingWsBreakFieldsIntegrationTests`）

- [ ] **Step 1: 写/扩 IT**

真 DB + 真推送：开 EURAUD ISOLATED 仓 → 驱动 tick → 断言 position.pnl 帧含双币字段 + isolatedMargin；fill 后 account.update 帧含 equity/marginLevel/marginLevelStatus/marginMode。复用既有 WS 推送 IT harness（搜 publishPositionPnlUpdates / pushAccountUpdated 的 IT）。

- [ ] **Step 2: Run + 全量回归**

Run: `mvn -pl falconx-market-contract -am install -DskipTests -q && mvn -pl falconx-trading-core-service test 2>&1 | grep -E "Tests run: [0-9]+, Fail|BUILD|<<<" | tail -20`
Expected: 全绿（pre-existing flake `TradingKafkaWalletDepositIntegrationTests` + 瞬态死锁若现单独标注；E0 已修 stale IT 应绿）。据实记录失败定性。

- [ ] **Step 3: Commit**

```bash
git add falconx-trading-core-service/src/test/java/com/falconx/trading/
git commit -m "test(trading): STAGE-14E1 Task3 WS break 字段端到端 IT(position.pnl 双币 + account.update marginLevel)+全量回归

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: 客户端 WS hook + 类型同步切字段

**Files:**
- Modify: `falconx-frontend/src/features/trading/tradingTypes.ts`、`tradingApi.ts`、`useTradingSocket.ts`
- Modify: `falconx-frontend/src/features/terminal/TradingTerminal.tsx`
- Test: `falconx-frontend/src/features/trading/useTradingSocket.test.ts`（若有；无则新建最小测试）

**先读**：`useTradingSocket.ts`（PositionPnlUpdate 接口行 ~7-15、account.update 分支行 ~263-274、position.pnl 分支行 ~212-224、AccountBalanceChangedEvent 行 ~65-71）、`tradingTypes.ts`（PositionItem）、`tradingApi.ts`（TradingAccountResponse）、`TradingTerminal.tsx`（pnlMap/totalUnrealizedPnl state 行 ~193）。

- [ ] **Step 1: 类型 + 消费切字段（硬 break，删旧 unrealizedPnl 引用）**

- `tradingTypes.ts` PositionItem：删/改 `unrealizedPnl`，加 quoteCurrency/unrealizedPnlInQuote/unrealizedPnlInAccount/isolatedMargin（liquidationPrice 已有）。
- `tradingApi.ts` TradingAccountResponse：加 equity/marginLevel/marginLevelStatus（marginMode 已有）。
- `useTradingSocket.ts`：PositionPnlUpdate 接口加双币字段；position.pnl 分支解析新字段入 pnlMap；account.update 分支新增 onAccountUpdate handler 传出 equity/marginLevel/marginLevelStatus/marginMode（accountMarginMode）。删所有旧 `unrealizedPnl` 引用。
- `TradingTerminal.tsx`：新 state 持有 marginLevel/marginLevelStatus/accountMarginMode + 双币聚合；挂 onAccountUpdate handler。

- [ ] **Step 2: vitest（hook 解析）**

测 position.pnl/account.update 帧解析出新字段、无旧字段引用残留。

- [ ] **Step 3: Run vitest + lint + build**

Run: `cd falconx-frontend && npx vitest run src/features/trading/useTradingSocket.test.ts 2>&1 | tail -8 && npm run lint 2>&1 | tail -3 && npm run build 2>&1 | tail -3`
Expected: 绿 / lint 0(改动文件) / build 0（删字段后无 TS 残留引用——build 是硬 break 完整性的关键守卫）。

- [ ] **Step 4: Commit**

```bash
git add falconx-frontend/src/features/trading/tradingTypes.ts falconx-frontend/src/features/trading/tradingApi.ts falconx-frontend/src/features/trading/useTradingSocket.ts falconx-frontend/src/features/terminal/TradingTerminal.tsx falconx-frontend/src/features/trading/useTradingSocket.test.ts
git commit -m "feat(frontend): STAGE-14E1 Task4 客户端 WS hook+类型硬切双币/marginLevel 字段(删旧 unrealizedPnl)+Terminal 持有实时 state+vitest

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: 客户端 MarginModeToggle（对接 D1 /me/margin-mode）

**Files:**
- Modify: `falconx-frontend/src/features/trading/tradingApi.ts`（marginMode REST client）
- Create: `falconx-frontend/src/features/trading/MarginModeToggle.tsx` + `MarginModeToggle.test.tsx`
- Modify: 挂载点（`OrderTicket.tsx` 顶部 或 `DashboardPage.tsx` 账户区）

**先读**：D1 端点契约（GET `/api/v1/me/margin-mode` → {marginMode, coolingUntil, canSwitch, blockers[]}；POST {targetMode} 错误 30080/30081/30082/30083/30088）、`lib/api.ts`（requestJson）、现有 `fx-modal` 系统（global.css）+ `preferencesStore.defaultMarginMode`（区分本地偏好 vs 账户级）、错误码中文映射现状。

- [ ] **Step 1: REST client**

`tradingApi.ts` 加 `getMarginMode()` GET + `setMarginMode(targetMode)` POST `/api/v1/me/margin-mode`，类型 `MarginModeResponse {marginMode, coolingUntil, canSwitch, blockers}`。

- [ ] **Step 2: 组件 + vitest**

`MarginModeToggle.tsx`：读 getMarginMode 显示当前账户 mode（ISOLATED/CROSS）；canSwitch=false 时 disabled + blockers 中文 tooltip（持仓/挂单/冷静期/CROSS未启用）；切换弹确认 modal（fx-modal）→ setMarginMode → 成功 invalidate account + message，失败映射 30080-30088 中文。挂 OrderTicket 顶部（或 Dashboard 账户区）。**命名 accountMarginMode 区分本地 defaultMarginMode。**
vitest：渲染当前 mode / canSwitch=false disabled+blockers / 切换调 POST / 错误码中文。

- [ ] **Step 3: Run vitest + lint + build**

Run: `cd falconx-frontend && npx vitest run src/features/trading/MarginModeToggle.test.tsx 2>&1 | tail -8 && npm run lint 2>&1 | tail -3 && npm run build 2>&1 | tail -3`
Expected: 绿/0/0。

- [ ] **Step 4: Commit**

```bash
git add falconx-frontend/src/features/trading/tradingApi.ts falconx-frontend/src/features/trading/MarginModeToggle.tsx falconx-frontend/src/features/trading/MarginModeToggle.test.tsx falconx-frontend/src/features/trading/OrderTicket.tsx
git commit -m "feat(frontend): STAGE-14E1 Task5 客户端 MarginModeToggle 对接 /me/margin-mode(canSwitch/blockers 中文/确认 modal/30080-30088)+vitest

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: 客户端 MarginLevelIndicator 浮窗

**Files:**
- Create: `falconx-frontend/src/features/trading/MarginLevelIndicator.tsx` + `MarginLevelIndicator.test.tsx`
- Modify: `falconx-frontend/src/features/dashboard/DashboardPage.tsx`（嵌入卡 + marginMode 展示）、`features/terminal/TradingTerminal.tsx`（传 marginLevel state + critical toast）

**先读**：Task4 的 TradingTerminal marginLevel state、三态 token（--fx-long/--fx-risk/--fx-danger）、`dispatchToast`（falconx:toast 事件总线，TradingTerminal 行 ~65-67）、DashboardPage stat 卡区（行 ~226-282）。

- [ ] **Step 1: 组件 + vitest**

`MarginLevelIndicator.tsx`：props {marginLevel, marginLevelStatus}；渲染百分比 + 三态颜色 badge（HEALTHY 绿/MARGIN_CALL 橙/STOP_OUT 红）+ hover/click 浮窗（含状态文字说明）；null 时显示「—」。
DashboardPage：第 6 张 stat 卡嵌入 + 显示 accountMarginMode。
TradingTerminal：marginLevelStatus 变为 MARGIN_CALL/STOP_OUT 时 dispatchToast critical（复用现有总线）。
vitest：三态渲染 + null 态 + 百分比格式。

- [ ] **Step 2: Run vitest + lint + build**

Run: `cd falconx-frontend && npx vitest run src/features/trading/MarginLevelIndicator.test.tsx 2>&1 | tail -8 && npm run lint 2>&1 | tail -3 && npm run build 2>&1 | tail -3`
Expected: 绿/0/0。

- [ ] **Step 3: Commit**

```bash
git add falconx-frontend/src/features/trading/MarginLevelIndicator.tsx falconx-frontend/src/features/trading/MarginLevelIndicator.test.tsx falconx-frontend/src/features/dashboard/DashboardPage.tsx falconx-frontend/src/features/terminal/TradingTerminal.tsx
git commit -m "feat(frontend): STAGE-14E1 Task6 客户端 MarginLevelIndicator 浮窗(三态颜色+百分比+MARGIN_CALL/STOP_OUT toast)+Dashboard 卡+marginMode 展示+vitest

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 7: 客户端双币 PnL 展示

**Files:**
- Modify: `falconx-frontend/src/features/trading/PositionsTable.tsx`、`PositionItemCard.tsx`、`features/dashboard/DashboardPage.tsx`（持仓 mini 表）
- Test: 对应 `.test.tsx`（若有）或新建最小测试

**先读**：Task4 后的 PositionItem 双币字段、PositionsTable PositionRow（行 ~346-390 unrealizedPnl 列）、PositionItemCard（行 ~54-57）、DashboardPage 持仓 mini 表（行 ~361-372）。

- [ ] **Step 1: 双币展示**

- PositionsTable PositionRow：`unrealizedPnl` 列改双行——主行账户币 `unrealizedPnlInAccount`（标账户币种）+ 副行报价币 `unrealizedPnlInQuote`（标 quoteCurrency）。
- PositionItemCard 未实现行同款双币。
- DashboardPage mini 表加 quoteCurrency 标注。
- 删所有旧 `unrealizedPnl` 引用（硬 break 完整性）。

- [ ] **Step 2: vitest（双币渲染）**

测持仓行展示双币 + 币种标注正确。

- [ ] **Step 3: Run vitest + lint + build（全量 vitest 一次确认无回归）**

Run: `cd falconx-frontend && npm run test 2>&1 | tail -12 && npm run lint 2>&1 | tail -3 && npm run build 2>&1 | tail -3`
Expected: 全量 vitest 绿(含新页)/lint 0(改动文件)/build 0。

- [ ] **Step 4: Commit**

```bash
git add falconx-frontend/src/features/trading/PositionsTable.tsx falconx-frontend/src/features/trading/PositionItemCard.tsx falconx-frontend/src/features/dashboard/DashboardPage.tsx
git commit -m "feat(frontend): STAGE-14E1 Task7 客户端持仓双币 PnL 展示(账户币主行+报价币副行+币种标注)+vitest+全量三件套

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 8: 浏览器 QA + R8 文档 + R7 收口

**Files:**
- Modify: `docs/api/WebSocket接口规范.md`（§5.3 position.update/pnl + account.update break 字段表）、`docs/api/FalconX统一接口文档.md`、`docs/setup/当前开发计划.md`、`docs/process/BBook一期完成执行路径.md`
- Create: `docs/test/STAGE-14E1-WS-BREAK-CLIENT-UI-R7-verification-report.md`

- [ ] **Step 1: 浏览器 QA**

尝试 Playwright/browse 截图桌面+移动（mode toggle / MarginLevel 浮窗 / 双币持仓）；WSL chromium 受限则标注已知（vitest+build 覆盖），不伪造。

- [ ] **Step 2: R8 文档**

- `docs/api/WebSocket接口规范.md` §5.3：position.update/position.pnl 字段表删 unrealizedPnl 加双币+isolatedMargin；account.update 加 equity/marginLevel/marginLevelStatus；标注「STAGE-14E1 硬 break，无 legacy」。
- `docs/api/FalconX统一接口文档.md`：account REST 响应新字段 + /me/margin-mode 客户端对接登记。
- `docs/setup/当前开发计划.md` §1：STAGE-14E1 收口条目 + 下一步 E2。
- `docs/process/BBook一期完成执行路径.md`：§15I E1 收口。

- [ ] **Step 3: R7 报告**

`docs/test/STAGE-14E1-WS-BREAK-CLIENT-UI-R7-verification-report.md`（沿 D2/C2 结构）：§0 部署阻断（14B 库漂移沿用 + **硬 break 需 trading-core+falconx-frontend 同窗口部署**）/ §1 范围 / §2 角色(R2 契约/R4/R5/R6/R7/R8) / §3 task 证据 / §4 测试(trading UT/IT + 前端 vitest + 三件套) / §5 已知不阻断(浏览器 QA WSL 受限 + marginLevel 推送 fill/close 事件非 per-tick 属 refinement) / §7 master §8.3 E 验收(客户端 6 截图 + WS break 无 legacy 残留) / §9 结论 + 使用说明 + 下一步 E2。

- [ ] **Step 4: 链接自检 + Commit**

```bash
git add docs/
git commit -m "docs(R7+R8): STAGE-14E1 WS break + 客户端实时 UI 收口报告 + WebSocket 规范 break 字段 + 计划录入(下一步 E2 管理端)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## 下一步：STAGE-14E2 管理端（FX 监控 + 多币聚合）

E1（WS break + 客户端 UI）后进入 **E2**：admin FX rate 监控页（console 透传 market `/internal/v1/market/fx/rates` + 错误码 90940 + console-frontend FxRateMonitorPage + 可选 admin.fx.rate.update WS channel）+ admin 多币种聚合（exposure 按 quoteCurrency 聚合 + admin.exposure.update 补 quoteCurrency）+ admin.account.mode.changed channel。E2 完成后 STAGE-14E 整体完成、STAGE-14 全阶段(A-E)收官，push。
