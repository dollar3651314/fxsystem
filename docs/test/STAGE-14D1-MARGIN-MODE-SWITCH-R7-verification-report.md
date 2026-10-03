# STAGE-14D1-MARGIN-MODE-SWITCH R7 验证报告

> 验证日期：2026-06-01
> 验证人：Claude Opus 4.8（在 R1 Commander 调度下作为 R7）
> 任务：`STAGE-14D1` 用户级 margin mode 切换闸门 + 5min 冷静期 + supplement-margin `/me/` 收口（纯 trading-core 后端切片，master §9 D 阶段第一切片 D1；CROSS 账户级强平 / 实时 MM 精化 / 打开 `cross_mode.enabled` 留 D2）

---

## §0. 部署前置阻断项（最高优先级，必读）

> **🔴 本阶段 D1（用户级 margin mode 切换闸门 + 冷静期 + supplement）在 trading-core 后端代码侧已实现并通过 UT/IT 验证，但存在一个生产/演示库部署阻断项（沿 STAGE-14B/C1 叠加），部署前必须先处理，不得让生产/演示库裸跑 `flyway migrate`。**

**阻断项：本地开发库 `falconx_trading`（及远程演示库）存在 STAGE-14B 遗留 schema 漂移（V28/V29），D1 新增 V33/V34 叠加其上。**

- 根因（沿 14B）：14B 的 V28（`t_ledger` 三列）与 V29（`t_position.entry_fx_rate`）migration 在 root bug 期间误写 `USE falconx_trading;`（已由 `1031a9ce` 修复），导致这两列在 `falconx_trading` 库**已物理存在且已回填**，但 `flyway_schema_history` **没有 V28/V29 行**。
- D1 新增的 migration：**仅 trading `V33__account_mode_switch_columns.sql`（`t_account` 加 `mode_changed_at` / `mode_cooling_until`）+ `V34__seed_account_mode_changed_notification.sql`（ACCOUNT_MODE_CHANGED 通知模板 seed），二者均干净、无 `USE`，与 V1-V32 一致**；market / console 无新 migration。
- 风险：被 14B `USE` 污染过的既有 trading 库下次 `flyway migrate` 仍会因 V28/V29 列已存在而先撞 `Duplicate column` 失败，trading-core 启动/部署受阻；该失败连带 V30-V34 无法应用。**D1 本身不引入新的 trading 库阻断，但不解除 14B/C1 的既有阻断。**

**修复指引（部署前由 DBA / 部署执行，沿 C1/C2 §0）：**

1. **先修 14B V28/V29 漂移**（二选一）：手动向 `flyway_schema_history` 插入 V28/V29 成功行（`success=1`，checksum 用删 USE 后 SQL 计算）；或 `flyway repair` + 人工核对目标库 `t_ledger` 三列 + `t_position.entry_fx_rate` 确已存在且回填正确。
2. **再正常 migrate**：trading 库 V30-V34 为干净顺序 migration，正常应用即可。

执行须按授权进行。**禁止在未核对前对生产/演示库直接 `flyway migrate`。**

> 全新部署的干净库无此问题（trading 库从 V27 顺序到 V34 均干净）。本阻断项仅影响 root bug 期间被 14B `USE` 污染过的既有 trading 库。

---

## §1. 范围

本阶段覆盖 [STAGE-14 多币种 + CROSS/ISOLATED 保证金总设计稿](../design/STAGE-14-MULTICURRENCY-AND-CROSS-MARGIN-MASTER-design.md) §9 D 阶段的**第一切片 D1**（纯 trading-core 后端：用户级 margin mode 切换闸门 + 5min 冷静期 + supplement-margin `/me/` 收口），落地 master §6.1（切换状态机）+ §7.4（端点）：

