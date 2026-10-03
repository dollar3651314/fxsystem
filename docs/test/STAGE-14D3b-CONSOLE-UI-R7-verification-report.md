# STAGE-14D3b-CONSOLE-UI R7 验证报告

> 验证日期：2026-06-01
> 验证人：Claude Opus 4.8（在 R1 Commander 调度下作为 R7）
> 任务：`STAGE-14D3b` console 三端 UI——把 D3a 落地的 trading-core 平台风控配置 internal RPC 经 console 透传给运营，落地 3 配置页（保证金模式切换冷静期 / StopOut·MarginCall 阈值 / FX_PAUSED 8 类目行为）的三端 UI（console 透传 + RBAC + 高危审计 + V13 权限/菜单 + 前端），照搬 STAGE-14C2 console tier UI 模式（master §9 D 阶段第三切片 D3b；D3a 后端见 [D3a R7 报告](STAGE-14D3a-CONFIG-BACKEND-R7-verification-report.md)；三端展示 UI 留 E）

---

## §0. 部署前置阻断项（最高优先级，必读）

> **🔴 本阶段 D3b（3 配置页 console 三端 UI）在 console-service 后端 + console-frontend 前端代码侧已实现并通过 IT/vitest 验证，但存在一个生产/演示库部署阻断项（沿 STAGE-14B/C1/C2/D1/D2 叠加），部署前必须先处理，不得让生产/演示库裸跑 `flyway migrate`。**

**阻断项：本地开发库 `falconx_trading`（及远程演示库）存在 STAGE-14B 遗留 schema 漂移（V28/V29），后续 C1 V30-V32 / D2 V35-V36 / D3a V37 叠加其上；D3b 不向 trading 库新增 migration。**

- 根因（沿 14B）：14B 的 V28（`t_ledger` 三列）与 V29（`t_position.entry_fx_rate`）migration 在 root bug 期间误写 `USE falconx_trading;`（已由 `1031a9ce` 修复），导致这两列在 `falconx_trading` 库**已物理存在且已回填**，但 `flyway_schema_history` **没有 V28/V29 行**。
- D3b 新增的 migration：**仅 console `V13__seed_platform_config_permissions_and_menu.sql`（干净，无 `USE`，作用于 `falconx_console` 库，Flyway 实测 `Migrating to v13` 通过）**；**trading 无新 migration**（沿 D3a V37）；**market 无新 migration**。
- 风险：被 14B `USE` 污染过的既有 trading 库下次 `flyway migrate` 仍会因 V28/V29 列已存在而先撞 `Duplicate column` 失败，trading-core 启动/部署受阻；该失败连带 V30-V37 无法应用。**D3b 本身不引入新的 trading 库阻断，但不解除 14B/C1/D2/D3a 的既有阻断。**

**修复指引（部署前由 DBA / 部署执行，沿 D3a §0）：**

1. **先修 14B V28/V29 漂移**（二选一）：手动向 `flyway_schema_history` 插入 V28/V29 成功行（`success=1`，checksum 用删 USE 后 SQL 计算）；或 `flyway repair` + 人工核对目标库 `t_ledger` 三列 + `t_position.entry_fx_rate` 确已存在且回填正确。
2. **再正常 migrate**：trading 库 V30-V37 + console 库 V13 为干净顺序 migration，正常应用即可。

执行须按授权进行。**禁止在未核对前对生产/演示库直接 `flyway migrate`。**

> 全新部署的干净库无此问题（trading 库从 V27 顺序到 V37、console 库到 V13 均干净）。本阻断项仅影响 root bug 期间被 14B `USE` 污染过的既有 trading 库。
>
> **D3b 未新增任何「计划未预见的 schema 阻断」**；console V13 是 RBAC 权限/菜单 seed（幂等 `WHERE NOT EXISTS`），无业务表结构变更、无 NOT NULL 回填风险。

---

## §1. 范围

本阶段覆盖 [STAGE-14 多币种 + CROSS/ISOLATED 保证金总设计稿](../design/STAGE-14-MULTICURRENCY-AND-CROSS-MARGIN-MASTER-design.md) §9 D 阶段的**第三切片 D3b**（console 三端 UI），把 D3a 落地的 trading-core 配置 internal RPC（§3.32）经 console 透传给运营，补齐 master §8.3 D 阶段「冷静期 admin 可配 UI」「StopOut/MarginCall admin 可配 UI」「FX_PAUSED 8 类目×3 行为 admin 可配 UI」的三端 UI 部分：

