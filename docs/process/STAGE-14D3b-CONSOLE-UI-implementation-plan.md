# STAGE-14D3b console 三端 UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给 D3a 落地的三类 admin 可配后端（margin mode 冷静期 / StopOut+MarginCall 阈值 / FX_PAUSED 8 类目×3 行为开关）补齐管理端三端 UI——console-service 透传 + RBAC + 审计 + 错误翻译，console-frontend 3 个配置页（高危二次确认/reason + 按钮级 RBAC），照搬 STAGE-14C2 tier 配置三端模式。

**Architecture:** console-service 照搬 C2 `AdminTierController`→`AdminTierApplicationService`→`InternalRpcClient` 透传链，3 类配置各一个 controller+applicationService，`@RequiresPermission` + `OperationAuditAspect`（高危写 `t_admin_operation_log`）+ `AdminGlobalExceptionHandler` 把 trading 端 `99004 INVALID_REQUEST_PAYLOAD`/`30087` 翻成 console 90xxx；透传目标全部是 **trading-core** internal RPC（含 fx-pause-behavior，master §7.4 的 `/admin/market` 路由按实际表 owner 修正到 trading）。console-frontend 照搬 `PlatformRiskConfigPage`（单 Form）/ `RiskConfigListPage`（列表）/ `TierFormModal`（高危确认）模式建 3 页。

**Tech Stack:** console-service: Java 25 / Spring Boot 4 / MyBatis / MySQL / Flyway / JUnit5 + WireMock(透传 IT)。console-frontend: Vite + React + TS + AntD + React Query + Vitest。

**关键约束（务必带入）：**
- console 跨业务 schema 只读，**不得写业务表**；写操作只走 trading-core internal RPC（D3a 已冻结：`/internal/v1/trading/console/config/*` + `/internal/v1/trading/console/fx-pause-behavior[/{category}]`）。
- 所有 admin API 必须 `@RequiresPermission`；写操作高危（`OperationAuditAspect` 写 `t_admin_operation_log`，写前 `AuditSnapshotHolder.set`）。
- 权限点本身由 `AdminPermissionDictionaryInitializer` 启动自动入库；V13 只 seed 角色关联 + 菜单（不依赖运行时时序，显式 seed 权限点行也可，沿 C2 V12 幂等口径）。
- console 当前最高 Flyway 版本 **V12** → 新增 **V13**（实查 `ls falconx-console-service/src/main/resources/db/migration | sort -V | tail` 顺延）。
- 前端改文件后若用 worktree/dev server，必须 cp 同步主仓（见仓库 memory）。
- 默认 main，每 task 一个 commit（不 amend）+ 中文 message + 末尾 `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`。
- 仅 git add 本 task 文件，禁止混入 untracked（docker-compose.override.yml / qa-ind-*.png）。
- D3a 已冻结的 trading RPC（透传目标）：
  - `GET /internal/v1/trading/console/config/platform-risk` → `{coolingPeriodSeconds, stopOutLevel, marginCallLevel}`
  - `PUT /internal/v1/trading/console/config/cooling-period` body `{coolingPeriodSeconds}`（60-604800）
  - `PUT /internal/v1/trading/console/config/risk-thresholds` body `{stopOutLevel, marginCallLevel}`（0.05-0.95 / 0.50-2.00）
  - `GET /internal/v1/trading/console/fx-pause-behavior` → 8 行 `{category, categoryName?, allowOpen, allowClose, allowLiquidation, updatedByAdminId}`
  - `PUT /internal/v1/trading/console/fx-pause-behavior/{category}` body `{allowOpen, allowClose, allowLiquidation}`（category 1-8）
  - trading 端校验失败返回 `99004 INVALID_REQUEST_PAYLOAD`；FX_PAUSED 相关业务码 `30087`。

---

## 契约决策（D3b 落地口径，沿 master §7.3/§7.4 + C2）

