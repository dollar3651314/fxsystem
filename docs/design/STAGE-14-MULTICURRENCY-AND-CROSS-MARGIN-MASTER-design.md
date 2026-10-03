# STAGE-14 多币种与 CROSS/ISOLATED 保证金模式 总设计稿（MASTER）

> 本文件是 FalconX 跨币种交易（FX 数据接入、PnL/保证金/手续费/Swap 货币换算、账本币种留痕）与 CROSS/ISOLATED 保证金模式（杠杆/MM 双重分级、账户级 MarginLevel、强平升级、用户级模式切换）的总设计稿。
>
> 本设计跨 5 个子阶段（A/B/C/D/E）独立交付。本文件是顶层 master，子阶段细化设计稿在 R2 派发各 stage 时分别落地（命名建议：`STAGE-14A-FX-DATA-design.md` 等）。
>
> 来源：基于 2026-05-28 brainstorming 对话沉淀，覆盖 14 项业务/算法决策 + 1 项实施路径决策。

---

## 0. 背景与问题陈述

### 0.1 现状

FalconX 当前交易算法（trading-core-service）在代码层**完全不带币种概念**：

- 订单价值 / 保证金 / 手续费 / Swap / PnL 全部用 quote currency 原生计算，结果直接当作账户币（USDT）写入 `t_account.balance` / `t_ledger.amount`
- 全代码 grep `fxRate / convertTo / quoteCurrency / baseCurrency` 零命中
- `TradingCoreServiceProperties.settlementToken = "USDT"` 硬编码，不读 `t_account.currency`

### 0.2 影响

- BTCUSDT / ETHUSDT 等 quote=USD 品种正常工作
- EURAUD / GBPJPY / XAUEUR / USDJPY 等非 USD-quoted 品种：
  - 保证金按 quote 原生数字冻结，未做 FX 换算
  - 浮盈/已实现 PnL 推送给前端时单位混乱（用户看到的"100"实际是 100 AUD 但被当 100 USDT）
  - 强平价数学公式正确，但与账户净值脱钩

### 0.3 既有已就位的基础设施（含 2026-05-28 Task 2 调研补充）

- schema 层：`t_symbol.base_currency / quote_currency`、`t_account.currency` 已存在
- `V2__seed_symbols.sql` 已落 130 个真实品种（含 8 个核心 FX：EURUSD/AUDUSD/USDJPY/GBPUSD/USDCAD/USDCHF/NZDUSD/USDCNH，已 `status=1`）
- `V3__seed_historical_crypto_symbols.sql` 已落约 370 个长尾 crypto
- `t_ledger` 已有 `biz_type=10 isolated_margin_supplement`（追加保证金支持）
- STAGE-12 group markup 的 `bidExtra/askExtra` 冻结模式可作为本设计 `fxRateAtClose` 留痕的参考
- **LP 行情链路对 FX 已 90% 就位**（Task 2 调研补充）：
  - `SocketIoLpMarketQuoteProvider` 对所有 symbol 无差别处理，FX symbol 进入 `subscribedSymbols` 即自动落 Redis `falconx:market:price:{symbol}` + ClickHouse `quote_tick` + Kafka `falconx.market.price.tick` + WebSocket `/ws/v1/market`
  - V7 / V15 / V17 自动化已为 8 FX symbol 配齐 `t_symbol_quote_mapping`（source_symbol=platform_symbol、source_lp_code=GODSA、price_multiplier=1.0、bid/ask_adjustment=0）
  - V18 (`STAGE-14A` baseline) 作为幂等校验已 commit

### 0.4 缺失能力

1. **FX 实时汇率换算服务**（核心缺口）：现有 LP 链路只推送各 FX symbol 的 tick 行情，**没有**专门的"`{base}→{quote}` 实时汇率查询 + 交叉换算（EUR→AUD via EURUSD÷AUDUSD）+ Redis cache + stale 检测 + 独立 Kafka 节流推送"服务。
2. 算法层货币转换（margin/PnL/fee/swap 不带币种）
3. 账户级 Equity / MarginLevel / 30% StopOut 强平
4. 杠杆/MM 双重分级（应对滥用高杠杆开大单）
5. CROSS / ISOLATED 用户级开关 + 切换闸门 + 冷静期
6. 账本与持仓的原币种历史留痕
7. 三端 UI 多币种展示与 admin 多币种聚合

---

## 1. 决策清单（14 项业务 + 1 项实施路径）

| # | 决策 | 选项 |
|---|---|---|
| D1 | FX 数据源 | 复用 LP 行情（注入 FX symbol，复用现有 quote tick 链路） |
| D2 | 实时重算范围 | PnL / MM / IM **全部实时**（最激进） |
| D3 | StopOut 阈值 | 30%，admin 可配 |
| D4 | 保证金模式 | 用户级 ISOLATED / CROSS toggle |
| D5 | ISOLATED 开仓 | 系统按 leverage 自动算 IM，不允许自定义 |
| D6 | ISOLATED 加保证金 | 允许追加（既有 `biz_type=10`） |
| D7 | 切换闸门 | 未平仓 + 未触发挂单都拒绝；切换后冷静期 admin 可配（默认 5 分钟） |
| D8 | 杠杆/MM 模型 | 杂杠杆 + MM 双重分级（币安/Bybit 模式） |
| D9 | MarginLevel 公式 | `Equity / MM × 100%` |
| D10 | "200x 立刻强平" | tier 自动降杠杆 / 拒绝下单（数学上等价；实际是开仓被拦截） |
| D11 | FX 服务降级 | 最后一次 rate + 告警，超阈值 → GLOBAL_PAUSE |
| D12 | FX 跳点保护 | 不单独设保护，依赖 LP halt + FX stale 超时检测 |
| D13 | 挂单触发资金不足 | 拒绝触发 + CANCELLED + 站内信 |
| D14 | 账本币种留痕 | t_ledger 加 `original_amount` + `original_currency` + `fx_rate_at_settlement` 三列 |
| D15 | 实施路径 | 方案 A：分 5 阶段交付（A 数据源 → B 算法换算 → C MarginLevel+Tier → D CROSS/ISOLATED → E 三端 UI） |

---

## 2. 整体架构与服务边界

### 2.1 数据流总览

