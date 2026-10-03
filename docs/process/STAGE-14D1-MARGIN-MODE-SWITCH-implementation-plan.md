# STAGE-14D1 用户级 margin mode 切换闸门 + 冷静期 + supplement 收口 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development，每 task 实施→spec 评审→代码质量评审→收口。
>
> **R 角色映射**：R2 契约（Task 1-2）+ R4 业务后端（Task 3-5）+ R6 测试（Task 6）+ R7/R8（Task 6）。纯 trading-core 后端切片（CROSS 强平/实时 MM 留 D2，console mode 配置 UI 留 D3，客户端 mode toggle 留 E）。

**Goal:** 用户可通过 REST 在 ISOLATED/CROSS 间切换 margin mode（切换闸门 4 项 + 5min 冷静期 + Kafka/通知）；CROSS 目标在 D2 强平就位前由 feature flag gate；supplement-margin 收口对齐 master 错误码。

**Architecture:** V33 给 t_account 加 mode_changed_at/mode_cooling_until（margin_mode 列 V11 已存在）；新建 `/api/v1/me/margin-mode` GET/POST + 切换闸门（无 OPEN 持仓 30080 / 无 ACTIVE 开仓挂单 30081 / 不在冷静期 30082 / 与目标不同 30083）+ 冷静期 properties 5m + CROSS gating（risk switch `cross_mode.enabled` 默认 false → 未开时切 CROSS 拒 30088）；切换发 Kafka `falconx.trading.account.mode.changed`（Outbox）+ 通知 ACCOUNT_MODE_CHANGED；supplement-margin 复用现有实现 + 补错误码 30085/30086 + /me/ 路径 wrapper。

**Tech Stack:** Spring Boot 4.0.5 / MyBatis / Kafka Outbox / JDK 25。

**前置阅读：** master §6.1（mode 切换状态机）/§7.4（REST）/§7.3（错误码 30080-30088）/§7.1-7.2（Kafka topic + payload）/§4.2（t_account 字段）。

**关键现状（code-explorer 实查，据此实施）：**
- `t_account.margin_mode` 列 **V11 已存在**（TINYINT，CROSS=1/ISOLATED=2，`TradingMarginMode` 枚举存在）；缺 mode_changed_at/mode_cooling_until。`updateTradingAccount` 当前**不 UPDATE margin_mode**（仅 INSERT 时设 ISOLATED）。Flyway 最新 **V32** → D1 用 **V33**（实查复核）。
- `/api/v1/me/*` 用 `@RequestHeader("X-User-Id") Long userId`（gateway 注入，无 JWT 自解析）。范例 `UserWithdrawController` / `TradingAccountController`。
- **supplement-margin 已完整实现**：`TradingPositionController POST /api/v1/trading/positions/{id}/margin` → `TradingPositionMarginApplicationService.addIsolatedMargin` → `DefaultTradingAccountService.supplementIsolatedMargin`（marginUsed+=amount，写 biz_type=10 ISOLATED_MARGIN_SUPPLEMENT）+ `position.supplementMargin`（margin+=，重算 liqPrice）。**t_position.margin 字段即逐仓保证金累计（IM+supplement），等价 master isolated_margin，D1 不加独立 isolated_margin 列**（CROSS/ISOLATED 仓区分留 D2）。
- 闸门查询：`TradingPositionRepository.findOpenByUserId`（有）；`TradingPendingOrderTriggerRepository.countUserOpeningPending`（有，仅开仓挂单不含 SL/TP——SL/TP 必伴随 OPEN 持仓，已被 30080 覆盖）。
- Outbox：`TradingOutboxRepository.save` + `KafkaTradingOutboxEventPublisher.resolveTopic`（eventType=`trading.account.mode.changed` → topic `falconx.trading.account.mode.changed`，无需改 resolveTopic）。contract 加 `AccountMarginModeChangedEventPayload`。
- 通知：V31 模板 seed 模式；ACCOUNT_MODE_CHANGED 模板 seed。
- 错误码 30080-30086 空白可用；30087 已用（GLOBAL_PAUSE_ACTIVE）；CROSS gating 用 30088 CROSS_MODE_NOT_ENABLED（新）。30084 master 标通知类，D1 不作错误码（mode 切换不涉及挂单自动撤销，那是 D 的 pending 触发场景，本切片不含）。
- CROSS gating：`TradingRiskSwitch.ALLOWED_KEYS` 现仅 `auto_liquidate.enabled`；加 `cross_mode.enabled`（默认 false）。`evaluateMarketOrder` 已拒 CROSS 开仓（MARGIN_MODE_NOT_SUPPORTED）。
- 冷静期配置：`TradingCoreServiceProperties` 加 `marginMode.coolingDuration`（默认 5m，照搬 withdraw.coolingDuration，不依赖 config SDK）。
- IT：@SpringBootTest(MOCK) + @ActiveProfiles("stage5") + 真 MySQL falconx_trading_it + MockMvc + header X-User-Id（范例 `TradingControllerIntegrationTests`）。
- Flyway 不写 USE（14B root bug 教训）。

