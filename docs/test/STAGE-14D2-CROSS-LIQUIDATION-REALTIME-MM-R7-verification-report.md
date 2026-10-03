# STAGE-14D2-CROSS-LIQUIDATION-REALTIME-MM R7 验证报告

> 验证日期：2026-06-01
> 验证人：Claude Opus 4.8（在 R1 Commander 调度下作为 R7）
> 任务：`STAGE-14D2` CROSS 账户级强平排序「浮亏最大优先」+ 实时 MM 精化 + 放开 C1 latent 耦合 `closePositionByTrigger` 二次价格校验 + 打开 `cross_mode.enabled`（纯 trading-core 后端切片，master §9 D 阶段第二切片 D2；admin 配置 UI / FX_PAUSED 8×3 完整验收 / supplement pause gating 留 D3；三端 UI 留 E）

---

## §0. 部署前置阻断项（最高优先级，必读）

> **🔴 本阶段 D2（CROSS 账户级强平 + 实时 MM 精化）在 trading-core 后端代码侧已实现并通过 UT/IT/PERF 验证，但存在一个生产/演示库部署阻断项（沿 STAGE-14B/C1/C2/D1 叠加），部署前必须先处理，不得让生产/演示库裸跑 `flyway migrate`。**

**阻断项：本地开发库 `falconx_trading`（及远程演示库）存在 STAGE-14B 遗留 schema 漂移（V28/V29），D2 新增 V35/V36 叠加其上。**

- 根因（沿 14B）：14B 的 V28（`t_ledger` 三列）与 V29（`t_position.entry_fx_rate`）migration 在 root bug 期间误写 `USE falconx_trading;`（已由 `1031a9ce` 修复），导致这两列在 `falconx_trading` 库**已物理存在且已回填**，但 `flyway_schema_history` **没有 V28/V29 行**。
- D2 新增的 migration：**仅 trading `V35__liquidation_log_nullable_liquidation_price.sql`（`t_liquidation_log.liquidation_price` 改 NULL）+ `V36__seed_cross_stop_out_notification.sql`（CROSS_STOP_OUT_TRIGGERED 通知模板 seed），二者均干净、无 `USE`，与 V1-V34 一致**；market / console 无新 migration。
- 风险：被 14B `USE` 污染过的既有 trading 库下次 `flyway migrate` 仍会因 V28/V29 列已存在而先撞 `Duplicate column` 失败，trading-core 启动/部署受阻；该失败连带 V30-V36 无法应用。**D2 本身不引入新的 trading 库阻断，但不解除 14B/C1 的既有阻断。**

**修复指引（部署前由 DBA / 部署执行，沿 C1/C2/D1 §0）：**

1. **先修 14B V28/V29 漂移**（二选一）：手动向 `flyway_schema_history` 插入 V28/V29 成功行（`success=1`，checksum 用删 USE 后 SQL 计算）；或 `flyway repair` + 人工核对目标库 `t_ledger` 三列 + `t_position.entry_fx_rate` 确已存在且回填正确。
2. **再正常 migrate**：trading 库 V30-V36 为干净顺序 migration，正常应用即可（V35 `MODIFY COLUMN ... NULL` 把 `t_liquidation_log.liquidation_price` 放开为可空）。

执行须按授权进行。**禁止在未核对前对生产/演示库直接 `flyway migrate`。**

> 全新部署的干净库无此问题（trading 库从 V27 顺序到 V36 均干净）。本阻断项仅影响 root bug 期间被 14B `USE` 污染过的既有 trading 库。

> **另：`V35` 是计划未预见的 schema 阻断（已修）。** 原 `t_liquidation_log.liquidation_price` 为 NOT NULL；CROSS 账户级强平按账户级 MarginLevel 触发，单仓 `t_position.liquidation_price` 为 NULL（CROSS 仓不落单仓强平价），写强平日志时会撞 NOT NULL 约束失败。V35 放开为可空，是 CROSS 强平能落 `t_liquidation_log` 的必需修复。ISOLATED 强平仍落真实 liqPrice 不受影响。

---

