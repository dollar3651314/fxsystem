# STAGE-14D3a-CONFIG-BACKEND R7 验证报告

> 验证日期：2026-06-01
> 验证人：Claude Opus 4.8（在 R1 Commander 调度下作为 R7）
> 任务：`STAGE-14D3a` 运营可配后端——冷静期 / StopOut·MarginCall 阈值从硬编码/只读改为 admin 运行时可配（落 `t_risk_config` 平台行 + 内部 RPC 写）+ supplement-margin 接 FX_PAUSED 闸门（30087）+ `t_fx_pause_behavior` 加 admin 写路径 + FX_PAUSED 8 类目×3 开关组合完整验收（纯 trading-core 后端，master §9 D 阶段第三切片 D3a；console 透传 + 三端 UI 留 D3b；三端展示 UI 留 E）

---

## §0. 部署前置阻断项（最高优先级，必读）

> **🔴 本阶段 D3a（运营可配后端 + supplement pause gating + FX_PAUSED 8×3 验收）在 trading-core 后端代码侧已实现并通过 UT/IT 验证，但存在一个生产/演示库部署阻断项（沿 STAGE-14B/C1/C2/D1/D2 叠加），部署前必须先处理，不得让生产/演示库裸跑 `flyway migrate`。**

**阻断项：本地开发库 `falconx_trading`（及远程演示库）存在 STAGE-14B 遗留 schema 漂移（V28/V29），D3a 新增 V37 叠加其上。**

- 根因（沿 14B）：14B 的 V28（`t_ledger` 三列）与 V29（`t_position.entry_fx_rate`）migration 在 root bug 期间误写 `USE falconx_trading;`（已由 `1031a9ce` 修复），导致这两列在 `falconx_trading` 库**已物理存在且已回填**，但 `flyway_schema_history` **没有 V28/V29 行**。
- D3a 新增的 migration：**仅 trading `V37__risk_config_cooling_period.sql`（`t_risk_config` 平台行 `ADD COLUMN cooling_period_seconds INT NOT NULL DEFAULT 300`），干净、无 `USE`，与 V1-V36 一致**；market / console 无新 migration。
- 风险：被 14B `USE` 污染过的既有 trading 库下次 `flyway migrate` 仍会因 V28/V29 列已存在而先撞 `Duplicate column` 失败，trading-core 启动/部署受阻；该失败连带 V30-V37 无法应用。**D3a 本身不引入新的 trading 库阻断，但不解除 14B/C1/D2 的既有阻断。**

**修复指引（部署前由 DBA / 部署执行，沿 C1/C2/D1/D2 §0）：**

1. **先修 14B V28/V29 漂移**（二选一）：手动向 `flyway_schema_history` 插入 V28/V29 成功行（`success=1`，checksum 用删 USE 后 SQL 计算）；或 `flyway repair` + 人工核对目标库 `t_ledger` 三列 + `t_position.entry_fx_rate` 确已存在且回填正确。
2. **再正常 migrate**：trading 库 V30-V37 为干净顺序 migration，正常应用即可（V37 `ADD COLUMN cooling_period_seconds` 给 `t_risk_config` 平台行/全表加列，NOT NULL DEFAULT 300，对既有行无破坏）。

执行须按授权进行。**禁止在未核对前对生产/演示库直接 `flyway migrate`。**

> 全新部署的干净库无此问题（trading 库从 V27 顺序到 V37 均干净）。本阻断项仅影响 root bug 期间被 14B `USE` 污染过的既有 trading 库。
>
> **D3a 未新增任何「计划未预见的 schema 阻断」**（不同于 D2 的 V35）；V37 是平台行加列的常规增量，无 NOT NULL 回填风险（带 DEFAULT 300）。

---

## §1. 范围