| 子能力 | D3b 交付 | 关键 commit |
|---|---|---|
| 实施计划 | 3 配置页透传 + RBAC + 审计 + 错误码 90950/90951/90952 + V13 seed + 前端，8 task | `a4438be9` |
| console 错误码 + 翻译 + 高危注册 | 90950 ADMIN_MARGIN_MODE_CONFIG_INVALID / 90951 ADMIN_FX_PAUSE_BEHAVIOR_INVALID / 90952 ADMIN_RISK_THRESHOLD_INVALID + 400 翻译 + 3 个 edit 注册 `HighRiskPermissionRegistry` | `8a65be4b` |
| 冷静期/阈值 console 透传 | GET/PUT `/admin/trading/margin-mode-config\|risk-thresholds` → trading config/platform-risk + cooling-period/risk-thresholds + RBAC + 审计 + 99004→90950/90952 | `3b599059` / `ef3616eb` |
| FX_PAUSED 行为 console 透传 | GET `/admin/trading/fx-pause-behavior` + PUT `/{category}` → trading fx-pause-behavior + RBAC + 审计 + 99004→90951；3 写请求 `@NotNull`/`@Valid` 拒 null 返 400 | `1a4aa3c1` / `5faf89e1` |
| console V13 权限/菜单 seed | 6 权限点（margin-mode-config / risk-threshold / fx:pause-behavior view+edit）+ 角色关联 + 3 菜单（Flyway 实测 v13 通过） | `09c644de` |
| 前端冷静期配置页 | 单 Form 60s-7d + RBAC + 高危 reason/acknowledge | `529f4df7` |
| 前端 StopOut/MarginCall 阈值配置页 | 0.05-0.95 / 0.50-2.00 + RBAC + 高危确认 | `37c77ea4` |
| 前端 FX_PAUSED 8 类目行为配置页 | 8 行 Table + 3 开关 Switch + RBAC + 高危 reason/acknowledge | `c6d790b1` |
| R8 文档同步 + R7 报告 + 计划录入 | 本 commit | 本 commit |

**不在 D3b 范围**（划归 E / 另立修复）：

- **E**：三端展示 UI（客户端 mode toggle + MarginLevel 浮窗 + 双币 PnL）+ admin 多币种聚合 + WebSocket break 字段最终切换 + admin FX rate 监控 page。
- D1/D2 遗留 stale IT `shouldReturnMarginModeNotSupportedWhenOrderRequestsCross` 应另立修复（见 §5）。

---

## §2. 角色

D3b 为**两端**（console-service 后端 + console-frontend 管理端前端），透传 D3a 已落地的 trading-core internal RPC，按 [`AI工作模式 §2`](../process/AI工作模式.md) 角色路由：

- **R9 管理端后端**：console 错误码 90950/90951/90952 + 翻译 + 高危注册 / 冷静期·阈值透传 / FX_PAUSED 行为透传（含 `@Valid` 防 NPE）/ console V13 权限/菜单 seed。
- **R10 管理端前端**：3 配置页（冷静期 / 阈值 / FX_PAUSED 8 类目行为）。
- **R6 Test**：console 透传 IT（`AdminPlatformConfigEndpointIntegrationTests` 10 + `AdminFxPauseBehaviorEndpointIntegrationTests` 7）+ 前端 vitest 三页。
- **R7 QA**：本报告。
- **R8 Doc**：R8 文档同步（§8）。

> **三端硬约束**：本阶段为管理端配置能力（运营经 console 改平台风控配置），客户端无新可见行为（配置影响通过既有开仓/切换/强平路径间接体现，由 D1/D2/D3a 后端承载）；管理端后端 + 管理端前端均已实现并验证，透传目标 trading-core internal RPC 由 D3a 已验证，满足 [`AI工作模式 §5.1`](../process/AI工作模式.md) 管理端配置类三端交付口径（与 C2 同款）。各 task 均经实施 → spec 合规评审 → 代码质量评审 → 收口完整门禁（含 `5faf89e1` 评审收口补 `@NotNull`/`@Valid`）。

---

## §3. 各 Task 证据