**console REST 路径**（统一挂 `/admin/trading/*`，与 C2 tier `/admin/trading/tiers` 同组；master §7.4 的 `/admin/config/margin-mode`、`/admin/market/fx/pause-behavior` 按实际 owner 收敛到 trading）：
| console 路径 | 方法 | 透传 trading RPC | RBAC |
|---|---|---|---|
| `/admin/trading/margin-mode-config` | GET | config/platform-risk（取 coolingPeriodSeconds） | `margin-mode-config:view` |
| `/admin/trading/margin-mode-config` | PUT | config/cooling-period | `margin-mode-config:edit`（高危） |
| `/admin/trading/risk-thresholds` | GET | config/platform-risk（取 stopOut/marginCall） | `risk-threshold:view` |
| `/admin/trading/risk-thresholds` | PUT | config/risk-thresholds | `risk-threshold:edit`（高危） |
| `/admin/trading/fx-pause-behavior` | GET | fx-pause-behavior（8 行） | `fx:pause-behavior:view` |
| `/admin/trading/fx-pause-behavior/{category}` | PUT | fx-pause-behavior/{category} | `fx:pause-behavior:edit`（高危） |

**console 错误码（AdminErrorCode 新增，沿 C1 90930-90932 风格，master §7.3 已列 90950/90951）：**
- `90950 ADMIN_MARGIN_MODE_CONFIG_INVALID`（冷静期范围非法）
- `90951 ADMIN_FX_PAUSE_BEHAVIOR_INVALID`（FX 行为参数非法）
- `90952 ADMIN_RISK_THRESHOLD_INVALID`（阈值范围非法）

trading 端 `99004 INVALID_REQUEST_PAYLOAD` 在各 ApplicationService 的 catch 按端点翻成对应 90950/90951/90952。

**console 权限点（V13 + @RequiresPermission 自动入库）：** `margin-mode-config:view/edit`、`risk-threshold:view/edit`、`fx:pause-behavior:view/edit`（3 个 `:edit` 注册高危）。

---

## File Structure

**console-service（R9）新增：**
- `error/AdminErrorCode.java`（修改：加 90950/90951/90952）
- `config/AdminGlobalExceptionHandler.java`（修改：新码映射 400）
- `security/HighRiskPermissionRegistry.java`（修改：加 3 个 edit 码）
- `config/AdminPlatformConfigController.java`（新建：冷静期 + 阈值 6 端点）
- `config/AdminPlatformConfigApplicationService.java`（新建：透传 + 审计 + 错误翻译）
- `config/dto/`（新建：`MarginModeConfigView`/`UpdateCoolingPeriodRequest`/`RiskThresholdView`/`UpdateRiskThresholdRequest`，均含 reason）
- `fxpause/AdminFxPauseBehaviorController.java`（新建：GET/PUT {category}）
- `fxpause/AdminFxPauseBehaviorApplicationService.java`（新建）
- `fxpause/dto/`（新建：`FxPauseBehaviorView`/`UpdateFxPauseBehaviorRequest`，含 reason）
- `db/migration/V13__seed_platform_config_permissions_and_menu.sql`（新建）
- 测试：`AdminPlatformConfigEndpointIntegrationTests.java`、`AdminFxPauseBehaviorEndpointIntegrationTests.java`（WireMock for trading）

**console-frontend（R10）新增：**
- `features/platform-config/marginModeApi.ts` + `types.ts` + `MarginModeConfigPage.tsx` + `MarginModeConfigPage.test.tsx`
- `features/platform-config/riskThresholdApi.ts` + `RiskThresholdConfigPage.tsx` + `RiskThresholdConfigPage.test.tsx`
- `features/fx-pause/fxPauseApi.ts` + `types.ts` + `FxPauseBehaviorConfigPage.tsx` + `FxPauseBehaviorConfigPage.test.tsx`
- `App.tsx`（修改：3 路由）、`features/layout/AdminLayout.tsx`（修改：ICON_MAP 如需新图标）

**文档（R8）：** `docs/api/管理端接口规范.md`、`docs/api/FalconX统一接口文档.md`、`docs/setup/当前开发计划.md`、`docs/process/BBook一期完成执行路径.md`、`docs/test/STAGE-14D3b-CONSOLE-UI-R7-verification-report.md`

---

## Task 1: console 错误码 + 翻译 + 高危注册

**Files:**
- Modify: `falconx-console-service/src/main/java/com/falconx/console/error/AdminErrorCode.java`
- Modify: `falconx-console-service/src/main/java/com/falconx/console/config/AdminGlobalExceptionHandler.java`
- Modify: `falconx-console-service/src/main/java/com/falconx/console/security/HighRiskPermissionRegistry.java`

