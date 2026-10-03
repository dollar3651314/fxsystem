# STAGE-14B trading-core 货币转换 + 账本三列留痕 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.
>
> **FalconX R 角色映射**：R2 契约（Task 1-3）+ R4 业务后端（Task 4-10）+ R6 测试（Task 11）+ R7 验证（Task 13）+ R8 文档（Task 12）。

**Goal:** trading-core 算法层（Margin/PnL/Fee/Swap）引入 `CurrencyConverter`，账本与持仓增加原币种历史留痕（`t_ledger` 三列 + `t_position.entry_fx_rate`），消费 STAGE-14A 的 FX rate 数据完成跨币换算。

**Architecture:** trading-core 通过 RPC（启动加载 `/internal/v1/market/fx/rates`）+ Kafka topic（`falconx.market.fx.rate.update` 增量刷新）维护本地 FX rate 内存快照；`CurrencyConverter` 注入到 `MarginCalculator` / `TradingPricingSupport` / `FeeCalculator` / `SwapCalculator`；落账与平仓时锁定 `fxRateAtClose` 写 t_ledger / t_position。

**Tech Stack:** Spring Boot 4.0.5 / MyBatis Plus 3.5.15 / Kafka 4.2.0 / JDK 25。

**前置阅读：**
- [`docs/design/STAGE-14-MULTICURRENCY-AND-CROSS-MARGIN-MASTER-design.md`](../design/STAGE-14-MULTICURRENCY-AND-CROSS-MARGIN-MASTER-design.md) §3（核心算法公式）/ §4.2（t_ledger + t_position 改动）/ §3.4（算法层改动汇总）
- [`docs/process/STAGE-14A-FX-DATA-implementation-plan.md`](STAGE-14A-FX-DATA-implementation-plan.md)（市场端 FX 数据源已就位）
- [`docs/test/STAGE-14A-FX-DATA-R7-verification-report.md`](../test/STAGE-14A-FX-DATA-R7-verification-report.md)（A 阶段收口证据）
- AGENTS.md §3.3 / §3.4 / §3.8 / §3.9

**重要前提（基于 STAGE-14A 调研经验）**：
- Flyway 版本号：trading-core 当前最大 V27（STAGE-12 group markup），本 plan 假设 B 阶段用 V28/V29，**实施时由 implementer 实查最新版本号**，遇占用顺位推进
- 老数据回填：所有现有 ledger 假定 `original=USDT, fx=1`，所有现有 position 假定 `entry_fx_rate=1`
- 跨服务依赖：market-service FX RPC + Kafka 已就位（commit `9ba7709f`）

---

## File Structure

### Create

- `falconx-trading-core-service/src/main/resources/db/migration/V28__ledger_currency_columns.sql`
- `docs/sql/V28__ledger_currency_columns.sql`
- `falconx-trading-core-service/src/main/resources/db/migration/V29__position_open_fx_snapshot.sql`
- `docs/sql/V29__position_open_fx_snapshot.sql`
- `falconx-trading-core-service/src/main/java/com/falconx/trading/service/FxRateService.java`（trading-side client）
- `falconx-trading-core-service/src/main/java/com/falconx/trading/service/impl/DefaultFxRateService.java`（RPC 启动加载 + Kafka 增量刷新 + 本地缓存）
- `falconx-trading-core-service/src/main/java/com/falconx/trading/consumer/FxRateUpdateEventConsumer.java`（Kafka @KafkaListener）
- `falconx-trading-core-service/src/main/java/com/falconx/trading/service/CurrencyConverter.java`
- `falconx-trading-core-service/src/main/java/com/falconx/trading/service/impl/DefaultCurrencyConverter.java`
- 各对应 `*Tests.java`

### Modify

- `SymbolSpec` 实际位置（grep 定位）→ 加 `baseCurrency` / `quoteCurrency` 字段（同 FX rate getter 给消费者）
- `MarginCalculator.calculateInitialMargin()` / `calculateFee()` → 接 converter，返回 `MarginResult{inQuote, inAccount, fxRate}`
- `TradingPricingSupport.calculatePositionPnl()` → 返回 `PnlResult{inQuote, inAccount, fxRate, quoteCurrency}`
- `FeeCalculator` / `SwapCalculator` 同 MarginCalculator 模式
- `LedgerService.appendEntry()` → 新增 3 参数 `(originalAmount, originalCurrency, fxRateAtSettlement)`，**所有 9 处写账点同步加参**
- `TradingOrderPlacementApplicationService` / `TradingPositionCloseApplicationService` / `QuoteDrivenEngine`（强平落账） → 调链传 fx rate
- `falconx-trading-core-service/src/main/resources/application.yml` → 新增 `falconx.trading.fx.*` 配置（market RPC URL / Kafka topic / 启动加载策略）