| 子能力 | D1 交付 | 关键 commit |
|---|---|---|
| V33 `t_account` mode 切换时间列 | `mode_changed_at` + `mode_cooling_until` + 实体/record/XML 串入 + `updateMarginMode`（`margin_mode` 列 V11 已有，不重复加） | `d7b0527d` |
| 错误码 30080-30088 + 事件 payload | MODE_HAS_OPEN_POSITIONS/ACTIVE_PENDING/COOLING_PERIOD_ACTIVE/NO_CHANGE/POSITION_NOT_ISOLATED/SUPPLEMENT_AMOUNT_INVALID/CROSS_MODE_NOT_ENABLED + `AccountMarginModeChangedEventPayload` | `603eed42` |
| `GET/POST /api/v1/me/margin-mode` 切换端点 | 切换闸门（30080-30083）+ CROSS gating（30088）+ 5min 冷静期 + 状态机 + queryMode blockers | `afbd8c6a` |
| Kafka + 通知 | 切换成功同事务发 Outbox `falconx.trading.account.mode.changed` + V34 ACCOUNT_MODE_CHANGED 站内信模板 | `3a156005` |
| supplement-margin `/me/` 收口 | `/api/v1/me/positions/{id}/supplement-margin` 复用既有 service + 越权校验（40004）+ 30085/30086 错误码对齐 | `986b2396` |
| R8 文档同步 + R7 报告 + 计划录入 | 本 commit | 本 commit |

**不在 D1 范围**（划 D2 / D3 / E）：

- **D2**：CROSS 账户级强平排序「浮亏最大优先」（master §6.4/§8.3）+ 实时 MM 精化（master §3.2 D2 全实时）+ **放开 C1 latent 耦合 `closePositionByTrigger` 二次价格校验**（ML 与 liqPrice 解耦前必须放开）+ **打开 `cross_mode.enabled`**（解除 30088 gate）+ FX_PAUSED 8 类目×3 开关完整验收 + 升级窗口 `isolated_margin` 回填 + CROSS 强平高并发 PERF。
- **D3**：admin 冷静期配置 UI（60s-7d）+ supplement pause gating（30087）+ StopOut 阈值 admin 可配 UI。
- **E**：三端 UI（客户端 mode toggle + MarginLevel 浮窗 + 双币 PnL）+ admin 多币种聚合 + WebSocket break 字段最终切换。

---

## §2. 角色

D1 为**纯 trading-core 后端**切片（无 console / 前端），按 [`AI工作模式 §2`](../process/AI工作模式.md) 角色路由：

- **R2 Contract Designer**：`/api/v1/me/margin-mode` GET/POST + supplement `/me/` 路径 + 错误码 30080-30088 + `AccountMarginModeChangedEventPayload` 契约。
- **R4 业务后端**：V33 列 + `MarginModeSwitchApplicationService` 切换闸门/冷静期/CROSS gating + Outbox + V34 通知 + supplement 越权校验。
- **R6 Test**：12 UT + 4 IT/repo 测试 + supplement `/me/` IT。
- **R7 QA**：本报告。
- **R8 Doc**：R8 文档同步（§8）。

> 后端切片交付口径：用户级账户行为后端，客户端 UI（mode toggle / MarginLevel 浮窗）留 STAGE-14E；后端 + 契约 + 事件 + 通知已实现并验证。各 task 均经实施 → spec 合规评审 → 代码质量评审 → 收口完整门禁。

---

## §3. 各 Task 证据

| Task | 内容 | 关键 commit | 证据 |
|---|---|---|---|
| 1 | V33 `t_account` mode 切换时间列 + 实体串入 + `updateMarginMode` | `d7b0527d` | `TradingAccountModeSwitchRepositoryIntegrationTests` IT + `MybatisTradingAccountRepositorySwitchMarginModeTests` |
| 2 | 错误码 30080-30088 + `AccountMarginModeChangedEventPayload` | `603eed42` | `TradingErrorCode` 编译 + contract record |
| 3 | `GET/POST /api/v1/me/margin-mode` 切换闸门 + 5min 冷静期 + CROSS gating | `afbd8c6a` | `MarginModeSwitchApplicationServiceTests` 12 UT（闸门各拒 + gating + 冷静期 + blockers） |
| 4 | Outbox `falconx.trading.account.mode.changed` + V34 ACCOUNT_MODE_CHANGED 通知 | `3a156005` | `MarginModeSwitchKafkaNotificationIntegrationTests` IT（Outbox 计数 + 通知渲染） |
| 5 | supplement-margin `/me/` 收口（30085/30086 + 越权 40004） | `986b2396` | `TradingControllerIntegrationTests` supplement `/me/` 成功 + 越权 40004 IT + `TradingPositionMarginApplicationServiceTests` |
| 6 | R8 文档同步 + R7 收口报告 + 当前开发计划录入（本 commit） | 本 commit | 本报告 + R8 同步清单（§8）+ 计划 §1 条目 |