**先读** `AdminErrorCode`（确认枚举风格 + 最高码 90932）、`AdminGlobalExceptionHandler`（确认 switch/case 把 AdminErrorCode 映射 HTTP status 的写法）、`HighRiskPermissionRegistry`（确认 CODES Set.of 风格）。

- [ ] **Step 1: 加错误码**

`AdminErrorCode.java` 加（沿 90930-90932 风格，message 英文）：
```java
ADMIN_MARGIN_MODE_CONFIG_INVALID("90950", "Admin Margin Mode Config Invalid"),
ADMIN_FX_PAUSE_BEHAVIOR_INVALID("90951", "Admin FX Pause Behavior Invalid"),
ADMIN_RISK_THRESHOLD_INVALID("90952", "Admin Risk Threshold Invalid"),
```

- [ ] **Step 2: 异常处理映射 400**

`AdminGlobalExceptionHandler.java`：把上面 3 个码映射为 HTTP 400（沿既有 90931/90932→400 BAD_REQUEST 的 case 风格，加入对应分支）。

- [ ] **Step 3: 注册高危**

`HighRiskPermissionRegistry.java` 的 `CODES` 加：`"margin-mode-config:edit"`, `"risk-threshold:edit"`, `"fx:pause-behavior:edit"`。

- [ ] **Step 4: 编译**

Run: `mvn -pl falconx-console-service -am compile -DskipTests -q 2>&1 | tail -3`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add falconx-console-service/src/main/java/com/falconx/console/error/AdminErrorCode.java falconx-console-service/src/main/java/com/falconx/console/config/AdminGlobalExceptionHandler.java falconx-console-service/src/main/java/com/falconx/console/security/HighRiskPermissionRegistry.java
git commit -m "feat(console): STAGE-14D3b Task1 平台配置错误码 90950/90951/90952 + 400 翻译 + 3 个 edit 高危注册

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: console 冷静期 + 阈值透传（AdminPlatformConfigController/Service）

**Files:**
- Create: `falconx-console-service/.../config/dto/MarginModeConfigView.java`、`UpdateCoolingPeriodRequest.java`、`RiskThresholdView.java`、`UpdateRiskThresholdRequest.java`
- Create: `falconx-console-service/.../config/AdminPlatformConfigApplicationService.java`
- Create: `falconx-console-service/.../config/AdminPlatformConfigController.java`
- Test: `falconx-console-service/src/test/java/com/falconx/console/AdminPlatformConfigEndpointIntegrationTests.java`

**先读** C2 `AdminTierController` + `AdminTierApplicationService` + `InternalRpcClient`（get/put 签名 + ParameterizedTypeReference<ApiResponse<T>> 用法）+ `AuditSnapshotHolder` + `AdminTierEndpointIntegrationTests`（WireMock + AdminTokenSupport.issueAccessToken + RBAC 正/负路 + verify internalRpcClient 调用）。**完全照搬该模式。**

- [ ] **Step 1: DTO（record，写请求含 reason）**

```java
public record MarginModeConfigView(int coolingPeriodSeconds) {}
public record UpdateCoolingPeriodRequest(Integer coolingPeriodSeconds, String reason) {}
public record RiskThresholdView(BigDecimal stopOutLevel, BigDecimal marginCallLevel) {}
public record UpdateRiskThresholdRequest(BigDecimal stopOutLevel, BigDecimal marginCallLevel, String reason) {}
```
（前端范围校验为主；console 端 reason 必填校验沿 C2 `verifyReason`。trading 端 Bean Validation 兜底范围。）

- [ ] **Step 2: ApplicationService（照搬 AdminTierApplicationService）**

`AdminPlatformConfigApplicationService`（注入 `InternalRpcClient`）：
- `getMarginModeConfig()`: GET `/internal/v1/trading/console/config/platform-risk` → 取 coolingPeriodSeconds 包成 MarginModeConfigView。
- `updateCoolingPeriod(req)`: `verifyReason(req.reason())`；`AuditSnapshotHolder.set(before, after)`（before 可为当前值或 null，after 为 `Map.of("coolingPeriodSeconds", req.coolingPeriodSeconds())`）；`internalRpcClient.put("/internal/v1/trading/console/config/cooling-period", Map.of("coolingPeriodSeconds", req.coolingPeriodSeconds()), ApiResponse<Void> ref)`；catch `InternalRpcException` → `translateConfigError(ex)`（downstreamCode 99004 → `ADMIN_MARGIN_MODE_CONFIG_INVALID`；其余 rethrow 或通用）。
- `getRiskThresholds()`: GET platform-risk → RiskThresholdView。
- `updateRiskThresholds(req)`: verifyReason + AuditSnapshotHolder.set + PUT `/internal/v1/trading/console/config/risk-thresholds` + catch → 99004 翻 `ADMIN_RISK_THRESHOLD_INVALID`。