```
market-service (owner: FX + symbol 行情)
  ↓ V18 (已存在) seed 8 FX symbol (EURUSD/AUDUSD/USDJPY/GBPUSD/USDCAD/USDCHF/NZDUSD/USDCNH)
  ↓ SocketIoLpMarketQuoteProvider (已存在) → quoteConsumer (已存在)
  ↓ MarketDataIngestionApplicationService.ingestPlatformQuote (已存在)
  ↓   → 现有路径: Redis price + ClickHouse + Kafka price.tick + WS
  ↓   → 新增分支 (STAGE-14A): 若 SymbolSpec.category==2 (FX) → FxRateService.onQuote()
  ↓ FxRateService (新)
  ↓ Redis: falconx:fx:rate:{base}:{quote} (TTL 5s)
  ↓ 交叉换算: EUR→AUD = EURUSD ÷ AUDUSD
  ↓ stale 超时检测
  ↓ RPC + Kafka tick (1Hz 节流)
        ↓
trading-core-service (owner: margin + position)
  ↓ CurrencyConverter (新)
  ↓ 算法层全部接 converter:
  ↓   MarginCalculator / TradingPricingSupport / FeeCalculator / SwapCalculator
  ↓   AccountEquityCalculator (新) / MarginLevelMonitor (新) / LeverageTierResolver (新)
  ↓ QuoteDrivenEngine 改造:
  ↓   每 tick 重算所有相关持仓 PnL/MM/IM (D2 全部实时)
  ↓   ISOLATED: 单仓 liqPrice + 单仓 MarginLevel 双触发
  ↓   CROSS: 账户级 MarginLevel 触发，按浮亏最大优先逐仓强平
  ↓ t_account.margin_mode (ISOLATED/CROSS)
  ↓ t_ledger + 3 列 (original_amount/currency/fx_rate)
  ↓ t_symbol_leverage_tier (新表)
  ↓ t_fx_pause_behavior (新表)
        ↓
console-service (owner: admin)
  ↓ tier 配置页 CRUD
  ↓ margin_mode 切换冷静期配置
  ↓ StopOut 阈值配置
  ↓ FX_PAUSED 行为开关 (按品种类目)
  ↓ 多币种聚合报表
        ↓
falconx-frontend + falconx-console-frontend (UI)
```

### 2.2 5 子阶段拆分

| 阶段 | 名称 | 主 owner | 阶段交付价值 | 独立可上线 |
|---|---|---|---|---|
| **A** | FX 数据源 | market-service | FX 行情对客户端、admin 可见 | 是 |
| **B** | 算法层货币转换 | trading-core | PnL / 手续费 / Swap 立刻准确 | 是（强平继续单仓价格触发） |
| **C** | MarginLevel + 强平升级 + Tier | trading-core + console | 真实风控；30% StopOut 启用；杠杆分级生效 | 是（CROSS 延后） |
| **D** | CROSS / ISOLATED 用户级开关 | trading-core + console | 跨保证金模式真正交付 | 是（UI 在 E 阶段） |
| **E** | 三端 UI + admin 多币种聚合 | frontend + console-frontend | 用户可见 MarginLevel + 模式切换 + 跨币 PnL；admin 跨币聚合视图 | 全闭环 |

### 2.3 与既有阶段的兼容性

| 既有阶段 | 兼容性 | 说明 |
|---|---|---|
| STAGE-12 group markup | 兼容 | bidExtra/askExtra 冻结字段独立于 FX |
| STAGE-13 配置中心 | 复用 | StopOut 阈值、冷静期时长、FX stale 阈值都走配置中心 |
| STAGE-8 通知系统 | 复用 | 新增 MARGIN_CALL / STOP_OUT / MODE_SWITCH / PENDING_TRIGGER_INSUFFICIENT_MARGIN 模板 |
| STAGE-2 admin exposure | 需改 | 推送按账户币聚合，E 阶段同步 |
| STAGE-3 pending order | 需改 | 触发器评估时如 ISOLATED 资金不足 → 拒绝触发 + CANCELLED |
| STAGE-7 withdraw | 兼容 | 出金/入金不影响保证金模式（D7） |
| BBOOK-RISK-CONTROL-01 | 兼容 | GLOBAL_PAUSE 复用为 FX 降级兜底 |
| CROSS-MARGIN-EXEC-01 (P1 延后) | **覆盖** | 本设计 D 阶段就是 CROSS 模式实施，覆盖该 P1 项，可归档 |

---

## 3. 跨币种核心算法公式

### 3.1 符号约定

| 符号 | 含义 |
|---|---|
| `fillPrice` | 成交价（原币：quote currency） |
| `qty` | 数量（base currency 单位） |
| `lev` | 杠杆 |
| `mmRate` | 维持保证金率（tier 决定） |
| `feeRate` | 手续费率 |
| `bidExtra/askExtra` | 用户组加点（STAGE-12 冻结值） |
| `QC` | quote currency |
| `BC` | base currency |
| `AC` | account currency（`t_account.currency`，默认 USDT） |
| `fx(X→Y)` | 实时汇率，X 币种 → Y 币种 |

### 3.2 公式

#### 订单价值 Notional

```
fillPrice(QC) = BUY ? quote.ask + askExtra : quote.bid + bidExtra
Notional(QC) = fillPrice × qty
Notional(AC) = Notional(QC) × fx(QC→AC)
```

#### 初始保证金 IM

```
IM(QC) = Notional(QC) / lev
IM(AC) = IM(QC) × fx(QC→AC)  ← D2 实时随 FX 重算
```

账户 `frozen` 列存 IM(AC)，FX 跳变时实时重算。

#### 维持保证金 MM

```
mmRate = LeverageTierResolver.resolve(symbol, Notional(AC), group_code).mmRate
MM(QC) = Notional(QC) × mmRate
MM(AC) = MM(QC) × fx(QC→AC)
```

#### 浮动盈亏 Unrealized PnL（D2 实时）

```
markPrice(QC) = BUY ? quote.bid - bidExtra : quote.ask + askExtra
                                            (markup 反向，对持仓 mark 用出场方向)
                                            (用 STAGE-12 冻结的 position.bidExtraAtOpen)
delta(QC) = side == BUY
              ? (markPrice - entryPrice)
              : (entryPrice - markPrice)
UnrealizedPnL(QC) = delta × qty
UnrealizedPnL(AC) = UnrealizedPnL(QC) × fx(QC→AC)  ← 实时换算
```

#### 已实现盈亏 Realized PnL（平仓时落账）

```
RealizedPnL(QC) = 同上公式（closePrice 代替 markPrice）
fxAtClose = fx(QC→AC) 当时快照值，写入 t_ledger.fx_rate_at_settlement
RealizedPnL(AC) = RealizedPnL(QC) × fxAtClose

t_ledger 写入 (biz_type=8 realized_pnl 或 9 liquidation_pnl):
   amount = RealizedPnL(AC)
   original_amount = RealizedPnL(QC)
   original_currency = QC
   fx_rate_at_settlement = fxAtClose
```

#### 账户净值 Equity（D2 实时）

```
ISOLATED 模式 (每仓独立):
   Equity_i(AC) = isolated_margin_i(AC) + UnrealizedPnL_i(AC)

CROSS 模式 (账户级):
   Equity(AC) = balance(AC) + frozen(AC) + Σ UnrealizedPnL_i(AC)
```

#### 保证金率 MarginLevel（D9）