| Task | 内容 | 关键 commit | 证据 |
|---|---|---|---|
| 1 | console 错误码 90950/90951/90952 + 400 翻译 + 3 个 edit 高危注册 | `8a65be4b` | `AdminErrorCode`（90950-90952）+ `HighRiskPermissionRegistry`（margin-mode-config:edit / risk-threshold:edit / fx:pause-behavior:edit） |
| 2 | 冷静期/阈值 console 透传（GET/PUT `/admin/trading/margin-mode-config\|risk-thresholds`）+ RBAC + 审计 + 99004→90950/90952 | `3b599059` / `ef3616eb` | `AdminPlatformConfigController` + `AdminPlatformConfigApplicationService`（99004→90950 冷静期 / 90952 阈值）；`ef3616eb` 去死 catch + 补 99004→90950/阈值无 reason 对称 IT |
| 3 | FX_PAUSED 行为 console 透传（GET `/admin/trading/fx-pause-behavior` + PUT `/{category}`）+ RBAC + 审计 + 99004→90951 | `1a4aa3c1` / `5faf89e1` | `AdminFxPauseBehaviorController` + `AdminFxPauseBehaviorApplicationService`（99004→90951）；`5faf89e1` 3 写请求 `@NotNull`+`@Valid` 拒 null 返 400（防 `Map.of` NPE 500）+ 对称 IT |
| 4 | console V13 seed 6 权限点 + 角色关联 + 3 菜单 | `09c644de` | `V13__seed_platform_config_permissions_and_menu.sql`（6 权限点 view/edit ×3 + 关联 risk-config 角色 + 3 菜单；Flyway 实测 `Migrating to v13` 通过） |
| 5 | 前端冷静期配置页（单 Form 60s-7d + RBAC + 高危 reason/acknowledge） | `529f4df7` | `MarginModeConfigPage.test.tsx` vitest 7 |
| 6 | 前端 StopOut/MarginCall 阈值配置页（0.05-0.95 / 0.50-2.00 + RBAC + 高危确认） | `37c77ea4` | `RiskThresholdConfigPage.test.tsx` vitest 8 |
| 7 | 前端 FX_PAUSED 8 类目行为配置页（8 行 Table + 3 开关 Switch + RBAC + 高危 reason/acknowledge） | `c6d790b1` | `FxPauseBehaviorConfigPage.test.tsx` vitest 8 |
| 8 | 全量验证 + R8 文档同步 + R7 收口报告 + 当前开发计划录入（本 commit） | 本 commit | 本报告 + R8 同步清单（§8）+ 计划 §1 条目 |

---

## §4. 测试统计

D3b 验证结果（2026-06-01，据实记录）：

| 测试 | 类型 | 数量 | 覆盖 |
|---|---:|---:|---|
| `AdminPlatformConfigEndpointIntegrationTests` | console IT | 10 全绿 | 冷静期/阈值 GET/PUT 透传 + RBAC（margin-mode-config:view/edit / risk-threshold:view/edit）+ 审计 + 99004→90950/90952 翻译 + reason 必填 |
| `AdminFxPauseBehaviorEndpointIntegrationTests` | console IT | 7 全绿 | FX_PAUSED GET / PUT `/{category}` 透传 + RBAC（fx:pause-behavior:view/edit）+ 审计 + 99004→90951 + `@NotNull` 拒 null 字段 400 |
| **console IT 合计** | IT | **17 全绿** | `mvn -pl falconx-console-service test -Dtest=...` → Tests run: 17, Failures: 0, Errors: 0, Skipped: 0, **BUILD SUCCESS** |
| console-frontend vitest（全量） | 前端 | **100（97 通过 + 3 既有 skip），16 文件全过** | D3b 三新页 23：冷静期 `MarginModeConfigPage` 7 / 阈值 `RiskThresholdConfigPage` 8 / FX_PAUSED `FxPauseBehaviorConfigPage` 8；既有页（tier 10 等）不回归 |
| 三件套 | 前端 | — | `npm run build` 退出 0；`npm run lint` 改动文件 0（见下） |

### 三件套 lint baseline 说明（据实，不计本阶段）