## §1. 范围

本阶段覆盖 [STAGE-14 多币种 + CROSS/ISOLATED 保证金总设计稿](../design/STAGE-14-MULTICURRENCY-AND-CROSS-MARGIN-MASTER-design.md) §9 D 阶段的**第二切片 D2**（纯 trading-core 后端：CROSS 账户级强平 + 实时 MM 精化 + 打开 `cross_mode.enabled`），落地 master §6.3（CROSS 强平流程）+ §3.2（D2 全实时 MM）：

| 子能力 | D2 交付 | 关键 commit |
|---|---|---|
| CROSS_STOP_OUT close_reason + 放开 latent 耦合 | `TradingPositionCloseReason` 扩 `CROSS_STOP_OUT` + `closePositionByTrigger` 对 `CROSS_STOP_OUT` 放开二次价格校验（账户级触发不依赖单仓 liqPrice）+ `settlePositionExit` liquidation 判断扩 | `0dcf048e` |
| CROSS 开仓放开 + 仓 liqPrice=null | `cross_mode.enabled` gate→30088 放行 + CROSS 仓 `liquidation_price=null` + IM 冻结同 ISOLATED | `d7559759` |
| 实时 MM 精化 | `maintenanceMargin` 用实时 fx，`mmRate` 冻结（`mm_rate_at_open`），FX 不可用降级 `entry_fx_rate` | `927b28db` |
| CROSS 账户级强平编排 + V35 | `CrossLiquidationOrchestrator` 账户级 ML 触发 + 浮亏最大优先逐仓平直到恢复 + Redisson user-level 锁 + `QuoteDrivenEngine` CROSS 接入 + V35（liquidation_price NULL） | `4e31fe47` |
| 账户级通知 + V36 | `CROSS_STOP_OUT_TRIGGERED` 账户级通知（仓位清单）+ V36 模板 + 逐仓 POSITION_LIQUIDATED 去重 + Kafka close_reason | `202506d3` |
| PERF + 全链 IT + docs 镜像 | CROSS 强平 PERF（P99<500ms 达成）+ 实时 MM/cross_mode 全链 IT 汇总 + docs/sql V35 镜像 | `4327545d` |
| R8 文档 + R7 报告 + 计划录入 | 本 commit | 本 commit |

**不在 D2 范围**（划 D3 / E）：

- **D3**：FX_PAUSED 8 类目 × 3 开关组合完整行为验收（C2 已接 allow_open/allow_liquidation 两开关 + 类目降级，完整 8×3 矩阵留 D3）+ supplement pause gating（30087）+ admin 冷静期配置 UI（60s-7d）+ StopOut 阈值 admin 可配 UI + 升级窗口 `isolated_margin` 回填（停服 5min 演练）。
- **E**：三端 UI（客户端 mode toggle + MarginLevel 浮窗 + 双币 PnL）+ admin 多币种聚合 + WebSocket break 字段最终切换。
- **后续**：挂单触发 CROSS 资金校验（master §6.4 挂单 CROSS 资金校验，D2 仅放开市价开仓）。

---

## §2. 角色

D2 为**纯 trading-core 后端**切片（无 console / 前端），按 [`AI工作模式 §2`](../process/AI工作模式.md) 角色路由：

- **R4 业务后端**：CROSS_STOP_OUT close_reason + 放开 latent 耦合 + CROSS 开仓放行 + 实时 MM 精化 + `CrossLiquidationOrchestrator` 账户级强平 + V35/V36 + 账户级通知。
- **R6 Test**：6 UT（`CrossLiquidationOrchestratorTests`）+ 5 IT（`CrossLiquidationIntegrationTests`，含 PERF）+ ISOLATED 回归 + 实时 MM UT。
- **R7 QA**：本报告。
- **R8 Doc**：R8 文档同步（§8）。

> 后端切片交付口径：CROSS 账户级强平 / 实时 MM 后端，客户端 UI（mode toggle / MarginLevel 浮窗）留 STAGE-14E；后端 + 算法 + 事件 + 通知已实现并验证。各 task 均经实施 → spec 合规评审 → 代码质量评审 → 收口完整门禁。