```
ISOLATED 模式 (每仓独立判定):
   MarginLevel_i = Equity_i(AC) / MM_i(AC) × 100%
   触发: MarginLevel_i ≤ stopOutLevel(默认 30%) → 单仓强平

CROSS 模式 (账户级):
   MarginLevel = Equity(AC) / Σ MM_i(AC) × 100%
   触发: MarginLevel ≤ stopOutLevel → 按浮亏绝对值最大优先逐仓强平直到恢复

MarginCall 告警 (仅推送，不强平):
   MarginLevel ≤ marginCallLevel(默认 100%)
   推送 STAGE-8 MARGIN_CALL_TRIGGERED，同用户 5 分钟节流
```

#### 强平价 Liquidation Price（原币计算保留不变）

```
ISOLATED:
   marginPerUnit(QC) = isolatedMargin(QC) / qty
                       (isolatedMargin = IM + supplement，全部 QC)
   maintenanceComponent(QC) = entryPrice × mmRate
   BUY:  liqPrice(QC) = entryPrice - marginPerUnit + maintenanceComponent
   SELL: liqPrice(QC) = entryPrice + marginPerUnit - maintenanceComponent

CROSS:
   不存单仓 liqPrice (账户级 MarginLevel 触发；单仓 liqPrice 字段写 NULL)
```

ISOLATED 双触发：单仓 liqPrice 命中 或 单仓 MarginLevel ≤ 30%，任一即强平。

#### 手续费 Fee 与 Swap

```
Fee(QC) = Notional(QC) × feeRate
Fee(AC) = Fee(QC) × fx(QC→AC) 当时快照

Swap(QC) = position.qty × dailySwapRate × days
Swap(AC) = Swap(QC) × fx(QC→AC) 当时快照

落账：同 Realized PnL，三列留痕（original_amount/original_currency/fx_rate_at_settlement）。
```

### 3.3 完整算例：EURAUD 0.1 lot 200x（USDT 账户）

```
报价：EURAUD ask=1.6500, bid=1.6498, AUDUSDT=0.6500
品种: 0.1 lot = 10,000 EUR (FX 标准)
Tier 1 (notional < 50K USDT): max 200x, MM 0.5%

fillPrice(AUD) = 1.6500
Notional(AUD) = 16,500 AUD
Notional(USDT) = 10,725 USDT (< 50K → tier 1 OK)
IM(AUD) = 82.5 AUD
IM(USDT) = 53.625 USDT  ← 冻结到 frozen
MM(AUD) = 82.5 AUD
MM(USDT) = 53.625 USDT  (200x × 0.005 = 1.0，IM = MM，杠杆上限)

开仓后 markPrice = 1.6498:
   uPnL(AUD) = -2 AUD, uPnL(USDT) = -1.30 USDT
   用户余额 100 USDT:
   Equity = 100 + (-1.30) = 98.70 USDT
   MarginLevel = 98.70 / 53.625 = 184% → HEALTHY

若 EURAUD 跌到 1.5800, AUDUSDT 跌到 0.6300:
   uPnL(AUD) = -700 AUD, uPnL(USDT) = -441 USDT
   Equity = 100 - 441 = -341 USDT (负净值)
   MarginLevel = -341 / 51.975 = -656% → STOP_OUT 强平

t_ledger 写:
   amount = -341 USDT, original_amount = -700 AUD,
   original_currency = AUD, fx_rate_at_settlement = 0.6300
```

### 3.4 算法层改动汇总

| 文件 | 改动 |
|---|---|
| `SymbolSpec` (字段) | 加 `baseCurrency` / `quoteCurrency` |
| `MarginCalculator` | 加 `quoteCurrency` / `accountCurrency` / `converter` 参数；返回 `MarginResult{inQuote, inAccount, fxRate}` |
| `TradingPricingSupport.calculatePositionPnl()` | 返回 `PnlResult{inQuote, inAccount, fxRate, quoteCurrency}` |
| `LiquidationPriceCalculator` | 不变（保留原币计算） |
| `FeeCalculator`, `SwapCalculator` | 同 MarginCalculator |
| `CurrencyConverter` (新) | `convert(amount, from, to) → BigDecimal`；走 FxRateService Redis cache |
| `LeverageTierResolver` (新) | `resolve(symbol, notionalInAccount, groupCode) → Tier{maxLev, mmRate}` |
| `AccountEquityCalculator` (新) | 实时计算 Equity(AC)；CROSS / ISOLATED 双路径 |
| `MarginLevelMonitor` (新) | tick 驱动 + 阈值判定 + 触发 StopOut / MarginCall |
| `QuoteDrivenEngine` | 接入 MarginLevelMonitor；FX rate update tick 也要触发重算 |

---

## 4. 数据模型变更

### 4.1 Flyway 版本号规划

| 阶段 | 服务 | 版本号建议 | 文件名 |
|---|---|---|---|
| A | market | `V18__seed_fx_symbols.sql`（已 commit `ae9b84a`） | FX symbol baseline 幂等校验（V2 已 seed 8 FX symbol，V18 通过 ON DUPLICATE KEY UPDATE 标记 STAGE-14A 数据基线；占位 ID 2001-2008） |
| B | trading | `V28__ledger_currency_columns.sql` | t_ledger 加 3 列 |
| B | trading | `V29__position_open_fx_snapshot.sql` | t_position 加 entry_fx_rate |
| C | trading | `V30__symbol_leverage_tier.sql` | 新表 t_symbol_leverage_tier + seed |
| C | trading | `V31__risk_config_thresholds_and_fx_pause_behavior.sql` | t_risk_config 加 stop_out_level / margin_call_level + 新表 t_fx_pause_behavior |
| C | console | `V14__grant_tier_permissions.sql` | tier:view / tier:edit |
| D | trading | `V32__account_margin_mode.sql` | t_account 加 margin_mode 等 3 列 |
| D | trading | `V33__position_isolated_margin.sql` | t_position 加 isolated_margin |
| D | console | `V15__grant_margin_mode_permissions.sql` | margin-mode 配置权限 |
| E | console | `V16__admin_multicurrency_views.sql` (可选) | 跨币聚合视图（仅需要时） |

R2 派发各阶段时按当时实际最新版本号顺位推进；Flyway 允许稀疏号段。

### 4.2 表/列详细定义

#### t_symbol（A 阶段，seed 数据）

仅 INSERT seed，不改 schema：

```sql
INSERT INTO t_symbol (
    id, lp_code, symbol, category, market_code,
    base_currency, quote_currency, price_precision, qty_precision, status
) VALUES
    (?, 'GODSA', 'EURUSD', 2, 'FX', 'EUR', 'USD', 5, 2, 1),
    (?, 'GODSA', 'AUDUSD', 2, 'FX', 'AUD', 'USD', 5, 2, 1),
    (?, 'GODSA', 'USDJPY', 2, 'FX', 'USD', 'JPY', 3, 2, 1),
    (?, 'GODSA', 'GBPUSD', 2, 'FX', 'GBP', 'USD', 5, 2, 1),
    (?, 'GODSA', 'USDCAD', 2, 'FX', 'USD', 'CAD', 5, 2, 1),
    (?, 'GODSA', 'USDCHF', 2, 'FX', 'USD', 'CHF', 5, 2, 1),
    (?, 'GODSA', 'NZDUSD', 2, 'FX', 'NZD', 'USD', 5, 2, 1),
    (?, 'GODSA', 'USDCNH', 2, 'FX', 'USD', 'CNH', 5, 2, 1)
ON DUPLICATE KEY UPDATE updated_at = CURRENT_TIMESTAMP;
```