本阶段覆盖 [STAGE-14 多币种 + CROSS/ISOLATED 保证金总设计稿](../design/STAGE-14-MULTICURRENCY-AND-CROSS-MARGIN-MASTER-design.md) §9 D 阶段的**第三切片 D3a**（纯 trading-core 后端：运营可配后端 + supplement pause gating + FX_PAUSED 8×3 验收），落地 master §6.5（FX_PAUSED 类目行为）+ §7.4/§7.6（supplement 错误集 + 可配阈值范围）+ §8.3 D 阶段验收硬约束的可配/验收部分：

| 子能力 | D3a 交付 | 关键 commit |
|---|---|---|
| 实施计划 | `docs/process/STAGE-14D3a-CONFIG-BACKEND-implementation-plan.md` | `26f3f4eb` |
| 冷静期落库可配（V37） | `t_risk_config` 平台行加 `cooling_period_seconds`（admin 可配 60-604800 默认 300）+ docs/sql 镜像 | `a78bc2ac` |
| 冷静期读路径改 DB | `MarginModeSwitchApplicationService` 冷静期改从 `t_risk_config` 平台行读（缺失回退 properties 默认 300s）+ UT | `1dbf7045` / `bb60b0c3` |
| 冷静期 + 阈值写方法 | `t_risk_config` 平台行冷静期 / StopOut·MarginCall 阈值写方法（Mapper + Repository）+ 隔离库写回读 IT | `0b3e1d9e` |
| 配置 internal RPC | `/internal/v1/trading/console/config`（GET `/platform-risk`、PUT `/cooling-period`、PUT `/risk-thresholds`，Bean Validation 60-604800 / 0.05-0.95 / 0.50-2.00 → 99004）+ UT/IT | `0c3adb2c` |
| supplement pause gating | `addIsolatedMargin` 接 FX_PAUSED 闸门（`allow_open`，category/behavior 缺失保守全拒 30087）+ UT/IT | `7633a361` |
| FxPauseBehavior 写 + 缓存失效 | `updateByCategory` + 写后失效快照即时生效 + `findAll` + 隔离库 IT | `73c9be9b` |
| FxPauseBehavior internal RPC | GET `/fx-pause-behavior` 全量 8 行 / PUT `/fx-pause-behavior/{category}` 1-8 + adminUserId 落审计列 + IT | `7249ecfd` |
| FX_PAUSED 8×3 验收 IT | 8 类目×3 开关组合（开仓/supplement/被动强平/手动平仓 + admin override + 降级）验收 IT | `72483c2c` |
| Task3 IT 收口 fix | 配置写 IT `@AfterEach` 还原 `t_risk_config` 平台行 V31 默认（修 it013 共享库阈值污染回归） | `7780670c` |
| R8 文档 + R7 报告 + 计划录入 | 本 commit | 本 commit |

**不在 D3a 范围**（划 D3b / E）：

- **D3b**：console 三端 UI（3 配置页：冷静期 / StopOut·MarginCall 阈值 / FX_PAUSED 8 类目行为）console 透传 + RBAC（`margin-mode-config:edit` / `risk-threshold:edit` / `fx:pause-behavior:edit` 高危）+ 审计 + 前端 + console 侧错误码（90950 / 90951 + 阈值非法码）+ console migration V13 权限/菜单 seed。**`fx-pause-behavior` 的 console 透传目标修正为 trading-core**（见 §5 第 4 条 owner 边界）。
- **E**：三端展示 UI（客户端 mode toggle + MarginLevel 浮窗 + 双币 PnL）+ admin 多币种聚合 + WebSocket break 字段最终切换。

---

## §2. 角色

D3a 为**纯 trading-core 后端**切片（无 console / 前端），按 [`AI工作模式 §2`](../process/AI工作模式.md) 角色路由：

- **R4 业务后端**：V37 + 冷静期读路径改 DB + 冷静期/阈值写方法 + 配置 internal RPC + supplement FX_PAUSED gating + FxPauseBehavior 写 + 缓存失效 + FxPauseBehavior internal RPC。
- **R6 Test**：MarginModeSwitch UT、配置写 repo IT、TradingPlatformConfig UT、配置 RPC IT（含 fx-pause）、supplement gating UT/IT、FxPauseBehavior 写 IT、8×3 验收 IT。
- **R7 QA**：本报告。
- **R8 Doc**：R8 文档同步（§8）。