- [ ] **Step 3: Controller（照搬 AdminTierController）**

`AdminPlatformConfigController` @RequestMapping("/admin/trading")：
```java
@GetMapping("/margin-mode-config") @RequiresPermission("margin-mode-config:view") → success(service.getMarginModeConfig())
@PutMapping("/margin-mode-config")  @RequiresPermission("margin-mode-config:edit") @RequestBody UpdateCoolingPeriodRequest → service.updateCoolingPeriod(req); success(null)
@GetMapping("/risk-thresholds")     @RequiresPermission("risk-threshold:view") → success(service.getRiskThresholds())
@PutMapping("/risk-thresholds")     @RequiresPermission("risk-threshold:edit") @RequestBody UpdateRiskThresholdRequest → service.updateRiskThresholds(req); success(null)
```
（`@RequiresPermission` 注解参数风格以仓库实际为准——可能是 `value=` + `description=`。）

- [ ] **Step 4: 透传 IT（WireMock for trading，照搬 AdminTierEndpointIntegrationTests）**

`AdminPlatformConfigEndpointIntegrationTests`：
- GET margin-mode-config（superadmin token）→ WireMock 返回 platform-risk → 200，coolingPeriodSeconds 透传。
- PUT margin-mode-config（margin-mode-config:edit token）→ WireMock 200 → 200；verify PUT 调 cooling-period + 审计写入。
- PUT margin-mode-config 无 reason → 400（verifyReason）。
- PUT margin-mode-config 缺 `margin-mode-config:edit` 权限的角色 → 403。
- PUT risk-thresholds WireMock 返回 99004 → 翻 90952。
- GET/PUT risk-thresholds 正路 + RBAC 负路。

- [ ] **Step 5: Run IT**

Run: `mvn -pl falconx-console-service test -Dtest=AdminPlatformConfigEndpointIntegrationTests 2>&1 | grep -E "Tests run:|BUILD"`
Expected: 全绿。

- [ ] **Step 6: Commit**

