# STAGE-14E2 管理端 FX 监控 + 多币聚合 + admin WS break Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** STAGE-14 收官——管理端落地 FX rate 监控页（透传 14A market FX RPC）+ 多币种聚合（exposure 按 quoteCurrency 汇总 USD 等价）+ 完成 master §8.3 E 验收的 admin 侧 WS break（admin.position.update/admin.exposure.update 字段切换，无 legacy 残留），与 E1 客户端侧 break 对齐。

**Architecture:** console-service 照搬 C2/D3b 透传模式新建 AdminMarketFx（透传 market `/internal/v1/market/fx/rates` + 90940 + V14 权限）+ exposure 响应补 quoteCurrency；trading-core admin WS payload（admin.position.update）按 E1 同款硬切双币、admin.exposure.update 补 quoteCurrency；console-frontend 新建 FxRateMonitorPage（REST 轮询 8 FX + stale）+ TradingExposureBoardPage 加多币聚合 tab（已有 useAdminTradingSocket 消费 admin.* channel）。

**Tech Stack:** trading-core/console-service: Java 25 / Spring Boot 4 / JUnit5 + WireMock。market-service: 已有 FX RPC（14A，不改）。console-frontend: Vite + React + TS + AntD + Vitest。

**关键约束（务必带入）：**
- console 跨业务 schema 只读，不写业务表；FX 走 market internal RPC 透传（market `/internal/v1/market/fx/rates` 14A 已上线，不改 market）。
- FX 监控用 **REST 轮询**（master §7.5 admin.fx.rate.update 1Hz WS 推送 = 后续 refinement，本片用 REST poll，R7 标注）。`admin.account.mode.changed` 专用推送同样 defer（D1 Kafka 已发，additive）。
- **admin WS break（master §8.3 E 强制「admin 同步切换无 legacy」）**：admin.position.update 若仍单币 unrealizedPnl → 硬切双币（同 E1 口径）；admin.exposure.update 补 quoteCurrency。admin-frontend 同步消费。
- console 当前最高 Flyway 版本 **V13**（D3b）→ 新增 **V14**（实查 ls 顺延）。错误码 90940 ADMIN_FX_RATE_NOT_FOUND（master §7.3，console 当前最高 90952，加 90940 不冲突）。
- 跑 trading IT 前先 `mvn -pl falconx-market-contract -am install -DskipTests`。前端改文件 cp 同步主仓（若用 worktree）。
- 默认 main，每 task 一个 commit（不 amend）+ 中文 message + 末尾 `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`。仅 git add 本 task 文件，禁止混入 untracked。
- 既有 admin WS 基建：`useAdminTradingSocket.ts`（5 channel：admin.exposure/risk-actions/risk-switches/withdraws/positions）；exposure 看板 `TradingExposureBoardPage.tsx`（netExposureUsd per-symbol）；FX 上游 market `MarketFxRateInternalController`（GET /internal/v1/market/fx/rates 全量 + /{base}/{quote}）。
- 14B 既有 mixed-currency 聚合问题（getPositionSummary/UserPositionSummaryAggregator 按单币求和）——多币聚合 task 用 netExposureUsd（已 USD 折算）按 quoteCurrency 分组，不复用有缺陷的单币求和。

---

## File Structure

**trading-core（R4）admin WS break + exposure：**
- `websocket/AdminPositionPnlUpdatePayload.java`（改：硬切双币，对齐 E1）+ 其组装点（admin payload factory）
- exposure RPC 响应 + 算子（加 quoteCurrency；找 `/internal/v1/trading/console/exposures` 的 controller/service/DTO）
- 测试：admin payload UT + exposure RPC IT

**console-service（R9）：**
- `error/AdminErrorCode.java`（加 90940）+ `config/AdminGlobalExceptionHandler.java`（90940→404/400 映射）
- `market/AdminMarketFxController.java` + `AdminMarketFxApplicationService.java`（透传 market FX RPC）+ dto
- exposure 响应 DTO 加 quoteCurrency（`AdminTradingExposureListResponse`）+ 透传/聚合
- `db/migration/V14__seed_fx_monitor_permissions_and_menu.sql`（fx:view 权限 + 菜单）
- 测试：`AdminMarketFxEndpointIntegrationTests`（WireMock for market）