> 后端切片交付口径：配置可配 / supplement gating / 8×3 验收后端 + 算法已实现并验证，console 透传 + 三端 UI 留 D3b/E。各 task 均经实施 → spec 合规评审 → 代码质量评审 → 收口完整门禁。

---

## §3. 各 Task 证据

| Task | 内容 | 关键 commit | 证据 |
|---|---|---|---|
| 1 | V37 `t_risk_config` 平台行加 `cooling_period_seconds`（60-604800 默认 300）+ docs/sql 镜像 | `a78bc2ac` | V37 migration + 隔离库 migrate（Task3/4 IT 实证） |
| 2 | 冷静期改从 `t_risk_config` 平台行读（缺失回退 properties 默认 300s） | `1dbf7045` / `bb60b0c3` | `MarginModeSwitchApplicationServiceTests` 14 UT（含 DB 读 / 回退默认） |
| 3 | 冷静期 / StopOut·MarginCall 阈值写方法（Mapper + Repository） | `0b3e1d9e` | 隔离库写回读 IT 2 |
| 4 | 配置 internal RPC（GET `/platform-risk` / PUT `/cooling-period` / PUT `/risk-thresholds`，Bean Validation → 99004） | `0c3adb2c` | `TradingPlatformConfigApplicationServiceTests` 4 UT + 配置 RPC IT |
| 5 | supplement-margin 接 FX_PAUSED 闸门（`allow_open`，缺失保守全拒 30087） | `7633a361` | `SupplementMarginFxPauseGatingTests` 6 UT/IT |
| 6 | FxPauseBehavior `updateByCategory` + 写后失效快照即时生效 + `findAll` | `73c9be9b` | FxPauseBehavior 写 IT 3 |
| 7 | FxPauseBehavior internal RPC（GET 全量 8 行 / PUT `/{category}` 1-8 + adminUserId 落审计列） | `7249ecfd` | 配置 RPC IT 9（含 fx-pause） |
| 8 | FX_PAUSED 8 类目×3 开关组合完整验收 IT | `72483c2c` | 8×3 验收 IT 22 |
| — | Task3 IT 收口 fix（`@AfterEach` 还原平台行 V31 默认，修 it013 污染回归） | `7780670c` | 配置写IT + 8×3 + TierStopOut 42 tests 同跑全绿 |
| 9 | R8 文档同步 + R7 收口报告 + 当前开发计划录入（本 commit） | 本 commit | 本报告 + R8 同步清单（§8）+ 计划 §1 条目 |

---

## §4. 测试统计

D3a 新增/改动测试全绿（trading-core，2026-06-01，跑前已 `mvn -pl falconx-market-contract -am install -DskipTests`）：

| 测试类 / 套件 | 类型 | 数量 | 覆盖 |
|---|---:|---:|---|
| `MarginModeSwitchApplicationServiceTests` | UT | 14 全绿 | D1 既有 12 + DB 读冷静期 / DB 缺失回退 properties 默认 300s |
| 配置写 repo IT（`t_risk_config` 平台行冷静期 / 阈值写回读） | IT | 2 全绿 | 隔离库真 migrate 到 V37 + 写后回读一致 |
| `TradingPlatformConfigApplicationServiceTests` | UT | 4 全绿 | updateCoolingPeriod / updateRiskThresholds 委托 repo + getPlatformConfig 回退默认 |
| 配置 internal RPC IT（含 fx-pause） | IT | 9 全绿 | PUT/GET cooling-period / risk-thresholds + 越界 → 99004 + GET fx-pause 8 行 + PUT/{category} + 越界/缺字段 → 99004 |
| `SupplementMarginFxPauseGatingTests` | UT/IT | 6 全绿 | pause + forex(allow_open=false) → 30087 / pause + crypto(allow_open=true) → 放行 / category=null 保守全拒 30087 / 无 pause 放行回归 |
| FxPauseBehavior 写 IT | IT | 3 全绿 | updateByCategory 后 findByCategory 即时反映 + 快照失效 + adminUserId 落审计列 |
| FX_PAUSED 8×3 验收 IT | IT | 22 全绿 | 8 类目（crypto/forex/metal/index/energy/stock/etf/other）× 3 开关（allow_open 影响开仓+supplement / allow_close 恒通手动平仓 / allow_liquidation 影响被动强平）+ admin override 翻转可配生效 + category=null 降级 |

