# FalconX v1 数据库设计

## 1. 定位

本文件服务于 FalconX v1 的最终架构：

`GODSA (Gateway-Orchestrated Domain Core Services Architecture)`

数据库策略固定为：

- `Database per Service`
- 一期先使用 `1` 个 MySQL 实例
- `1` 个 ClickHouse 实例
- 每个服务拥有独立 MySQL schema

## 2. 一期物理布局

推荐在同一 MySQL 实例内创建 4 个 schema：

- `falconx_identity`
- `falconx_market`
- `falconx_trading`
- `falconx_wallet`

同时为 `market-service` 创建 ClickHouse database：

- `falconx_market_analytics`

说明：

- `gateway` 不持有业务数据库
- `identity / market / trading-core / wallet` 各自只写自己的 schema
- 服务之间不共享业务表
- K 线和报价历史由 `market-service` 写 ClickHouse，不写 MySQL

## 3. 设计原则

- 一期只支持真实盘
- 一期不做分表
- 所有金额字段统一使用 `DECIMAL(24,8)`
- 高频 tick 不直接落主交易库
- 资金流水必须可重放
- 不依赖物理外键维持跨服务一致性
- 跨服务一致性依赖事件、幂等和状态机

## 4. schema 与 owner

### 4.1 `falconx_identity`

owner 服务：

- `falconx-identity-service`

核心表：

- `t_user`
- `t_refresh_token_session`
- `t_inbox`

职责：

- 邮箱密码账号
- 密码哈希
- Refresh Token 一次性会话
- 用户状态
- 邮箱可信度事实
- 激活时间

### 4.2 `falconx_market`

owner 服务：

- `falconx-market-service`

核心表：

- `t_symbol`
- `t_trading_hours`
- `t_trading_hours_exception`
- `t_trading_holiday`
- `t_swap_rate`
- `t_symbol_quote_mapping`
- `t_symbol_group_visibility`
- `t_symbol_group_markup`（STAGE-12 V14，用户组×symbol 双向 bid/ask 绝对加点配置）
- `t_outbox`

职责：

- 品种元数据
- 交易时间周规则
- 交易时间例外规则
- 交易节假日规则
- 隔夜利息费率
- 平台 symbol 到报价源 symbol 的映射配置，以及是否参与 LP 实时报价订阅
- 用户组维度的 symbol 可见性配置
- 用户组维度的 symbol 双向加点配置（`t_symbol_group_markup`，STAGE-12）：market 行情出参（REST/WS）按 `X-User-Group-Code` 加 bid/ask 绝对加点，trading 开仓时把 (group_code, bid_extra, ask_extra) 冻结到 `t_position` / `t_pending_order_trigger` 供 PnL/强平/挂单触发全生命周期一致使用（见 §4.3）
- 市场事件发件箱

ClickHouse 核心表：

- `quote_tick`
- `kline`

说明：

- 最新价格以 Redis 为准
- 交易时间快照由 `market-service` 写 Redis，`trading-core-service` 只读 Redis 校验交易时段
- ClickHouse 保存报价历史和 K 线历史
- MySQL 只保存低频元数据和 Outbox
- `t_symbol.lp_code + t_symbol.symbol` 表示某个 LP 上游源 symbol 及其上游元数据；`t_symbol_quote_mapping.platform_symbol` 表示 FalconX 页面展示和交易 symbol，`source_lp_code + source_symbol` 表示实际订阅的 LP 源身份。`platform_symbol` 是系统 Symbol，创建后不可改名；改名必须新建 mapping 并停用旧 mapping。默认 LP 导入品种为 1:1 映射，自定义平台 symbol 通过乘数和 Bid/Ask 绝对加点映射到 LP 源报价。`t_symbol_quote_mapping.lp_subscribe_enabled` 控制该平台 symbol 是否参与 LP 实时报价订阅与源报价映射，默认 `1` 保持现有行为；接口不按 `.p / .c / .f` 或其他后缀做特殊限制，Symbol 是否可见、可订阅、可报价统一由三表数据与状态决定。
- `identity.t_user.group_code` 由 gateway 透传为 `X-User-Group-Code`，market-service 使用 `t_symbol_group_visibility` 过滤用户可见 platform symbol；不可见 symbol 对外按 `30001 Symbol Not Found` 处理。
- `t_swap_rate.symbol`、`t_trading_hours.symbol` 和 `t_trading_hours_exception.symbol` 统一关联 `t_symbol_quote_mapping.platform_symbol`；Swap 与交易时段不再直接挂 `t_symbol.symbol`。
- **R2 三轮冻结决议（任务卡 [`STAGE-2-SYMBOL-PARAMS-DOWNSHIFT`](../process/task-cards/STAGE-2-SYMBOL-PARAMS-DOWNSHIFT.md)，待 R4/R9/R10 实施）**：
  - `t_symbol` 降级为**纯 LP 源元数据表**，字段裁剪：删除 `max_leverage / taker_fee_rate / spread / min_qty / max_qty / min_notional`；保留 `id / lp_code / symbol / category / market_code / base_currency / quote_currency / price_precision / qty_precision / status / created_at / updated_at`，其中 `lp_code + symbol` 唯一标识上游源，`category / market_code / price_precision / qty_precision` 只表达上游源元数据，不作为 FalconX 系统级配置真源。不加 `last_tick_at`，死 symbol 检测由 ClickHouse `falconx_market_analytics.quote_tick.event_time` 聚合查询提供（market internal RPC `GET /internal/v1/market/symbols/last-tick`）
  - `t_symbol_quote_mapping` 升级为**系统唯一 Symbol 配置真源**，字段扩展：新增 `source_lp_code / category / market_code / max_leverage / taker_fee_rate / spread / min_qty / max_qty / min_notional / price_precision / qty_precision`（必填 NOT NULL，DB CHECK 约束 category 1-8 / leverage 1-500 / fee 0-0.05 / spread ≥0 / max>min / notional ≥0 / precision 0-10）。同步把 `created_at / updated_at` 升级到 `datetime(3)` 与 t_symbol 对齐
  - market 在 mapping CRUD `afterCommit` 把完整 `SymbolSpec`（含 leverage/fee/spread/qty/precision）写入 Redis Hash `falconx:market:symbol-spec:{platformSymbol}`，trading-core 通过该快照消费，禁止跨 schema 直查 `falconx_market`
    - **STAGE-14B（2026-05-29）**：`falconx-market-contract.SymbolSpec` record 新增 `baseCurrency` / `quoteCurrency` 两字段（market-service `RedisMarketSymbolSpecRepository.toSpec` 从 owner `t_symbol` 数据填充；trading-core 货币转换消费）。**向后兼容**：旧 Redis 快照无此两字段时 Jackson 反序列化为 `null`，market 重发布快照后补齐；trading-core 消费侧对 `quoteCurrency` 为 `null` 做防守（开仓拒单或降级，见 [事务与幂等规范](../architecture/事务与幂等规范.md) §6.10）
  - Flyway 编号：market `V11__downshift_symbol_params_to_mapping.sql`（market schema）+ console `V2__migrate_symbol_update_to_source_update.sql`（console RBAC 权限码迁移）+ trading `V13__add_open_fee_rate_snapshot.sql`（trading 加快照字段）
  - Phase A（schema 改 + 管理端可配）与 Phase B（trading-core 消费）必须**同一发布窗口**完成，分开部署 = 调配不生效