---

## Tasks

### Task 1: V28 — t_ledger 加三列

**Files:** `falconx-trading-core-service/src/main/resources/db/migration/V28__ledger_currency_columns.sql` + `docs/sql/V28__ledger_currency_columns.sql`

- [ ] **Step 1: SQL**

```sql
USE falconx_trading;

ALTER TABLE t_ledger
    ADD COLUMN original_amount DECIMAL(24,8) NULL COMMENT '原币金额（quote currency）' AFTER amount,
    ADD COLUMN original_currency VARCHAR(16) NULL COMMENT '原币种代码' AFTER original_amount,
    ADD COLUMN fx_rate_at_settlement DECIMAL(24,8) NULL COMMENT '结算时 FX rate (original→account)' AFTER original_currency;

UPDATE t_ledger
SET original_amount = amount,
    original_currency = COALESCE((SELECT currency FROM t_account WHERE t_account.id = t_ledger.account_id), 'USDT'),
    fx_rate_at_settlement = 1.00000000
WHERE original_amount IS NULL;

ALTER TABLE t_ledger
    MODIFY COLUMN original_amount DECIMAL(24,8) NOT NULL,
    MODIFY COLUMN original_currency VARCHAR(16) NOT NULL,
    MODIFY COLUMN fx_rate_at_settlement DECIMAL(24,8) NOT NULL;
```

- [ ] **Step 2:** 复制到 docs/sql/
- [ ] **Step 3: 编译 + Flyway lint**：`mvn -pl falconx-trading-core-service -am test-compile`；**真 migrate 留 Task 11 IT**
- [ ] **Step 4: Commit**

```
feat(trading): STAGE-14B Task 1 V28 t_ledger 加 original_amount/original_currency/fx_rate_at_settlement 三列 + 老数据回填
```

---

### Task 2: V29 — t_position 加 entry_fx_rate

**Files:** `V29__position_open_fx_snapshot.sql`

- [ ] **Step 1: SQL**

```sql
USE falconx_trading;

ALTER TABLE t_position
    ADD COLUMN entry_fx_rate DECIMAL(24,8) NULL COMMENT '开仓时 FX rate (quote→account)，仅审计用'
        AFTER entry_price;

UPDATE t_position SET entry_fx_rate = 1.00000000 WHERE entry_fx_rate IS NULL;

ALTER TABLE t_position MODIFY COLUMN entry_fx_rate DECIMAL(24,8) NOT NULL DEFAULT 1.00000000;
```

- [ ] **Step 2-4:** 同 Task 1 模式

---

### Task 3: trading-side FxRateService 接口 + 实现（消费市场 FX 数据）

**Files:**
- `service/FxRateService.java`（interface）
- `service/impl/DefaultFxRateService.java`
- `consumer/FxRateUpdateEventConsumer.java`
- `application.yml` 加 `falconx.trading.fx.market-rpc-url` / `fx-rate-update-topic` / `consumer-group-id`

**关键设计**：
- 启动时调 market-service `GET /internal/v1/market/fx/rates` 拉一次全量到内存（`ConcurrentHashMap`）
- @KafkaListener 监听 `falconx.market.fx.rate.update` 增量刷新内存
- 接口 `Optional<BigDecimal> queryRate(String from, String to)`（同 14A FxRateService.queryRate 模式，含 USD pivot 交叉）

**测试**：mock RPC + 测试 Kafka 消费 → 内存更新 → queryRate 命中。5+ UT。

**Commit**: `feat(trading): STAGE-14B Task 3 trading-side FxRateService + Kafka consumer + RPC bootstrap + 5+ UT`

---

### Task 4: CurrencyConverter

**Files:** `service/CurrencyConverter.java` + `service/impl/DefaultCurrencyConverter.java` + UT

**核心**：薄包装 `FxRateService.queryRate`，提供 `BigDecimal convert(amount, from, to)` + same-currency short-circuit + null fallback warn。8 位精度 HALF_UP 与 market-side 一致。

**测试**：5+ UT 覆盖 same-currency / direct / reverse / cross / null。

**Commit**: `feat(trading): STAGE-14B Task 4 CurrencyConverter + 5+ UT`

---

### Task 5: SymbolSpec 扩展 + LedgerService.appendEntry 加 3 参数

**Files:**
- `entity/SymbolSpec.java`（或现有位置）+ 加字段 + Mapper XML 调整 select
- `service/LedgerService.java`（or `TradingAccountLedgerService`）的 `appendEntry()` 方法签名加 3 参数：`originalAmount` / `originalCurrency` / `fxRateAtSettlement`
- 9 处调用 appendEntry 的位置同步加参（grep 定位）