#### t_ledger 加 3 列（B 阶段）

```sql
ALTER TABLE t_ledger
    ADD COLUMN original_amount DECIMAL(24,8) NULL COMMENT '原币金额（quote currency）' AFTER amount,
    ADD COLUMN original_currency VARCHAR(16) NULL COMMENT '原币种代码' AFTER original_amount,
    ADD COLUMN fx_rate_at_settlement DECIMAL(24,8) NULL COMMENT '结算时 FX rate' AFTER original_currency;

UPDATE t_ledger
SET original_amount = amount,
    original_currency = (SELECT currency FROM t_account WHERE t_account.id = t_ledger.account_id),
    fx_rate_at_settlement = 1.00000000
WHERE original_amount IS NULL;

ALTER TABLE t_ledger
    MODIFY COLUMN original_amount DECIMAL(24,8) NOT NULL,
    MODIFY COLUMN original_currency VARCHAR(16) NOT NULL,
    MODIFY COLUMN fx_rate_at_settlement DECIMAL(24,8) NOT NULL;
```

#### t_position 加 entry_fx_rate（B 阶段）

```sql
ALTER TABLE t_position
    ADD COLUMN entry_fx_rate DECIMAL(24,8) NULL COMMENT '开仓时 FX rate (quote→account)，仅审计用'
        AFTER entry_price;
UPDATE t_position SET entry_fx_rate = 1.00000000 WHERE entry_fx_rate IS NULL;
ALTER TABLE t_position MODIFY COLUMN entry_fx_rate DECIMAL(24,8) NOT NULL DEFAULT 1.00000000;
```

#### t_symbol_leverage_tier 新表（C 阶段）

```sql
CREATE TABLE t_symbol_leverage_tier (
    id              BIGINT       PRIMARY KEY COMMENT '主键ID（雪花ID）',
    symbol          VARCHAR(32)  NOT NULL,
    group_code      VARCHAR(32)  NOT NULL DEFAULT 'default',
    tier_no         TINYINT      NOT NULL,
    notional_lower  DECIMAL(24,8) NOT NULL,
    notional_upper  DECIMAL(24,8) NULL,
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

#### t_risk_config 阈值列（C 阶段）

```sql
ALTER TABLE t_risk_config
    ADD COLUMN stop_out_level DECIMAL(8,6) NOT NULL DEFAULT 0.30,
    ADD COLUMN margin_call_level DECIMAL(8,6) NOT NULL DEFAULT 1.00;
```

#### t_fx_pause_behavior 新表（C 阶段，与 t_risk_config 阈值列同 V31）

```sql
CREATE TABLE t_fx_pause_behavior (
    category          TINYINT      PRIMARY KEY,
    category_name     VARCHAR(32)  NOT NULL,
    allow_open        TINYINT      NOT NULL DEFAULT 1,
    allow_close       TINYINT      NOT NULL DEFAULT 1,
    allow_liquidation TINYINT      NOT NULL DEFAULT 1,
    updated_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by_admin_id BIGINT     NULL
) ENGINE=InnoDB COMMENT='FX_PAUSED 状态下各品种类目允许操作开关';

INSERT INTO t_fx_pause_behavior VALUES
  (1, 'crypto', 1, 1, 1, NOW(), NULL),
  (2, 'forex',  0, 1, 0, NOW(), NULL),
  (3, 'metal',  0, 1, 0, NOW(), NULL),
  (4, 'index',  1, 1, 1, NOW(), NULL),
  (5, 'energy', 1, 1, 1, NOW(), NULL),
  (6, 'stock',  1, 1, 1, NOW(), NULL),
  (7, 'etf',    1, 1, 1, NOW(), NULL),
  (8, 'other',  1, 1, 1, NOW(), NULL);
```

#### t_account 加模式字段（D 阶段）

```sql
ALTER TABLE t_account
    ADD COLUMN margin_mode VARCHAR(16) NOT NULL DEFAULT 'ISOLATED' COMMENT 'ISOLATED 或 CROSS',
    ADD COLUMN mode_changed_at DATETIME NULL,
    ADD COLUMN mode_cooling_until DATETIME NULL;
```

#### t_position 加 isolated_margin（D 阶段）

```sql
ALTER TABLE t_position
    ADD COLUMN isolated_margin DECIMAL(24,8) NULL
        COMMENT 'ISOLATED 独立保证金（含 IM + supplement），NULL=CROSS 仓位'
        AFTER margin;

-- 老数据回填（升级窗口期执行，停 trading 写入 5 分钟）
UPDATE t_position SET isolated_margin = margin
WHERE isolated_margin IS NULL AND status = 'OPEN';