### t_symbol — STAGE-14A V18 baseline（2026-05-29）

V18 通过 `ON DUPLICATE KEY UPDATE` 确保 8 个核心 FX symbol 在 V2 已 seed 的基础上规范化为 STAGE-14A 数据基线。

| symbol | base | quote | price_precision | qty_precision | category | market_code |
| --- | --- | --- | --- | --- | --- | --- |
| EURUSD | EUR | USD | 5 | 2 | 2 forex | FX |
| AUDUSD | AUD | USD | 5 | 2 | 2 forex | FX |
| USDJPY | USD | JPY | 3 | 2 | 2 forex | FX |
| GBPUSD | GBP | USD | 5 | 2 | 2 forex | FX |
| USDCAD | USD | CAD | 5 | 2 | 2 forex | FX |
| USDCHF | USD | CHF | 5 | 2 | 2 forex | FX |
| NZDUSD | NZD | USD | 5 | 2 | 2 forex | FX |
| USDCNH | USD | CNH | 5 | 2 | 2 forex | FX |

commit `ae9b84a`，占位 ID 2001-2008（V5 已占用 ID 1-1870，幂等命中 `uk_symbol_lp_symbol(lp_code, symbol)`）。

### 4.3 `falconx_trading`

owner 服务：

- `falconx-trading-core-service`

核心表：

- `t_account`
- `t_ledger`
- `t_deposit`
- `t_order`
- `t_position`
- `t_trade`
- `t_risk_exposure`
- `t_risk_config`
- `t_symbol_leverage_tier`（STAGE-14C1 V30，杠杆/MM 双重分级表）
- `t_fx_pause_behavior`（STAGE-14C1 V31，FX_PAUSED 各类目操作开关）
- `t_hedge_log`
- `t_liquidation_log`
- `t_outbox`
- `t_inbox`

职责：

- 账户
- 账本
- 入账后的业务入金事实
- 订单
- 持仓
- 成交
- B-book 净敞口
- 风控参数
- B-book 风险观测日志
- 强平日志

说明：

- 一期把 `trade + fund + risk` 放在同一事务边界内
- `wallet` 不直接写这些表
- **trading-core 不读 `falconx_market` schema**：杠杆/费率/qty 等 symbol 参数通过 Redis Hash `falconx:market:symbol-spec:{platformSymbol}` 消费（owner 在 market-service）。这是 owner 边界硬约束
- **R2 三轮冻结决议（任务卡 [`STAGE-2-SYMBOL-PARAMS-DOWNSHIFT`](../process/task-cards/STAGE-2-SYMBOL-PARAMS-DOWNSHIFT.md)，待 R4 实施）**：
  - `t_order` 与 `t_position` 各新增 `open_fee_rate decimal(10,6) NOT NULL DEFAULT 0`：开仓时把当时的 `mapping.taker_fee_rate` 快照入表，后续平仓 / 强平 / Swap 按快照值计算费率，**不再回查 mapping**，保证运营改 mapping 后历史持仓的费率口径不被反向影响
  - `t_risk_config.max_leverage`（trading-core 自有的风控运维兜底）与 `mapping.max_leverage`（market 侧的产品配置上限）并存，开仓校验取 `min(mapping.max_leverage, risk_config.max_leverage)` 作为 effective max；前者由阶段 5.5 风控管理 Tab 编辑，后者由阶段 5.X 的 Symbol 管理 Tab 编辑
  - Flyway 编号建议：trading `V13__add_open_fee_rate_snapshot.sql`
- **STAGE-14B 货币转换 + 账本三列留痕（V28 / V29，已落地，2026-05-29）**：
  - `t_ledger` 新增 3 列（trading `V28__ledger_currency_columns.sql`），承担原币种历史留痕：
    - `original_amount DECIMAL(24,8) NOT NULL`：原币金额（quote currency）
    - `original_currency VARCHAR(16) NOT NULL`：原币种代码（如 USD / EUR / JPY）
    - `fx_rate_at_settlement DECIMAL(24,8) NOT NULL`：结算/成交/开仓时刻的 FX rate（original→account currency）
    - 老数据回填口径：`original_amount = amount`、`original_currency = COALESCE(t_account.currency, 'USDT')`、`fx_rate_at_settlement = 1.0`（migration 先 `ADD COLUMN` 为 NULL → `UPDATE` 回填 → `MODIFY COLUMN` 改 NOT NULL）
    - 落账自洽性约束见 [`事务与幂等规范`](../architecture/事务与幂等规范.md)「写账三列口径与自洽性」
  - `t_position` 新增 `entry_fx_rate DECIMAL(24,8) NOT NULL DEFAULT 1.0`（trading `V29__position_open_fx_snapshot.sql`）：开仓时 `fx(quote→account)` 快照，**仅审计留痕，不参与强平价计算**；老数据回填 1.0