```bash
git add falconx-console-service/src/main/java/com/falconx/console/config/ falconx-console-service/src/test/java/com/falconx/console/AdminPlatformConfigEndpointIntegrationTests.java
git commit -m "feat(console): STAGE-14D3b Task2 冷静期/StopOut阈值 console 透传(/admin/trading/margin-mode-config|risk-thresholds GET/PUT)+RBAC+审计+99004→90950/90952 翻译+WireMock IT

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: console FX_PAUSED 行为透传（AdminFxPauseBehaviorController/Service）

**Files:**
- Create: `falconx-console-service/.../fxpause/dto/FxPauseBehaviorView.java`、`UpdateFxPauseBehaviorRequest.java`
- Create: `falconx-console-service/.../fxpause/AdminFxPauseBehaviorApplicationService.java`
- Create: `falconx-console-service/.../fxpause/AdminFxPauseBehaviorController.java`
- Test: `falconx-console-service/src/test/java/com/falconx/console/AdminFxPauseBehaviorEndpointIntegrationTests.java`

- [ ] **Step 1: DTO**

```java
public record FxPauseBehaviorView(int category, String categoryName, boolean allowOpen, boolean allowClose, boolean allowLiquidation) {}
public record UpdateFxPauseBehaviorRequest(Boolean allowOpen, Boolean allowClose, Boolean allowLiquidation, String reason) {}
```
（categoryName 透传 trading 返回；若 trading 未返回名则用 category 数字，前端映射展示名。）

- [ ] **Step 2: ApplicationService**

`AdminFxPauseBehaviorApplicationService`（注入 InternalRpcClient）：
- `listBehaviors()`: GET `/internal/v1/trading/console/fx-pause-behavior` → `List<FxPauseBehaviorView>`（ParameterizedTypeReference<ApiResponse<List<...>>>）。
- `updateBehavior(category, req)`: `verifyReason(req.reason())` + `AuditSnapshotHolder.set(null, Map.of("category", category, "allowOpen", ..., "allowClose", ..., "allowLiquidation", ...))` + PUT `/internal/v1/trading/console/fx-pause-behavior/{category}` body `{allowOpen, allowClose, allowLiquidation}` + catch 99004 → `ADMIN_FX_PAUSE_BEHAVIOR_INVALID`。

- [ ] **Step 3: Controller**

`AdminFxPauseBehaviorController` @RequestMapping("/admin/trading")：
```java
@GetMapping("/fx-pause-behavior") @RequiresPermission("fx:pause-behavior:view") → success(service.listBehaviors())
@PutMapping("/fx-pause-behavior/{category}") @RequiresPermission("fx:pause-behavior:edit") @PathVariable int category @RequestBody UpdateFxPauseBehaviorRequest → service.updateBehavior(category, req); success(null)
```

- [ ] **Step 4: 透传 IT（WireMock）**

`AdminFxPauseBehaviorEndpointIntegrationTests`：
- GET fx-pause-behavior（fx:pause-behavior:view token）→ WireMock 8 行 → 200，8 行透传。
- PUT fx-pause-behavior/2（fx:pause-behavior:edit token，含 reason）→ WireMock 200 → 200；verify PUT 调 trading /{2} + 审计写入。
- PUT 无 reason → 400。
- PUT 缺 `fx:pause-behavior:edit` 权限 → 403。
- PUT WireMock 返回 99004 → 翻 90951。

- [ ] **Step 5: Run IT**

Run: `mvn -pl falconx-console-service test -Dtest=AdminFxPauseBehaviorEndpointIntegrationTests 2>&1 | grep -E "Tests run:|BUILD"`
Expected: 全绿。

- [ ] **Step 6: Commit**

```bash
git add falconx-console-service/src/main/java/com/falconx/console/fxpause/ falconx-console-service/src/test/java/com/falconx/console/AdminFxPauseBehaviorEndpointIntegrationTests.java
git commit -m "feat(console): STAGE-14D3b Task3 FX_PAUSED 行为 console 透传(/admin/trading/fx-pause-behavior GET/PUT{category})+RBAC+审计+99004→90951 翻译+WireMock IT

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: console V13 权限 + 菜单 seed

**Files:**
- Create: `falconx-console-service/src/main/resources/db/migration/V13__seed_platform_config_permissions_and_menu.sql`

**先读** C2 `V12__seed_tier_permissions_and_menu.sql`（三段结构：t_admin_permission 幂等 seed + t_admin_role_permission 平滑关联 + t_admin_menu 幂等）+ 确认 console 下一版本号（ls migration | sort -V | tail）+ 父菜单 id（tier 菜单挂在哪个父，沿用同父）。

- [ ] **Step 1: 确认版本 + 父菜单**

Run: `ls falconx-console-service/src/main/resources/db/migration | sort -V | tail -3`（确认 V13 下一个）
读 V12 找 tier 菜单的 parent_id 与 id 段。

- [ ] **Step 2: 写 V13（沿 V12 三段，无 USE，幂等 WHERE NOT EXISTS）**

seed 6 个权限点（id 段避开已用，如 9700001-9700006）：`margin-mode-config:view/edit`、`risk-threshold:view/edit`、`fx:pause-behavior:view/edit`（module/action 拆分沿 V12 风格）+ 角色关联（关联到已持 `tier:edit`/`risk-config` 的角色 + SUPER_ADMIN，沿 V12 JOIN 补齐）+ 3 个菜单（「冷静期配置」「StopOut 阈值配置」「FX 暂停行为配置」挂 tier 同父菜单，permission_code 用对应 :view，icon 用 ICON_MAP 已有键如 SlidersOutlined/SafetyOutlined/ControlOutlined）。

- [ ] **Step 3: 编译（migration 语法）+ console 起动加载验证（如有 smoke）**

Run: `mvn -pl falconx-console-service -am compile -DskipTests -q 2>&1 | tail -3`
Expected: BUILD SUCCESS（migration 真应用由 Task2/3 IT 的 Spring 上下文或后续 smoke 验证）。

