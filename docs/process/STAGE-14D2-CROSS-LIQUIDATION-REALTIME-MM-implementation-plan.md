# STAGE-14D2 CROSS 账户级强平 + 实时 MM 精化 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development，每 task 实施→spec 评审→代码质量评审→收口。CROSS 强平 + user-level 锁是 STAGE-14 最高风险算法，核心 task（Task 4）必须完整两阶段评审。
>
> **R 角色映射**：R4 业务后端（Task 1-6）+ R6 测试（Task 6）+ R7/R8（Task 7）。纯 trading-core 后端切片（console mode 配置 UI 留 D3，客户端 UI 留 E）。

**Goal:** 启用 CROSS 用户级保证金模式——账户级 MarginLevel ≤ stopOut 时按浮亏绝对值最大优先逐仓强平直到恢复；MM 随 FX 实时重算（master §3.2 D2 全实时）；放开 C1 latent 耦合；user-level 串行防并发竞态；打开 cross_mode.enabled。

**Architecture:** 复用 C1 已实现的 `AccountEquityCalculator.computeAccountMarginLevel`（账户级 Equity=balance+frozen+ΣuPnL / totalMM=ΣMM）。新建 `CrossLiquidationOrchestrator`（账户级触发 + 排序 + 逐仓平 + 重算 + 止步 + Redisson user-level 锁 + CROSS_STOP_OUT 通知）。`QuoteDrivenEngine` 对 CROSS 账户 tick 按 userId 聚合（listOpenByUserId + 各 symbol quote）调账户级评估。放开 `closePositionByTrigger` 对 CROSS_STOP_OUT 跳过二次价格校验。实时 MM：`maintenanceMargin` 用实时 fx（mmRate 冻结），FX 不可用降级 entryFxRate。CROSS 开仓放开（cross_mode.enabled）+ liquidationPrice=null。

**Tech Stack:** Spring Boot 4.0.5 / MyBatis / Kafka Outbox / Redisson（分布式锁）/ JDK 25。

**前置阅读：** master §3.2（CROSS Equity/MarginLevel + 实时 MM + CROSS 强平价 NULL）/§6.3（CROSS 强平流程 + user-level 锁）/§6.6（close_reason 扩 CROSS_STOP_OUT）/§7.5（Kafka close_reason）/§8.3 D 验收。C1 R7 报告（Finding 2 + latent 耦合）+ D1 R7（cross_mode.enabled gate）。

**关键现状（code-explorer 实查，据此实施）：**
- `DefaultAccountEquityCalculator.computeAccountMarginLevel`（账户级 Equity/totalMM/ML）**已完整实现**（C1 Task 7），`PositionMarkInput(position, effectiveMarkPrice, quoteCurrency)` 入参就绪；缺 CROSS tick 调用路径 + 强平编排。`AccountMarginState{equity, totalMaintenanceMargin, marginLevel}`。
- `QuoteDrivenEngine.processTick` 按 symbol 遍历（`listOpenBySymbol`）；CROSS 需按 userId 聚合（`openPositionSnapshotStore.listOpenByUserId(userId)` 已存在 + 各 symbol quote 从 `tradingQuoteSnapshotRepository`）。`AccountMarginStateCache`（1s TTL，invalidate(userId)）。**user-level 锁缺失**（`SymbolPartitionedPriceTickExecutor` 按 symbol hash 分区，同用户多 symbol 并发）。
- `TradingPositionCloseApplicationService.closePositionByTrigger`（约 L211）二次校验 `positionTriggerRuleEvaluator.evaluate` 对 CROSS 仓（liquidationPrice=null）返回 null → 静默吞。`settlePositionExit`（L301）`liquidation = (closeReason == LIQUIDATION)`。
- `DefaultTradingRiskService.evaluateMarketOrder`（L151）`marginMode != ISOLATED → reject MARGIN_MODE_NOT_SUPPORTED`（未读 riskSwitch）。`MarginModeSwitchApplicationService` 已用 `riskSwitchCache.isEnabled(KEY_CROSS_MODE_ENABLED, false)` gate 切换。
- `DefaultAccountEquityCalculator.maintenanceMargin`（L109-121）用 `entryFxRate`（冻结）+ `mmRateAtOpen`（冻结）。
- `TradingPositionCloseReason{MANUAL,TAKE_PROFIT,STOP_LOSS,LIQUIDATION}`（无 CROSS_STOP_OUT）。close_reason 存 VARCHAR 枚举名。
- 不需 isolated_margin 列（CROSS 账户级 + liqPrice=null；CROSS/ISOLATED 不混仓，切换闸门 30080 保证）。
- Flyway 最新 V34 → D2 用 V35（通知模板，实查复核）。
- Redisson 已在技术栈（Redisson 4.3.0）；找现有分布式锁用法（grep RLock/redisson）。
- Flyway 不写 USE。