-- 兜底 UPDATE 在 migration 末尾再扫一次
```

`t_position.margin` 列保留（兼容），D 阶段后续 cleanup 议题。

### 4.3 老数据回填总览

| 表 | 策略 | 风险 |
|---|---|---|
| t_ledger | original_*=amount, currency, 1.0 | 零（与历史一致） |
| t_position | entry_fx_rate=1 | 零 |
| t_symbol_leverage_tier | seed 10 模板 | 低（admin 上线后 review） |
| t_risk_config | stop_out_level=0.30, margin_call_level=1.00 | 零 |
| t_account | margin_mode='ISOLATED' default | 零 |
| t_position.isolated_margin | margin 列 copy（升级窗口期） | 低（停服 5 分钟） |

### 4.4 ClickHouse 同步

B 阶段不镜像 t_ledger 到 ClickHouse（独立 stage 后续做）。FX symbol 自动落入既有 `quote_tick` 表，无需额外 schema。

### 4.5 索引清单（新增）

| 表 | 索引 | 用途 |
|---|---|---|
| t_symbol_leverage_tier | `idx_symbol_group_lower (symbol, group_code, notional_lower)` | tier 解析主索引 |

---

## 5. 全品种 Tier Seed（10 模板 + 映射规则）

### 5.1 10 个 Tier 模板

#### T1 顶级 crypto（BTC/ETH 系列）

| Tier | Notional (USDT) | Max Lev | MM Rate |
|---|---|---|---|
| 1 | 0 – 50K | 125x | 0.4% |
| 2 | 50K – 250K | 100x | 0.5% |
| 3 | 250K – 1M | 50x | 1.0% |
| 4 | 1M – 5M | 20x | 2.5% |
| 5 | 5M – 20M | 10x | 5.0% |
| 6 | 20M+ | 5x | 10.0% |

#### T2 主流 crypto（top 30）

| Tier | Notional | Max Lev | MM Rate |
|---|---|---|---|
| 1 | 0 – 50K | 75x | 0.5% |
| 2 | 50K – 200K | 50x | 1.0% |
| 3 | 200K – 1M | 25x | 2.0% |
| 4 | 1M – 5M | 10x | 5.0% |
| 5 | 5M+ | 5x | 10.0% |

#### T3 长尾 crypto

| Tier | Notional | Max Lev | MM Rate |
|---|---|---|---|
| 1 | 0 – 10K | 25x | 2.0% |
| 2 | 10K – 50K | 10x | 5.0% |
| 3 | 50K+ | 5x | 10.0% |

#### T4 稳定币对

| Tier | Notional | Max Lev | MM Rate |
|---|---|---|---|
| 1 | 0 – 100K | 50x | 1.0% |
| 2 | 100K – 1M | 20x | 2.5% |
| 3 | 1M+ | 10x | 5.0% |

> **🔴 2026-06-03 T5-T10 修正（满杠杆瞬时强平）**：T5-T10 原稿每档 MM Rate = 1/MaxLev（lev×mm=0.99~1.0），即满杠杆开仓时维持保证金占满初始保证金、强平缓冲≈0——按对手价成交后标记价（点差）立即低于强平价 → 瞬时强平（demo 实证：AUDCAD 300x 开仓 199ms 被强平，强平价距离仅 0.33 pip < 0.5 pip 点差）。修正为与 T1-T4 既有准则一致：**MaxLev × MM Rate ≤ 0.5**（满杠杆时 MM = IM 的 50%，强平缓冲 ≥ 50% IM），T5-T10 全部 MM 减半。DB CHECK（§4.2 ≤1.0）保持为硬下限不变，本准则为模板设计软约束。存量库经 trading V38 UPDATE 修正（`mm_rate>0.5/max_leverage` 的行减半），开仓已冻结的 `mm_rate_at_open` 不回溯。

#### T5 主流 FX（G10 直接含 USD）

| Tier | Notional | Max Lev | MM Rate |
|---|---|---|---|
| 1 | 0 – 100K | 500x | 0.1% |
| 2 | 100K – 500K | 200x | 0.25% |
| 3 | 500K – 2M | 100x | 0.5% |
| 4 | 2M – 10M | 50x | 1.0% |
| 5 | 10M+ | 20x | 2.5% |

#### T6 G10 FX 交叉盘

| Tier | Notional | Max Lev | MM Rate |
|---|---|---|---|
| 1 | 0 – 50K | 300x | 0.165% |
| 2 | 50K – 250K | 100x | 0.5% |
| 3 | 250K – 1M | 50x | 1.0% |
| 4 | 1M+ | 20x | 2.5% |

#### T7 次级 FX（含 SEK/NOK/DKK/HKD/SGD/CZK）

| Tier | Notional | Max Lev | MM Rate |
|---|---|---|---|
| 1 | 0 – 50K | 200x | 0.25% |
| 2 | 50K – 250K | 100x | 0.5% |
| 3 | 250K – 1M | 50x | 1.0% |
| 4 | 1M+ | 20x | 2.5% |

#### T8 新兴市场 FX（含 CNH/MXN/TRY/ZAR/PLN/HUF/BYN）

| Tier | Notional | Max Lev | MM Rate |
|---|---|---|---|
| 1 | 0 – 25K | 100x | 0.5% |
| 2 | 25K – 100K | 50x | 1.0% |
| 3 | 100K – 500K | 20x | 2.5% |
| 4 | 500K+ | 10x | 5.0% |

#### T9 贵金属

| Tier | Notional | Max Lev | MM Rate |
|---|---|---|---|
| 1 | 0 – 50K | 200x | 0.25% |
| 2 | 50K – 200K | 100x | 0.5% |
| 3 | 200K – 1M | 50x | 1.0% |
| 4 | 1M – 5M | 20x | 2.5% |
| 5 | 5M+ | 10x | 5.0% |

> 注：T9 tier3 原稿 2.5% 笔误（50×0.025=1.25 违反 §4.2 CHECK max_lev×mm_rate≤1.0），STAGE-14C1 修正为 2.0%（与 T6/T7 同档一致）；2026-06-03 随 T5-T10 整体减半为 1.0%。

#### T10 股指 + 能源

| Tier | Notional | Max Lev | MM Rate |
|---|---|---|---|
| 1 | 0 – 100K | 100x | 0.5% |
| 2 | 100K – 500K | 50x | 1.0% |
| 3 | 500K – 2M | 20x | 2.5% |
| 4 | 2M+ | 10x | 5.0% |

### 5.2 品种到模板映射规则

R4 实施时按以下规则脚本展开 INSERT：

```
CASE
  WHEN symbol IN ('BTCUSDT','BTCUSD','BTCUSDC','BTCFDUSD','BTCEUR',
                  'ETHUSDT','ETHUSD','ETHUSDC')              → T1
  WHEN base_currency IN ('SOL','BNB','XRP','ADA','DOGE','AVAX','LINK','DOT',
                         'TRX','POL','LTC','BCH','ATOM','NEAR','FIL',
                         'ARB','OP','APT','SUI','TON','ETC','HBAR','ICP',
                         'AAVE','UNI','RENDER','TIA','SEI',
                         'PEPE','SHIB','PAXG','XAUT')         → T2
  WHEN base_currency IN ('USDC','USDD','TUSD','PYUSD','FDUSD') 
       AND quote_currency IN ('USDT','USD')                   → T4
  WHEN category = 1                                            → T3
  WHEN category = 2 AND
       (base_currency='USD' AND quote_currency IN ('EUR','GBP','JPY','CHF','CAD','AUD','NZD'))
    OR (quote_currency='USD' AND base_currency IN ('EUR','GBP','JPY','CHF','CAD','AUD','NZD','MXN'))
                                                              → T5
  WHEN category = 2 AND
       (base_currency IN ('SEK','NOK','DKK','HKD','SGD','CZK')
     OR quote_currency IN ('SEK','NOK','DKK','HKD','SGD','CZK'))
                                                              → T7
  WHEN category = 2 AND
       (base_currency IN ('CNH','MXN','TRY','ZAR','PLN','HUF','BYN')
     OR quote_currency IN ('CNH','MXN','TRY','ZAR','PLN','HUF','BYN'))
                                                              → T8
  WHEN category = 2                                            → T6
  WHEN category = 3 OR base_currency IN ('XAU','XAG','XPT','XPD')
                                                              → T9
  WHEN category IN (4, 5)                                      → T10
END
```

R4 实施时由 Java/Python 脚本读 t_symbol 全表按规则展开，输出 V30 完整 INSERT SQL（约 500 symbol × 平均 4-6 档 = 约 2500 行）。

---

## 6. 状态机

### 6.1 t_account.margin_mode 切换状态机

```
ISOLATED (default)
    │  ▲
    │ POST /api/v1/me/margin-mode (用户)
    ▼  │
