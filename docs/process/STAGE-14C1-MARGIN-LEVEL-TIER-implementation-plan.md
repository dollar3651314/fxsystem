# STAGE-14C1 杠杆/MM 分级 Tier + 账户 MarginLevel + 30% StopOut 强平升级 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development 按 task 执行；每个 task 实施→spec 合规评审→代码质量评审→收口。
>
> **FalconX R 角色映射**：R2 契约（tier 表/错误码/通知模板，Task 1-3）+ R4 业务后端（Task 4-10）+ R6 测试（Task 11）+ R7 验证（Task 12-13）+ R8 文档（Task 12）。本计划为 **C1：trading-core 纯后端核心**，console tier CRUD 三端 UI = C2 单独计划。

**Goal:** trading-core 引入按 (symbol, group_code, notional) 的杠杆/MM 双重分级 Tier、账户级 Equity/MarginLevel 实时计算、30% StopOut 强平升级（与既有单仓 liqPrice 双触发），使非 USD/高杠杆滥用场景下的风控真实生效。

**Architecture:** 新增 `t_symbol_leverage_tier` 表（V30）+ build-time 生成的 seed；`t_risk_config` 加 stop_out_level/margin_call_level（V31）+ `t_fx_pause_behavior` 表；`t_position` 加 mm_rate_at_open/tier_no_at_open 冻结列（V32）。新组件 `LeverageTierResolver`（按 notional 解析档位，30s 内存缓存）/ `AccountEquityCalculator`（ISOLATED 单仓 Equity）/ `MarginLevelMonitor`（tick 驱动算 MarginLevel + 阈值判定 + StopOut/MarginCall 触发 + 5min 节流）。开仓风控接 tier（替换硬码 mmRate）；`QuoteDrivenEngine` 每 tick 对受影响用户重算 MarginLevel，≤ stopOut 触发强平。

**Tech Stack:** Spring Boot 4.0.5 / MyBatis Plus 3.5.15 / Kafka 4.2.0 / Redisson / JDK 25。

**前置阅读：**
- `docs/design/STAGE-14-MULTICURRENCY-AND-CROSS-MARGIN-MASTER-design.md` §3.2（公式：MM/Equity/MarginLevel/强平价）/§4.2（t_symbol_leverage_tier、t_risk_config、t_fx_pause_behavior schema）/§5（10 tier 模板 + 映射规则）/§6.2-6.3（MarginLevel 状态机 + 强平流程）/§7.3（错误码 30070-30072/30087）/§8.3 C 阶段验收硬约束。
- `docs/test/STAGE-14B-CURRENCY-CONVERTER-R7-verification-report.md`（B 阶段产出：MarginResult/账户币口径/FX 真源 FxRateService/账本三列）。
- AGENTS.md §3.2/§3.3/§3.4/§3.9。

**关键现状（code-explorer 已实查，据此实施）：**
- 开仓 `DefaultTradingRiskService.evaluateMarketOrder`：maxLeverage=min(SymbolSpec, riskConfig)；mmRate 硬码 `properties.getMaintenanceMarginRate()=0.005`（**C1 改为 tier 解析**）；group_code 来自 header `command.resolvedGroupCode()`（兜底 "default"），开仓冻结到 `t_position.group_code_at_open`（V27）。notional=fillPrice×qty（fillPrice 含 markup）。reject(reason) → Controller 映射 HTTP 码。
- 强平：`QuoteDrivenEngine.processTick` 遍历 `openPositionSnapshotStore.listOpenBySymbol(symbol)` → `PositionTriggerRuleEvaluator.evaluate`（**仅单仓 liqPrice 比较，无账户维度**）→ `closePositionByTrigger`（Task 9b 已接 FX）。
- `LiquidationPriceCalculator.calculate(side, entryPrice/fillPrice, quantity, margin, mmRate)`（5 参，mmRate 现传 properties）。
- Equity/MarginLevel **完全不存在**；`TradingAccount{balance,frozen,marginUsed,currency,marginMode}`，`available()=balance-frozen-marginUsed`，无 equity/uPnL 列。
- 浮盈聚合 `UserPositionSummaryAggregator`（Task 9b 已账户币化）：`ConcurrentHashMap<symbol,<userId,Σpnl>>`，`totalForUser(userId)` 跨 symbol 求和；当前仅 WS 推送用。
- t_risk_config：有 maintenance_margin_rate（**未被强平链路读**）、max_leverage、平台全局行（symbol NULL）；**无 stop_out/margin_call 列**。
- 通知：`TradingNotificationApplicationService.send(templateCode,userId,type,params,relatedKey,relatedId,payloadJson)`；模板 seed 在 migration（参考 V22）；占位符 `${var}`。
- t_symbol（owner=market schema `falconx_market`，trading **不可跨 schema 直读**）：列 `symbol,category,market_code,base_currency,quote_currency,status`；category 编码 `1=crypto/2=forex/3=metal/4=index/5=energy`。本地 docker `falconx-mysql` 同实例含 falconx_market（V2/V3 已 seed ~500 symbol）→ **tier seed 用 build-time 脚本连读 falconx_market.t_symbol 生成 V30 INSERT SQL**（非运行时跨 schema）。
- Flyway 最新 V29，V30/V31/V32 可用（实施时 `ls .../db/migration | sort -V | tail -3` 复核）。
- trading-core **未接 config SDK** → stop_out_level/margin_call_level 走 t_risk_config DB 读（config SDK 接入延后，不阻断 C1）。
- DECIMAL 精度沿用：金额 8 位；mmRate `DECIMAL(8,6)`；MarginLevel 计算用 BigDecimal，展示百分比。