- **STAGE-14C1 杠杆/MM 分级 + MarginLevel 阈值 + 开仓冻结列（V30 / V31 / V32，已落地，2026-05-29）**：
  - 新建 `t_symbol_leverage_tier`（trading `V30__symbol_leverage_tier.sql`）—— 杠杆/MM 双重分级表：
    - 列：`id`（PK，自 30000001 递增）、`symbol VARCHAR(32)`、`group_code VARCHAR(32) NOT NULL DEFAULT 'default'`、`tier_no TINYINT`、`notional_lower DECIMAL(24,8)`、`notional_upper DECIMAL(24,8) NULL`（NULL=无上限，最高档）、`max_leverage INT`、`mm_rate DECIMAL(8,6)`、`enabled TINYINT NOT NULL DEFAULT 1`、`created_at`、`updated_at`
    - 约束：`UNIQUE(symbol, group_code, tier_no)`；`INDEX(symbol, group_code, notional_lower)`；`CHECK(max_leverage * mm_rate <= 1.0)`
    - **档位解析口径**：按 notional（账户币 AC）落档，`notional_lower` 含、`notional_upper` 不含（`[lower, upper)`）；解析见 [`状态机规范`](../domain/状态机规范.md) §6.2 与 [`事务与幂等规范`](../architecture/事务与幂等规范.md) MarginLevel 章节
    - **seed 生成方式**：build-time 静态快照，由 `tools/tier-seed/generate_tier_seed.py` 读 owner（market）`falconx_market.t_symbol`（`status=1`，1572 symbol），按 master 设计 §5.2 CASE 映射 §5.1 的 T1–T10 模板生成；`category 6(stock)/7(etf)` 复用 T10（股指+能源）模板；`default` 组，共 6311 行。trading-core 运行时不跨 schema 直读 `falconx_market`
    - master §5.1 勘误：T9 tier3 原稿 50x×2.5%=1.25 违反 CHECK，已修正为 50x/2.0%（与 T6/T7 同档一致，50×0.02=1.0），已回写 master §5.1
  - `t_risk_config` 新增 2 列（trading `V31__risk_config_thresholds_fx_pause_behavior.sql`）—— 账户级 MarginLevel 阈值（平台全局行）：
    - `stop_out_level DECIMAL(8,6) NOT NULL DEFAULT 0.30`：StopOut 强平阈值（MarginLevel）
    - `margin_call_level DECIMAL(8,6) NOT NULL DEFAULT 1.00`：MarginCall 告警阈值
    - 三态判定与状态机见 [`状态机规范`](../domain/状态机规范.md) §6.2；config SDK 接入延后
  - 新建 `t_fx_pause_behavior`（同 trading `V31`）—— FX_PAUSED 各品种类目允许的操作开关：
    - 列：`category TINYINT PRIMARY KEY`、`category_name VARCHAR(32)`、`allow_open TINYINT`、`allow_close TINYINT`、`allow_liquidation TINYINT`、`updated_at`、`updated_by_admin_id BIGINT NULL`
    - seed 8 类目：`forex(2)/metal(3)` 默认停开仓 + 停被动强平（仅允许平仓 `allow_open=0/allow_close=1/allow_liquidation=0`），其余类目（crypto/index/energy/stock/etf/other）默认全允许
    - **范围说明**：按类目读 `t_fx_pause_behavior` 落地 FX_PAUSED 行为（Task 10）因 category 来源缺失划归 C2/D，C1 未接入运行时路径
  - `t_position` 新增 2 列（trading `V32__position_tier_freeze.sql`）—— 开仓冻结（强平价/MM 计算用）：
    - `mm_rate_at_open DECIMAL(8,6) NOT NULL DEFAULT 0.005`：开仓时 tier mmRate 冻结（替换原硬码 properties 0.005），`AFTER entry_fx_rate`（V29 已加）
    - `tier_no_at_open TINYINT NOT NULL DEFAULT 1`：开仓时 tier 档位号冻结（审计）
    - 老数据回填：`mm_rate_at_open=0.005 / tier_no_at_open=1`（与历史强平价口径一致；migration 先 `ADD COLUMN` NULL → `UPDATE` 回填 → `MODIFY COLUMN` NOT NULL DEFAULT）
  - Flyway 约定：V30–V32 均不写 `USE <schema>;`（沿 §10 强约束与 STAGE-14B root bug 教训），schema 由连接绑定决定