---

## §4. 测试统计

D1 IT 汇总（`mvn -pl falconx-trading-core-service test -Dtest='MarginModeSwitch*,*PositionMargin*,TradingAccountModeSwitch*,TradingControllerIntegrationTests'`，2026-06-01，跑前已 `mvn -pl falconx-market-contract -am install -DskipTests`）：

| 测试类 | 类型 | 数量 | 覆盖 |
|---|---:|---:|---|
| `MarginModeSwitchApplicationServiceTests` | UT | 12 全绿 | 切换闸门 30080-30083 各拒 + CROSS gating 30088 + 同模式短路 + 冷静期内拒/到期允许 + queryMode blockers（OPEN_POSITIONS/ACTIVE_PENDING/COOLING） |
| `MarginModeSwitchKafkaNotificationIntegrationTests` | IT | 1 全绿 | 切换成功同事务 Outbox 计数 + ACCOUNT_MODE_CHANGED 通知渲染（oldMode/newMode 插值） |
| `TradingAccountModeSwitchRepositoryIntegrationTests` | IT | 1 全绿 | `switchMarginMode` 真 DB 落库（margin_mode + mode_changed_at + mode_cooling_until） |
| `MybatisTradingAccountRepositorySwitchMarginModeTests` | 测试 | 2 全绿 | repository switchMarginMode CAS / 列映射 |
| `TradingControllerIntegrationTests` | IT | 40 全绿 | supplement `/me/` 成功 + 越权 40004 + 旧端点兼容 + 既有交易控制器全量（含 margin mode 显式开仓回显） |
| `TradingPositionMarginApplicationServiceTests` | UT | 2 全绿 | supplement service（追加后 margin/liqPrice 重算） |
| **汇总** | UT+IT | **58 全绿** | Tests run: 58, Failures: 0, Errors: 0 |

> 跑测过程中 `TradingExternalRpcClient` 对 market FX RPC（`localhost:18080`）`Connect timed out` 为预期（market-service 未起，trading 侧 FX bootstrap 失败降级），不影响本切片 mode switch / supplement 逻辑，相关 IT 全绿。

### 切换闸门 4 项 + CROSS gating + 冷静期覆盖（master §8.3 D 阶段可证部分）

| 场景 | 行为 | 证据 |
|---|---|---|
| 目标模式 = 当前模式 | 拒 30083 MODE_NO_CHANGE（最先短路） | UT |
| 目标 CROSS + `cross_mode.enabled` 关闭 | 拒 30088 CROSS_MODE_NOT_ENABLED（D1 默认 gate） | UT |
| 存在 OPEN 持仓 | 拒 30080 MODE_HAS_OPEN_POSITIONS | UT |
| 存在开仓挂单 | 拒 30081 MODE_HAS_ACTIVE_PENDING | UT |
| 冷静期内 | 拒 30082 MODE_COOLING_PERIOD_ACTIVE | UT |
| 闸门全过 | 落库 + 同事务 Outbox + 通知 | UT + Kafka IT |
| 冷静期到期 | 允许再次切换（`mode_cooling_until ≤ now` / NULL） | UT（冷静期判定）+ 落库 IT |
| supplement CROSS 仓 | 拒 30085 POSITION_NOT_ISOLATED | UT/IT |
| supplement 非本人持仓 | 拒 40004 POSITION_NOT_FOUND（越权天然防护） | IT |

---

## §5. 已知不阻断项 / 边界