---

## File Structure

### Create
- `falconx-trading-core-service/src/main/resources/db/migration/V30__symbol_leverage_tier.sql`（表 + seed）+ `docs/sql/V30__symbol_leverage_tier.sql`
- `tools/tier-seed/generate_tier_seed.*`（build-time 生成脚本，读 falconx_market.t_symbol 按 §5.2 映射 → 输出 V30 INSERT；脚本与生成产物都入库留痕）
- `falconx-trading-core-service/src/main/resources/db/migration/V31__risk_config_thresholds_fx_pause_behavior.sql` + `docs/sql/V31__...sql`（t_risk_config 加 2 列 + t_fx_pause_behavior 表+seed + 通知模板 seed）
- `falconx-trading-core-service/src/main/resources/db/migration/V32__position_tier_freeze.sql` + `docs/sql/V32__...sql`（t_position 加 mm_rate_at_open/tier_no_at_open）
- `falconx-trading-core-service/.../entity/SymbolLeverageTier.java`、`repository/SymbolLeverageTierRepository.java` + `MybatisSymbolLeverageTierRepository.java` + `mapper/SymbolLeverageTierMapper.java`(+ XML) + `mapper/record/SymbolLeverageTierRecord.java`
- `falconx-trading-core-service/.../service/LeverageTierResolver.java` + `impl/DefaultLeverageTierResolver.java`（含 30s 缓存）
- `falconx-trading-core-service/.../service/model/LeverageTier.java`（record: tierNo, maxLeverage, mmRate, notionalLower, notionalUpper）
- `falconx-trading-core-service/.../service/AccountEquityCalculator.java` + `impl/DefaultAccountEquityCalculator.java`
- `falconx-trading-core-service/.../service/model/AccountMarginState.java`（record: equity, marginUsed, marginLevel, status）
- `falconx-trading-core-service/.../service/MarginLevelMonitor.java` + `impl/DefaultMarginLevelMonitor.java`（含节流 Map）
- `falconx-trading-core-service/.../entity/MarginLevelStatus.java`（enum: HEALTHY/MARGIN_CALL/STOP_OUT）
- 各 `*Tests.java`

### Modify
- `entity/TradingRiskConfig.java` + record + XML：加 stopOutLevel/marginCallLevel
- `entity/TradingPosition.java` + `TradingPositionRecord` + `TradingPositionMapper.xml`：加 mmRateAtOpen/tierNoAtOpen（所有 `new TradingPosition(` 构造点补参，参考 STAGE-14B Task 9a entryFxRate 做法）
- `service/model/TradingRiskDecision.java`：加 mmRateAtOpen/tierNoAtOpen（供开仓 INSERT 冻结）
- `service/impl/DefaultTradingRiskService.java`：开仓接 LeverageTierResolver（tier 校验 30070/30072 + mmRate 用 tier + 冻结值写 decision）
- `engine/QuoteDrivenEngine.java`：每 tick 接入 MarginLevelMonitor（账户级重算 + StopOut 触发）
- `engine/PositionTriggerRuleEvaluator.java` 或新增并列评估：StopOut（MarginLevel ≤ stopOut）双触发
- `controller/TradingOrderController.java`：reject reason → 错误码 30070/30071/30072/30087 映射
- `application.yml`：tier.cache-refresh-seconds、stop-out/margin-call 默认（兜底，真值 DB）