- **STAGE-14D1 用户级 margin mode 切换列 + 通知模板（V33 / V34，已落地，2026-06-01）**：trading `t_account` 加 `mode_changed_at` / `mode_cooling_until`（DATETIME(3) NULL，V33）+ ACCOUNT_MODE_CHANGED 站内信模板 seed（V34）。结构小增量，详见 [统一接口文档 §3.31](../api/FalconX统一接口文档.md) / [状态机规范 §6.4](../domain/状态机规范.md)；均不写 `USE`。
- **STAGE-14D2 CROSS 强平 liqPrice 放开 NULL + CROSS 强平通知模板（V35 / V36，已落地，2026-06-01）**：
  - `t_liquidation_log.liquidation_price` 改 **NULL**（trading `V35__liquidation_log_nullable_liquidation_price.sql`）—— CROSS 账户级强平按账户级 MarginLevel 判据触发，单仓 `t_position.liquidation_price` 为 NULL（CROSS 仓不落单仓强平价），强平日志须允许 `liquidation_price` 为 NULL 才能记录 CROSS 强平事实。
    - **计划未预见的 schema 阻断（已修）**：原 `t_liquidation_log.liquidation_price` 为 NOT NULL，CROSS 强平写日志时会因 liqPrice=null 撞约束失败；V35 `MODIFY COLUMN ... NULL` 放开。ISOLATED 强平仍落真实 liqPrice 不受影响。
  - 新增 `CROSS_STOP_OUT_TRIGGERED` 站内信模板 seed（trading `V36__seed_cross_stop_out_notification.sql`）—— 账户级 CROSS 强平汇总通知（params：`marginLevel` / `count` / `symbols`），逐仓 `POSITION_LIQUIDATED` 去重后由编排器一次汇总发送，见 [Kafka 事件规范 §12.16](../event/Kafka事件规范.md) / [状态机规范 §6.6](../domain/状态机规范.md)。
  - Flyway 约定：V35 / V36 均不写 `USE <schema>;`（沿 §10 强约束），干净顺序 migration。
- **STAGE-14D3a 冷静期落库可配（V37，已落地，2026-06-01）**：trading `t_risk_config` 平台行（`symbol IS NULL`，C1 V31 已承载 `stop_out_level` / `margin_call_level`）`ADD COLUMN cooling_period_seconds INT NOT NULL DEFAULT 300 AFTER margin_call_level`（trading `V37__risk_config_cooling_period.sql`）—— margin mode 切换冷静期改从硬编码（properties `cooling-duration=5m`）转为 admin 运行时可配（范围 60-604800 秒，默认 300=5min；缺失回退 properties 默认）。`MarginModeSwitchApplicationService` 改从该列直读（低频用户动作，无缓存即时生效）；StopOut/MarginCall 阈值写后由 `DefaultMarginLevelMonitor` 30s TTL 自然生效。配置写经 trading-core internal RPC（见 [统一接口文档 §3.32](../api/FalconX统一接口文档.md) / [管理端接口规范 §4.3a](../api/管理端接口规范.md)）。Flyway 约定：V37 不写 `USE <schema>;`，带 DEFAULT 的常规加列对既有行无破坏（**非「计划未预见的 schema 阻断」**，不同于 D2 V35）。
  - **`isolated_margin` 不加列的结论（D3a 评估作废）**：D3a 评估后**不**给 `t_position` 加 `isolated_margin` 列。依据：D1/D2 已豁免——`t_position.margin` + `marginMode` 字段已足够区分 CROSS/ISOLATED 并承载逐仓保证金，D2 CROSS 账户级强平不依赖单仓 `margin` 区分；master §4.2 升级窗口 `isolated_margin` 回填演练（停服 5min）相应作废（无需）。
  - FX_PAUSED 8 类目行为表 `t_fx_pause_behavior`（C1 V31 建表 + seed 8 行，物理在 `falconx_trading`）D3a 加 admin 写路径（`updateByCategory` + `updated_by_admin_id` 审计列写入）；表结构不变，无新 migration（写经 trading-core internal RPC）。

### 4.4 `falconx_wallet`

owner 服务：

- `falconx-wallet-service`

核心表：

- `t_wallet_address`
- `t_wallet_deposit_tx`
- `t_wallet_chain_cursor`

职责：

- 地址分配
- 原始链上交易记录
- 监听游标与确认推进

说明：

- `wallet` 管原始链事实
- `trading-core` 管最终入账事实
- 入金页默认地址由 `wallet-service` 幂等分配，当前冻结为每个用户申请 `TRC20 / TRON / USDT` 与 `ERC20 / ETH / USDT` 两条地址
- 地址生成必须基于 account-level xpub 的非硬化公钥派生，服务内不得保存 mnemonic、私钥或可花费密钥
- 若 `FALCONX_WALLET_ETH_ACCOUNT_XPUB` 或 `FALCONX_WALLET_TRON_ACCOUNT_XPUB` 缺失，入金地址申请必须 fail-closed，不得退回 stub 地址
- `t_wallet_address` 通过 `token / network / derivation_path` 记录入金资产、前端展示网络和 HD 派生路径；历史迁移保留 `legacy:*` 路径用于识别旧 stub 地址

跨服务唯一标识冻结：

- `falconx_wallet.t_wallet_deposit_tx.id` 是 wallet owner 产出的稳定原始交易主键
- 该主键在跨服务协作中统一命名为 `walletTxId`
- `falconx_trading.t_deposit.wallet_tx_id` 作为 wallet -> trading 的唯一业务幂等键
- `chain + txHash` 仅保留为链上检索字段，不再作为跨服务唯一业务主键
- 原始链事实若存在“一笔交易多条 transfer”的场景，wallet owner 必须通过 `log_index` 拆分事实，再由 `walletTxId` 对外输出稳定标识

金额口径冻结：

- `falconx_wallet.t_wallet_deposit_tx.amount` 存储的是**已按 token decimals 归一化后的业务金额**，不是链上最小单位 raw amount
- `falconx_trading.t_deposit.amount` 同样存储归一化后的业务金额，并与 `wallet-service` 事件 payload 中的 `amount` 保持同一语义
- 一期金额统一保留到 `DECIMAL(24,8)` / `DECIMAL(20,8)` 对应的 `8` 位小数，不在 wallet 层额外裁剪成 `2` 位
- 链上最小单位（如 `wei`、ERC20 的 `10^decimals`、SPL token 最小单位）只允许存在于监听器 / SDK 解析阶段，进入 owner 持久化、事件 payload 和业务入账前必须先完成显式换算
- 若 token decimals 未知、token metadata 未冻结或无法可靠换算，listener 必须 fail-closed：不生成 `ObservedDepositTransaction`，只记录告警日志，等待后续补齐 metadata 后再接入