**console-frontend（R10）：**
- `features/market/fxRateApi.ts` + `types.ts` + `FxRateMonitorPage.tsx` + `FxRateMonitorPage.test.tsx`
- `features/trading/TradingExposureBoardPage.tsx`（改：多币聚合 tab）+ `useAdminTradingSocket.ts`（admin.exposure.update quoteCurrency 消费）
- `App.tsx`（FX 路由）、`features/layout/AdminLayout.tsx`（ICON_MAP/菜单 icon）

**文档（R8）：** `docs/api/管理端接口规范.md`、`docs/api/FalconX统一接口文档.md`、`docs/api/WebSocket接口规范.md`（admin break）、`docs/event/Kafka事件规范.md`（console FX consumer 标注）、`docs/setup/当前开发计划.md`、`docs/process/BBook一期完成执行路径.md`、`docs/test/STAGE-14E2-ADMIN-FX-AGG-R7-verification-report.md`

---

## Task 1: admin WS 字段 break（admin.position.update 双币 + admin.exposure.update quoteCurrency）

**Files:**
- Modify: trading-core admin WS payload（`AdminPositionPnlUpdatePayload` 或同名 + 组装 factory）+ exposure 推送 payload
- Test: admin payload UT + 既有 admin WS IT 回归

**先读**：E1 Task1 的 `TradingUserRealtimePayloadFactory.computeDualPnl`（复用同款双币算法）；admin WS 推送（搜 `TradingAdminRealtimePushService` / `AdminPositionPnlUpdatePayload` / admin.position.update / admin.exposure.update payload）；当前 admin.position.update 是否单币 unrealizedPnl；exposure payload 当前字段。

- [ ] **Step 1: 写失败 UT**

admin position payload UT：断言 admin.position.update payload 含双币（quoteCurrency/unrealizedPnlInQuote/unrealizedPnlInAccount，对齐 E1 客户端口径），无旧单币 unrealizedPnl。exposure payload 含 quoteCurrency。

- [ ] **Step 2: Run 失败**

Run: `mvn -pl falconx-market-contract -am install -DskipTests -q && mvn -pl falconx-trading-core-service test -Dtest=<admin payload UT> 2>&1 | grep -E "Tests run:|BUILD|ERROR"`
Expected: FAIL/编译错误。

- [ ] **Step 3: 硬切 admin payload**

admin position payload 删单币 unrealizedPnl 加双币（复用 computeDualPnl，FX 降级同 E1）；exposure payload 加 quoteCurrency（从 SymbolSpec）。修连带引用。

- [ ] **Step 4: Run 通过 + admin WS 回归 + test-compile**

Run: `mvn -pl falconx-trading-core-service test -Dtest=<admin payload UT,既有 admin WS/exposure IT> 2>&1 | grep -E "Tests run:|BUILD"` + `mvn -pl falconx-trading-core-service test-compile -q`（无残留单币 unrealizedPnl）。
Expected: 全绿。

- [ ] **Step 5: Commit**

```bash
git add falconx-trading-core-service/src/main/java/com/falconx/trading/websocket/ falconx-trading-core-service/src/test/java/com/falconx/trading/
git commit -m "feat(trading): STAGE-14E2 Task1 admin WS 硬切双币(admin.position.update)+admin.exposure.update 补 quoteCurrency(对齐 E1 无 legacy)+UT

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: exposure 多币种聚合（trading RPC + console 响应 quoteCurrency）

**Files:**
- Modify: trading-core exposure internal RPC（`/internal/v1/trading/console/exposures` 响应加 quoteCurrency）
- Modify: console-service `AdminTradingExposureListResponse`（加 quoteCurrency）+ 透传
- Test: exposure RPC IT + console 透传 IT

**先读**：trading exposure RPC（找 controller/service/DTO，当前 symbol/totalLong/totalShort/netExposure/netExposureUsd）；console `AdminTradingExposureListResponse`（explorer：netExposureUsd 已有，per-symbol）；SymbolSpec.quoteCurrency 取法。

- [ ] **Step 1: 写失败 IT/UT**

trading exposure RPC 响应含 quoteCurrency（每 symbol 的报价币）。console exposure 透传保留 quoteCurrency。（聚合在前端按 quoteCurrency 分组求和 netExposureUsd——后端只需暴露 quoteCurrency，聚合可前端做；若 master 要求后端聚合端点则加 `/exposures/by-currency`，否则前端聚合更简单——本 task 后端只补 quoteCurrency 字段，聚合放 Task5 前端。）

- [ ] **Step 2: Run 失败 → 加 quoteCurrency → Run 通过**

trading 加 quoteCurrency（从 SymbolSpec）；console DTO 加字段透传。Run exposure RPC IT + console 透传 IT 全绿。

- [ ] **Step 3: Commit**

```bash
git add falconx-trading-core-service/ falconx-console-service/
git commit -m "feat(trading+console): STAGE-14E2 Task2 exposure RPC + console 响应补 quoteCurrency(多币聚合数据基础)+IT

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: console FX 监控透传 + 90940 + V14 权限