---

## Tasks

### Task 1: V30 — t_symbol_leverage_tier 表 + build-time tier seed

**Files:** `V30__symbol_leverage_tier.sql`（migration + docs/sql）、`tools/tier-seed/`（生成脚本 + 产物说明）

- [ ] **Step 1: 复核 Flyway 版本** `ls falconx-trading-core-service/src/main/resources/db/migration/ | sort -V | tail -3`，确认 V30 未占用（占用则顺延，全计划同步改号）。
- [ ] **Step 2: 建表 SQL**（master §4.2，**不写 USE，schema 由连接绑定** —— 见 STAGE-14B root bug 教训）：
```sql
CREATE TABLE t_symbol_leverage_tier (
    id              BIGINT       PRIMARY KEY COMMENT '主键ID（雪花）',
    symbol          VARCHAR(32)  NOT NULL,
    group_code      VARCHAR(32)  NOT NULL DEFAULT 'default',
    tier_no         TINYINT      NOT NULL,
    notional_lower  DECIMAL(24,8) NOT NULL,
    notional_upper  DECIMAL(24,8) NULL COMMENT 'NULL=无上限（最高档）',
    max_leverage    INT          NOT NULL,
    mm_rate         DECIMAL(8,6) NOT NULL,
    enabled         TINYINT      NOT NULL DEFAULT 1,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_symbol_group_tier (symbol, group_code, tier_no),
    INDEX idx_symbol_group_lower (symbol, group_code, notional_lower),
    CONSTRAINT chk_tier_leverage_mm CHECK (max_leverage * mm_rate <= 1.0)
) ENGINE=InnoDB COMMENT='杠杆/MM 双重分级表';
```
- [ ] **Step 3: 生成 seed**：写脚本（Java `tools` 一次性 main，或 SQL+shell）连本地 `falconx_market.t_symbol`（`docker exec falconx-mysql mysql -uroot -proot`），按 master §5.2 的 CASE 映射规则把每个 `status=1` 的 symbol 归入 T1-T10 模板（§5.1），group_code 仅 seed `'default'`，展开成 `INSERT INTO t_symbol_leverage_tier (...) VALUES ...`（每 symbol 对应模板的各档，id 用脚本生成的稳定雪花/序列）。把脚本与生成的 INSERT 一并写入 V30（migration 内联 INSERT）。脚本与映射规则注释保留在 `tools/tier-seed/`。
  - 校验：生成后本地核对每模板抽样 symbol 档位与 §5.1 一致；CHECK 约束 `max_leverage*mm_rate<=1.0` 对所有模板成立（§5.1 模板已满足，T5 tier1 500x×0.002=1.0 边界 OK）。
- [ ] **Step 4: 复制 docs/sql + 编译** `mvn -pl falconx-trading-core-service -am test-compile`（真 migrate 留 Task 11/13）。
- [ ] **Step 5: Commit** `feat(trading): STAGE-14C1 Task 1 V30 t_symbol_leverage_tier 表 + build-time 脚本按 §5.2 映射生成全 symbol tier seed（default 组）`

---

### Task 2: V31 — t_risk_config 阈值列 + t_fx_pause_behavior 表 + 通知模板 seed

**Files:** `V31__risk_config_thresholds_fx_pause_behavior.sql`（+docs/sql）