### 全模块回归（关键，逐条据实定性）

**全量 `mvn -pl falconx-trading-core-service test`：577 tests，3 failures**。逐条定性：

| 失败用例 | 性质 | 证据 / 结论 |
|---|---|---|
| **`TradingLeverageTierStopOutIntegrationTests.it013_thresholdsReadFromRiskConfigAndChangeTakesEffect`** | **D3a 一度引入的回归，已修复（消除）** | Task3 配置写 IT 未复位共享库 `falconx_trading_it` 平台行 → 污染 it013 开头断言。commit `7780670c` 加 `@AfterEach` 还原 `t_risk_config` 平台行 V31 默认后，三类同跑（配置写IT + 8×3 + TierStopOut）**42 tests 全绿，回归消除**。**此项不计入"残留 3 failures"——已修。** |
| #1 `TradingKafkaWalletDepositIntegrationTests.shouldCreditDepositWhenConfirmedMessageArrivesViaKafka` | 既有 Kafka flake（非 D3a） | consumer group 异步计数竞态，历次报告（D1/D2）均已记录，非本阶段引入，不阻断。 |
| #2 `TradingControllerIntegrationTests.shouldReturnMarginModeNotSupportedWhenOrderRequestsCross` | **既有失败，D1/D2 遗留 stale IT，非 D3a 引入** | 证据：(a) 在 D3a 之前基线 commit `aefb9f9c` 全量跑即失败（529 tests / 2 failures 含此条）；(b) 单独隔离跑（clean Redis）仍失败。性质：D1/D2 引入账户级 margin mode 模型后，该 pre-D1 旧 IT 仍断言「市价单带 `marginMode=CROSS` 应返回 40010 Margin Mode Not Supported」，与现行 CROSS 处理语义不符；D2 当时只跑「非 IT 305+」未跑全量 IT 故未被发现。**应另立修复（明确现行 per-order `marginMode=CROSS` 的预期返回码/行为），不在 D3a 范围。** |
| #3 `TradingKafkaMarketEventIntegrationTests.cleanOwnerTables` `DeadlockLoserDataAccessException` | 瞬态 flake（非确定性，非 D3a） | 清表并发死锁；基线 `aefb9f9c` 全量跑未现、隔离跑未现，非确定性，非 D3a 逻辑缺陷。 |

> **结论：D3a 自身新增测试 100% 通过；D3a 对既有套件零新增确定性失败。** 唯一一度引入的 it013 回归已由 `7780670c` 修复消除；残留 3 failures（#1 Kafka flake / #2 D1/D2 stale IT / #3 瞬态死锁 flake）均非 D3a 引入、且均不阻断本切片可证范围（据实定性，不隐瞒、不伪造）。

---

## §5. 已知不阻断项 / 边界

> 部署阻断项（生产/演示库 schema 漂移，沿 14B/C1/C2/D1/D2 叠加）见 **§0**，为最高优先级，单独提级，不在本节"不阻断"列表内。