---

## Tasks

### Task 1: CROSS_STOP_OUT 枚举 + 放开 latent 耦合
**Files:** `entity/TradingPositionCloseReason.java`（+CROSS_STOP_OUT）+ `TradingPositionCloseApplicationService`（closePositionByTrigger 二次校验放开 + settlePositionExit liquidation 判断扩）+ UT
- `TradingPositionCloseReason` 加 `CROSS_STOP_OUT`。
- `closePositionByTrigger(positionId, closeReason, snapshot)`：当 `closeReason == CROSS_STOP_OUT` 时**跳过二次价格校验**（账户级触发，不查单仓 liqPrice/SL/TP）；其余 reason 保持二次校验（C1 行为不变）。
- `settlePositionExit`：`liquidation = (closeReason == LIQUIDATION || closeReason == CROSS_STOP_OUT)` → 走 LIQUIDATED 状态 + LIQUIDATION trade type。
- `publishPositionNotification` 的 reason switch 加 CROSS_STOP_OUT 分支（Task 5 完善通知，本 task 先不漏分支编译）。
- UT：CROSS_STOP_OUT 强平跳过二次校验成功平仓（liqPrice=null 仍平）；LIQUIDATION/MANUAL 二次校验不变（回归）；CROSS_STOP_OUT → LIQUIDATED 状态。
- **Commit:** `feat(trading): STAGE-14D2 Task 1 CROSS_STOP_OUT close_reason + closePositionByTrigger 对 CROSS_STOP_OUT 放开二次价格校验（latent 耦合）+ settlePositionExit liquidation 判断扩 + UT`

### Task 2: CROSS 开仓放开（cross_mode.enabled）+ liquidationPrice=null
**Files:** `DefaultTradingRiskService.evaluateMarketOrder`（注入 riskSwitchCache）+ `LiquidationPriceCalculator`/开仓 position 构造 + UT
- 注入 `RedisTradingRiskSwitchCache` 到 `DefaultTradingRiskService`。
- L151 改：`marginMode == CROSS`：若 `!riskSwitchCache.isEnabled(cross_mode.enabled, false)` → reject `CROSS_MODE_NOT_ENABLED`（30088）；enabled → **放行 CROSS 开仓**。`marginMode` 其它非 ISOLATED/CROSS 值 → 既有 reject。
- CROSS 仓开仓：`liquidationPrice = null`（master §3.2 CROSS 不存单仓 liqPrice，账户级触发）。tier/IM/fee 校验同 ISOLATED（IM 仍冻结 frozen）。开仓 position 写 marginMode=CROSS + liquidationPrice=null。
- UT：cross_mode.enabled=false + CROSS 开仓 → 30088；enabled=true + CROSS 开仓 → 通过 + position.liquidationPrice=null + marginMode=CROSS + frozen=IM；ISOLATED 开仓不受影响（回归）。
- **Commit:** `feat(trading): STAGE-14D2 Task 2 CROSS 开仓放开（cross_mode.enabled gate）+ CROSS 仓 liquidationPrice=null + UT`

### Task 3: 实时 MM 精化
**Files:** `DefaultAccountEquityCalculator.maintenanceMargin`（实时 fx）+ `AccountEquityCalculator` 签名 + UT/既有 IT 验证
- `maintenanceMargin(position, quoteCurrency)`：`MM(AC) = quantity × entryPrice × mmRateAtOpen × fx(QC→AC)`，**fx 用实时 `fxRateService.queryRate(quoteCurrency, accountCurrency)`**（替换 entryFxRate）；mmRate 仍冻结 mmRateAtOpen。
- **FX 不可用降级**：queryRate empty → 用 `position.entryFxRate()`（最后已知，master §3.5.5 用最后 rate，不停 MM）+ warn。
- 影响 ISOLATED（computePositionMarginLevel）+ CROSS（computeAccountMarginLevel）——master D2「全部实时」含两者。
- **验证 C1 ISOLATED IT 不破**：C1 IT seed 了正确 fx（EURAUD 0.65），实时取同值，MarginLevel 结果应一致；运行 `TradingLeverageTierStopOutIntegrationTests` 确认全绿；若因 FX 缓存时机不稳定则在测试 seed fx 后确保 queryRate 命中。
- UT：异币种 MM 用实时 fx（fx 变 → MM 变）；FX 不可用 → 降级 entryFxRate；同币种 fx=1。
- **Commit:** `feat(trading): STAGE-14D2 Task 3 实时 MM 精化（maintenanceMargin 用实时 fx，mmRate 冻结，FX 不可用降级 entryFxRate）+ UT + ISOLATED IT 回归`