- [ ] **Step 1:**
```sql
ALTER TABLE t_risk_config
    ADD COLUMN stop_out_level    DECIMAL(8,6) NOT NULL DEFAULT 0.30 COMMENT 'StopOut 强平阈值（MarginLevel）',
    ADD COLUMN margin_call_level DECIMAL(8,6) NOT NULL DEFAULT 1.00 COMMENT 'MarginCall 告警阈值';

CREATE TABLE t_fx_pause_behavior (
    category            TINYINT      PRIMARY KEY,
    category_name       VARCHAR(32)  NOT NULL,
    allow_open          TINYINT      NOT NULL DEFAULT 1,
    allow_close         TINYINT      NOT NULL DEFAULT 1,
    allow_liquidation   TINYINT      NOT NULL DEFAULT 1,
    updated_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by_admin_id BIGINT       NULL
) ENGINE=InnoDB COMMENT='FX_PAUSED 各品种类目允许操作开关';
INSERT INTO t_fx_pause_behavior (category,category_name,allow_open,allow_close,allow_liquidation) VALUES
  (1,'crypto',1,1,1),(2,'forex',0,1,0),(3,'metal',0,1,0),(4,'index',1,1,1),(5,'energy',1,1,1);
-- 通知模板（参考 V22 列结构，实施时按 t_notification_template 实际列对齐）
INSERT INTO t_notification_template (code, type, title_template, body_template, channels, enabled) VALUES
  ('MARGIN_CALL_TRIGGERED','MARGIN_CALL_TRIGGERED','保证金预警','您的账户保证金率 ${marginLevel}% 已低于预警线，请及时补充保证金或减仓','IN_APP',1),
  ('STOP_OUT_TRIGGERED','STOP_OUT_TRIGGERED','强制平仓','您的账户保证金率 ${marginLevel}% 触发强平线，已强平仓位 ${symbol}','IN_APP',1);
```
- [ ] **Step 2:** 实施前 `read` V22 模板 SQL 确认 `t_notification_template` 真实列名/约束，对齐 INSERT（不得臆造列）。
- [ ] **Step 3-4:** docs/sql 复制 + test-compile。
- [ ] **Step 5: Commit** `feat(trading): STAGE-14C1 Task 2 V31 t_risk_config 加 stop_out_level/margin_call_level + t_fx_pause_behavior 表 + MARGIN_CALL/STOP_OUT 通知模板 seed`

---

### Task 3: V32 — t_position 加 mm_rate_at_open / tier_no_at_open 冻结列

**Files:** `V32__position_tier_freeze.sql`（+docs/sql）

- [ ] **Step 1:**
```sql
ALTER TABLE t_position
    ADD COLUMN mm_rate_at_open DECIMAL(8,6) NULL COMMENT '开仓时 tier mmRate 冻结（强平价/MM 用）' AFTER entry_fx_rate,
    ADD COLUMN tier_no_at_open TINYINT      NULL COMMENT '开仓时 tier 档位冻结（审计）' AFTER mm_rate_at_open;
-- 老数据回填：用 properties 默认 mmRate 0.005、tier_no=1（与历史强平价口径一致）
UPDATE t_position SET mm_rate_at_open = 0.005000, tier_no_at_open = 1 WHERE mm_rate_at_open IS NULL;
ALTER TABLE t_position
    MODIFY COLUMN mm_rate_at_open DECIMAL(8,6) NOT NULL DEFAULT 0.005000,
    MODIFY COLUMN tier_no_at_open TINYINT NOT NULL DEFAULT 1;
```
- [ ] **Step 2-3:** docs/sql + test-compile。
- [ ] **Step 4: Commit** `feat(trading): STAGE-14C1 Task 3 V32 t_position 加 mm_rate_at_open/tier_no_at_open 冻结列 + 老数据回填（0.005/1）`

---

### Task 4: SymbolLeverageTier 实体 + Repository + Mapper（串入 + 读路径）

**Files:** entity/record/repository/mapper(+XML) + UT

- [ ] **Step 1:** `SymbolLeverageTier` 实体 record + `SymbolLeverageTierRecord` + Mapper XML（resultMap arg 顺序严格对齐 record；参考 STAGE-14B Task 5 ledger 做法）。
- [ ] **Step 2:** `SymbolLeverageTierRepository.findTiers(symbol, groupCode)` → `List<SymbolLeverageTier>`（按 notional_lower 升序，enabled=1）；group_code 缺失档位时回退 'default' 组（先查 groupCode，空则查 'default'）。
- [ ] **Step 3:** UT（mock mapper）+ 一个轻量 IT（真 DB 查 seed 行）。
- [ ] **Step 4: Commit** `feat(trading): STAGE-14C1 Task 4 SymbolLeverageTier 实体/record/Mapper/Repository（按 symbol+group 查档位，回退 default 组）+ UT`

---

### Task 5: LeverageTierResolver（按 notional 解析档位 + 30s 缓存）

**Files:** `service/LeverageTierResolver.java` + `impl/DefaultLeverageTierResolver.java` + `model/LeverageTier.java` + UT

- [ ] **Step 1:** `LeverageTier` record（tierNo, maxLeverage, mmRate, notionalLower, notionalUpper）。
- [ ] **Step 2:** `LeverageTierResolver.resolve(String symbol, BigDecimal notionalInAccount, String groupCode) → Optional<LeverageTier>`：从 Repository 取该 symbol+group 档位列表（30s 本地缓存，参考 RedisMarketSymbolSpecRepository 缓存模式），按 notional 落入 `[notional_lower, notional_upper)` 档；无匹配/无配置返回 empty（caller 判 empty → 30072 TIER_CONFIG_NOT_FOUND）。
- [ ] **Step 3:** UT ≥6：notional 落各档边界、最高档 upper=NULL、缓存命中不重复查、group 回退 default、空配置 empty、跨档边界（lower 含 / upper 不含）。
- [ ] **Step 4: Commit** `feat(trading): STAGE-14C1 Task 5 LeverageTierResolver 按 notional 解析 tier 档位 + 30s 缓存 + 6 UT`