---

## §3. 各 Task 证据

| Task | 内容 | 关键 commit | 证据 |
|---|---|---|---|
| 1 | CROSS_STOP_OUT close_reason + 放开 `closePositionByTrigger` 二次价格校验（latent 耦合）+ liquidation 判断扩 | `0dcf048e` | `TradingPositionCloseCrossStopOutTests` UT/IT |
| 2 | CROSS 开仓放开（gate→30088）+ CROSS 仓 liqPrice=null + IM 冻结同 ISOLATED | `d7559759` | `DefaultTradingRiskServiceRiskControlTests` UT |
| 3 | 实时 MM 精化（实时 fx MM / mmRate 冻结 / FX 降级 entryFxRate） | `927b28db` | `DefaultAccountEquityCalculatorTests` UT + ISOLATED IT 回归 |
| 4 | `CrossLiquidationOrchestrator` 账户级 ML 触发 + 浮亏最大优先逐仓 + Redisson user-level 锁 + QuoteDrivenEngine 接入 + V35 | `4e31fe47` | `CrossLiquidationOrchestratorTests` 6 UT + `CrossLiquidationIntegrationTests` IT |
| 5 | `CROSS_STOP_OUT_TRIGGERED` 账户级通知 + V36 + 逐仓去重 + Kafka close_reason | `202506d3` | `CrossLiquidationIntegrationTests`（账户级通知 + 去重） |
| 6 | CROSS PERF + 实时 MM/cross_mode 全链 IT + docs/sql V35 镜像 | `4327545d` | `CrossLiquidationIntegrationTests`（PERF + 全链） |
| 7 | R8 文档同步 + R7 收口报告 + 当前开发计划录入（本 commit） | 本 commit | 本报告 + R8 同步清单（§8）+ 计划 §1 条目 |

---

## §4. 测试统计

D2 测试汇总（trading-core `mvn test`，2026-06-01，跑前已 `mvn -pl falconx-market-contract -am install -DskipTests`）：

| 测试类 | 类型 | 数量 | 覆盖 |
|---|---:|---:|---|
| `CrossLiquidationOrchestratorTests` | UT | 6 全绿 | 排序逐仓 / 恢复止步 / ML 健康不平 / ML==null 不平 / 锁失败跳过 / 平 1 仓恢复 |
| `CrossLiquidationIntegrationTests` | IT | 5 全绿 | 真 DB：CROSS 3 仓浮亏最大优先逐仓平 + close_reason=CROSS_STOP_OUT + `biz_type=9` + 账户级通知（仓位清单）+ 逐仓去重 + 实时 MM + cross_mode flag gate + PERF |
| `TradingPositionCloseCrossStopOutTests` | UT/IT | 全绿 | CROSS_STOP_OUT close_reason 落账 + 放开二次价格校验 |
| `DefaultAccountEquityCalculatorTests` | UT | 全绿 | 实时 MM（实时 fx）+ mmRate 冻结 + FX 不可用降级 entryFxRate |
| `DefaultTradingRiskServiceRiskControlTests` | UT | 全绿 | CROSS 开仓放行（gate→30088）+ CROSS 仓 liqPrice=null + IM 冻结 |
| ISOLATED 强平 IT 回归 | IT | 18/18 绿 | 实时 MM 数值一致（C1 强平链路无回归） |
| trading 全量非 IT 单测 | UT | 305+ 全绿 | 无回归（排除 IntegrationTests） |

> 跑测过程中 `TradingExternalRpcClient` 对 market FX RPC（`localhost:18080`）`Connect timed out` 为预期（market-service 未起，trading 侧 FX bootstrap 失败降级），实时 MM 在 IT 用 seed fx，不影响本切片 CROSS 强平 / 实时 MM 逻辑，相关 IT 全绿。

### CROSS 强平 PERF