### Task 4: CROSS 账户级强平编排（核心，最高风险）
**Files:** `application/CrossLiquidationOrchestrator.java`（新）+ `QuoteDrivenEngine`（CROSS 账户 tick 聚合接入）+ user-level Redisson 锁 + UT/IT
- **`CrossLiquidationOrchestrator.evaluateAndLiquidate(userId, accountCurrency)`**（master §6.3）：
  1. **Redisson user-level 锁** `cross-liq:{userId}`（tryLock，防同用户多 symbol tick 并发；获取不到则跳过本次，下个 tick 再评估——避免阻塞 tick 线程）。
  2. 取该账户所有 OPEN 持仓（listOpenByUserId）+ 各 symbol 最新 quote（effectiveMarkPrice + quoteCurrency from SymbolSpec）。
  3. `computeAccountMarginLevel` → 账户级 ML。
  4. ML > stopOut（t_risk_config，默认 0.30）→ 不强平，返回。
  5. ML ≤ stopOut：按 `|uPnL_i(AC)|` 降序排序持仓 → 逐仓 `closePositionByTrigger(positionId, CROSS_STOP_OUT, snapshot)` → 每平一仓 invalidate(userId) + 重算 `computeAccountMarginLevel`（剩余持仓）→ ML > stopOut 即 break。
  6. 收集强平仓位清单 → 发 CROSS_STOP_OUT_TRIGGERED 通知（Task 5）。
- **`QuoteDrivenEngine.processTick` 接入**：遍历持仓时，`position.marginMode() == CROSS` → 调 `crossLiquidationOrchestrator.evaluateAndLiquidate(userId, ...)`（每个 CROSS 用户每 tick 至多评估一次；用 set 去重同 tick 内同 userId）。ISOLATED 仓走既有单仓路径（C1）不变。
  - 注意：CROSS 与 ISOLATED 不混账户（切换闸门保证），故按 position.marginMode 分流安全。
- **user-level 锁（R1）**：CROSS 强平编排全程在 `cross-liq:{userId}` 锁内（账户级评估 + 逐仓平 + 重算原子串行），防并发竞态。`closePositionByTrigger` 内 FOR UPDATE 仍兜底单仓重复。
- UT（mock）：账户 ML≤30% → 排序逐仓平 + 重算 + 止步（3 仓场景：平第 1 大亏仓后 ML 恢复则停，不平第 2/3）；ML>30% 不平；锁获取失败跳过。
- IT（真 DB）：CROSS 账户 3 仓不同 uPnL → tick 触发 → 浮亏最大优先平 + 逐仓直到恢复 + biz_type=9 落账 + close_reason=CROSS_STOP_OUT；并发多 symbol tick 不重复平（FOR UPDATE + 锁）。
- **Commit:** `feat(trading): STAGE-14D2 Task 4 CrossLiquidationOrchestrator 账户级 MarginLevel 触发 + 浮亏最大优先逐仓强平直到恢复 + Redisson user-level 锁 + QuoteDrivenEngine CROSS 接入 + UT/IT`

### Task 5: CROSS_STOP_OUT 通知 + Kafka close_reason
**Files:** `V35__seed_cross_stop_out_notification.sql` + 通知发送（含仓位清单）+ Kafka close_reason 确认 + UT/IT
- V35 seed 通知模板 CROSS_STOP_OUT_TRIGGERED（title「账户强制平仓」，body 含 `${marginLevel}` + 强平仓位数/清单 `${positions}`；按 V31/V34 真实列）。
- `CrossLiquidationOrchestrator` 强平完成后发 `notificationService.send("CROSS_STOP_OUT_TRIGGERED", userId, ..., {marginLevel, positions: 强平 symbol 清单}, "ACCOUNT", accountId, null)`。
- Kafka：`closePositionByTrigger` 强平已发 position.closed outbox（buildLiquidationOutbox），close_reason=CROSS_STOP_OUT 自动随枚举名带出（确认 outbox payload 序列化）。
- UT/IT：CROSS 强平 → countNotificationByUserIdAndType("CROSS_STOP_OUT_TRIGGERED")==1 + payload 含仓位清单 + Kafka close_reason=CROSS_STOP_OUT。
- **Commit:** `feat(trading): STAGE-14D2 Task 5 CROSS_STOP_OUT_TRIGGERED 通知（仓位清单）+V35 模板 + Kafka close_reason=CROSS_STOP_OUT + UT/IT`