---

### Task 6: 开仓接 tier（替换硬码 mmRate + 30070/30072 + 冻结）

**Files:** `DefaultTradingRiskService.java` + `TradingRiskDecision.java` + `TradingOrderController.java`（错误码）+ 开仓 INSERT 冻结 + UT

- [ ] **Step 1:** `TradingRiskDecision` 加 `mmRateAtOpen`/`tierNoAtOpen`（reject 分支 null/默认）。
- [ ] **Step 2:** `evaluateMarketOrder`：notional(AC) 算出后调 `leverageTierResolver.resolve(symbol, notionalInAccount, groupCode)`：
  - empty → `reject("TIER_CONFIG_NOT_FOUND")`（30072）
  - `leverage > tier.maxLeverage()` → `reject("LEVERAGE_EXCEEDS_TIER")`（30070）
  - mmRate 用 `tier.mmRate()`（替换 `properties.getMaintenanceMarginRate()`）；强平价 `LiquidationPriceCalculator.calculate(..., tier.mmRate())`；decision 写 mmRateAtOpen=tier.mmRate()/tierNoAtOpen=tier.tierNo()。
  - notional(AC)：用 `margin.inAccount() × leverage` 或 `notional(QC)×fxRate`，与 MarginResult 口径一致（注意 tier 按账户币 notional 分档，master §3.2 LeverageTierResolver.resolve(notionalInAccount)）。
  - 余额不足分支保留既有 INSUFFICIENT_AVAILABLE_BALANCE（30071 语义；按现有 reject 机制，错误码映射在 controller）。
- [ ] **Step 3:** 开仓 `new TradingPosition(...)`（`TradingOrderPlacementApplicationService`）写入 mmRateAtOpen/tierNoAtOpen（decision 取）。所有 `new TradingPosition(` 构造点补参。
- [ ] **Step 4:** `TradingOrderController` reject reason → HTTP 码映射加 30070/30072（30071/30087 若未映射一并补）。
- [ ] **Step 5:** UT：lev≤tier.maxLev 通过、lev>tier.maxLev 拒 30070、tier 缺失拒 30072、mmRate 用 tier 值（强平价随之变）、position 冻结 mmRateAtOpen。改既有开仓 UT 同步。
- [ ] **Step 6: Commit** `feat(trading): STAGE-14C1 Task 6 开仓接 LeverageTierResolver（tier 校验 30070/30072 + mmRate 用 tier 替换硬码 + 冻结 mm_rate_at_open/tier_no）+ UT`

---

### Task 7: AccountEquityCalculator（ISOLATED 单仓 Equity）

**Files:** `service/AccountEquityCalculator.java` + impl + `model/AccountMarginState.java` + UT

- [ ] **Step 1:** `AccountMarginState` record（equity, totalMaintenanceMargin, marginLevel, status）。
- [ ] **Step 2:** ISOLATED 口径（master §3.2，C1 仅 ISOLATED；CROSS 延 D）：
  - 每仓 `MM_i(AC) = notional_i(AC) × position.mmRateAtOpen()`（用冻结 mmRate；notional_i(AC)=qty×entryPrice×entryFxRate 或用持仓账户币口径，与开仓 frozen 一致）。
  - 账户级（ISOLATED 跨仓汇总用于账户 MarginLevel 展示/CROSS 预留）：`Equity(AC) = balance + frozen + Σ uPnL_i(AC)`；`marginLevel = Equity / Σ MM_i × 100%`。
  - 单仓判定（StopOut 用）：`Equity_i = margin_i(AC) + uPnL_i(AC)`，`MarginLevel_i = Equity_i / MM_i × 100%`。
  - uPnL_i(AC) 来源：复用 `TradingPricingSupport.calculatePositionPnlInAccount`（Task 7 STAGE-14B）或聚合器账户币口径。