| 指标 | 实测 | master 约束 | 结论 |
|---|---|---|---|
| 单账户强平编排 tick 延迟 | P50≈97ms / P99≈177ms | 单仓强平 P99 < 500ms | ✅ 达成 |
| 强平队列吞吐 | ≈20 ops/s | ≥ 100 ops/s | ⚠️ WSL 顺序构造限制未达（**实测不伪造**），1000 规模 + ≥100 ops/s 留正式环境压测 |
| 跌穿规模 | 120 用户 CROSS 账户同时跌穿 | 1000 用户 | ⚠️ WSL 资源用 120 用户，1000 规模留正式环境 |

> P99<500ms 延迟约束已达成；吞吐 ≥100 ops/s 与 1000 用户规模因 WSL 顺序构造资源限制未达，据实标注（不伪造数字），留正式环境压测补，同 C1 PERF 口径。

### CROSS 账户级强平算法覆盖（master §8.3 D 阶段可证部分）

| 场景 | 行为 | 证据 |
|---|---|---|
| CROSS 3 仓账户级 ML 跌穿 stopOut | 按 `\|uPnL(账户币)\|` 降序浮亏最大优先逐仓平 | IT（3 仓场景）+ UT（排序逐仓） |
| 平仓后账户级 ML 恢复（>stopOut） | 止步，不过度强平 | UT（恢复止步 / 平 1 仓恢复）+ IT |
| 账户级 ML 健康（>stopOut） | 不强平 | UT（ML 健康不平） |
| 账户级 ML==null（FX 降级 / 无持仓） | 不强平 | UT（null 不平） |
| 同一用户多 symbol tick 并发评估 | user-level 锁 tryLock 跳过本次（不阻塞 tick） | UT（锁失败跳过） |
| 实时 MM：fx 变 → MM 变 → ML 变 → 触发 | 实时 fx 重算 MM | UT（实时 MM）+ IT |
| CROSS 仓 close | close_reason=CROSS_STOP_OUT + biz_type=9 + liqPrice=NULL | IT |
| 逐仓 POSITION_LIQUIDATED 去重 + 账户级通知 | 一条 CROSS_STOP_OUT_TRIGGERED（仓位清单） | IT |
| cross_mode.enabled flag gate | 关闭拒 CROSS 开仓 30088 / 打开放行 | IT（flag gate）+ UT |

---

## §5. 已知不阻断项 / 边界

> 部署阻断项（生产/演示库 schema 漂移，沿 14B/C1 叠加；V35 计划未预见 schema 阻断已修）见 **§0**，为最高优先级，单独提级，不在本节"不阻断"列表内。

1. **【部署阻断项，提级到 §0】** 14B V28/V29 `USE` 污染遗留；D2 新增 V35/V36 干净无 `USE`，但被污染 trading 库部署前仍须先 repair V28/V29 再 migrate（V30-V36）（详见 §0）。
2. **`cross_mode.enabled` 生产默认 false**：D2 代码全链就位（CROSS 开仓 + 账户级强平 + 实时 MM），生产启用 = admin 经 risk-switch 接口开启 `cross_mode.enabled`（运维决策）。启用步骤见 §9。
3. **PERF 吞吐 WSL 限制**：≈20 ops/s（顺序构造，P99<500ms 延迟已达标），1000 规模 + 吞吐 ≥100 ops/s 留正式环境压测（实测不伪造）。
4. **挂单触发 CROSS gate 未放开**（master §6.4 挂单 CROSS 资金校验）：D2 仅放开市价开仓，挂单触发的 CROSS 资金校验留后续。
5. **`t_liquidation_log.liquidation_price` 放开 NULL（V35）**：CROSS 仓 liqPrice=null 的必需修复（计划未预见的 schema 阻断，已修，提级 §0）。
6. **实时 MM 影响 ISOLATED**：master D2「全部实时」含 ISOLATED；C1 IT 因 seed fx 同值无数值漂移，D2 实时 MM 对 ISOLATED 一致生效，ISOLATED 强平 IT 18/18 回归绿（数值一致）。
7. **IT 库共享 V30 seed 教训**：IT 勿删真实 seed 行（已修复）。
8. **FX_PAUSED 8×3 完整验收 / supplement pause gating（30087）/ admin 冷静期/StopOut 配置 UI / 升级窗口 isolated_margin 回填**：留 D3。
9. **三端 UI / admin 多币聚合 / WS break 字段最终切换**：留 E。
10. **真三端跨服务 HTTP E2E（gateway→trading）= WSL 受限手动项**：证据靠 orchestrator UT + 真 DB IT（CrossLiquidationIntegrationTests）拼接，未跑真网关链路。
11. **pre-existing flake `TradingKafkaWalletDepositIntegrationTests`**（Kafka consumer group 异步计数竞态，非本阶段，建议后续单独修，不阻断本阶段）。