### Task 6: CROSS IT 全链 + 实时 MM + PERF + 打开 cross_mode.enabled
**Files:** `CrossLiquidationIntegrationTests` + PERF + cross_mode.enabled 启用方式
- IT：cross_mode.enabled=true（测试内开 risk switch）→ CROSS 开仓（liqPrice=null）→ 多仓不同 uPnL → 账户级跌穿 → 浮亏最大优先逐仓强平直到恢复 → close_reason=CROSS_STOP_OUT + 通知 + Kafka；ML>30% 不强平；实时 MM（fx 变 → ML 变 → 触发）。
- PERF（master §8.3 D，WSL 资源受限则记实测 + 标注）：N 用户（WSL 取可行规模如 100-200，非 1000）CROSS 账户同时跌穿 → 强平吞吐 + 单仓强平 P99（目标 <500ms，记实测）。
- cross_mode.enabled 启用：测试经 risk switch 写开；**生产默认 false**（D2 验收后由 admin 经 risk-switch 接口开，R7 标注运维步骤——D2 代码就位但生产启用是运维决策）。
- **Commit:** `test(trading): STAGE-14D2 Task 6 CROSS 强平全链 IT（多仓排序+逐仓直到恢复+CROSS_STOP_OUT+实时MM）+ PERF + cross_mode.enabled 启用验证`

### Task 7: R8 文档 + R7 收口 + 计划录入 + push
**Files:** docs（状态机 CROSS 强平/close_reason、Kafka、实时 MM 事务）+ R7 报告 + 计划
- R8：`状态机规范`（§6.3 CROSS 强平流程 + close_reason CROSS_STOP_OUT）；`Kafka事件规范`（position.closed close_reason 扩 + CROSS_STOP_OUT 通知）；`事务与幂等规范`（CROSS 强平 user-level 锁串行 + 实时 MM）；`FalconX统一接口文档`（CROSS 开仓行为）；BBook §15D2。
- R7 收口报告（验收：CROSS 强平排序 3 仓 + 实时 MM + close_reason + PERF；沿部署阻断项）+ 计划 §1 录入 + 下一步 D3。
- push origin main（控制者统一）。
- **Commit:** `docs(R7+R8): STAGE-14D2 CROSS 强平 + 实时 MM 收口报告 + 文档同步 + 计划录入（下一步 D3 console mode 配置 UI + FX_PAUSED 8×3）`

---

## Self-Review
1. **Spec 覆盖**：master §3.2（CROSS Equity/ML + 实时 MM + liqPrice NULL）→ Task 2/3/4；§6.3（CROSS 强平 + user-level 锁）→ Task 4；§6.6/§7.5（close_reason CROSS_STOP_OUT + Kafka）→ Task 1/5；§8.3 D（排序 3 仓 + PERF）→ Task 6 ✅。FX_PAUSED 8×3 / console mode 配置 UI = D3；客户端 UI = E。
2. **类型一致**：CROSS_STOP_OUT（reason）、CrossLiquidationOrchestrator、AccountMarginState 在各 task 一致。
3. **依赖顺序**：Task 1（放开 latent + 枚举）→ Task 4（CROSS 编排依赖放开）；Task 3（实时 MM）独立可并；Task 2（CROSS 开仓）→ Task 6（IT 需开仓）。

## 关键设计决策（plan 锁定）
- **user-level 锁**：Redisson `cross-liq:{userId}` tryLock（防并发竞态，R1）；获取失败跳过本 tick（不阻塞）。
- **实时 MM 降级**：FX 不可用用 entryFxRate（最后已知，不停 MM，master §3.5.5）。
- **latent 耦合放开**：仅 CROSS_STOP_OUT reason 跳过二次校验，LIQUIDATION/MANUAL 不变（C1 ISOLATED 行为零影响）。
- **CROSS 不混 ISOLATED**：账户级 margin_mode，切换闸门保证，processTick 按 position.marginMode 分流。
- **cross_mode.enabled 生产默认 false**：D2 代码就位，生产启用 = admin 运维决策（R7 标注）。
- **不加 isolated_margin 列**：CROSS 账户级 + liqPrice=null 不需要。

## 实施风险
- **R1 user-level 锁**（最高）：CROSS 并发强平竞态 → Redisson 锁 + FOR UPDATE 双保险 + IT 并发验证。
- **R2 CROSS tick 聚合**：每 tick 取用户所有 symbol quote → account 缓存 + quote snapshot；CROSS 账户仓数有限。
- **R3 实时 MM 波及 ISOLATED IT**：Task 3 验证 C1 IT 全绿（seed fx 同值）。
- **R4 CROSS_STOP_OUT DB 兼容**：settlePositionExit liquidation 判断必须扩（Task 1），否则走 CLOSED 而非 LIQUIDATED。
- Flyway 不写 USE；V35 实查顺延。

## 下一步（D2 后）
- **D3**：console mode 冷静期/StopOut 阈值配置 UI + FX_PAUSED 8 类目×3 开关完整验收 + supplement pause gating（30087）。
- **E**：三端 UI（客户端 mode toggle + MarginLevel 浮窗 + 双币 PnL + WebSocket break 字段切换 + admin 多币聚合）。

— END —