- [ ] **Step 3:** UT ≥5：EURAUD 单仓 MarginLevel 计算（master §3.3 算例：Equity=98.70/MM=53.625→184%）、亏损跌破 30%、同币种、MM 用冻结 mmRate、空仓位。
- [ ] **Step 4: Commit** `feat(trading): STAGE-14C1 Task 7 AccountEquityCalculator ISOLATED 单仓+账户级 Equity/MarginLevel（MM 用冻结 mmRate）+ 5 UT`

---

### Task 8: MarginLevelMonitor（阈值判定 + StopOut/MarginCall 触发 + 节流）

**Files:** `service/MarginLevelMonitor.java` + impl + `entity/MarginLevelStatus.java` + UT

- [ ] **Step 1:** `MarginLevelStatus` enum（HEALTHY/MARGIN_CALL/STOP_OUT）。
- [ ] **Step 2:** `MarginLevelMonitor.evaluate(userId, AccountMarginState, stopOutLevel, marginCallLevel) → MarginLevelStatus`：
  - marginLevel > marginCallLevel → HEALTHY
  - stopOutLevel < marginLevel ≤ marginCallLevel → MARGIN_CALL（发 STAGE-8 `MARGIN_CALL_TRIGGERED`，**同用户 5min 节流**，内存 `Map<userId, lastNotifyAt>`）
  - marginLevel ≤ stopOutLevel → STOP_OUT（触发强平，发 `STOP_OUT_TRIGGERED`）
  - stopOutLevel/marginCallLevel 从 t_risk_config 平台行读（带本地缓存，缺失用默认 0.30/1.00）。
- [ ] **Step 3:** UT ≥5：三态边界、MarginCall 5min 节流（同用户二次 tick 不重复发）、恢复 HEALTHY、StopOut 触发回调、阈值从 config 读。
- [ ] **Step 4: Commit** `feat(trading): STAGE-14C1 Task 8 MarginLevelMonitor 三态判定 + MarginCall 5min 节流 + StopOut 触发 + 5 UT`

---

### Task 9: QuoteDrivenEngine 接入 MarginLevel + StopOut 双触发

**Files:** `QuoteDrivenEngine.java` + `PositionTriggerRuleEvaluator.java`（或新增 StopOut 路径）+ 账户缓存 + UT/IT

- [ ] **Step 1:** processTick 中，对受本 tick 影响的每个 userId（该 symbol 上有 open 持仓的用户），用 `AccountEquityCalculator` 算单仓 MarginLevel_i + `MarginLevelMonitor.evaluate`。
- [ ] **Step 2: 双触发**：单仓满足 `liqPrice 命中`（既有 PositionTriggerRuleEvaluator）**或** `MarginLevel_i ≤ stopOutLevel`，任一即 `closePositionByTrigger(positionId, LIQUIDATION, snapshot)`。避免对同一仓位 double-trigger（已 CLOSED 跳过；closePositionByTrigger 内 FOR UPDATE + 二次校验已有）。
- [ ] **Step 3: 性能**：账户 balance/frozen/marginUsed 读取用内存缓存或随 snapshot 携带，避免每 tick 每用户打 MySQL（参考 openPositionSnapshotStore 模式）。account 变更（开/平仓）时失效/更新缓存。**记录该缓存的 TTL/刷新/降级**（AGENTS §3.9）。
- [ ] **Step 4:** MarginCall 仅告警不强平（Monitor 内发通知，不调 close）。
- [ ] **Step 5:** UT + IT：EURAUD 跌破 30% → MarginLevel 触发强平（即使 liqPrice 未命中）；MarginCall 100%→30% 发告警不强平；liqPrice 与 MarginLevel 同时命中只强平一次。
- [ ] **Step 6: Commit** `feat(trading): STAGE-14C1 Task 9 QuoteDrivenEngine 接入账户 MarginLevel 实时重算 + StopOut/liqPrice 双触发强平 + 账户内存缓存 + UT/IT`

---

### Task 10: GLOBAL_PAUSE / FX_PAUSED 接 t_fx_pause_behavior（按类目行为）

**Files:** 开仓/平仓/强平校验接 t_fx_pause_behavior + Repository + UT