- [ ] **Step 4: Commit**

```bash
git add falconx-console-service/src/main/resources/db/migration/V13__seed_platform_config_permissions_and_menu.sql
git commit -m "feat(console): STAGE-14D3b Task4 V13 seed 平台配置 6 权限点(margin-mode-config/risk-threshold/fx:pause-behavior view+edit)+角色关联+3 菜单

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: console-frontend 冷静期配置页

**Files:**
- Create: `falconx-console-frontend/src/features/platform-config/types.ts`、`marginModeApi.ts`、`MarginModeConfigPage.tsx`、`MarginModeConfigPage.test.tsx`
- Modify: `falconx-console-frontend/src/App.tsx`（路由）、`features/layout/AdminLayout.tsx`（如需 ICON_MAP 键）

**先读** `PlatformRiskConfigPage.tsx`（单 Form + reason + 提交模式）、`tier/tierApi.ts`（adminApi get/put）、`TierFormModal.tsx`（高危 reason+acknowledge）、`components/RequiresPermission.tsx`、`TierConfigListPage.test.tsx`（vitest mock 模式）、`App.tsx`（lazy import + route）。

- [ ] **Step 1: types + api**

`types.ts`: `MarginModeConfig { coolingPeriodSeconds: number }`。
`marginModeApi.ts`: `getMarginModeConfig()` → `adminApi.get("/admin/trading/margin-mode-config")`；`updateCoolingPeriod(coolingPeriodSeconds, reason)` → `adminApi.put("/admin/trading/margin-mode-config", {coolingPeriodSeconds, reason})`。

- [ ] **Step 2: Page（单 Form + RBAC + 高危确认）**

`MarginModeConfigPage.tsx`：useQuery 拉当前值填 Form（InputNumber 60-604800，显示「秒（60s-7d）」）；提交按钮用 `<RequiresPermission code="margin-mode-config:edit">` 包裹；点保存弹高危确认（reason 必填 + acknowledge 勾选，照搬 TierFormModal 模式）；成功 message + 刷新。无 `:edit` 权限按钮不显示。

- [ ] **Step 3: 路由注册**

`App.tsx`：lazy import + `<Route path="trading/margin-mode-config" .../>`（照搬 TierConfigListPage 注册）。如需新 icon 在 AdminLayout ICON_MAP 注册（与 V13 菜单 icon 字符串一致）。

- [ ] **Step 4: vitest（照搬 TierConfigListPage.test.tsx）**

`MarginModeConfigPage.test.tsx`：mock adminApi + useHasPermission；测：渲染拉取当前值 / API 路径正确 / 有 edit 权限显示保存、无权限隐藏 / 提交带 reason+acknowledge 调 put / 范围校验。

- [ ] **Step 5: Run vitest + lint + build**

Run: `cd falconx-console-frontend && npx vitest run src/features/platform-config/MarginModeConfigPage.test.tsx 2>&1 | tail -8 && npm run lint 2>&1 | tail -3 && npm run build 2>&1 | tail -3`
Expected: 测试绿 / lint 0（改动文件）/ build 退出 0。

- [ ] **Step 6: Commit**

```bash
git add falconx-console-frontend/src/features/platform-config/ falconx-console-frontend/src/App.tsx falconx-console-frontend/src/features/layout/AdminLayout.tsx
git commit -m "feat(console-frontend): STAGE-14D3b Task5 冷静期配置页(单Form 60s-7d + RBAC + 高危reason/acknowledge)+路由+vitest

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: console-frontend StopOut/MarginCall 阈值配置页

**Files:**
- Create: `falconx-console-frontend/src/features/platform-config/riskThresholdApi.ts`、`RiskThresholdConfigPage.tsx`、`RiskThresholdConfigPage.test.tsx`
- Modify: `falconx-console-frontend/src/App.tsx`（路由）、`features/platform-config/types.ts`（加 RiskThreshold 类型）

**先读** Task5 产出 + `PlatformRiskConfigPage.tsx`。

- [ ] **Step 1: types + api**

`types.ts` 加 `RiskThreshold { stopOutLevel: string; marginCallLevel: string }`（字符串承载精度）。
`riskThresholdApi.ts`: `getRiskThresholds()` → get `/admin/trading/risk-thresholds`；`updateRiskThresholds(stopOutLevel, marginCallLevel, reason)` → put 同路径。