原始入金事实冻结：

- `falconx_wallet.t_wallet_deposit_tx` 必须预留：
  - `token_contract_address`
  - `log_index`
- 原始链事实唯一键冻结为：
  - `(chain, tx_hash, log_index)`
- 原生币转账统一使用：
  - `token_contract_address = null`
  - `log_index = 0`
- ERC20 / SPL / TRC20 等日志型转账必须以真实日志索引作为 `log_index`

业务入账事实冻结：

- `falconx_trading.t_deposit` 的业务幂等键统一切换为：
  - `wallet_tx_id`
- `chain + tx_hash` 仍保留为检索字段和审计字段，不再承担唯一约束语义
- `trading-core-service` 必须按 `walletTxId` 做 confirmed / reversed 的业务入账与回滚幂等

### 4.4A STAGE-7-WITHDRAW 出金链路新增表（Phase 0 冻结，2026-05-14）

owner 拆分：

- `falconx_trading.t_withdraw_order`：trading-core owner（驱动状态机 + 持有 user_id + 余额冻结/释放/结算事务）
- `falconx_wallet.t_withdraw_tx`：wallet-service owner（链上签名 + nonce + tx_hash + 确认数）
- `falconx_wallet.t_withdraw_whitelist`：wallet-service owner（地址白名单 + 24h 冷静期）

跨服务唯一键：

- `t_withdraw_order.id` 是出金请求的全局稳定主键，跨服务事件 payload 中统一命名 `withdrawId`
- `t_withdraw_tx.withdraw_order_id` 关联回 trading-core 主表，1:1（同一 withdraw 不会广播多次 tx；重试是新 nonce 但相同 withdraw_order_id 会 UPSERT 而非新增）
- `t_withdraw_tx.(network, from_address, nonce)` UNIQUE，保证 nonce 在同链同地址内不重复
- `t_withdraw_whitelist.(user_id, network, address, status)` UNIQUE，REMOVED 后允许重新添加（status 进入唯一键避免软删除冲突）

t_ledger biz_type 扩展（trading-core）：

| biz_type | 含义 | 触发时机 |
| --- | --- | --- |
| `12` | `withdraw_freeze` | 用户提交（balance 不变，frozen += amount） |
| `13` | `withdraw_refund_cancel` | 用户冷静期取消（frozen -= amount） |
| `14` | `withdraw_refund_reject` | admin 拒绝（frozen -= amount） |
| `15` | `withdraw_refund_emergency` | admin 紧急取消（frozen -= amount） |
| `16` | `withdraw_settle` | 链上确认（frozen -= amount，balance -= amount） |
| `17` | `withdraw_refund_chain_failed` | 链上失败（frozen -= amount） |

完整 schema 蓝图见 [`docs/sql/STAGE-7-WITHDRAW-blueprint.sql`](../sql/STAGE-7-WITHDRAW-blueprint.sql)；Phase 1+ 实施时拷贝为 trading V21 + wallet V5 + V6 Flyway 文件。

## 5. 核心数据边界

### 5.1 用户可用状态与入金事实

`identity-service` 只维护用户账户可用状态。

当前语义冻结为：

- 注册完成后用户状态直接为 `ACTIVE`
- `t_user.activated_at` 表示账户可用时间，新注册用户创建时写入
- `PENDING_DEPOSIT` 仅作为历史兼容状态，不作为用户可见状态，不作为登录拦截状态，不再作为新注册默认值
- 用户是否已入金由 `trading-core-service` 的入金事实、账户资金状态或后续冻结的独立入金历史标记表达，不得通过 `t_user.status` 表达
- `t_user.email_verified` 表示邮箱可信度事实，注册默认 `0`，不改写 `t_user.status`
- 一期不发送验证邮件，不生成验证 token，不提供验证端点，不因 `email_verified=0` 限制登录、交易或 Refresh

入金完成链路仍由 `wallet-service` 发出入金确认事件，`trading-core-service` 完成入账并发布 `falconx.trading.deposit.credited`。`identity-service` 消费该事件时写入 `t_inbox` 做幂等留痕；若历史用户仍为 `PENDING_DEPOSIT`，归一化为 `ACTIVE` 并补齐 `activated_at`。不允许跨库直写。

### 5.2 入金边界

原始链交易归 `wallet`：

- 地址
- 交易哈希
- 区块高度
- 确认数
- 原始状态

业务入账归 `trading-core`：

- `t_account`
- `t_ledger`
- `t_deposit`

这两层不要混在一起。

### 5.3 风控边界

一期不再有独立 `risk-service` 数据库。

风险参数和强平日志都归：

- `falconx_trading`

原因：

- 风控与订单、持仓、保证金是强一致链路
- 一期没必要先拆成独立服务和独立库

## 6. 核心账户语义

`falconx_trading.t_account` 语义冻结如下：

- `balance`：账户总现金余额
- `frozen`：已预留未确认金额
- `margin_used`：已占用保证金
- `margin_mode`（V11 新增）：账户级默认保证金模式偏好（`1=cross / 2=isolated`），一期默认 ISOLATED，CROSS 为预留扩展占位
- `available = balance - frozen - margin_used`

下单 inheritance 规则（STAGE-0-INFRA-EXT-01）：

- 显式传 `marginMode` → 用请求值
- 不传 → inherit `t_account.margin_mode`
- 兜底 → ISOLATED
- 一期硬约束：最终 marginMode 不为 ISOLATED 时返回 `MARGIN_MODE_NOT_SUPPORTED`
- 创建持仓时把最终模式落库到 `t_position.margin_mode`，账户级 `margin_mode` 后续变更不影响已有持仓

与之配套的 `falconx_trading.t_ledger` 必须同时记录：

- `balance_before / balance_after`
- `frozen_before / frozen_after`
- `margin_used_before / margin_used_after`