- [ ] **Step 1:** `FxPauseBehaviorRepository.findByCategory(category)`（缓存）。
- [ ] **Step 2:** 在 GLOBAL_PAUSE/FX_PAUSED 状态下（复用既有 GLOBAL_PAUSE 触发源 + STAGE-14B FX stale 升级），按 symbol category 查 t_fx_pause_behavior：allow_open=0 → 开仓拒 30087（GLOBAL_PAUSE_ACTIVE）；allow_liquidation=0 → 跳过被动强平（forex/metal 默认停）。category 来源：SymbolSpec 暂无 category → 需从 market 取（Redis SymbolSpec 加 category？或 RPC）；**若 category 来源缺失，本 task 记 DONE_WITH_CONCERNS，按平台级 allow_open 兜底**，category 细分留 C2/后续（不阻断 C1 主线）。
- [ ] **Step 3:** UT：forex 停开仓、crypto 允许、停被动强平。
- [ ] **Step 4: Commit** `feat(trading): STAGE-14C1 Task 10 FX_PAUSED 按 t_fx_pause_behavior 类目行为（开仓/强平开关）+ UT`

---

### Task 11: IT 跨场景 + StopOut 强平整链 + tier 边界 + PERF

**Files:** `src/test/java/.../integration/` + tier seed 抽查

**用例骨架（master §8.3 C 阶段，后端 ≥15 IT + 2 PERF）：**
- TC-TIER-IT-001 V30/V31/V32 migrate apply + CHECK 约束（max_lev×mm_rate≤1.0 插入越界被拒）
- TC-TIER-IT-002 tier seed 抽查：每模板（T1-T10）抽 ≥5 symbol 核对档位/maxLev/mmRate 匹配 §5.1
- TC-TIER-IT-003 全 10 模板边界 tier 切换各覆盖 1 次（notional 跨档 → maxLev/mmRate 变）
- TC-TIER-IT-004 200x XAUUSD 大单（落 tier 3/4，maxLev<200）→ 开仓拒 30070 + 消息清晰
- TC-TIER-IT-005 tier 缺失 → 30072
- TC-TIER-IT-006 EURAUD 开仓 mmRate 用 tier 值 + 冻结 mm_rate_at_open
- TC-TIER-IT-007 MarginLevel 计算（EURAUD master §3.3：184% HEALTHY）
- TC-TIER-IT-008 跌破 30% → StopOut 强平整链（< 500ms：触发→平仓→落账 biz_type=9 三列）
- TC-TIER-IT-009 MarginCall 100%≥ML>30% → 发 MARGIN_CALL_TRIGGERED 通知 + 5min 节流 + 不强平
- TC-TIER-IT-010 liqPrice 与 MarginLevel 双触发只强平一次
- TC-TIER-IT-011 BTCUSDT 同币种 tier（T1/T2）开/平仓回归不破坏
- TC-TIER-IT-012 老仓位 mm_rate_at_open 回填 0.005 抽查
- TC-TIER-IT-013 FX_PAUSED forex 停开仓（Task 10）
- TC-TIER-IT-014 stop_out/margin_call 阈值从 t_risk_config 读 + 改值生效
- TC-TIER-IT-015 PERF：1000 用户同时跌破 30%，强平队列吞吐 + 单仓强平 P99（master §8.3 C：StopOut→落账 < 500ms；记实测）
- TC-TIER-IT-016 PERF：MarginLevel 每 tick 重算（多用户多仓）账户缓存命中下 tick 处理延迟（不打 MySQL 证据）

- [ ] **Commit** `test(trading): STAGE-14C1 Task 11 tier/MarginLevel/StopOut 跨场景 16 IT（含 10 模板边界 + 200x 拒单 + 强平整链 + tier seed 抽查 + 2 PERF）`

---

### Task 12: 真 Flyway migrate + tier seed 全量抽查 + R8 文档同步

**Files:** 验证证据 + docs

- [ ] **Step 1:** 隔离库真 migrate V1→V32（参考 STAGE-14B Task 11 方法：build-time 脚本生成的 seed 真落库），抽查 t_symbol_leverage_tier 行数 + 每模板 5 symbol；t_fx_pause_behavior 5 行；t_risk_config 2 新列默认值。
- [ ] **Step 2:** R8 文档同步：
  - `docs/database/falconx一期数据库设计.md`：t_symbol_leverage_tier / t_risk_config 2 列 / t_fx_pause_behavior / t_position 2 列
  - `docs/domain/状态机规范.md`：MarginLevel 状态机（§6.2）+ StopOut 强平流程（§6.3）
  - `docs/architecture/事务与幂等规范.md`：MarginLevel 实时重算 + StopOut 强平串行（user-level lock）
  - `docs/api/FalconX统一接口文档.md`：错误码 30070/30071/30072/30087
  - `docs/event/Kafka事件规范.md`：（若 C1 不发 tier.changed 则注明 C2 引入）
  - `docs/process/BBook一期完成执行路径.md`：STAGE-14C1 收口