**Files:**
- Modify: `falconx-console-service/.../error/AdminErrorCode.java`（90940）+ `config/AdminGlobalExceptionHandler.java`
- Create: `market/AdminMarketFxController.java` + `AdminMarketFxApplicationService.java` + dto（`FxRateView`）
- Create: `db/migration/V14__seed_fx_monitor_permissions_and_menu.sql`
- Test: `AdminMarketFxEndpointIntegrationTests.java`

**先读**：D3b `AdminPlatformConfigController`/`AdminTierController` 透传模式 + `InternalRpcClient`（透传 market `/internal/v1/market/fx/rates`——确认 InternalRpcClient 是否支持调 market，路由 `/internal/v1/market/**`→market；C2 tier 透传 trading，FX 透传 market，确认 client 可指定目标服务）；market `MarketFxRateInternalController` 响应结构（8 FX rate + stale）；C2/D3b V12/V13 seed 模式；`@RequiresPermission` + `AdminErrorCode`（90952 当前最高）。

- [ ] **Step 1: 错误码 + handler**

`AdminErrorCode` 加 `ADMIN_FX_RATE_NOT_FOUND("90940", "Admin FX Rate Not Found")`；handler 映射（404 或 400，沿既有风格）。

- [ ] **Step 2: 透传 Controller/Service + DTO**

`AdminMarketFxController` @RequestMapping("/admin/market") `GET /fx/rates` @RequiresPermission("fx:view") → `AdminMarketFxApplicationService` 透传 market `/internal/v1/market/fx/rates`（InternalRpcClient 调 market；确认 client 怎么指定 market 目标）→ `List<FxRateView>{baseCurrency, quoteCurrency, rate, stale, updatedAt}`（对齐 market 响应）。catch InternalRpcException → 90940。

- [ ] **Step 3: V14 权限 + 菜单**

`V14__seed_fx_monitor_permissions_and_menu.sql`（沿 V12/V13 三段幂等）：seed `fx:view`（id 段避开，如 9800001）+ 角色关联（risk-config:view 持有者 + SUPER_ADMIN）+ 「FX 汇率监控」菜单（parent 同 trading/market 父，icon ICON_MAP 已有键如 LineChartOutlined）。

- [ ] **Step 4: 透传 IT（WireMock for market）**

`AdminMarketFxEndpointIntegrationTests`：GET /admin/market/fx/rates（fx:view token）→ WireMock market 返回 8 rate → 200 透传；market 报错 → 90940；缺 fx:view → 403。

- [ ] **Step 5: Run + Commit**