---

## Tasks

### Task 1: V33 t_account 冷静期列 + 实体/mapper 串入 + updateMarginMode
**Files:** `db/migration/V33__account_mode_switch_columns.sql`(+docs/sql) + `TradingAccount`/`TradingAccountRecord`/`TradingAccountMapper.xml`/`MybatisTradingAccountRepository`
- V33（不写 USE，实查版本顺延）：`ALTER TABLE t_account ADD COLUMN mode_changed_at DATETIME(3) NULL, ADD COLUMN mode_cooling_until DATETIME(3) NULL;`（margin_mode V11 已有，不重复）。
- `TradingAccount` 实体加 `modeChangedAt`/`modeCoolingUntil`（OffsetDateTime/LocalDateTime 对齐既有时间字段类型）；record + resultMap arg 顺序串入；所有 `new TradingAccount(` 构造点补参（grep 定位）。
- `TradingAccountMapper.xml` 加 `updateMarginMode`（SET margin_mode=#{code}, mode_changed_at=#{...}, mode_cooling_until=#{...}, updated_at + WHERE account_id/version）+ Repository `switchMarginMode` 方法。
- UT/轻量 IT（真 DB：updateMarginMode 落库 + 读回）。
- **Commit:** `feat(trading): STAGE-14D1 Task 1 V33 t_account 加 mode_changed_at/mode_cooling_until + 实体/record/XML 串入 + updateMarginMode + UT/IT`

### Task 2: 错误码 + contract payload
**Files:** `TradingErrorCode`（+30080-30083/30085/30086/30088）+ `falconx-trading-contract` `AccountMarginModeChangedEventPayload`
- 错误码：30080 MODE_HAS_OPEN_POSITIONS / 30081 MODE_HAS_ACTIVE_PENDING / 30082 MODE_COOLING_PERIOD_ACTIVE / 30083 MODE_NO_CHANGE / 30085 POSITION_NOT_ISOLATED / 30086 SUPPLEMENT_AMOUNT_INVALID / 30088 CROSS_MODE_NOT_ENABLED。
- contract record `AccountMarginModeChangedEventPayload(Long userId, String oldMode, String newMode, long changedAtMillis, Long coolingUntilMillis)`（master §7.2）。
- **Commit:** `feat(trading): STAGE-14D1 Task 2 mode 切换错误码 30080-30088 + AccountMarginModeChangedEventPayload contract`

### Task 3: mode 切换 ApplicationService + REST（闸门 + 冷静期 + CROSS gating + 状态机）
**Files:** `application/MarginModeSwitchApplicationService.java`（新）+ `controller/UserMarginModeController.java`（新）+ `TradingCoreServiceProperties`（marginMode.coolingDuration）+ `TradingRiskSwitch`（cross_mode.enabled）+ UT
- `GET /api/v1/me/margin-mode`：返回 `{currentMode, modeChangedAt, coolingUntil, canSwitch, blockers[]}`（master §7.4）。
- `POST /api/v1/me/margin-mode` body `{targetMode}`：
  - 闸门（SELECT FOR UPDATE account，事务）：targetMode==current → 30083；targetMode==CROSS 且 `!riskSwitch.isEnabled("cross_mode.enabled")` → **30088 CROSS_MODE_NOT_ENABLED**（D1 默认 gate）；findOpenByUserId 非空 → 30080；countUserOpeningPending>0 → 30081；mode_cooling_until!=null 且 >now → 30082。
  - pass → `switchMarginMode`（UPDATE margin_mode + mode_changed_at=now + mode_cooling_until=now+coolingDuration）+ 发 Outbox `trading.account.mode.changed` + 通知 ACCOUNT_MODE_CHANGED。
  - 冷静期 coolingDuration 从 properties（默认 5m）。
- UT（mock repo/switch）：4 闸门各拒 + CROSS gate 30088 + 正常切换（cross_mode.enabled=true 时 ISOLATED→CROSS 成功）+ 冷静期重入拒。
- **Commit:** `feat(trading): STAGE-14D1 Task 3 /api/v1/me/margin-mode 切换闸门(30080-30083)+5min冷静期+CROSS gating(30088)+状态机 + UT`

### Task 4: Kafka account.mode.changed + 通知模板
**Files:** Outbox 发布接线（Task 3 内调）+ `db/migration/V34__seed_account_mode_changed_notification.sql`（通知模板）+ Kafka 规范确认 + UT/IT
- 切换成功后 `TradingOutboxRepository.save(eventType="trading.account.mode.changed", payload=AccountMarginModeChangedEventPayload JSON)`。
- V34 seed 通知模板 ACCOUNT_MODE_CHANGED（参考 V31 模板列：title_template/body_template/level/channels/enabled；占位 `${oldMode}`/`${newMode}`）。
- IT：切换 → countOutboxByEventType("trading.account.mode.changed")==1 + 通知写入。
- **Commit:** `feat(trading): STAGE-14D1 Task 4 mode 切换发 Kafka falconx.trading.account.mode.changed(Outbox)+V34 ACCOUNT_MODE_CHANGED 通知模板 + IT`