> 部署阻断项（生产/演示库 schema 漂移，沿 14B/C1 叠加）见 **§0**，为最高优先级，单独提级，不在本节"不阻断"列表内。

1. **【部署阻断项，提级到 §0】** 14B V28/V29 `USE` 污染遗留；D1 新增 V33/V34 干净无 `USE`，但被污染 trading 库部署前仍须先 repair V28/V29 再 migrate（详见 §0）。
2. **CROSS 入口 gated（`cross_mode.enabled` 默认 false）**：D1 切到 CROSS 被 30088 拒；D1 实际可切换的是 CROSS→ISOLATED（或开关打开后 ISOLATED→CROSS）。CROSS 账户级强平判据 / 排序「浮亏最大优先」/ 实时 MM 未就位前不放用户进入无强平保护的 CROSS。打开开关 + CROSS 强平 + 实时 MM 留 **D2**。
3. **不加 `t_position.isolated_margin` 列**：D1 supplement 直接增 `t_position.margin`（与既有逐仓追加保证金等价口径，master §3.4）；CROSS 仓的保证金占用区分（`isolated_margin` 升级窗口回填）留 **D2**。
4. **supplement pause gating（30087）未接**：活跃 GLOBAL_PAUSE 下是否禁止 supplement 属 D1 范围外，留 D2/D3。
5. **30086 SUPPLEMENT_AMOUNT_INVALID 与 Bean Validation 并存**：金额非正同时被 controller `@Valid` 与 service 层 30086 拦截（service 层兜底，避免绕过 controller 的内部调用）。
6. **冷静期时长 admin 可配 UI 未引入**：当前为 `falconx.trading.margin-mode.cooling-duration` properties（默认 5min）；admin console 配置 UI（60s-7d）留 **D3**。
7. **`falconx.trading.account.mode.changed` 跨服务消费方按需启用**：D1 内 ACCOUNT_MODE_CHANGED 通知已由 trading 侧 V34 模板直接 `notificationService.send` 落库站内信；console 审计 / identity 消费该 topic 留 STAGE-14D 后续。
8. **D2 前置注意（沿 C1 Finding 2 / latent 耦合）**：ISOLATED 单仓下 MarginLevel-30% 与 liqPrice 差集为空，30% StopOut 真正价值在 CROSS（D2）；`closePositionByTrigger` 二次价格校验须在 D2 引入实时 MM/CROSS（ML 与 liqPrice 解耦）前放开，否则 StopOut-without-liqPrice 被静默吞掉。
9. **真三端跨服务 HTTP E2E（gateway→trading）= WSL 受限手动项**：证据靠 controller IT（注入 `X-User-Id`）+ application service UT + repository / Kafka IT 拼接，未跑真网关链路。
10. **pre-existing flake `TradingKafkaWalletDepositIntegrationTests`**（Kafka consumer group 异步计数竞态，非本阶段，建议后续单独修，不阻断本阶段；本次定向 IT 未包含该类）。

---

## §6. margin mode 切换闸门证据

| 验证项 | 证据 |
|---|---|
| 同模式短路 30083 | 目标 = 当前 mode → 30083 MODE_NO_CHANGE（UT，最先判定避免无谓查询） |
| CROSS gate 30088 | 目标 CROSS + `cross_mode.enabled` 关闭 → 30088（UT；开关默认 false，`RedisTradingRiskSwitchCache.isEnabled(KEY_CROSS_MODE_ENABLED, false)`） |
| OPEN 持仓拒 30080 | 存在 OPEN 持仓 → 30080（UT；`findOpenByUserId` 非空） |
| 开仓挂单拒 30081 | 存在开仓挂单 → 30081（UT；`countUserOpeningPending > 0`） |
| 冷静期拒 30082 | `mode_cooling_until > now` → 30082（UT） |
| 冷静期到期允许 | `mode_cooling_until ≤ now` / NULL → 闸门通过可切（UT + 落库 IT） |
| 切换成功原子 | 同 `@Transactional` + FOR UPDATE：落库 + Outbox + 通知（Kafka IT；rollback 一并回滚） |
| supplement 越权防护 | `findByIdAndUserIdForUpdate(positionId, userId)` 非本人查不到 → 40004（IT） |