这样账本才能支持完整回放与审计，避免只回放余额而无法解释冻结和保证金占用变化。

动作规则：

- 预留保证金：`frozen += margin`
- 成交确认：`frozen -= margin`，`margin_used += margin`
- 扣手续费：`balance -= fee`
- 平仓结算：`margin_used -= margin`，`balance += pnl`
- 取消订单：`frozen -= margin`

禁止再次出现：

- 同时扣 `balance` 又增加 `frozen` 的双扣模型

## 6.1 持仓语义补充

`falconx_trading.t_position` 的正式产品规则已在 `SPEC-TRD-001` 冻结为“净持仓主事实表”，口径如下：

- 单用户单 `symbol` 任一时刻最多只允许存在一条 `OPEN` 净持仓
- 不允许多张同方向独立逐仓仓位
- 不允许同时持有相反方向仓位
- 净持仓模型下保留“每用户每 `symbol` 一个稳定 `positionId`”
- 同向下单视为加仓
- 反向下单且数量小于当前净仓时，视为减仓
- 反向下单且数量等于当前净仓时，当前净仓减为 `0` 并进入终态
- 反向下单且数量大于当前净仓时，先把当前净仓减为 `0`，剩余数量翻为反向净仓
- 后续正式实现中，加仓、减仓、穿零翻仓都在同一稳定 `positionId` 上演进，不再额外创建新的独立持仓主键
- `unrealized_pnl` 不持久化，不写 MySQL
- 未实现盈亏由查询层基于 `entry_price + 当前 mark_price` 动态计算
- `take_profit_price / stop_loss_price` 作为净持仓级触发价持久化到 `t_position`

字段语义：

- `t_position` 表达“当前净持仓状态”，承载当前方向、净数量、均价、保证金、杠杆、TP/SL、强平价和状态
- `t_trade` 表达每次开仓、减仓、平仓、强平的离散成交事实，不能被 `t_position` 替代
- `t_ledger` 表达保证金、手续费、已实现盈亏、强平损益、Swap 等账务事实，不能被 `t_position` 替代

说明：

- 上述规则是正式产品冻结口径，不等于当前仓库已完成实现切换
- 当前仓库仍存在按独立 `positionId` 管理 `OPEN` 持仓的历史实现事实；后续若进入实现阶段，必须同步调整接口契约、状态机迁移、风控计算和快照索引语义

## 6.2 净敞口语义补充

`falconx_trading.t_risk_exposure` 代表 B-book 平台对每个品种的实时净暴露：

- `total_long_qty`
- `total_short_qty`
- `net_exposure = total_long_qty - total_short_qty`
- `net_exposure_usd = net_exposure * mark_price`

要求：

- 开仓、平仓、强平必须与订单/持仓写入处于同一本地事务内更新该表
- 报价刷新到 fresh tick 时，只重算 `net_exposure_usd`，不改动数量口径净敞口
- 阈值判断读取 `falconx_trading.t_risk_config.hedge_threshold_usd`
- 超阈值与恢复到阈值内都要写入 `falconx_trading.t_hedge_log`
- 超阈值时额外发布服务内 Spring Event stub，供 BBook 自营风险告警链路接入；`t_hedge_log` 仍是 owner 审计事实
- 当前阶段只落地“Spring Event stub + 告警日志 + 审计留痕”的可观测性基础，不代表 BBook 自营风控闭环已完成，也不新增 Kafka topic 契约；FalconX 一期不做 A-book 对冲执行出口
- 该表用于实时风控视图，不替代订单与持仓明细事实

## 6.3 负净值保护

`falconx_trading.t_liquidation_log` 增加：

- `platform_covered_loss`
- `margin_mode`

含义：

- 强平后若亏损超过账户可承受范围，账户余额归零
- 超出的兜底金额记入 `platform_covered_loss`
- 一期不允许把用户账户打成负余额
- `margin_mode` 记录强平发生时持仓的保证金模式，当前真实写入值为 `2=ISOLATED`，来源于 `t_position.margin_mode`

## 6.4 交易时间管理方案 B

交易时间管理固定采用方案 B，并补齐节假日规则。

固定结构：

- `t_symbol_quote_mapping.market_code`（系统级市场维度；`t_symbol.market_code` 仅保留上游源元数据）
- `t_trading_hours`
- `t_trading_hours_exception`
- `t_trading_holiday`

结构语义：

- `category`：固定为 `1=CRYPTO,2=FX,3=METAL,4=INDEX,5=ENERGY,6=STOCK,7=ETF,8=OTHER`
- `market_code`：把品种映射到更细粒度的市场维度，当前取值包括 `CRYPTO / FX / METAL / INDEX / ENERGY / US_STOCK / HK_STOCK / JP_STOCK / ETF / OTHER`
- `t_trading_hours`：定义按 `platform_symbol` 重复生效的基础交易时段，可支持多段 session
- `t_trading_hours_exception`：面向单个 `platform_symbol` 的人工覆盖规则，优先级最高
- `t_trading_holiday`：面向 `market_code` 的节假日规则，用于休市、提前收盘和晚开盘

运行时优先级固定为：

1. `t_trading_hours_exception`
2. `t_trading_holiday`
3. `t_trading_hours`

运行时约束：