- [ ] **Step 2: Page**

`RiskThresholdConfigPage.tsx`：单 Form 两字段（stopOutLevel 0.05-0.95 / marginCallLevel 0.50-2.00，InputNumber step 0.01，显示百分比提示如「0.30 = 30%」）；`<RequiresPermission code="risk-threshold:edit">` 包裹保存；高危确认 reason+acknowledge；前端范围校验（stopOut<marginCall 合理性提示可选）。

- [ ] **Step 3: 路由 `trading/risk-thresholds`**

- [ ] **Step 4: vitest**

`RiskThresholdConfigPage.test.tsx`：渲染拉值 / API 路径 / RBAC 显隐 / 提交带 reason / 范围校验拒绝越界。

- [ ] **Step 5: Run vitest + lint + build**

Run: `cd falconx-console-frontend && npx vitest run src/features/platform-config/RiskThresholdConfigPage.test.tsx 2>&1 | tail -8 && npm run lint 2>&1 | tail -3 && npm run build 2>&1 | tail -3`
Expected: 绿 / lint 0 / build 0。

- [ ] **Step 6: Commit**

```bash
git add falconx-console-frontend/src/features/platform-config/ falconx-console-frontend/src/App.tsx
git commit -m "feat(console-frontend): STAGE-14D3b Task6 StopOut/MarginCall 阈值配置页(0.05-0.95/0.50-2.00 + RBAC + 高危确认)+路由+vitest

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 7: console-frontend FX_PAUSED 8 类目行为配置页

**Files:**
- Create: `falconx-console-frontend/src/features/fx-pause/types.ts`、`fxPauseApi.ts`、`FxPauseBehaviorConfigPage.tsx`、`FxPauseBehaviorConfigPage.test.tsx`
- Modify: `falconx-console-frontend/src/App.tsx`（路由）

**先读** `RiskConfigListPage.tsx` 或 `RiskMarketConfigListPage.tsx`（全量加载无分页 Table 模式）+ `TierFormModal.tsx`（高危确认）+ Task5 产出。

- [ ] **Step 1: types + api**

`types.ts`: `FxPauseBehavior { category: number; categoryName?: string; allowOpen: boolean; allowClose: boolean; allowLiquidation: boolean }`。
`fxPauseApi.ts`: `listFxPauseBehaviors()` → get `/admin/trading/fx-pause-behavior`（8 行）；`updateFxPauseBehavior(category, {allowOpen, allowClose, allowLiquidation, reason})` → put `/admin/trading/fx-pause-behavior/{category}`。

- [ ] **Step 2: Page（固定 8 行 Table + 编辑）**

`FxPauseBehaviorConfigPage.tsx`：useQuery 拉 8 行渲染 Table（列：类目（category + 中文名映射 crypto/forex/metal/index/energy/stock/etf/other）、allow_open、allow_close、allow_liquidation、操作）；每行「编辑」按钮 `<RequiresPermission code="fx:pause-behavior:edit">`；点编辑弹 Modal（3 个 Switch + reason 必填 + acknowledge 勾选，高危）；`allow_close` 可编辑但默认提示「手动平仓不限制，建议保持开启」；保存调 put + 刷新。无 `:edit` 隐藏编辑按钮。

- [ ] **Step 3: 路由 `trading/fx-pause-behavior`**

- [ ] **Step 4: vitest**

`FxPauseBehaviorConfigPage.test.tsx`：渲染 8 行 / API 路径 / RBAC 编辑按钮显隐 / 编辑 Modal 提交带 reason+acknowledge 调 put{category} / 8 类目名映射正确。

- [ ] **Step 5: Run vitest + lint + build（全量 build 一次）**

Run: `cd falconx-console-frontend && npx vitest run src/features/fx-pause/FxPauseBehaviorConfigPage.test.tsx 2>&1 | tail -8 && npm run lint 2>&1 | tail -3 && npm run build 2>&1 | tail -3`
Expected: 绿 / lint 0 / build 0。

- [ ] **Step 6: Commit**

```bash
git add falconx-console-frontend/src/features/fx-pause/ falconx-console-frontend/src/App.tsx
git commit -m "feat(console-frontend): STAGE-14D3b Task7 FX_PAUSED 8 类目行为配置页(8行Table+3开关Switch+RBAC+高危reason/acknowledge)+路由+vitest

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 8: 三件套全量 + 浏览器 QA + R7/R8 收口 + 计划