---

## §7. master §8.3 D 阶段验收硬约束对照（D1 可证部分逐条标注）

| 硬约束 | 状态 | 验证证据 |
|---|---|---|
| 切换闸门 4 项（OPEN/挂单/冷静期/同模式）单独验证 | ✅ | Task 3 `MarginModeSwitchApplicationServiceTests` 12 UT，30080/30081/30082/30083 各拒单独覆盖 |
| 5min 冷静期到期后允许再次切换 | ✅ | 冷静期逻辑（properties 5min + `mode_cooling_until`）+ `isInCoolingPeriod` 到期判定（UT 拒/允许 + 落库 IT） |
| CROSS 强平排序「浮亏最大优先」3 仓位场景 | ⏳ D2 | D1 不实现 CROSS 强平（`cross_mode.enabled` gated，30088）；留 D2 |
| FX_PAUSED 8 类目 × 3 开关组合行为 | ⏳ D2 | C2 已接 allow_open/allow_liquidation 两开关；完整 8×3 留 D2 |
| 升级窗口 `isolated_margin` 回填（停服 5min 演练） | ⏳ D2 | D1 不加 `isolated_margin` 列；留 D2 |
| CROSS 强平高并发（1000 用户跌穿 30%，吞吐 ≥ 100 ops/s，单仓 P99 < 500ms） | ⏳ D2 | CROSS 路径留 D2 |

#### 通用硬约束

| 通用硬约束 | 状态 | 备注 |
|---|---|---|
| mvn compile + test-compile BUILD SUCCESS | ✅ | trading-core 各 task 实施门禁覆盖 |
| 涉及服务 mvn test 全过 | ✅ | trading-core D1 定向 58 全绿 |
| 文档同步完成 | ✅ | R8 同步（本 commit，见 §8） |
| Git 回滚点 push 到 main | ⏳ | 本地 main 领先 origin/main（按约定由控制者统一执行） |
| 当前开发计划 §1 阶段收口条目录入 | ✅ | 本 commit（D1 收口，下一步 D2） |

---

## §8. 文档同步清单（R8）

| 文档 | 状态 | 内容 |
|---|---|---|
| `docs/api/FalconX统一接口文档.md` | ✅ | §3.31 用户级 margin mode 切换 + supplement-margin `/me/`：GET/POST `/api/v1/me/margin-mode`（请求/响应/闸门错误码 30080-30083/30088）+ `/api/v1/me/positions/{id}/supplement-margin`（30085/30086/40004） |
| `docs/domain/状态机规范.md` | ✅ | §6.4 账户 margin mode 切换状态机（闸门 4 项 + CROSS gate 30088 + 5min 冷静期 + queryMode blockers + 切换成功原子事件/通知；CROSS 强平判据指向 D2） |
| `docs/event/Kafka事件规范.md` | ✅ | §12.15 `falconx.trading.account.mode.changed`（Outbox 投递 + `AccountMarginModeChangedEventPayload` 字段约定 + 幂等键 + 分区键）+ §11 主题清单登记 |
| `docs/process/BBook一期完成执行路径.md` | ✅ | §15E STAGE-14D1 收口条目（范围 + 闸门/冷静期 + 测试 58 + 部署阻断 + D2/D3/E 边界 + commits） |
| `docs/setup/当前开发计划.md` §1 | ✅ | STAGE-14D1 收口条目 + 下一步指向 D2/D3/E |

> 数据库设计：D1 仅 trading `t_account` 加两列（V33）+ 通知模板 seed（V34），属业务库结构小增量，按既有阶段口径在 R7 报告 §0 + 执行路径 §15E 登记，未单列 `docs/database/falconx一期数据库设计.md` 大改（与 C1/C2 口径一致）。

---

## §9. 结论

按 [AGENTS.md §8.1.2](../../AGENTS.md) 生产可用判定：