---

## §6. CROSS 账户级强平串行化与幂等证据

| 验证项 | 证据 |
|---|---|
| user-level 锁串行化 | `cross-liq:{userId}` tryLock(0,...) 不等待，获取不到跳过本次（UT 锁失败跳过；不阻塞 tick 线程） |
| 锁与 FOR UPDATE 不死锁 | 评估只读账户快照（AccountMarginStateCache 不持行锁）；逐仓 close 各自 `SELECT FOR UPDATE` 落账，维度正交 |
| 逐仓重算闭环 | 平一仓→失效账户缓存→重取剩余 OPEN 持仓重算账户级 ML→恢复止步（UT 恢复止步 + IT） |
| 逐仓幂等 | `closePositionByTrigger` FOR UPDATE + isTerminal 兜底（并发已平返回 null→跳过继续重算） |
| close_reason 落账 | CROSS_STOP_OUT 同 LIQUIDATION 口径：LIQUIDATED + biz_type=9 + t_liquidation_log（liqPrice=NULL）（IT） |
| 账户级通知去重 | 逐仓 POSITION_LIQUIDATED 去重 + 一条 CROSS_STOP_OUT_TRIGGERED 汇总（IT） |
| 放开 latent 耦合 | closePositionByTrigger 对 CROSS_STOP_OUT 放开二次价格校验（账户级触发不依赖单仓 liqPrice 命中，否则被静默吞掉）（Task1 UT/IT） |

---

## §7. master §8.3 D 阶段验收硬约束对照（D2 可证部分逐条标注）

| 硬约束 | 状态 | 验证证据 |
|---|---|---|
| 切换闸门 4 项（OPEN/挂单/冷静期/同模式）单独验证 | ✅ D1 已证 | D1 `MarginModeSwitchApplicationServiceTests` 12 UT |
| 5min 冷静期到期后允许再次切换 | ✅ D1 已证 | D1 冷静期 UT + 落库 IT |
| CROSS 强平排序「浮亏最大优先」3 仓位场景 | ✅ | Task4 UT（排序逐仓）+ `CrossLiquidationIntegrationTests`（真 DB 3 仓） |
| 实时 MM（fx 变 → MM 变 → ML 变 → 触发） | ✅ | Task3 `DefaultAccountEquityCalculatorTests` UT + Task6 全链 IT |
| close_reason CROSS_STOP_OUT + 账户级通知 | ✅ | Task1（close_reason）+ Task5（CROSS_STOP_OUT_TRIGGERED 通知）IT |
| CROSS 强平高并发 P99 < 500ms | ✅ | 120 用户跌穿 P99≈177ms < 500ms |
| CROSS 强平高并发吞吐 ≥ 100 ops/s + 1000 用户 | ⚠️ 部分 | ≈20 ops/s（WSL 顺序构造限制，实测标注不伪造），1000 规模 + ≥100 ops/s 留正式环境 |
| FX_PAUSED 8 类目 × 3 开关组合行为正确 | ⏳ D3 | C2 已接 allow_open/allow_liquidation 两开关 + 类目降级；完整 8×3 矩阵留 D3 |
| 升级窗口 `isolated_margin` 回填（停服 5min 演练） | ⏳ D3 | D2 不加 `isolated_margin` 列；留 D3 |

#### 通用硬约束