**Files:**
- Create: `docs/test/STAGE-14D3b-CONSOLE-UI-R7-verification-report.md`
- Modify: `docs/api/管理端接口规范.md`、`docs/api/FalconX统一接口文档.md`、`docs/setup/当前开发计划.md`、`docs/process/BBook一期完成执行路径.md`

- [ ] **Step 1: console-service 全量相关 IT + 前端三件套全量**

Run: `mvn -pl falconx-console-service test -Dtest='AdminPlatformConfigEndpointIntegrationTests,AdminFxPauseBehaviorEndpointIntegrationTests' 2>&1 | grep -E "Tests run:|BUILD"`
Run: `cd falconx-console-frontend && npm run test 2>&1 | tail -8 && npm run lint 2>&1 | tail -3 && npm run build 2>&1 | tail -3`
Expected: 全绿 / lint 0 / build 0。

- [ ] **Step 2: 浏览器 QA（WSL chromium 受限则标注已知）**

尝试 Playwright/browse 截图 3 页桌面态（登录 console → 3 配置页 → 高危 modal）；WSL chromium 受限则按 C2 口径标注「vitest + build 覆盖，真机截图待有 sudo 环境/CI 补」。

- [ ] **Step 3: R8 文档同步**

- `docs/api/管理端接口规范.md`：把 Task9(D3a) 占位的 console 端点落实（6 端点真实路径 `/admin/trading/margin-mode-config|risk-thresholds|fx-pause-behavior` + RBAC + 审计 + 错误码 90950/90951/90952 + V13 权限/菜单 + 测试结论）。
- `docs/api/FalconX统一接口文档.md`：console admin REST 端点登记（沿 §3.30 tier 风格）。
- `docs/setup/当前开发计划.md` §1：新增 STAGE-14D3b 收口条目（范围 + commits + 测试 + 标注 STAGE-14D3 整体完成 = D3a+D3b + 下一步 STAGE-14E）；D3a 条目「下一步」更新为「D3b 已收口（下条）」。
- `docs/process/BBook一期完成执行路径.md`：§15H STAGE-14D3b 收口。

- [ ] **Step 4: R7 报告**

`docs/test/STAGE-14D3b-CONSOLE-UI-R7-verification-report.md`（沿 C2 报告结构：§0 部署阻断（14B 库漂移 + console V13 干净）/ §1 范围 / §2 角色（R9/R10/R6/R7/R8）/ §3 各 task 证据 / §4 测试统计（console IT + 前端 vitest + 三件套）/ §5 已知不阻断（浏览器 QA WSL 受限 + 真三端 E2E 手动项 + 沿用 D3a stale IT 提示）/ §7 master §8.3 D 验收（FX_PAUSED 8×3 UI + 冷静期/阈值 UI 三端齐全）/ §9 结论 + 使用说明 + 下一步 STAGE-14E）。

- [ ] **Step 5: Commit**

```bash
git add docs/
git commit -m "docs(R7+R8): STAGE-14D3b console 三端 UI 收口报告 + 管理端接口规范/统一接口文档同步 + 计划录入（STAGE-14D3 整体完成，下一步 STAGE-14E）

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

- [ ] **Step 6: push（D3 全部 task 完成，按用户约定形成 GitHub 回滚点）**

Run: `git push origin main`
（D3a 11 commits + D3b commits 一并推送；push 前确认 `git status` 无混入 untracked。）

---

## 下一步（避免计划真空，§3.6.4）：STAGE-14E

STAGE-14D（D1+D2+D3a+D3b）整体完成后进入 **STAGE-14E 三端 UI**（master §9 E 阶段）：客户端 mode toggle + MarginLevel 浮窗 + 双币 PnL + admin 多币种聚合 + WebSocket break 字段最终切换 + admin FX rate 监控页。另：D3a 发现的既有 stale IT `shouldReturnMarginModeNotSupportedWhenOrderRequestsCross`（per-order marginMode=CROSS 语义）建议在 E 或独立 task 修复。