- `npm run lint` 输出 **18 errors + 1 warning**，全部落在**既有 baseline 文件**：`audit/AuditLogListPage.tsx`、`dashboard/DashboardPage.tsx`、`kyc/KycReviewListPage.tsx`、`notification/NotificationListPage.tsx`、`notification/NotificationTemplateListPage.tsx`、`reconciliation/ReconciliationListPage.tsx`、`symbol-group-markup/GroupMarkupListPage.tsx`、`system-config/SystemConfigPage.tsx`、`withdraw/WithdrawDetailPage.tsx`、`withdraw/WithdrawListPage.tsx`（均为既有 `react-hooks/set-state-in-effect` 规则）。
- **D3b 三新页（`platform-config/MarginModeConfigPage.tsx`、`platform-config/RiskThresholdConfigPage.tsx`、`fx-pause/FxPauseBehaviorConfigPage.tsx`）lint 0 error**——pre-existing baseline 不计本阶段（沿 C2「lint 0 改动文件」口径）。

### admin 改平台配置生效证据链（master §8.3 D 阶段可配 UI 部分）

| 环节 | 证据 |
|---|---|
| 冷静期写后即时生效 | D3a：trading 直读 `t_risk_config` 平台行（无缓存）；D3b console PUT 透传 trading config/cooling-period |
| 阈值写后 ≤30s 生效 | D3a：`DefaultMarginLevelMonitor` 30s TTL（同 C2 tier）；D3b console PUT 透传 trading config/risk-thresholds |
| FX_PAUSED 行为写后即时生效 | D3a：写后失效快照即时生效；D3b console PUT `/{category}` 透传 trading fx-pause-behavior |
| console→trading 透传 + RBAC + 审计 + 99004→90xxx | Task2/3 console 透传 IT 17（含越界翻译 / RBAC / reason 必填 / `@Valid` 拒 null 400） |
| 前端页面行为（渲染/RBAC 按钮/二次确认/校验/请求） | Task5/6/7 vitest 23（jsdom） |
| 真三端跨服务 HTTP E2E | console→gateway→trading **= WSL 受限手动项**，靠 console 透传 IT + D3a trading 配置 RPC IT 拼接（见 §5） |

---

## §5. 已知不阻断项 / 边界

> 部署阻断项（生产/演示库 schema 漂移，沿 14B/C1/C2/D1/D2/D3a 叠加）见 **§0**，为最高优先级，单独提级，不在本节"不阻断"列表内。

1. **【部署阻断项，提级到 §0】** 14B V28/V29 `USE` 污染遗留 + C1/D2/D3a 叠加；D3b 新增仅 console V13（干净）、trading/market 无新 migration。被污染 trading 库部署前仍须先 repair V28/V29 再 migrate（trading V30-V37 + console V13）（详见 §0）。
2. **浏览器 QA WSL chromium 受限**：本地 console-frontend dev server 起在 `http://localhost:5300/`，但 3 配置页在登录态 + RBAC + 活跃 console-service/trading-core/gateway 全栈之后，WSL 本会话未起后端全栈。Playwright 导航 `/admin/trading/margin-mode-config` **重定向到 `/admin/login`**（无后端 session），证实页面服务正常但登录态截图为受限手动项。**不伪造登录态截图**；页面行为由 vitest（渲染 + RBAC + 二次确认 + 校验 + 请求断言）+ build 覆盖。沿 C2/前阶段口径，真机/CI 截图待有可用环境补。
3. **真三端跨服务 HTTP E2E（console→gateway→trading）= WSL 受限手动项**：D3b 为透传切片，未跑真网关链路；证据靠 console 透传 IT（含 99004→90xxx 翻译 / RBAC / reason / `@Valid` 拒 null）+ D3a trading 配置 internal RPC IT 9（含 fx-pause）拼接。
4. **沿用 D3a #2 stale IT（`shouldReturnMarginModeNotSupportedWhenOrderRequestsCross`）**：D1/D2 遗留 trading IT，基线 `aefb9f9c` 已失败，非 D3b 引入（D3b 不改 trading 代码）；建议另立修复明确 per-order `marginMode=CROSS` 预期返回码/行为（见 D3a R7 §4 #2）。
5. **`Map.of` 透传 NPE 风险已修**：`5faf89e1` 给 3 个写请求加 `@NotNull` + `@Valid`，拒 null 字段返 400（避免 null 进 `Map.of` 抛 NPE 500），并补对称 IT。
6. **DELETE/写请求 reason 口径同 C2**：前端 reason 用于二次确认/审计，写操作经 `OperationAuditAspect` 落 `t_admin_operation_log`。
7. **`allow_close` 业务上恒 1**（master §6.5 手动平仓不限制）：FX_PAUSED 页前端可展示，但写时不暴露翻转语义改变（沿 D3a 口径）。
8. **后续阶段**（独立 plan）：STAGE-14E 三端展示 UI（客户端 mode toggle + MarginLevel 浮窗 + 双币 PnL）+ admin 多币聚合 + WS break 切换 + admin FX rate 监控 page。