### Task 5: supplement-margin 收口（错误码对齐 + /me/ 路径）
**Files:** `TradingPositionMarginApplicationService`（错误码 30085/30086）+ `UserMarginModeController` 或新 `/api/v1/me/positions/{id}/supplement-margin` wrapper（复用现有 service）+ UT
- 现有 supplement 实现已就位；补：非 OPEN/非 ISOLATED 仓 → 30085 POSITION_NOT_ISOLATED；amount≤0/非法 → 30086 SUPPLEMENT_AMOUNT_INVALID（若现有用别的 reason，对齐 master 码）。
- master §7.4 路径 `/api/v1/me/positions/{id}/supplement-margin`：加 wrapper 复用 `addIsolatedMargin`（或确认现有 `/api/v1/trading/positions/{id}/margin` 即满足，与 R1 对齐——本计划默认加 /me/ wrapper 对齐 master 路径，复用同一 ApplicationService 不重复逻辑）。
- UT/IT：supplement 成功 + 30085/30086。
- **Commit:** `feat(trading): STAGE-14D1 Task 5 supplement-margin 收口（30085/30086 错误码 + /api/v1/me/positions/{id}/supplement-margin 复用现有 service）+ UT/IT`

### Task 6: IT 汇总 + R8 文档 + R7 收口 + 计划录入 + push
**Files:** `MarginModeSwitchIntegrationTests` + docs（REST 接口/状态机/Kafka/错误码）+ R7 报告 + 计划
- IT：4 闸门拒绝 + CROSS gate 30088 + cross_mode.enabled 开启后正常切换 + 冷静期重入 + Outbox 事件 + supplement。
- R8：`FalconX统一接口文档`（/me/margin-mode + supplement-margin + 错误码）；`状态机规范`（§6.1 mode 切换闸门，注明 CROSS gated 到 D2）；`Kafka事件规范`（account.mode.changed topic + payload）；BBook §15D1。
- R7 收口报告（验收：4 闸门 + 冷静期 + CROSS gating + supplement；沿部署阻断项提示）+ 计划 §1 录入 + 下一步 D2。
- push origin main（控制者统一）。
- **Commit:** `docs(R7+R8): STAGE-14D1 收口报告 + 文档同步 + 计划录入（mode 切换闸门/冷静期/CROSS gating/supplement，下一步 D2 CROSS 强平+实时 MM）`

---

## Self-Review
1. **Spec 覆盖**：master §6.1（闸门 4 项 + 冷静期 + Kafka + 通知）→ Task 3/4；§7.4（REST）→ Task 3/5；§7.3（错误码）→ Task 2；§7.1-7.2（Kafka）→ Task 4；§4.2（t_account 列）→ Task 1 ✅。CROSS 强平/实时 MM = D2；console mode 配置 UI = D3；客户端 toggle = E。
2. **类型一致**：TradingMarginMode（既有 CROSS/ISOLATED）、AccountMarginModeChangedEventPayload、错误码 30080-30088 在各 task 一致。
3. **break 面**：TradingAccount 加 2 字段（构造点同步，Task 1）。

## 关键设计决策（plan 锁定）
- **不加 isolated_margin 列**：t_position.margin 已等价（IM+supplement），CROSS/ISOLATED 仓区分留 D2。
- **CROSS gating**：D1 切 CROSS 默认拒 30088（risk switch cross_mode.enabled 默认 false），测试可开 flag 验证真正切换；D2 强平就位后 admin 开。
- **冷静期**：properties 5m（不依赖 config SDK，照搬 withdraw）；admin 可配留 D3（console mode 配置 UI）。
- **supplement 复用**：不重复造，补错误码 + /me/ wrapper。
- **闸门挂单**：findOpen（30080）+ countUserOpeningPending（30081）；SL/TP 必伴随持仓已被 30080 覆盖。

## 实施风险
- Flyway 不写 USE；V33 实查版本顺延。
- TradingAccount 加字段 break 构造点（grep 全补）。
- CROSS gating 确保 D1 不让用户进入无强平保护的 CROSS。

## 下一步（D1 后）
- **D2**：CROSS 账户级强平（浮亏最大优先逐仓）+ 实时 MM 精化（放开 C1 latent 耦合 closePositionByTrigger 二次校验）+ CROSS 高并发 PERF + 开放 cross_mode.enabled。
- **D3**：console mode 冷静期/StopOut 配置 UI + FX_PAUSED 8×3 完整验收。
- **E**：三端 UI（客户端 mode toggle + MarginLevel 浮窗 + 双币 PnL + WebSocket break 切换）。

— END —