1. **【部署阻断项，提级到 §0】** 14B V28/V29 `USE` 污染遗留；D3a 新增 V37 干净无 `USE`，但被污染 trading 库部署前仍须先 repair V28/V29 再 migrate（V30-V37）（详见 §0）。
2. **阈值生效有 ≤30s 延迟（设计如此，非缺陷）**：StopOut/MarginCall 阈值写后由 `DefaultMarginLevelMonitor` 既有 30s TTL 缓存自然生效（≤30s，同 C2 tier 口径，不强制 invalidate）。冷静期为直读 DB（无缓存），admin 改后即时运行时生效。FxPauseBehavior 写后**失效快照即时生效**。
3. **`isolated_margin` 列不加（已评估作废）**：D3a 评估后**不**给 `t_position` 加 `isolated_margin` 列。依据：D1/D2 已豁免——`t_position.margin` + `marginMode` 字段已足够区分 CROSS/ISOLATED 并承载逐仓保证金，D2 CROSS 账户级强平不依赖单仓 `margin` 区分。master §4.2 升级窗口 `isolated_margin` 回填演练相应作废（无需停服回填）。
4. **`fx-pause-behavior` admin 写路径 owner 修正（master §7.4 设计稿偏差）**：master §7.4 把 admin 路由写成 `/admin/market/fx/pause-behavior`（market 侧），但 `t_fx_pause_behavior` 表**物理在 trading-core `falconx_trading` 库**（C1 V31 建表 + seed 8 行），由 trading 读做风控。按实际表 owner 修正：admin 写路径加在 **trading-core** internal RPC（`/internal/v1/trading/console/fx-pause-behavior`），D3b console 透传到 **trading 而非 market**（market 不得写 trading 库，[AGENTS §3.2](../../AGENTS.md) owner 边界）。`allow_close` 恒 1（手动平仓不限制，写时不暴露翻转）。
5. **不新增 trading 错误码**：D3a 配置写校验走 Bean Validation → 既有 `INVALID_REQUEST_PAYLOAD`（99004）；supplement 复用既有 `GLOBAL_PAUSE_ACTIVE`（30087）。console 侧 90950/90951 + 阈值非法码留 D3b。
6. **console 三端 UI 未做**：3 配置页（冷静期 / 阈值 / FX_PAUSED 行为）console 透传 + RBAC + 审计 + 前端 + console 错误码 + console V13 权限/菜单 seed 全留 D3b。
7. **#2 stale IT（`shouldReturnMarginModeNotSupportedWhenOrderRequestsCross`）**：D1/D2 遗留，非 D3a 引入；应另立修复明确 per-order `marginMode=CROSS` 预期行为（见 §4）。
8. **cross-run Redis `cross_mode` 持久化观察**：跨多次 IT 进程时 Redis 中 `cross_mode.enabled` 风控开关状态可能残留，建议跨进程跑前清 Redis（沿 D2 口径，非 D3a 缺陷）。
9. **pre-existing flake `TradingKafkaWalletDepositIntegrationTests`（#1）/ 瞬态死锁 `cleanOwnerTables`（#3）**：均非本阶段引入，不阻断（见 §4）。
10. **真三端跨服务 HTTP E2E（console→trading）= WSL 受限手动项**：D3a 为 trading-core 后端切片，未跑 console→trading 真网关链路；证据靠配置 RPC IT（MockMvc + internal token）+ supplement gating UT/IT + 8×3 验收 IT 拼接。

---

## §6. supplement FX_PAUSED 闸门与可配生效证据