Run: `mvn -pl falconx-console-service test -Dtest=AdminMarketFxEndpointIntegrationTests 2>&1 | grep -E "Tests run:|BUILD"`（全绿）。
```bash
git add falconx-console-service/
git commit -m "feat(console): STAGE-14E2 Task3 admin FX 监控透传(/admin/market/fx/rates→market RPC)+90940+V14 fx:view 权限/菜单+WireMock IT

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: console-frontend FX rate 监控页

**Files:**
- Create: `falconx-console-frontend/src/features/market/types.ts`、`fxRateApi.ts`、`FxRateMonitorPage.tsx`、`FxRateMonitorPage.test.tsx`
- Modify: `App.tsx`（路由）、`features/layout/AdminLayout.tsx`（icon 如需）

**先读**：D3b `FxPauseBehaviorConfigPage`/`MarginModeConfigPage`（Table/Form 页 + adminApi + RequiresPermission）；既有 Table 页 stale/badge 展示；adminApi get；App.tsx 路由注册。

- [ ] **Step 1: types + api**

`types.ts` `FxRate {baseCurrency, quoteCurrency, rate: string, stale: boolean, updatedAt: string}`。`fxRateApi.ts` `list()` → adminApi.get("/admin/market/fx/rates")。

- [ ] **Step 2: Page（8 FX Table + stale badge + REST 轮询）**

`FxRateMonitorPage.tsx`：useQuery（refetchInterval 3-5s 轮询）拉 FX rate；Table（列：货币对 base/quote、rate、stale badge 绿/红、更新时间）；`RequiresPermission code="fx:view"` 守卫（无权限空态）。复用现有 Table/badge 样式，不引入新库。

- [ ] **Step 3: 路由 + 菜单 icon**

App.tsx 加 `market/fx-rates` 路由（lazy）；AdminLayout ICON_MAP 确认菜单 icon 存在（与 V14 一致）。

- [ ] **Step 4: vitest**

`FxRateMonitorPage.test.tsx`：mock adminApi list 返回 8 rate；测渲染 8 行 / stale badge / API 路径 / RBAC 空态。

- [ ] **Step 5: Run + Commit**

Run: `cd falconx-console-frontend && npx vitest run src/features/market/FxRateMonitorPage.test.tsx 2>&1 | tail -8 && npm run lint 2>&1 | tail -3 && npm run build 2>&1 | tail -3`（绿/0/0）。
```bash
git add falconx-console-frontend/src/features/market/ falconx-console-frontend/src/App.tsx falconx-console-frontend/src/features/layout/AdminLayout.tsx
git commit -m "feat(console-frontend): STAGE-14E2 Task4 FX rate 监控页(8 FX Table+stale badge+REST 轮询+fx:view RBAC)+路由+vitest

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: console-frontend exposure 多币聚合 tab + admin WS 字段消费

**Files:**
- Modify: `falconx-console-frontend/src/features/trading/TradingExposureBoardPage.tsx`（多币聚合 tab）、`useAdminTradingSocket.ts`（admin.exposure.update quoteCurrency + admin.position.update 双币消费）、exposure types

**先读**：`TradingExposureBoardPage.tsx`（现有 per-symbol netExposureUsd 表 + admin.exposure.update 消费）、`useAdminTradingSocket.ts`（admin.exposure/positions channel handler）、Task2 后 exposure 响应的 quoteCurrency。

- [ ] **Step 1: 类型 + WS 消费切字段**

exposure item 类型加 quoteCurrency；admin.position.update 消费切双币（对齐 Task1 后端，删旧单币引用——admin frontend build/tsc 守卫）；useAdminTradingSocket 解析新字段。

- [ ] **Step 2: 多币聚合 tab**

TradingExposureBoardPage 加「按报价币聚合」tab/视图：按 quoteCurrency 分组汇总 netExposureUsd（USD 等价，已折算无 mixed-currency 问题）+ 各币种敞口占比。复用现有 Table。

- [ ] **Step 3: vitest + build（admin 硬 break 守卫）**

Run: `cd falconx-console-frontend && npm run test 2>&1 | tail -12 && npm run lint 2>&1 | tail -3 && npm run build 2>&1 | tail -3`（全量 vitest 绿 / lint 0 / build 0——build 守卫 admin 侧无旧单币字段残留）。

- [ ] **Step 4: Commit**