---

## §6. console 透传 / RBAC / 错误翻译证据

| 验证项 | 证据 |
|---|---|
| 6 console 端点路径 | `AdminPlatformConfigController`（GET/PUT `/admin/trading/margin-mode-config`、GET/PUT `/admin/trading/risk-thresholds`）+ `AdminFxPauseBehaviorController`（GET `/admin/trading/fx-pause-behavior`、PUT `/{category}`） |
| RBAC 权限码 | `@RequiresPermission` margin-mode-config:view/edit / risk-threshold:view/edit / fx:pause-behavior:view/edit（3 个 edit 描述含「高危」） |
| 高危注册 | `HighRiskPermissionRegistry` 注册 3 个 edit 码（Task1）；写操作 `OperationAuditAspect` 落审计 |
| 错误翻译 99004→90xxx | `AdminPlatformConfigApplicationService`（99004→90950 冷静期 / 90952 阈值）+ `AdminFxPauseBehaviorApplicationService`（99004→90951）；console IT 验证越界翻译 |
| `@Valid` 拒 null 返 400 | 3 写请求 `@Valid @RequestBody` + 命令 record `@NotNull`；`AdminFxPauseBehaviorEndpointIntegrationTests` 验证缺字段 400 |
| V13 seed | 6 权限点 + 角色关联（risk-config:view/update）+ 3 菜单（冷静期配置 / StopOut 阈值配置 / FX 暂停行为配置），作用于 `falconx_console` 库，Flyway 实测 `Migrating to v13` 通过 |
| 透传目标 owner | FX_PAUSED 透传到 **trading-core**（非 master §7.4 写的 market），因 `t_fx_pause_behavior` 物理在 `falconx_trading` 库（沿 D3a owner 修正） |

---

## §7. master §8.3 D 阶段验收硬约束对照（D3b UI 部分逐条标注）

| 硬约束（UI 部分） | 状态 | 验证证据 |
|---|---|---|
| 冷静期 admin 运行时可配 **UI** 三端齐全 | ✅ **D3b** | Task5 前端冷静期配置页（单 Form 60s-7d + RBAC + 二次确认）+ Task2 console 透传（→ D3a trading cooling-period） |
| StopOut/MarginCall admin 可配 **UI** 三端齐全 | ✅ **D3b** | Task6 前端阈值配置页（0.05-0.95 / 0.50-2.00 + RBAC + 确认）+ Task2 console 透传（→ D3a trading risk-thresholds） |
| FX_PAUSED 8 类目 × 3 开关 **UI** 三端齐全 | ✅ **D3b** | Task7 前端 8 行 Table + 3 开关 Switch + RBAC + 确认 + Task3 console 透传（→ D3a trading fx-pause-behavior 全量 8 / PUT `/{category}`） |
| 客户端可见行为 | ✅ 无新增（设计如此） | D3b 为管理端配置类，客户端无新可见行为（配置由 D1/D2/D3a 后端在开仓/切换/强平路径间接生效，口径同 C2） |

> 补充：master §8.3 D 阶段的算法/后端验收（冷静期落库 / 阈值落库 / FX_PAUSED 8×3 行为 / supplement 受闸门 / CROSS 强平 / 实时 MM）已由 D1/D2/D3a 覆盖（见各阶段 R7）；D3b 补齐的是这些配置项的 **admin 可配 UI 三端口径**（与 C2 管理端配置类三端交付口径一致）。

#### 通用硬约束