| 验证项 | 证据 |
|---|---|
| supplement 接 FX_PAUSED（`allow_open`）插入点 | `TradingPositionMarginApplicationService.addIsolatedMargin` 在 `POSITION_NOT_ISOLATED`（30085）校验之后、余额校验（40001）之前接闸门（`SupplementMarginFxPauseGatingTests`） |
| supplement pause 命中行为 | `hasActiveGlobalPause()` + `SymbolSpec.category()` → `FxPauseBehaviorRepository.findByCategory` → `allow_open=false` → 抛 `GLOBAL_PAUSE_ACTIVE`（30087）（口径与开仓侧 `evaluatePauseOpenRejection` 一致，符合 master §7.4 supplement 错误集含 30087） |
| supplement 降级（缺信息从严） | category=null（spec 缺失）或 behavior 缺失 → 保守全拒 30087（与开仓侧 30009 降级同方向「从严」，supplement 端统一 30087） |
| 冷静期可配即时生效 | 直读 `t_risk_config` 平台行 `cooling_period_seconds`（无缓存），admin 经 RPC 改后即时运行时生效（缺失回退 properties 默认 300s） |
| 阈值可配 ≤30s 生效 | 写 `t_risk_config` 平台行 stop_out_level/margin_call_level，`DefaultMarginLevelMonitor` 30s TTL 自然生效（不强制 invalidate，同 C2 tier） |
| FxPauseBehavior 可配即时生效 | `updateByCategory` 后失效 `AtomicReference` 快照，下次读重建（写即时生效）；8×3 IT 中 admin override 翻转 forex→全开后开仓即放行 |
| adminUserId 审计落列 | PUT `/fx-pause-behavior/{category}` 经 `X-Admin-User-Id`（`TradingInternalApiTokenFilter` 注入）落 `updated_by_admin_id` 列（FxPauseBehavior 写 IT） |

---

## §7. master §8.3 D 阶段验收硬约束对照（D3a 可证部分逐条标注）

| 硬约束 | 状态 | 验证证据 |
|---|---|---|
| 切换闸门 4 项（OPEN/挂单/冷静期/同模式）单独验证 | ✅ D1 已证 | D1 `MarginModeSwitchApplicationServiceTests` 12 UT |
| 5min 冷静期到期后允许再次切换 | ✅ D1 已证 | D1 冷静期 UT + 落库 IT |
| **冷静期 admin 运行时可配（60s-7d）** | ✅ **D3a** | V37 `cooling_period_seconds`（60-604800）+ 配置 RPC（PUT `/cooling-period`）+ `MarginModeSwitchApplicationServiceTests` DB 读 UT（**UI 留 D3b**） |
| **StopOut/MarginCall admin 运行时可配** | ✅ **D3a** | `t_risk_config` 平台行写方法 + 配置 RPC（PUT `/risk-thresholds`，0.05-0.95 / 0.50-2.00）+ 写回读 IT，`DefaultMarginLevelMonitor` 30s TTL 生效（**UI 留 D3b**） |
| **FX_PAUSED 8 类目 × 3 开关组合行为正确** | ✅ **D3a** | Task8 8×3 验收 IT 22（开仓/supplement/被动强平/手动平仓 + admin override 可配生效 + category=null 降级） |
| **supplement-margin 受 FX_PAUSED 闸门（30087）** | ✅ **D3a** | Task5 `SupplementMarginFxPauseGatingTests` 6 UT/IT |
| CROSS 强平排序「浮亏最大优先」3 仓位场景 | ✅ D2 已证 | D2 `CrossLiquidationIntegrationTests` |
| 实时 MM（fx 变 → MM 变 → ML 变 → 触发） | ✅ D2 已证 | D2 `DefaultAccountEquityCalculatorTests` UT + 全链 IT |
| close_reason CROSS_STOP_OUT + 账户级通知 | ✅ D2 已证 | D2 Task1/Task5 IT |
| CROSS 强平高并发 P99 < 500ms | ✅ D2 已证 | D2 120 用户跌穿 P99≈177ms |
| 升级窗口 `isolated_margin` 回填（停服 5min 演练） | ✅ **已评估作废** | D3a 不加 `isolated_margin` 列；D1/D2 `t_position.margin` + `marginMode` 等价，无需停服回填（见 §5 第 3 条 / [数据库设计 §4.3](../database/falconx一期数据库设计.md)） |

#### 通用硬约束

| 通用硬约束 | 状态 | 备注 |
|---|---|---|
| mvn compile + test-compile BUILD SUCCESS | ✅ | trading-core 各 task 实施门禁覆盖 |
| 涉及服务 mvn test 全过 | ✅ D3a 自身 | D3a 新增测试全绿；全量 577/3 failures 逐条定性（§4），3 failures 均非 D3a 引入确定性失败 |
| 文档同步完成 | ✅ | R8 同步（本 commit，见 §8） |
| Git 回滚点 push 到 main | ⏳ | 本地 main 领先 origin/main（按约定由控制者统一执行） |
| 当前开发计划 §1 阶段收口条目录入 | ✅ | 本 commit（D3a 收口，下一步 D3b） |