- `market-service` owner 持久化这些规则
- `market-service` 的 `t_symbol` 初始化数据以 LP MT5 symbol 快照为准；`V5__replace_lp_mt5_symbols.sql` 使用 `/Users/ives/Desktop/mt5_symbols.csv` 生成 `1839` 条 LP symbol，`V10__delete_lp_suffix_symbols.sql` 曾按当时快照口径删除 `.p / .c / .f` 后缀变体，当前 `t_symbol` 保留 `1581` 条，其中 `1571` 条为 `status=1`。后续管理端接口不得继续按后缀做特殊限制
- `TradeMode` 非 `4` 的 LP symbol 写入 `status=2 suspended`；这些记录只作为品种元数据储备，不进入当前 `status=1` 且映射开启 LP 订阅的运行时行情订阅白名单
- LP symbol 必须保留原始代码与普通市场后缀，例如 `AAPL.NAS`；不得在订阅、解析或本地白名单过滤时删除 `.` 后缀，也不得按后缀做特殊拒绝。只要 `source_lp_code + source_symbol` 存在于 `t_symbol.lp_code + t_symbol.symbol` 且该源 `status=1`、mapping 启用订阅，就可以进入对应 LP 的 `symbolList`
- `V6__seed_lp_trading_hours.sql` 为当时 1:1 platform symbol 补齐基础周规则；V16 起 `t_trading_hours.symbol` / `t_trading_hours_exception.symbol` 由外键约束到 `t_symbol_quote_mapping.platform_symbol`，新增自定义 platform symbol 的交易时段必须在 admin 报价映射行中按 platform symbol 配置
- `t_trading_holiday` 是 market_code 维度的节假日 owner 表，admin 市场假期 tab 负责新增、编辑和删除；节假日变更会触发 market-service 刷新 Redis 交易时间快照
- `market-service` 启动时把每个启用 mapping 的 `platform_symbol` 交易时间快照写入 Redis；节假日按该 mapping 的系统级 `market_code` 拼装
- `market-service` 运行时白名单只依赖 `t_symbol_quote_mapping.enabled=1 AND lp_subscribe_enabled=1`，并要求 `source_lp_code + source_symbol` 对应的 `t_symbol.status=1`；运行时订阅的是当前 LP 配置对应的 `source_symbol`，推送和查询展示的是 `platform_symbol`。运行时不得按 `.p / .c / .f` 或其他后缀做二次过滤；系统级 `category / market_code / price_precision / qty_precision` 从 `t_symbol_quote_mapping` 读取
- `trading-core-service` 下单只读 Redis 快照，不跨服务读取 `falconx_market`
- 新开仓在非交易时段返回 `40008: Symbol Trading Suspended`
- 已存在持仓的手动平仓同样受交易时间校验阻塞，非交易时段返回 `40008`；TP/SL 和强平不得因休盘 tick 生成新的成交或强平事实

## 6.5 Stage 6A 已存在的平仓终态字段与逐仓预留字段

`2026-04-19` 通过 `V5__manual_close_and_margin_mode.sql` 已真实落地下列字段：

- `t_position.margin_mode`
- `t_position.close_price`
- `t_position.close_reason`
- `t_position.realized_pnl`
- `t_position.closed_at`
- `t_trade.trade_type`

当前真实写入语义：

- `margin_mode` 当前只写 `2=isolated`
- 手动平仓成功时写 `close_price / close_reason=1(manual) / realized_pnl / closed_at`
- TP/SL 自动触发成功时写 `close_price / close_reason=2(take_profit) 或 3(stop_loss) / realized_pnl / closed_at`
- 强平成功时写 `close_price / close_reason=4(liquidation) / realized_pnl / closed_at`，并额外写 `t_liquidation_log`
- 开仓成交写 `trade_type=1(open)`；手动平仓与 TP/SL 写 `trade_type=2(close)`；强平写 `trade_type=3(liquidation)`
- 手动平仓与 TP/SL 成功时同事务写 `t_outbox.event_type=trading.position.closed`；强平成功时写 `t_outbox.event_type=trading.liquidation.executed`
- 上述事实用于当前 `Stage 6A` 交易链路核对，不等于 `Stage 7 / 7A` 已进入验收完成
- 后续 `SPEC-TRD-001` 又冻结了“单用户单 `symbol` 单净持仓 + 稳定 `positionId`”规则；因此本节描述的是当前仓库已存在字段与历史写入事实，不代表净持仓模型已经实现落地

`2026-04-22` 已在 `Stage 7A` 首批逐仓增强子范围内真实补齐：

- `t_ledger.biz_type=10 isolated_margin_supplement`
- `POST /api/v1/trading/positions/{positionId}/margin` 对应的 owner 账务事实
- 追加保证金后的 `t_position.margin / liquidation_price` 同事务更新

`2026-04-28` 通过 `V8__liquidation_log_margin_mode.sql` 补齐 `STAGE7A-ISOLATED-02` 强平审计字段：

- `t_liquidation_log.margin_mode TINYINT NOT NULL DEFAULT 2`
- 强平日志写入时从 `t_position.margin_mode` 同步落盘
- `GET /api/v1/trading/liquidations` 响应项通过该字段回显 `marginMode`

`2026-05-08` 通过 `V11__account_margin_mode.sql` 补齐 `STAGE-0-INFRA-EXT-01` 账户级默认保证金模式字段：

- `t_account.margin_mode TINYINT NOT NULL DEFAULT 2`
- 下单 marginMode 按 inheritance 规则：显式传 → 用请求值；不传 → inherit `t_account.margin_mode`；兜底 → ISOLATED
- 创建持仓时把最终模式落库到 `t_position.margin_mode`；账户级模式后续变更不影响已有持仓
- 一期保留 `TradingMarginMode.CROSS` 枚举值作为扩展占位，但任何 CROSS 下单仍返回 `MARGIN_MODE_NOT_SUPPORTED`
- `GET /api/v1/trading/accounts/me` 响应增加 `marginMode` 字段（`CROSS`/`ISOLATED`）

`2026-05-21` 通过 `V27__position_pending_order_group_markup_freeze.sql` 补齐 `STAGE-12-GROUP-MARKUP` 用户组加点冻结字段（V21 已被 withdraw 占用，故编号 V27）：