| 通用硬约束 | 状态 | 备注 |
|---|---|---|
| mvn compile + test-compile BUILD SUCCESS | ✅ | console 各 task 实施门禁覆盖；本轮 console IT BUILD SUCCESS |
| 涉及服务 mvn test 全过 | ✅ | console 透传 IT 17 全绿（10+7） |
| 前端 npm 三件套 | ✅ | vitest 100（三新页 23）/ lint 0 改动文件（18 既有 baseline）/ build 退出 0 |
| 文档同步完成 | ✅ | R8 同步（本 commit，见 §8） |
| Git 回滚点 push 到 main | ⏳ | 本地 main 领先 origin/main（按约定由控制者统一执行） |
| 当前开发计划 §1 阶段收口条目录入 | ✅ | 本 commit（STAGE-14D3 整体完成，下一步 STAGE-14E） |

---

## §8. 文档同步清单（R8）

| 文档 | 状态 | 内容 |
|---|---|---|
| `docs/api/管理端接口规范.md` | ✅ | §19 STAGE-14D3b 平台配置三端 UI（console 6 端点 + trading internal RPC + 错误码 90950-90952 + RBAC/菜单/审计 V13 + 测试结论）；§16 占位表登记「✅ §19 已冻结」；§4.3a 把 D3a 登记的占位 console 端点落实为真实路径（`/admin/trading/margin-mode-config\|risk-thresholds` + `/admin/trading/fx-pause-behavior[/{category}]`）+ 90952 |
| `docs/api/FalconX统一接口文档.md` | ✅ | §3.33 console-service 平台风控配置 admin REST（6 端点 + RBAC + 99004→90950-90952 + V13 + 测试结论，沿 §3.30 tier console 风格）；§3.32 末尾错误码补 90952 + 指向 §3.33 |
| `docs/setup/当前开发计划.md` §1 | ✅ | STAGE-14D3b 收口条目（范围 8 task / 测试 console IT 17 + 前端 vitest + 三件套 / 部署阻断沿 14B + console V13 干净 / 已知不阻断 / 关联文档）+ **STAGE-14D3 整体完成（D3a 后端 + D3b console UI）** 标注 + 下一步 STAGE-14E；D3a 条目末「下一步 D3b」更新为「D3b 已收口（下条）」 |
| `docs/process/BBook一期完成执行路径.md` | ✅ | §15H STAGE-14D3b 收口条目（范围 + commits + console 端点 + 测试 + 部署阻断 + 边界 + STAGE-14D3 整体完成 + 下一步 E） |

> 数据库设计：D3b 仅 console V13 权限/菜单 seed（作用于 `falconx_console`）；`docs/database/falconx一期数据库设计.md` 为业务库（identity/market/trading/wallet）设计，不含 console RBAC 表，故 D3b 无该文档增补点（与 C2 同口径）。trading 配置表（`t_risk_config` / `t_fx_pause_behavior`）已由 C1/D3a 在该文档 §4.3 登记，D3b 不改 trading schema。

---

## §9. 结论

按 [AGENTS.md §8.1.2](../../AGENTS.md) 生产可用判定：

**D3b（3 配置页 console 三端 UI：保证金模式切换冷静期 / StopOut·MarginCall 阈值 / FX_PAUSED 8 类目行为）在 console-service 后端 + console-frontend 管理端前端代码层已完整实现，并通过 console 透传 IT 17 全绿（`AdminPlatformConfigEndpointIntegrationTests` 10 + `AdminFxPauseBehaviorEndpointIntegrationTests` 7，含 RBAC / 99004→90950/90951/90952 翻译 / reason 必填 / `@Valid` 拒 null 400，BUILD SUCCESS）+ console-frontend vitest 100（三新页 23）+ 三件套（build 退出 0 / lint 0 改动文件）验证。补齐了 master §8.3 D 阶段「冷静期 admin 可配 UI」「StopOut/MarginCall admin 可配 UI」「FX_PAUSED 8 类目×3 行为 admin 可配 UI」三条配置项的三端 UI 部分（透传 D3a 已验证的 trading-core internal RPC）。console 错误码 90950/90951/90952 + 翻译 + 3 个 edit 高危注册 + V13 权限/菜单 seed（Flyway 实测 v13 通过）齐备；`Map.of` 透传 NPE 风险已由 `5faf89e1` `@NotNull`/`@Valid` 修复。三端代码层完整。STAGE-14D3 整体完成（D3a 后端 + D3b console UI）。**

**🔴 但当前不满足无条件"生产可用"：**