---

## §8. 文档同步清单（R8）

| 文档 | 状态 | 内容 |
|---|---|---|
| `docs/api/FalconX统一接口文档.md` | ✅ | §3.32 配置 internal RPC（`/internal/v1/trading/console/config` platform-risk GET / cooling-period PUT / risk-thresholds PUT / fx-pause-behavior GET+PUT，校验范围 + 99004 + 关键日志 + 测试结论）+ §3.31.x supplement 补 30087 FX_PAUSED 行为 |
| `docs/api/管理端接口规范.md` | ✅ | §4.x 登记 D3a trading-core internal RPC + 占位 D3b console 端点（RBAC 高危，注明 fx-pause-behavior 透传目标修正为 trading-core） |
| `docs/database/falconx一期数据库设计.md` | ✅ | §4.3 STAGE-14D3a V37 `t_risk_config.cooling_period_seconds`（INT NOT NULL DEFAULT 300，admin 可配 60-604800）+ `isolated_margin` 不加列的结论 |
| `docs/domain/状态机规范.md` | ✅ | §6.5 补 supplement-margin 受 `allow_open` 闸门（30087，缺失保守全拒）+ 8 类目×3 行为矩阵确认（D 阶段验收）+ 冷静期 admin 可配（60-604800）补注 |
| `docs/process/BBook一期完成执行路径.md` | ✅ | §15G STAGE-14D3a 收口条目（范围 + commits + 测试 + 部署阻断 + #2 stale IT 提示 + 下一步 D3b/E） |
| `docs/setup/当前开发计划.md` §1 | ✅ | STAGE-14D3a 收口条目 + D2 条目末「下一步」更新为「D3a 已收口（下条），下一步 D3b」 |

---

## §9. 结论

按 [AGENTS.md §8.1.2](../../AGENTS.md) 生产可用判定：

**D3a（运营可配后端 + supplement pause gating + FX_PAUSED 8×3 验收）在 trading-core 后端代码侧已完整实现，并通过 `MarginModeSwitchApplicationServiceTests` 14 UT（含 DB 读冷静期 / 回退默认）+ `TradingPlatformConfigApplicationServiceTests` 4 UT + 配置 internal RPC IT 9（含 fx-pause）+ 配置写 repo IT 2 + `SupplementMarginFxPauseGatingTests` 6 UT/IT + FxPauseBehavior 写 IT 3 + FX_PAUSED 8×3 验收 IT 22 验证。落地了 master §6.5 FX_PAUSED 类目行为（supplement 接闸门 + 8 类目×3 开关完整矩阵）+ §7.4/§7.6（supplement 错误集含 30087 + 可配阈值范围）：冷静期 admin 运行时可配（60s-7d）、StopOut/MarginCall admin 可配、FX_PAUSED 8 类目×3 组合行为正确、supplement 受 FX_PAUSED 闸门四条 D 阶段验收硬约束（D3a 可证部分）已覆盖；升级窗口 `isolated_margin` 回填经评估作废（D1/D2 等价，无需停服）。冷静期改从 `t_risk_config` 平台行直读即时生效（缺失回退 properties 默认 300s），阈值写后由 `DefaultMarginLevelMonitor` 30s TTL 自然生效，FxPauseBehavior 写后失效快照即时生效。**

**🔴 但当前不满足无条件"生产可用"：**