- [ ] **Step 3: Commit** `docs(R8): STAGE-14C1 文档同步（tier 表/risk_config/fx_pause/position 冻结列 + MarginLevel/StopOut 状态机 + 错误码）`

---

### Task 13: R7 收口报告 + 当前开发计划录入 + push

**Files:** `docs/test/STAGE-14C1-MARGIN-LEVEL-TIER-R7-verification-report.md` + `docs/setup/当前开发计划.md`

**验收硬约束（master §8.3 C 阶段，后端可证部分）：**
- 全 10 模板边界 tier 切换 IT 各覆盖 1 次
- 200x 大单（落高档）下单被拒 30070 + 消息清晰
- StopOut 触发→强平→落账整链 < 500ms
- CHECK 约束 max_lev×mm_rate≤1.0 DB 层生效
- 全 symbol tier seed 每模板抽 5 个核对（Task 12）
- （admin 改 tier 30s 生效、tier CRUD UI 属 C2）

- [ ] R7 报告（参考 14B R7 模板）+ 计划 §1 录入（展示名用概要，§3.6.5）+ 下一步指向 C2（console tier CRUD 三端 UI）+ D 阶段。
- [ ] **Commit** `docs(R7): STAGE-14C1 R7 收口报告 + 当前开发计划录入（tier/MarginLevel/StopOut 后端核心）`
- [ ] push origin main（控制者统一执行）。

---

## Self-Review

1. **Spec 覆盖**：master §3.2（MM/Equity/MarginLevel/强平价 tier mmRate）→ Task 6/7/8/9；§4.2（3 张 schema 变更）→ Task 1/2/3；§5（10 模板+映射）→ Task 1 seed；§6.2/6.3（MarginLevel 状态机+强平流程）→ Task 8/9；§7.3（30070-30072/30087）→ Task 6/10；§8.3 C 验收 → Task 11/13 ✅。CROSS（§3.2 CROSS 路径 / D2 全部实时的 CROSS 部分）+ console tier CRUD + 前端 UI 明确划归 D/C2，不在 C1。
2. **Placeholder**：无 TBD（引用具体 §节 + 实查事实）。
3. **类型一致**：LeverageTier{tierNo,maxLeverage,mmRate,notionalLower,notionalUpper}、AccountMarginState{equity,totalMaintenanceMargin,marginLevel,status}、MarginLevelStatus{HEALTHY,MARGIN_CALL,STOP_OUT} 在 Task 5/7/8/9 一致引用；mmRateAtOpen/tierNoAtOpen 在 Task 3/6/7 一致。
4. **R 角色**：Task 1-3 R2/R4、4-10 R4、11 R6、12 R6/R7/R8、13 R7。

## 实施风险预警（基于 14B 经验）
- **Flyway 不写 USE**（14B root bug 教训）：V30/V31/V32 一律不写 `USE falconx_trading;`，schema 由连接绑定。
- **tier seed 跨 schema**：build-time 脚本连读 falconx_market.t_symbol 生成 INSERT（非运行时跨 schema）；脚本 + 产物入库留痕。
- **MarginLevel 实时重算性能**：account 内存缓存，避免每 tick 打 MySQL（Task 9 强约束 + PERF IT 证明）。
- **mmRate 冻结**：开仓冻结 mm_rate_at_open，后续 tier 调整不影响存量仓位强平价（同 STAGE-12 markup 冻结）。
- **break 面**：TradingRiskDecision/TradingPosition 加字段 → 所有构造点 + caller 同步（参考 14B Task 9a，覆盖率 ≥90%）。
- **CROSS 延 D**：C1 仅 ISOLATED；AccountEquityCalculator 预留 CROSS 路径但不实现，注释标注。
- **category 来源**（Task 10）：SymbolSpec 暂无 category，FX_PAUSED 按类目行为可能降级为平台级，细分留后续，不阻断 C1 主线。

## C2 / D 后续 Outline
- **C2**（console tier 三端 UI）：console `/admin/trading/tier/*` + internal RPC `/internal/v1/trading/console/tier` CRUD + RBAC tier:view/tier:edit（V14 console）+ `falconx.trading.tier.changed` Kafka（tier 改 30s 生效）+ console-frontend tier 配置页（vitest/E2E）。
- **D**：CROSS/ISOLATED 切换 + 冷静期 + CROSS 强平 + isolated_margin（V33）+ supplement-margin。

— END —