闸门校验 (事务 + SELECT FOR UPDATE):
  1. 无 OPEN 持仓
  2. 无 ACTIVE 挂单
  3. 不在冷静期 (mode_cooling_until IS NULL OR < NOW())
  4. 与目标模式不同
    │ pass            │ fail → 30080/30081/30082/30083
    ▼
UPDATE t_account SET margin_mode=<target>, mode_changed_at=NOW(),
    mode_cooling_until = NOW() + interval admin_config (默认 5min)
    │
    ▼
发布 Kafka: falconx.trading.account.mode.changed
STAGE-8 通知: ACCOUNT_MODE_CHANGED
    │
    ▼
CROSS (与 ISOLATED 对称)
```

冷静期默认 5 分钟（admin 可配 60s - 7d）。

### 6.2 MarginLevel 阶段状态

```
HEALTHY (MarginLevel > marginCallLevel=100%)
    │                          ▲
    │ MarginLevel ≤ 100%       │ MarginLevel > 100%
    ▼                          │
MARGIN_CALL (100% ≥ ML > stopOut=30%)
    推送 STAGE-8 MARGIN_CALL_TRIGGERED
    同用户 5 分钟节流
    │                          ▲
    │ ML ≤ 30%                 │ ML > 100% (恢复)
    ▼                          │
STOP_OUT (ML ≤ 30%)
    ISOLATED: 强平触发单仓
    CROSS:    按浮亏最大优先逐仓强平直到 ML > 30%
    推送 STAGE-8 STOP_OUT_TRIGGERED
```

状态不落 schema 列，`MarginLevelMonitor` 内存维护 `Map<userId, LastNotificationState>` 用于节流。

### 6.3 强平流程

#### ISOLATED 强平

```
QuoteDrivenEngine.onTick(symbol, quote, fxRateUpdate)
    │
    ▼
for each OPEN position with this symbol:
    ① liqPrice 触发 (markPrice ≤ liqPrice BUY || ≥ SELL)
    ② MarginLevel 触发 (Equity_i / MM_i ≤ 30%)
    │
    ▼ if ① OR ②
TradingPositionCloseApplicationService.liquidate(positionId)
    - SELECT FOR UPDATE t_position
    - 计算 realizedPnL + fxRateAtClose
    - UPDATE position status='CLOSED', close_reason='LIQUIDATION'
    - INSERT t_ledger biz_type=9 + 三列填充
    - UPDATE t_account: balance += pnl, frozen -= margin, margin_used -= margin
    │
    ▼
发布 Kafka: falconx.trading.position.closed (reason='LIQUIDATION')
STAGE-8 通知: POSITION_LIQUIDATED
```

#### CROSS 强平

```
QuoteDrivenEngine.onTick (任意 symbol 或 FX update)
    │
    ▼
AccountEquityCalculator.recompute(userId):
    Equity = balance + frozen + Σ uPnL_i
    TotalMM = Σ MM_i
    marginLevel = Equity / TotalMM
    │
    ▼ if marginLevel ≤ 30%:
排序 OPEN 持仓 by |uPnL(AC)| DESC
    │
    ▼
for position in sortedPositions:
    liquidate(position)
    recompute marginLevel
    if marginLevel > 30%: break
    │
    ▼
STAGE-8 通知: CROSS_STOP_OUT_TRIGGERED (含强平的仓位清单)
```

同一用户的强平流程必须串行（user-level lock，复用现有 userId 分区机制）。

### 6.4 挂单触发资金不足状态机（D13）

```
PendingOrderTriggerEvaluator.onTick
    │
    ▼
触发条件命中
    │
    ▼
ISOLATED 模式资金校验:
  1. IM(AC) = fillPrice × qty / lev × fx(QC→AC)
  2. tier = LeverageTierResolver.resolve(symbol, notional(AC), group_code)
  3. lev > tier.max_leverage → 30070
  4. balance - frozen < IM(AC) → 30071
    │ pass            │ fail
    ▼                 ▼
正常开仓流程    UPDATE pending_order SET status='CANCELLED',
                       cancel_reason='INSUFFICIENT_MARGIN',
                       cancelled_at=NOW()
                    │
                    ▼
                发布 Kafka: falconx.trading.pending.cancelled
                STAGE-8 通知: PENDING_TRIGGER_INSUFFICIENT_MARGIN
                错误码: 30084
```

CROSS 模式：资金校验改为"开仓后 marginLevel ≥ marginCallLevel"，不满足同样 CANCELLED。

### 6.5 FX rate 服务降级状态机（D11）

```
FX_HEALTHY (最近 FX update < 30s)
    │                          ▲
    │ > 30s 无更新              │ 行情恢复
    ▼                          │
FX_STALE
    使用最后一次 FX rate
    banner: "FX rate 过期 Xs"
    ops 告警 + Prometheus metric
    正常下单/平仓/强平继续可用
    │                          ▲
    │ stale > 5min              │ 恢复
    ▼                          │
FX_PAUSED → 升级为 GLOBAL_PAUSE
    按 t_fx_pause_behavior 按品种类目决定允许操作
    （admin 可配 8 类目 × 3 开关 = 24 项行为；默认 forex/metal 停开仓+停被动强平，其余允许）
    admin 告警 + 站内信
```

配置项：
- `falconx.market.fx.stale-threshold-seconds` = 30
- `falconx.market.fx.pause-threshold-seconds` = 300

### 6.6 对既有状态机的影响

| 既有状态机 | 影响 | 处理 |
|---|---|---|
| Position close_reason | 增加 `CROSS_STOP_OUT` | 状态机规范补一条 |
| PendingOrderTrigger cancel_reason | 增 `INSUFFICIENT_MARGIN`, `LEVERAGE_EXCEEDS_TIER` | 同上 |
| GLOBAL_PAUSE | 新增触发源 FX_PAUSED | BBOOK-RISK-CONTROL-01 文档补 |

---

## 7. 契约 / 事件 / 错误码 / REST 接口

### 7.1 Kafka Topics

| 阶段 | Topic | Producer | Consumer | Payload |
|---|---|---|---|---|
| A | `falconx.market.fx.rate.update` | market | trading-core, console | FxRateSnapshotPayload (1Hz 节流) |
| C | `falconx.trading.tier.changed` | trading-core | (内部缓存刷新) | LeverageTierChangedEventPayload |
| D | `falconx.trading.account.mode.changed` | trading-core | console (审计) + identity (通知) | AccountMarginModeChangedEventPayload |
| D | `falconx.trading.pending.cancelled` | trading-core | console (审计) | PendingOrderCancelledEventPayload |

复用既有 topic：
- `falconx.trading.position.closed`：close_reason 枚举扩 `CROSS_STOP_OUT`

### 7.2 契约 DTO

#### falconx-market-contract

```java
public record FxRateSnapshotPayload(
    String baseCurrency,
    String quoteCurrency,
    BigDecimal rate,
    long eventTimeMillis,
    String sourceLpCode,
    String sourceSymbol
) {}
```

#### falconx-trading-contract

```java
public record AccountMarginModeChangedEventPayload(
    Long userId, String oldMode, String newMode,
    long changedAtMillis, Long coolingUntilMillis
) {}