- **剩余阻断项（部署前必须处理，沿 14B/C1/C2/D1/D2 叠加）**：生产/演示库 `falconx_trading` 因 14B root bug 期间 `USE` 污染存在 V28/V29 schema 漂移，下次 `flyway migrate` 将先撞 `Duplicate column` 阻断启动；D3a 只新增干净的 V37，不引入新阻断但也不解除既有阻断。**部署前必须先按 §0 修复 14B V28/V29 漂移再 migrate（trading V30-V37），禁止裸跑 migrate。** D3a 未引入任何「计划未预见的 schema 阻断」（V37 为带 DEFAULT 的常规加列）。
- **范围边界（非阻断，按 §5）**：console 三端 UI（3 配置页透传 + RBAC + 审计 + 前端 + console 错误码 90950/90951 + console V13 权限/菜单 seed）留 D3b；三端展示 UI + 多币聚合 + WS break 切换留 E；#2 `shouldReturnMarginModeNotSupportedWhenOrderRequestsCross` 为 D1/D2 遗留 stale IT 应另立修复；真三端跨服务 HTTP E2E（console→trading）为 WSL 受限手动项；阈值 ≤30s 生效为设计（非缺陷）。
- **不满足生产可用的其他原因**：本系统整体仍处 BBook 一期建设中，按 [当前开发计划 §1](../setup/当前开发计划.md) 末条，当前系统不得表述为"生产可用"或"可安全对外公测"。

**使用说明（按 §8.1.3）：**

- **使用入口**：D3a 配置写/读经 trading-core internal RPC（`/internal/v1/trading/console/config/*`，沿 `TradingInternalApiTokenFilter` 鉴权，需 `X-Internal-Token` + `X-Admin-User-Id`）；正式三端入口经 D3b console 透传（未做）。当前可直接经 trading internal RPC 调用验证。
- **前置条件**：目标库已按 §0 完成 14B V28/V29 漂移修复 + trading V30-V37 migrate；trading-core 已部署。
- **步骤**：(1) `PUT /internal/v1/trading/console/config/cooling-period`（body `{ "coolingPeriodSeconds": 600 }`，60-604800）→ 冷静期即时生效；(2) `PUT .../risk-thresholds`（body `{ "stopOutLevel": "0.25", "marginCallLevel": "1.20" }`，0.05-0.95 / 0.50-2.00）→ ≤30s 生效；(3) `PUT .../fx-pause-behavior/{category}`（category 1-8，body `{ "allowOpen": true, "allowClose": true, "allowLiquidation": true }`，`X-Admin-User-Id` header）→ 快照失效即时生效；(4) `GET .../platform-risk` / `GET .../fx-pause-behavior` 回读核对。
- **预期结果**：越界参数（cooling <60 或 >604800、stopOut <0.05 或 >0.95、marginCall <0.50 或 >2.00、category <1 或 >8、缺字段）→ 400 `INVALID_REQUEST_PAYLOAD`（99004）；GLOBAL_PAUSE active 下 forex 仓（allow_open=false）supplement-margin → 30087，crypto（allow_open=true）放行；手动平仓不受 pause 限制（allow_close 恒 1）。
- **已知限制**：D3b console UI 未做（当前无三端入口，仅 internal RPC）；#2 stale IT 待另立修复；cross-run 跑前建议清 Redis（`cross_mode` 残留观察）；`fx-pause-behavior` 透传目标为 trading-core（非 market）。

**下一步**：

- **STAGE-14D3b**：console 三端 UI——3 配置页（冷静期 / StopOut·MarginCall 阈值 / FX_PAUSED 8 类目行为）console 透传到 trading internal RPC + RBAC（`margin-mode-config:edit` / `risk-threshold:edit` / `fx:pause-behavior:edit` 高危）+ 审计 + 前端（二次确认/reason）+ console 错误码（90950 ADMIN_MARGIN_MODE_CONFIG_INVALID / 90951 ADMIN_FX_PAUSE_BEHAVIOR_INVALID + 阈值非法码）+ console migration V13 权限/菜单 seed + `HighRiskPermissionRegistry` 注册。
- **STAGE-14E**：三端展示 UI（客户端 mode toggle + MarginLevel 浮窗 + 双币 PnL）+ admin 多币种聚合 + WebSocket break 字段最终切换 + admin FX rate 监控 page。