```bash
git add falconx-console-frontend/src/features/trading/
git commit -m "feat(console-frontend): STAGE-14E2 Task5 exposure 多币种聚合 tab(按 quoteCurrency 汇总 USD 等价)+admin WS 双币/quoteCurrency 消费+vitest+build

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: 全量验证 + R8 文档 + R7 收口 + push（STAGE-14 全阶段收官）

**Files:**
- Create: `docs/test/STAGE-14E2-ADMIN-FX-AGG-R7-verification-report.md`
- Modify: `docs/api/管理端接口规范.md`、`docs/api/FalconX统一接口文档.md`、`docs/api/WebSocket接口规范.md`（admin break 字段）、`docs/event/Kafka事件规范.md`（console FX consumer/admin.fx.rate.update defer 标注）、`docs/setup/当前开发计划.md`、`docs/process/BBook一期完成执行路径.md`

- [ ] **Step 1: 全量验证**

Run: `mvn -pl falconx-market-contract -am install -DskipTests -q && mvn -pl falconx-trading-core-service test 2>&1 | grep -E "Tests run: [0-9]+, Fail|BUILD" | tail -5`（trading 全量；失败逐条定性，区分 E2 新增 vs 既有 Kafka flake/瞬态死锁）。
Run: `mvn -pl falconx-console-service test -Dtest='AdminMarketFxEndpointIntegrationTests,AdminPlatformConfigEndpointIntegrationTests,AdminFxPauseBehaviorEndpointIntegrationTests' 2>&1 | grep -E "Tests run:|BUILD"`。
Run: `cd falconx-console-frontend && npm run test 2>&1 | tail -8 && npm run build 2>&1 | tail -3`；`cd falconx-frontend && npm run build 2>&1 | tail -3`（确认 E1 客户端不回归）。

- [ ] **Step 2: 浏览器 QA**

尝试 FX 监控页 + exposure 多币 tab 截图；WSL chromium 受限标注（vitest+build 覆盖）。

- [ ] **Step 3: R8 文档**

- `docs/api/管理端接口规范.md`：admin FX 监控端点（/admin/market/fx/rates + fx:view + 90940 + V14）+ exposure quoteCurrency。
- `docs/api/WebSocket接口规范.md`：admin.position.update/admin.exposure.update break 字段（双币/quoteCurrency，无 legacy）。
- `docs/event/Kafka事件规范.md`：console FX consumer 标注（admin.fx.rate.update 1Hz WS 推送 = defer refinement，本片 REST 轮询）。
- `docs/api/FalconX统一接口文档.md`：FX 监控 admin REST 登记。
- `docs/setup/当前开发计划.md` §1：STAGE-14E2 收口条目 + **STAGE-14E 整体完成（E0+E1+E2）+ STAGE-14 全阶段（A-E）收官** + 下一步（指向 BBook 后续阶段，避免计划真空）。
- `docs/process/BBook一期完成执行路径.md`：§15J E2 收口。

- [ ] **Step 4: R7 报告**

`docs/test/STAGE-14E2-ADMIN-FX-AGG-R7-verification-report.md`（沿 D2/C2 结构）：§0 部署阻断（14B 沿用 + console V14 干净 + E1 硬 break 同窗口部署沿用）/§1 范围（admin FX/多币聚合/admin WS break）/§2 角色（R4/R9/R10/R6/R7/R8）/§3 task 证据/§4 测试统计/§5 已知不阻断（admin.fx.rate.update/admin.account.mode.changed 专用 WS 推送 channel = defer refinement，FX 用 REST 轮询 + 浏览器 QA WSL 受限 + 沿用 E1 refinement/tech-debt）/§7 master §8.3 E 验收（admin 桌面 QA 截图 6 张[WSL 标注] + WS break 客户端+admin 同步无 legacy ✓ + 多币聚合 ✓）/§8 R8 清单/§9 结论（STAGE-14 A-E 全收官 + 不写无条件"生产可用" + 剩余 14B 库阻断/硬 break 部署）+ 使用说明 + 下一步。

- [ ] **Step 5: 链接自检 + Commit**

```bash
git add docs/
git commit -m "docs(R7+R8): STAGE-14E2 管理端 FX 监控+多币聚合+admin WS break 收口报告 + 文档同步 + 计划录入(STAGE-14 A-E 全阶段收官)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

- [ ] **Step 6: push（STAGE-14E 整体完成，按用户约定形成 GitHub 回滚点）**

Run: `git push origin main`（push 前确认 git status 无混入 untracked）。

---

## 下一步（避免计划真空，§3.6.4）

STAGE-14（A-E）全阶段收官后，按 [`docs/setup/当前开发计划.md`](../setup/当前开发计划.md) §1 与 [`BBook 一期完成执行路径`](./BBook一期完成执行路径.md) 推进 BBook 一期剩余阶段。延后 refinement（独立 task，非阻断）：admin.fx.rate.update 1Hz WS 推送 channel（当前 REST 轮询）+ admin.account.mode.changed 模式变更流 + marginLevel per-tick 实时化 + MarginLevelMonitor 事件化解耦 + getPositionSummary 多币聚合修正 + 真机浏览器 QA 截图补充。