**D1（用户级 margin mode 切换闸门 + 5min 冷静期 + supplement-margin `/me/` 收口）在 trading-core 后端代码侧已完整实现，并通过 D1 定向 58 tests 全绿（12 UT 切换闸门 30080-30083 各拒 + CROSS gating 30088 + 冷静期 + queryMode blockers / Kafka+通知 IT / repository 落库 IT / supplement `/me/` 成功+越权 40004 IT）验证。落地了 master §6.1 切换状态机 + §7.4 端点：切换闸门 4 项（OPEN/挂单/冷静期/同模式）单独验证、5min 冷静期到期后允许再次切换两条 D 阶段验收硬约束（D1 可证部分）已覆盖。切换成功在同事务内 Outbox 发 `falconx.trading.account.mode.changed` + ACCOUNT_MODE_CHANGED 站内信，与状态变更原子。supplement-margin 经 `/me/` 路径复用既有 service 并加越权校验（40004）。**

**🔴 但当前不满足无条件"生产可用"：**

- **剩余阻断项（部署前必须处理，沿 14B/C1 叠加）**：生产/演示库 `falconx_trading` 因 14B root bug 期间 `USE` 污染存在 V28/V29 schema 漂移，下次 `flyway migrate` 将先撞 `Duplicate column` 阻断启动；D1 只新增干净的 V33/V34，不引入新阻断但也不解除既有阻断。**部署前必须先按 §0 修复 14B V28/V29 漂移再 migrate（trading V30-V34），禁止裸跑 migrate。**
- **CROSS 入口 D1 gated**：`cross_mode.enabled` 默认 false，切到 CROSS 被 30088 拒；CROSS 账户级强平排序「浮亏最大优先」+ 实时 MM 精化 + 放开 C1 latent 耦合 `closePositionByTrigger` 二次价格校验 + FX_PAUSED 8×3 完整验收 + `isolated_margin` 回填 + CROSS 高并发 PERF 留 **D2**；admin 冷静期配置 UI + supplement pause gating 留 **D3**；三端 UI + 多币聚合 + WS break 切换留 **E**。
- **范围边界（非阻断，按 §5）**：不加 `isolated_margin` 列（margin 等价，CROSS 仓区分留 D2）；30086 与 Bean Validation 并存；真三端跨服务 HTTP E2E（gateway→trading）为 WSL 受限手动项（controller IT 注入 `X-User-Id` 代证）。
- **不满足生产可用的其他原因**：本系统整体仍处 BBook 一期建设中，按 [当前开发计划 §1](../setup/当前开发计划.md) 末条，当前系统不得表述为"生产可用"或"可安全对外公测"。

**使用说明（按 §8.1.3）：**

- **使用入口**：用户经客户端（D1 仅后端，UI 留 E）调 `GET /api/v1/me/margin-mode` 查询当前 mode + `canSwitch` + blockers；`POST /api/v1/me/margin-mode`（body `{targetMode}`）切换，全过则落库 + 发 Outbox + 站内信，写 `mode_cooling_until = now + 5min`；`POST /api/v1/me/positions/{id}/supplement-margin` 为本人 OPEN ISOLATED 仓追加保证金。
- **前置条件**：目标库已按 §0 完成 14B V28/V29 漂移修复 + trading V30-V34 migrate；`cross_mode.enabled`（默认 false）按 D2 就绪后由 admin 打开。
- **预期结果**：同模式拒 30083 / 目标 CROSS 且开关关闭拒 30088 / 有持仓拒 30080 / 有挂单拒 30081 / 冷静期内拒 30082；切换成功落 `t_account.margin_mode + mode_changed_at + mode_cooling_until` + 发 `falconx.trading.account.mode.changed` + ACCOUNT_MODE_CHANGED 站内信；supplement 对 CROSS 仓拒 30085、金额非正拒 30086、非本人拒 40004。
- **已知限制 / 禁用场景**：CROSS 入口 D1 gated（30088）；不加 `isolated_margin` 列；supplement pause gating（30087）/ admin 冷静期配置 UI 留 D2/D3；真三端 HTTP E2E 为手动项；禁止对未按 §0 修复的污染库直接 migrate。