**预检**：
```bash
grep -rln "SymbolSpec\b" falconx-trading-core-service/src/main/java --include="*.java"
grep -rln "appendEntry\|LedgerService" falconx-trading-core-service/src/main/java --include="*.java"
```

**关键**：所有写账点改动的同时，先用 default `(amount, "USDT", BigDecimal.ONE)` 占位，等 Task 6-9 完成算法层接 converter 后再改成真值。**避免 break 既有测试**。

**Commit**: `feat(trading): STAGE-14B Task 5 SymbolSpec 加 baseCurrency/quoteCurrency + LedgerService.appendEntry 3 参数扩展 (9 写账点占位)`

---

### Task 6: MarginCalculator 接 converter + UT

**Files:** `calculator/MarginCalculator.java` + UT

**改造**：
- `calculateInitialMargin(fillPrice, quantity, leverage, quoteCurrency, accountCurrency, converter)` 返回 `MarginResult{inQuote, inAccount, fxRate}`
- 已有 caller `DefaultTradingRiskService.executeOpenOrderPlacement` 同步改

**测试**：覆盖 quote=AC（fx=1）+ quote≠AC（EURAUD 等） 两种场景。5+ UT。

**Commit**: `feat(trading): STAGE-14B Task 6 MarginCalculator 接 CurrencyConverter + MarginResult record + 5+ UT`

---

### Task 7: TradingPricingSupport.calculatePositionPnl 接 converter + UT

**Files:** `support/TradingPricingSupport.java` + UT

**改造**：返回 `PnlResult{inQuote, inAccount, fxRate, quoteCurrency}`。caller 在 `TradingPositionCloseApplicationService` / `QuoteDrivenEngine` 中同步改用 `inAccount` 写账 / `inQuote` 留痕。

**Commit**: `feat(trading): STAGE-14B Task 7 TradingPricingSupport.calculatePositionPnl 接 converter + PnlResult + 5+ UT`

---

### Task 8: FeeCalculator / SwapCalculator 接 converter

**Files:** 两个 calculator + UT

**改造**：同 MarginCalculator 模式。

**Commit**: `feat(trading): STAGE-14B Task 8 Fee/Swap calculator 接 converter + 6+ UT`

---

### Task 9: 业务流路集成 — OrderPlacement / PositionClose / 强平 / 挂单

**Files:** 业务编排服务（grep `appendEntry\|.calculateInitialMargin\|.calculatePositionPnl` 定位）

**改造**：把 Task 5 占位的 default `(amount, "USDT", 1)` 替换为：
- 开仓写账：`(im.inQuote(), spec.quoteCurrency(), im.fxRate())`
- 平仓 PnL 写账：`(pnl.inQuote(), pnl.quoteCurrency(), pnl.fxRate())`
- Fee / Swap 类似

**特别**：t_position.entry_fx_rate 在开仓 INSERT 时写入（来自 MarginCalculator 算的 fxRate）。

**Commit**: `feat(trading): STAGE-14B Task 9 业务流路接通 — OrderPlacement/PositionClose/强平/挂单触发 写账三列真值 + t_position.entry_fx_rate`

---

### Task 10: IT 跨服务 + 集成测试（含 EURAUD 端到端）

**Files:** `falconx-trading-core-service/src/test/java/com/falconx/trading/integration/`

**用例骨架**（最少 15 IT）：
- TC-CCY-IT-001 V28/V29 migration apply + 老数据回填 verify
- TC-CCY-IT-002 trading FxRateService 启动 RPC 拉取 + 内存可用
- TC-CCY-IT-003 Kafka FX rate update 消费 → 内存刷新
- TC-CCY-IT-004 CurrencyConverter same-currency short-circuit
- TC-CCY-IT-005 CurrencyConverter cross via USD
- TC-CCY-IT-006 MarginCalculator EURAUD 0.1 lot 200x → IM(AUD)/IM(USDT) 与 master spec §3.3 算例匹配
- TC-CCY-IT-007 开仓 EURAUD → t_position.entry_fx_rate 写入 + t_account.frozen = IM(USDT)
- TC-CCY-IT-008 平仓 EURAUD → t_ledger biz_type=8 三列填充正确（amount=PnL_USDT, original_amount=PnL_AUD, original_currency=AUD, fx_rate≠1）
- TC-CCY-IT-009 BTCUSDT 同币种（fx=1）端到端不破坏
- TC-CCY-IT-010 老数据回填 100 行抽查：original_currency=USDT, fx_rate=1
- TC-CCY-IT-011 FX rate 缺失时（market-service down）→ 拒绝开仓 + 30073
- TC-CCY-IT-012 强平 EURAUD → t_ledger biz_type=9 三列写入
- TC-CCY-IT-013 Fee 写账三列
- TC-CCY-IT-014 Swap 写账三列
- TC-CCY-IT-015 PERF 1000 用户 × 5 持仓 × FX tick 10Hz 浮盈推送 P99 < 100ms