| 通用硬约束 | 状态 | 备注 |
|---|---|---|
| mvn compile + test-compile BUILD SUCCESS | ✅ | trading-core 各 task 实施门禁覆盖 |
| 涉及服务 mvn test 全过 | ✅ | trading-core D2 UT/IT 全绿 + 全量非 IT 305+ 无回归 + ISOLATED IT 18/18 回归绿 |
| 文档同步完成 | ✅ | R8 同步（本 commit，见 §8） |
| Git 回滚点 push 到 main | ⏳ | 本地 main 领先 origin/main（按约定由控制者统一执行） |
| 当前开发计划 §1 阶段收口条目录入 | ✅ | 本 commit（D2 收口，下一步 D3） |

---

## §8. 文档同步清单（R8）

| 文档 | 状态 | 内容 |
|---|---|---|
| `docs/domain/状态机规范.md` | ✅ | §6.6 CROSS 账户级强平流程（账户级 ML + 浮亏最大优先逐仓 + user-level 锁 + close_reason CROSS_STOP_OUT + 逐仓去重账户级通知）+ §6.3 补充实时 MM 精化 |
| `docs/event/Kafka事件规范.md` | ✅ | §12.16 `position.closed`/`liquidation.executed` close_reason 扩 CROSS_STOP_OUT + 逐仓去重 + CROSS_STOP_OUT_TRIGGERED 账户级通知 |
| `docs/architecture/事务与幂等规范.md` | ✅ | §6.4.1 CROSS 强平 user-level Redisson 锁串行 + 逐仓重算闭环 + 锁与 FOR UPDATE 不死锁 + 实时 MM |
| `docs/api/FalconX统一接口文档.md` | ✅ | §3.31.6 CROSS 开仓行为（cross_mode.enabled gate 30088 + CROSS 仓 liqPrice=null + IM 冻结同 ISOLATED + 启用步骤） |
| `docs/database/falconx一期数据库设计.md` | ✅ | §4.3 STAGE-14D2 V35（t_liquidation_log.liquidation_price 改 NULL）+ V36（CROSS_STOP_OUT_TRIGGERED 模板）+ D1 V33/V34 补登 |
| `docs/process/BBook一期完成执行路径.md` | ✅ | §15F STAGE-14D2 收口条目（范围 + CROSS 强平算法 + 测试/PERF + 部署阻断 + D3/E 边界 + cross_mode 启用步骤 + commits） |
| `docs/setup/当前开发计划.md` §1 | ✅ | STAGE-14D2 收口条目 + 下一步指向 D3/E |

---

## §9. 结论

按 [AGENTS.md §8.1.2](../../AGENTS.md) 生产可用判定：

**D2（CROSS 账户级强平排序「浮亏最大优先」+ 实时 MM 精化 + 放开 C1 latent 耦合 + 打开 `cross_mode.enabled` 全链）在 trading-core 后端代码侧已完整实现，并通过 `CrossLiquidationOrchestratorTests` 6 UT（排序逐仓/恢复止步/ML 健康不平/null 不平/锁失败跳过/平 1 仓恢复）+ `CrossLiquidationIntegrationTests` 5 IT（真 DB：CROSS 3 仓浮亏最大优先逐仓平 + close_reason=CROSS_STOP_OUT + biz_type=9 + 账户级通知 + 逐仓去重 + 实时 MM + cross_mode flag gate + PERF）+ 实时 MM UT + CROSS 开仓放行 UT + ISOLATED 强平 IT 18/18 回归绿 + trading 全量非 IT 305+ 无回归 验证。落地了 master §6.3 CROSS 强平流程 + §3.2 D2 实时 MM：CROSS 强平排序「浮亏最大优先」3 仓位场景、实时 MM（fx 变→MM 变→ML 变→触发）、close_reason CROSS_STOP_OUT + 账户级通知、CROSS 强平高并发 P99<500ms 四条 D 阶段验收硬约束（D2 可证部分）已覆盖。CROSS 账户级强平由 user-level Redisson 锁串行化（与单仓 FOR UPDATE 维度正交不死锁），逐仓平后重算账户级 ML 恢复止步，逐仓 POSITION_LIQUIDATED 去重后发一条账户级 CROSS_STOP_OUT_TRIGGERED 汇总通知。**