- **剩余阻断项（部署前必须处理，沿 14B/C1/C2/D1/D2/D3a 叠加）**：生产/演示库 `falconx_trading` 因 14B root bug 期间 `USE` 污染存在 V28/V29 schema 漂移，下次 `flyway migrate` 将先撞 `Duplicate column` 阻断启动；D3b 只新增干净的 console V13、trading/market 无新 migration，不引入新阻断但也不解除既有阻断。**部署前必须先按 §0 修复 14B V28/V29 漂移再 migrate（trading V30-V37 + console V13），禁止裸跑 migrate。** D3b 未引入任何「计划未预见的 schema 阻断」（console V13 为幂等 RBAC seed）。
- **已验证范围边界**：3 配置页 console 透传 + RBAC + 高危审计 + 错误翻译 + V13 seed 在代码侧完整且通过 IT/vitest；干净库全新部署无 schema 阻断；真三端跨服务 HTTP E2E（console→gateway→trading）为 WSL 受限手动项，由 console 透传 IT + D3a trading 配置 RPC IT 拼接代证；浏览器登录态截图同为 WSL 受限手动项（dev server 起在 :5300，未登录态重定向 `/admin/login`，未伪造）。
- **范围边界（非阻断，按 §5）**：三端展示 UI（客户端 mode toggle + MarginLevel 浮窗 + 双币 PnL）+ 多币聚合 + WS break 切换 + admin FX rate 监控 page 留 E；沿用 D3a #2 stale IT（`shouldReturnMarginModeNotSupportedWhenOrderRequestsCross`）应另立修复；阈值 ≤30s 生效为设计（非缺陷，沿 D3a/C2）。
- **不满足生产可用的其他原因**：本系统整体仍处 BBook 一期建设中，按 [当前开发计划 §1](../setup/当前开发计划.md) 末条，当前系统不得表述为"生产可用"或"可安全对外公测"。

**使用说明（按 §8.1.3）：**

- **使用入口**：运营经 console-frontend「交易监控」下三个配置页——「冷静期配置」（`margin-mode-config:view` 可见、`:edit` 可写）/「StopOut 阈值配置」（`risk-threshold:view`/`:edit`）/「FX 暂停行为配置」（`fx:pause-behavior:view`/`:edit`）。写操作走 console PUT（高危 + 二次确认/reason）→ console-service 透传 trading-core internal RPC（`/internal/v1/trading/console/config/*`，D3a）→ 写 `t_risk_config` 平台行 / `t_fx_pause_behavior`。
- **前置条件**：目标库已按 §0 完成 14B V28/V29 漂移修复 + trading V30-V37 + console V13 migrate；trading-core / console-service / console-frontend 已部署；gateway internal token 配置就绪；运营账号持有对应权限（V13 关联到 risk-config:view/update 角色）。
- **执行步骤**：按 §0 修复目标库 → migrate（trading V30-V37 + console V13）→ 启动 trading-core / console-service / console-frontend → 运营进配置页：(1) 冷静期页改 `coolingPeriodSeconds`（60-604800）→ 即时生效；(2) 阈值页改 `stopOutLevel`（0.05-0.95）/`marginCallLevel`（0.50-2.00）→ ≤30s 生效；(3) FX_PAUSED 页按类目（1-8）改 `allowOpen`/`allowClose`/`allowLiquidation` → 即时生效；均需 reason + 二次确认。
- **预期结果**：越界/缺字段 console 侧返 400 或翻译 90950（冷静期）/90951（FX_PAUSED）/90952（阈值）；写操作落 `t_admin_operation_log` 审计；无对应 view/edit 权限不可见/不可写；运行时生效语义同 D3a（冷静期/FX_PAUSED 即时、阈值 ≤30s）。
- **已知限制 / 禁用场景**：真三端跨服务 HTTP E2E + 浏览器登录态截图为 WSL 受限手动项（代码侧已 IT/vitest 证据）；FX_PAUSED `allow_close` 业务恒 1（手动平仓不限制）；沿用 D3a #2 stale IT 待另立修复；三端展示 UI / 多币聚合 / WS break 切换留 E；禁止对未按 §0 修复的污染库直接 migrate。

**下一步**：

- **STAGE-14E**：三端展示 UI（客户端 mode toggle + MarginLevel 浮窗 + 双币 PnL）+ admin 多币种聚合 + WebSocket break 字段最终切换 + admin FX rate 监控 page。