**Commit**: `test(trading): STAGE-14B Task 10 跨服务 + EURAUD 端到端 15 IT 全过`

---

### Task 11: 真 Flyway migrate + 老数据回填 验证

**Files:** 无新代码，纯环境验证

- [ ] **Step 1:** `mvn -pl falconx-trading-core-service flyway:migrate -Dflyway.url=jdbc:mysql://localhost:3306/falconx_trading_it ...`
- [ ] **Step 2:** sample 100 行 t_ledger 抽查 `original_currency = COALESCE(account.currency, 'USDT')` + `fx_rate = 1`
- [ ] **Step 3:** sample 100 行 t_position 抽查 `entry_fx_rate = 1`
- [ ] **Step 4:** 在 R7 报告 §5 记录抽查证据

无 commit（验证证据 PR comment / R7 报告内联）。

---

### Task 12: R8 文档同步

**Files:**
- `docs/database/falconx一期数据库设计.md`：补 t_ledger 三列 + t_position.entry_fx_rate
- `docs/event/Kafka事件规范.md`：confirm `falconx.market.fx.rate.update` consumer 章节加 trading-core
- `docs/process/BBook一期完成执行路径.md`：§16 STAGE-14B 收口
- `docs/architecture/事务与幂等规范.md`：补"写账三列幂等"约束
- `docs/api/FalconX统一接口文档.md`：MarginResult / PnlResult 字段 break change 说明（WebSocket position.update 字段在 STAGE-14E 同步切换）

**Commit**: `docs(R8): STAGE-14B 文档同步 (DB/Kafka/执行路径/事务规范/接口契约)`

---

### Task 13: R7 收口报告 + 当前开发计划录入

**Files:**
- `docs/test/STAGE-14B-CURRENCY-CONVERTER-R7-verification-report.md`
- `docs/setup/当前开发计划.md` §1 STAGE-14B 收口条目

参考 STAGE-14A R7 报告模板。

**验收硬约束（B 阶段，按 master spec §8.3）**：
- EURAUD 端到端开/平仓 t_ledger 三列填充正确
- 老数据回填脚本执行后 sample 100 行抽查全部 original_currency=USDT, fx_rate=1
- PnL 推送 P99 < 100ms（1000 用户压测，Task 10 PERF）
- CurrencyConverter 同币种 short-circuit 性能验证

**Commit**: `docs(R7+R8): STAGE-14B R7 收口报告 + 当前开发计划录入`

---

## Self-Review Checklist

1. **Spec coverage**：master spec §3.2（公式 ①-⑨）+ §3.3（EURAUD 算例）+ §3.4（10 改动文件）+ §4.2（V28/V29 schema）全部对应到 task ✅
2. **Placeholder scan**：无 TBD（spec 引用具体 §节）
3. **Type consistency**：MarginResult / PnlResult / CurrencyConverter API 在 Task 4/6/7/8/9 一致引用
4. **R 角色映射**：Task 1-3 R2/R4 / Task 4-9 R4 / Task 10 R6 / Task 11 R6/R7 / Task 12 R8 / Task 13 R7

## 实施风险预警（基于 STAGE-14A 经验）

- **跨服务通信失败**：market-service FX RPC down → trading 启动失败？建议加 **fallback strategy**：启动 RPC 失败仅 warn，等 Kafka 消费补内存（同 14A FxRateStaleDetector 模式）
- **9 处写账点改造范围**：实际数量以 grep 为准，可能 >9，需 implementer 逐一 verify
- **MarginResult/PnlResult break change**：调用者全部要改，**测试覆盖率必须 ≥ 90%**
- **WebSocket break**：B 阶段不动 WS payload（plan E 阶段统一 break）；本阶段 PnL 推送仍按"原 unrealizedPnl 字段名 + 现在是 inAccount 值"过渡

## STAGE-14C-E 后续 Outline

B 阶段 R7 收口后，由 R1 单独 invoke writing-plans 出独立 plan：
- **C**：LeverageTierResolver / AccountEquityCalculator / MarginLevelMonitor / V30 t_symbol_leverage_tier / V31 t_risk_config 阈值列 + t_fx_pause_behavior / 500 symbol tier seed 自动生成 + 校验
- **D**：V32 t_account.margin_mode / V33 t_position.isolated_margin / 切换闸门 + 5min 冷静期 / CROSS 强平 / FX_PAUSED 按类目行为 / supplement-margin 接口
- **E**：客户端 mode toggle + MarginLevel 浮窗 + 双币 PnL / admin tier CRUD + FX rate 监控 + 多币聚合 / WebSocket break 字段同步切换

— END —