**🔴 但当前不满足无条件"生产可用"：**

- **剩余阻断项（部署前必须处理，沿 14B/C1/C2/D1 叠加）**：生产/演示库 `falconx_trading` 因 14B root bug 期间 `USE` 污染存在 V28/V29 schema 漂移，下次 `flyway migrate` 将先撞 `Duplicate column` 阻断启动；D2 只新增干净的 V35/V36，不引入新阻断但也不解除既有阻断。**部署前必须先按 §0 修复 14B V28/V29 漂移再 migrate（trading V30-V36），禁止裸跑 migrate。** 另 V35 为计划未预见的 schema 阻断（`t_liquidation_log.liquidation_price` 原 NOT NULL，CROSS 强平 liqPrice=null 撞约束），已由 V35 放开为可空（提级 §0）。
- **`cross_mode.enabled` 生产默认 false**：D2 代码全链就位但开关默认关闭，CROSS 开仓/切换被 30088 gated；生产启用为运维决策（见下「cross_mode 启用步骤」）。
- **范围边界（非阻断，按 §5）**：PERF 吞吐 ≈20 ops/s + 120 用户跌穿因 WSL 顺序构造资源限制未达 master ≥100 ops/s + 1000 用户（P99<500ms 延迟已达标，实测标注不伪造，留正式环境压测）；挂单触发 CROSS 资金校验留后续；FX_PAUSED 8×3 完整验收 / supplement pause gating（30087）/ admin 冷静期/StopOut 配置 UI / 升级窗口 isolated_margin 回填留 D3；三端 UI + 多币聚合 + WS break 切换留 E；真三端跨服务 HTTP E2E（gateway→trading）为 WSL 受限手动项。
- **不满足生产可用的其他原因**：本系统整体仍处 BBook 一期建设中，按 [当前开发计划 §1](../setup/当前开发计划.md) 末条，当前系统不得表述为"生产可用"或"可安全对外公测"。

**cross_mode.enabled 启用步骤（按 §8.1.3）：**

- **前置条件**：目标库已按 §0 完成 14B V28/V29 漂移修复 + trading V30-V36 migrate；CROSS 全链代码（开仓 + 账户级强平 + 实时 MM）已部署。
- **启用动作**：由 admin 经 risk-switch 接口（`RedisTradingRiskSwitchCache` 对应的 `cross_mode.enabled` 风控开关）将 `cross_mode.enabled` 置 true（运维决策）。
- **生效行为**：开关 true 后，用户可切换到 CROSS（解除 30088 gate）、CROSS 账户可开仓（CROSS 仓 liqPrice=null）；`QuoteDrivenEngine` 每 tick 对受影响 CROSS 用户调 `CrossLiquidationOrchestrator` 按账户级 MarginLevel（实时 MM）判定，跌穿 stopOut（默认 30%）即浮亏最大优先逐仓强平直到恢复。
- **回退**：将 `cross_mode.enabled` 置 false 即重新 gate CROSS 开仓/切换（已是 CROSS 模式的存量账户行为另需运营评估，D2 仅控开仓/切换入口）。
- **预期结果**：CROSS 3 仓账户级 ML 跌穿 → 浮亏最大优先逐仓平直到 ML 恢复 → 逐仓 LIQUIDATED + biz_type=9 + t_liquidation_log（liqPrice=NULL）+ close_reason=CROSS_STOP_OUT → 一条账户级 CROSS_STOP_OUT_TRIGGERED 站内信（marginLevel/count/symbols）。

**下一步**：

- **STAGE-14D3**：admin 冷静期配置 UI（60s-7d）+ supplement pause gating（30087）+ StopOut 阈值 admin 可配 UI + FX_PAUSED 8 类目×3 开关组合完整验收 + 升级窗口 `isolated_margin` 回填（停服 5min 演练）。
- **STAGE-14E**：三端 UI（客户端 mode toggle + MarginLevel 浮窗 + 双币 PnL）+ admin 多币种聚合 + WebSocket break 字段最终切换 + admin FX rate 监控 page。