public record PendingOrderCancelledEventPayload(
    Long userId, Long pendingOrderId, String pendingOrderType,
    String cancelReason, long cancelledAtMillis
) {}

public record LeverageTierChangedEventPayload(
    String symbol, String groupCode,
    long changedAtMillis, Long changedByAdminId
) {}
```

### 7.3 错误码段汇总

#### trading-core 新增（3xxxx）

| Code | 名称 | 阶段 |
|---|---|---|
| 30070 | LEVERAGE_EXCEEDS_TIER | C |
| 30071 | INSUFFICIENT_MARGIN_FOR_OPEN | C |
| 30072 | TIER_CONFIG_NOT_FOUND | C |
| 30073 | FX_RATE_UNAVAILABLE | B |
| 30080 | MODE_HAS_OPEN_POSITIONS | D |
| 30081 | MODE_HAS_ACTIVE_PENDING | D |
| 30082 | MODE_COOLING_PERIOD_ACTIVE | D |
| 30083 | MODE_NO_CHANGE | D |
| 30084 | PENDING_AUTO_CANCELLED_INSUFFICIENT_MARGIN | D |
| 30085 | POSITION_NOT_ISOLATED | D |
| 30086 | SUPPLEMENT_AMOUNT_INVALID | D |
| 30087 | GLOBAL_PAUSE_ACTIVE | B/C |

#### console-service 新增（9xxxx）

| Code | 名称 | 阶段 |
|---|---|---|
| 90930 | ADMIN_TIER_NOT_FOUND | C |
| 90931 | ADMIN_TIER_VALIDATION_FAILED | C |
| 90932 | ADMIN_TIER_OVERLAP | C |
| 90940 | ADMIN_FX_RATE_NOT_FOUND | A |
| 90950 | ADMIN_MARGIN_MODE_CONFIG_INVALID | D |
| 90951 | ADMIN_FX_PAUSE_BEHAVIOR_INVALID | D |

#### market-service 新增（6xxxx）

| Code | 名称 | 阶段 |
|---|---|---|
| 60010 | FX_SYMBOL_NOT_FOUND | A |
| 60011 | FX_RATE_STALE | A |

### 7.4 REST 接口签名

#### 用户端（trading-core）

| Path | Method | 描述 |
|---|---|---|
| `/api/v1/me/margin-mode` | GET | 返回当前 mode + cooling_until + can_switch + blockers |
| `/api/v1/me/margin-mode` | POST | Body `{targetMode}`，错误 30080-30083 |
| `/api/v1/me/positions/{id}/supplement-margin` | POST | Body `{amount}`，ISOLATED 追加，错误 30085/30086/30087 |

#### Admin Internal RPC（trading-core）

| Path | Method | 用途 |
|---|---|---|
| `/internal/v1/trading/console/tier` | GET / POST | tier 列表 / 新建 |
| `/internal/v1/trading/console/tier/{id}` | PUT / DELETE | tier 编辑 / 软删 |
| `/internal/v1/trading/console/account-margin-modes` | GET | 用户模式审计 |
| `/internal/v1/trading/console/config/cooling-period` | PUT | 改冷静期 |

#### Admin REST（console-service）

| Path | 透传到 | RBAC |
|---|---|---|
| `/admin/trading/tier/*` | trading-core | `tier:view` / `tier:edit` |
| `/admin/market/fx/*` | market-service | `fx:view` |
| `/admin/market/fx/pause-behavior/*` | market-service | `fx:pause-behavior:edit` (高危) |
| `/admin/config/margin-mode/*` | trading-core | `margin-mode-config:edit` (高危) |

### 7.5 WebSocket 消息扩展（B/C/D 阶段同步切换，无 legacy 兼容，直接 break）

#### 用户端 `position.update`（扩展）

```json
{
  "channel": "position.update",
  "data": {
    "positionId": "...", "symbol": "EURAUD", "side": "BUY",
    "qty": "10000", "entryPrice": "1.6500", "markPrice": "1.6498",
    "quoteCurrency": "AUD",
    "fxRate": "0.6498",
    "unrealizedPnlInQuote": "-2.00",
    "unrealizedPnlInAccount": "-1.30",
    "isolatedMargin": "53.625",
    "liquidationPrice": "1.6418"
  }
}
```

#### 用户端 `account.update`（扩展）

```json
{
  "channel": "account.update",
  "data": {
    "balance": "100.00", "frozen": "53.625", "marginUsed": "53.625",
    "equity": "98.70",
    "marginLevel": "184.00",
    "marginLevelStatus": "HEALTHY",
    "marginMode": "ISOLATED"
  }
}
```

#### Admin 新增 channel

- `admin.fx.rate.update`（1Hz 节流推送所有 FX rate）
- `admin.account.mode.changed`（用户切换模式事件流）

### 7.6 配置项汇总（admin 可配）

| 配置 key | 默认 | 范围 |
|---|---|---|
| `falconx.trading.risk.stop-out-level` | 0.30 | 0.05 - 0.95 |
| `falconx.trading.risk.margin-call-level` | 1.00 | 0.50 - 2.00 |
| `falconx.trading.margin-mode.cooling-period-seconds` | 300 | 60 - 604800 |
| `falconx.market.fx.stale-threshold-seconds` | 30 | 5 - 300 |
| `falconx.market.fx.pause-threshold-seconds` | 300 | 60 - 3600 |
| `falconx.trading.tier.cache-refresh-seconds` | 30 | 5 - 300 |

FX_PAUSED 各品种类目行为由 `t_fx_pause_behavior` 表承载。stop_out_level 同时写 t_risk_config 兜底。其余统一进 STAGE-13 配置中心。

---

## 8. 测试覆盖矩阵

### 8.1 总计

| 阶段 | UT | IT | Vitest | E2E | PERF |
|---|---|---|---|---|---|
| A | 5 | 8 | — | 2 | 1 |
| B | 12 | 15 | — | 2 | 1 |
| C | 18 | 22 | 3 | 3 | 2 |
| D | 15 | 20 | 5 | 4 | **2** |
| E | — | 8 | 12 | 3 | — |
| **总计** | **50** | **73** | **20** | **14** | **6** |

D 阶段补 PERF：CROSS 强平高并发场景（1000 用户同时跌穿 30% MarginLevel，强平队列吞吐与延迟）。

### 8.2 关键 TC 用例骨架

详见各阶段子 spec（R2 派发时落地为 STAGE-14A/B/C/D/E-test-cases.md 子文件，共 163 条骨架）。

### 8.3 验收硬约束（每阶段 R7 必须证实）

#### 通用（每阶段）
- mvn compile + test-compile BUILD SUCCESS
- 涉及服务 mvn test 全过
- 前端 npm test/lint/build 三件套通过（涉及前端阶段）
- 文档同步完成（架构 / DB / 接口 / Kafka / 错误码 / 状态机）
- Git 回滚点 push 到 main
- 当前开发计划 §1 阶段收口条目录入

#### A 阶段
- 8 个 FX symbol Redis 实时可见
- internal RPC `/internal/v1/market/fx/rates` 200 OK + payload 完整
- `falconx.market.fx.rate.update` Kafka tick 持续 5min 无中断
- FX stale 状态告警通路联通

#### B 阶段
- EURAUD 端到端开/平仓 t_ledger 三列填充正确
- 老数据回填脚本执行后 sample 100 行抽查全部 original_currency=USDT, fx_rate=1
- PnL 推送 P99 < 100ms（1000 用户压测）
- CurrencyConverter 同币种 short-circuit 性能验证

#### C 阶段
- 全 10 模板边界 tier 切换在 IT 中各覆盖 1 次
- 200x XAUUSD 大单（落入 tier 3/4）下单被拒 30070 + 错误消息清晰
- StopOut 触发后强平到落账整链 < 500ms
- admin 改 tier 在 30s 内生效
- CHECK 约束 max_lev × mm_rate ≤ 1.0 在 DB 层生效
- **全 500 symbol tier seed 实际值人工抽查 + 自动校验脚本**（每模板抽 5 个 symbol 核对 tier 数据匹配模板）

#### D 阶段
- 切换闸门 4 项（OPEN/挂单/冷静期/同模式）单独验证
- 5min 冷静期到期后允许再次切换
- CROSS 强平排序"浮亏最大优先"在 IT 中验证 3 仓位场景
- FX_PAUSED 状态下 8 类目 × 3 开关组合行为正确
- 升级窗口期 t_position.isolated_margin 回填验证（停服 5min 演练）
- **CROSS 强平高并发：1000 用户同时跌穿 30% MarginLevel，强平队列吞吐 ≥ 100 ops/s，单仓强平 P99 < 500ms**

#### E 阶段
- 客户端桌面 + 移动浏览器 QA 截图 6 张
- admin 桌面浏览器 QA 截图 6 张
- WebSocket break 字段客户端 / admin 同步切换无 legacy 残留

### 8.4 已知不阻断项预留

每阶段都可能产生 R6 二轮或 WSL chromium 限制等已知不阻断项，统一在 R7 收口报告 §5 标注，不计入阻断条件。

---

## 9. 实施路径与回滚点（方案 A：分 5 阶段）

每阶段独立 R2→R8 三端协作 cycle，每阶段 commit + tag 作为回滚点。

| 阶段 | 范围 | 阶段产物 |
|---|---|---|
| A | market-service FX 数据源 | V15 seed + FxRateService + Redis + Kafka topic + RPC + stale 监控 |
| B | trading-core 货币转换 + 账本留痕 | V28 + V29 + CurrencyConverter + 算法层接 converter + 老数据回填 |
| C | trading-core MarginLevel + Tier + console tier 配置 | V30 + V31 + AccountEquityCalculator + MarginLevelMonitor + LeverageTierResolver + admin tier CRUD UI |
| D | trading-core CROSS/ISOLATED + console 模式配置 | V32 + V33 + 切换闸门 + 追加保证金 + CROSS 强平 + FX_PAUSED 按类目行为 + admin 冷静期配置 UI |
| E | 三端 UI + admin 多币种聚合 | 客户端 mode toggle + MarginLevel 浮窗 + 双币 PnL + admin 多币聚合报表 |

### 9.1 阶段间依赖

```
A → B → C → D → E
```

不允许 A 未完成就跑 B 的代码。每阶段都有独立 R7 收口报告。

---

## 10. 风险与已知技术债

| 风险 | 处理 |
|---|---|
| D2 全部实时可能引发 FX 跳点强平风暴 | 不单独设保护（D12），依赖 LP halt + FX stale 超时检测 + admin 可配 FX_PAUSED 行为 |
| FX 服务挂掉时强平算不出来 | 用最后一次 FX rate + 告警；超 5min 进 FX_PAUSED + 按类目控制行为（D11） |
| isolated_margin 回填并发风险 | 升级窗口期停服 5 分钟执行（D 阶段决策） |
| WebSocket break 字段 | 客户端/admin/服务端 B/C/D 阶段同步切换，无 legacy 兼容 |
| FX 大幅波动导致 IM 增长 > balance | 暂不加 MARGIN_DEFICIT 护栏，留口子未来需要再补 |

---

## 11. 文档与代码追溯

### 11.1 替换/废弃的既有内容

- `CROSS-MARGIN-EXEC-01`（[`docs/process/统一问题清单.md`](docs/process/统一问题清单.md) P1 项）→ 本设计 D 阶段覆盖，可归档
- `TradingCoreServiceProperties.settlementToken = "USDT"` 硬编码 → B 阶段废弃，读 `t_account.currency`

### 11.2 同步更新文档清单

- `docs/database/falconx一期数据库设计.md`：补 t_ledger 三列 / t_symbol_leverage_tier / t_fx_pause_behavior / t_account 三字段 / t_position isolated_margin + entry_fx_rate
- `docs/api/REST接口规范.md`：补用户端 3 接口
- `docs/api/管理端接口规范.md`：补 tier/FX/margin-mode 配置接口
- `docs/api/WebSocket接口规范.md`：补 position.update / account.update / admin.fx.rate.update / admin.account.mode.changed 字段
- `docs/event/Kafka事件规范.md`：补 4 个新 topic
- `docs/domain/状态机规范.md`：补 margin_mode 切换 / MarginLevel 阶段 / FX rate 降级 / pending cancel 状态机
- `docs/process/BBook一期完成执行路径.md`：新增 §15 STAGE-14 五子阶段
- `docs/process/统一问题清单.md`：归档 CROSS-MARGIN-EXEC-01

### 11.3 子 spec 文件（R2 派发时落地）

- `docs/design/STAGE-14A-FX-DATA-design.md`
- `docs/design/STAGE-14B-CURRENCY-CONVERTER-design.md`
- `docs/design/STAGE-14C-MARGIN-LEVEL-TIER-design.md`
- `docs/design/STAGE-14D-CROSS-ISOLATED-design.md`
- `docs/design/STAGE-14E-MULTICURRENCY-UI-design.md`
- 每个子 spec 对应 `docs/test/STAGE-14X-test-cases.md`

---

## 12. 决策对话来源

本设计基于 2026-05-28 与项目主导者的 brainstorming 对话沉淀，全部决策有完整对话记录与可追溯的"业界推荐做法"依据。详见会话记忆：

- 14 项业务决策（D1-D14）由 AskUserQuestion 逐题对齐
- 实施路径（D15）由方案 A/B/C 三选一对齐
- 全品种 tier 模板与映射规则由实际 V2 + V3 seed 数据校验

— END —