- `t_position` 加 `group_code_at_open` / `bid_extra_at_open` / `ask_extra_at_open`（开仓时冻结用户组加点快照，默认 `default`/0）
- `t_pending_order_trigger` 加 `group_code_at_create` / `bid_extra_at_create` / `ask_extra_at_create`（挂单创建时冻结，触发时用冻结值不重算）
- 行情侧（market `t_symbol_group_markup`，V14）按 `X-User-Group-Code` 实时加点，交易侧开仓即冻结到 position，PnL / 强平 effectiveMark / 平仓 / 挂单触发全生命周期一致使用冻结值，避免加点配置变更影响存量持仓

这批字段和账务语义的定位如下：

- 一期当前运行时仍按“只支持 `ISOLATED`，已存在手动平仓、TP/SL、强平终态持久化事实，并已完成追加保证金、强平价重算、旧强平价失效和强平审计 `marginMode` 能力，但未进入 `CROSS` 模式”执行
- `margin_mode` 只作为后续逐仓完善与全仓预留的扩展入口
- `close_price / close_reason / realized_pnl / closed_at` 用于把手动平仓、TP/SL、强平的终态信息持久化到 `t_position`
- `trade_type` 用于区分 `OPEN / CLOSE / LIQUIDATION`
- `biz_type=10 isolated_margin_supplement` 用于追加保证金账本记录
- `t_liquidation_log.margin_mode` 用于冻结强平发生时的保证金模式审计事实
- 追加保证金成功时：
  - `t_account.balance / frozen` 不变
  - `t_account.margin_used += amount`
  - `t_position.margin += amount`
  - `t_position.liquidation_price` 按最新 `margin` 重算
  - 不新增 Kafka topic / payload，也不写 Outbox 业务事件

文档约束：

- 不得把上述事实夸大为“`CROSS` 已实现”
- 不得把 `Stage 7 / 7A` 已验收反推为“生产可用”
- 后续继续扩展时，必须沿现有 Flyway 版本继续新增 migration，不能改历史 migration

## 7. 索引原则

索引只围绕一期真实查询路径建立：

- `identity`：邮箱登录、状态筛选
- `market`：symbol 查询，历史报价和 K 线按 ClickHouse 时间范围查询
- `trading`：账户查询、账本时间线、订单幂等、持仓扫描、强平审计
- `wallet`：按地址查用户、按链和确认状态轮询交易、按游标续扫

## 8. ClickHouse 写入策略

一期固定采用下面的市场数据持久化策略：

- 每个 tick 写 Redis 最新价：`falconx:market:price:{symbol}`，短 TTL，只服务实时交易与最新报价查询
- 每个 fresh tick 同步刷新展示用最后有效参考价：`falconx:market:last-valid-price:{symbol}`，长 TTL，只服务首页、列表和休盘展示，不允许用于成交
- 每个 tick 先进入内存缓冲队列，ClickHouse `quote_tick` 默认每 10 秒定时批量写入一次；`quote-batch-size=200` 表示每批 INSERT 的最大记录数，运行时低于 1 会按 1 执行，高于 10000 会按 10000 执行
- 当前未收盘 K 线只保留在内存或 Redis 聚合态
- K 线收盘时写一条最终 K 线到 ClickHouse `kline`

这样做的目的：

- 把高频写压力从 MySQL 转移到 ClickHouse
- 保持查询历史报价和 K 线的能力
- 避免 MySQL 承担每 tick 更新 K 线的写压力

## 9. SQL 文档说明

当前仓库中的 SQL 文档位于：

- [V1__init_schema.sql](../sql/V1__init_schema.sql)
- [V2__seed_symbols.sql](../sql/V2__seed_symbols.sql)
- [V3__seed_historical_crypto_symbols.sql](../sql/V3__seed_historical_crypto_symbols.sql)（历史 symbol 储备脚本，仅保留数据库演进事实；当前唯一生产报价源为 LP）
- [CH_V1__market_analytics.sql](../sql/CH_V1__market_analytics.sql)

它们现在表示的是：

- FalconX v1 的数据库蓝图文档
- 与当前 owner 服务中的运行时 migration 保持一致

当前运行时 migration 目录：

- `falconx-identity-service/src/main/resources/db/migration/`
- `falconx-market-service/src/main/resources/db/migration/`
- `falconx-trading-core-service/src/main/resources/db/migration/`
- `falconx-wallet-service/src/main/resources/db/migration/`

## 10. Flyway 接入约定

Flyway 已在 `Stage 5` 正式接入 owner 服务。

当前规则：

- 每个 owner 服务只管理自己的 migration
- migration 只允许变更本服务 owner schema
- 文档 SQL 蓝图与运行时 migration 必须保持一致
- **migration 不得写 `USE <schema>;`**（强约束）：目标 schema 一律由数据源连接绑定决定，migration 内只写 DDL/DML。一旦写 `USE <schema>;`，IT/隔离测试库的 DDL 会被强制打到固定 schema，造成跨库污染与 IT 永久失败（STAGE-14B V28/V29 曾误写 `USE` 触发该问题，已修复并删除）

目录约定：

- `falconx-identity-service/src/main/resources/db/migration/`
- `falconx-market-service/src/main/resources/db/migration/`
- `falconx-trading-core-service/src/main/resources/db/migration/`
- `falconx-wallet-service/src/main/resources/db/migration/`

命名规则：

- 与当前蓝图保持一致，使用 `V{版本号}__{描述}.sql`
- MySQL 与 ClickHouse migration 分开维护，不混在同一目录
- ClickHouse migration 由 `market-service` 自己管理
## 11. 当前结论

FalconX v1 的数据库设计已经从“单库里按模块 owner 划分表”升级为：

- `MySQL + ClickHouse`
- `MySQL 多 schema`
- `每服务独立 owner`

其中最关键的结构性决定是：

- `market` 拥有报价历史与 `K线`
- `wallet` 拥有原始链交易
- `trading-core` 拥有账户、账本、订单、持仓、风控和最终入账事实
- `identity` 拥有用户主表、收件箱以及 Refresh Token 一次性会话持久化
