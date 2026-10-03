# STAGE-12-GROUP-MARKUP R7 验证报告

> 验证日期：2026-05-21
> 验证人：Claude Opus 4.7（在 R1 Commander 调度下作为 R7）
> 任务：`STAGE-12-GROUP-MARKUP` 用户组双向加点全链路（market + trading + console + console-frontend）

---

## §1. 任务范围

- **核心需求**：在已有 `t_symbol_quote_mapping` 平台基准加点（Layer 1）之上叠加一层用户组级 bid/ask 双向加点（Layer 2）
- **冻结策略**：`t_position` / `t_pending_order_trigger` 冻结 `(groupCode, bidExtra, askExtra)` 至生命周期结束
- **影响范围**：WS 推送 / REST 报价 / 撮合 fillPrice / 平仓 exitPrice / unrealized PnL / 强平判定 / 挂单触发
- **豁免范围**：K 线 / ClickHouse `quote_tick` / Kafka tick 严格保持基准价（分析层数据中性）

不在 R7 范围（后续会话推进）：
- 浏览器 QA 6 截图（需要前端 dev server + admin login）
- 性能压测（需要 LP 真接入 + JMH 基准）
- WebSocket 多组分桶 E2E IT（TC-GM-006/007，需要 StandardWebSocketClient）
- trading-core 真 RPC refresh 30s 端到端 IT（TC-GM-013，需要真启动 market-service）

---

## §2. 三端代码闭环（[`完成定义`](../process/完成定义.md) §4.A 三端硬约束）

| 端 | 已落地 | 路径 |
| --- | --- | --- |
| 客户端 | ✅ 透过 X-User-Group-Code header 自动加点（gateway 透传） | market-service REST/WS + trading-core 撮合 |
| 业务后端 | ✅ market-service Layer 2 markup snapshot + trading-core 8 改造点 | `falconx-market-service/...service/MarketGroupMarkupService` + `falconx-trading-core-service/...service/TradingGroupMarkupService` |
| 管理端后端 | ✅ console-service 7 端点透传 + RBAC + 错误码 90640~90642 | `falconx-console-service/.../controller/AdminSymbolsController` (§group-markup) + `AdminGroupMarkupApplicationService` |
| 管理端前端 | ✅ GroupMarkupListPage + 新建/编辑/删除 Modal + 9 Vitest | `falconx-console-frontend/src/features/symbol-group-markup/` |

---

## §3. 数据库迁移

| Migration | 内容 |
| --- | --- |
| `falconx-market-service V14__symbol_group_markup.sql` | 新建 `t_symbol_group_markup` 表（PK = (group_code, platform_symbol)，CHECK 范围 ±1M），seed default 组 0/0 全 symbol |
| `falconx-trading-core-service V27__position_pending_order_group_markup_freeze.sql` | `t_position` 加 `group_code_at_open / bid_extra_at_open / ask_extra_at_open`，`t_pending_order_trigger` 加 `group_code_at_create / bid_extra_at_create / ask_extra_at_create` |

---

## §4. 测试矩阵（64 个测试全部通过）

### 4.1 market-service (25 测试)

| 测试类 | 数 | 覆盖 |
| --- | ---: | --- |
| `DefaultMarketGroupMarkupServiceTests` (UT) | 9 | TC-GM-UT-001~007 find / applyMarkup / refresh / disabled 剔除 |
| `StandardQuoteTests` (UT) | 4 | TC-GM-UT-011~012 withExtraMarkup 零开销回退 + bid/ask/mid 重算 |
| `MarketGroupMarkupInternalControllerIntegrationTests` (IT) | 6 | TC-GM-001~005 完整 CRUD + bulkUpsert 上限 + 增量端点 + 错误码 + token filter |
| `MarketQueryControllerGroupMarkupIntegrationTests` (IT) | 4 | TC-GM-008~010 REST quote / history / symbols 按 X-User-Group-Code 加点 |
| `MarketAnalyticsClickHouseGroupMarkupIntegrationTests` (IT) | 2 | TC-GM-011/012 ClickHouse quote_tick / K 线 OHLC 落基准价（**不含 markup**） |

### 4.2 trading-core (31 测试)

| 测试类 | 数 | 覆盖 |
| --- | ---: | --- |
| `DefaultTradingGroupMarkupServiceTests` (UT) | 4 | TC-GM-UT-020~021 find / 归一化 / RPC refresh 替换快照 |
| `TradingPricingSupportTests` (UT) | 8 | TC-GM-UT-022~024 + 4 原有 PnL 参数化（含 markup 自动加） |
| `TradingOpenPositionGroupMarkupIntegrationTests` (IT) | 4 | TC-GM-014~015 BUY/SELL fillPrice = (ask/bid) + (askExtra/bidExtra) + 冻结字段落库 |
| `TradingClosePositionGroupMarkupIntegrationTests` (IT) | 4 | TC-GM-016~017 平仓 round-trip PnL + 持仓期改 markup 不影响 |
| `TradingUnrealizedPnLGroupMarkupIntegrationTests` (IT) | 2 | TC-GM-018 unrealized PnL 含冻结 markup |
| `TradingLiquidationGroupMarkupIntegrationTests` (IT) | 2 | TC-GM-019 强平按 effective = bid + bidExtraAtOpen 判定（含反向 markup 真进入判定） |
| `TradingPendingOrderGroupMarkupIntegrationTests` (IT) | 5 | TC-GM-020/022 LIMIT/STOP 冻结 + evaluator 按 frozen 判定（运营改值不影响存量挂单） |
| `TradingGroupMarkupConfigChangePropagationIntegrationTests` (IT) | 2 | TC-GM-023/024 config 传播：新仓用新值 / 存量持仓口径稳定 |

### 4.3 console-service (8 测试)

| 测试类 | 数 | 覆盖 |
| --- | ---: | --- |
| `AdminSymbolGroupMarkupEndpointIntegrationTests` (IT) | 8 | TC-GM-030~034 7 端点透传 + 错误码 90640/90642 翻译 + RBAC + bulk 本地 @Size(500) 校验 + delete 强制 reason |

### 4.4 console-frontend (9 Vitest)

| 测试类 | 数 | 覆盖 |
| --- | ---: | --- |
| `groupMarkupApi.test.ts` | 9 | TC-GM-FE-001~005 list/grouped/detail/create/update/bulk/delete 透传 |

---

## §5. 关键 bug 抓出 + 修复（IT 价值佐证）

### 5.1 close 链路 double markup（commit `4a25c1a`）

| 项 | 内容 |
| --- | --- |
| 现象 | TC-GM-017 round-trip PnL 失败：expected 89.5，actual 90.0（多算 0.5 = `bidExtraAtOpen`） |
| 根因 | `TradingPositionCloseApplicationService.settlePositionExit` 调 `resolvePositionMarkPrice(quote, position)` 已加 markup（含 bidExtraAtOpen），紧接着调 `calculateRealizedPnl → calculatePositionPnl(position, effectiveMarkPrice)` 内部又加一次 markup → realized PnL 多算 `extra × qty` |
| 修复 | `calculateRealizedPnl` 不再调 `calculatePositionPnl`（它会再加一次 markup，适合 WS/admin monitor 传基准价的链路），直接 `(effective - entry) × qty × dir` |
| 回归 | TradingLiquidationIntegrationTests 6/6 + TradingAutoCloseIntegrationTests 5/5 + TradingPersistenceIntegrationTests 4/4 全过，无回归 |

---

## §6. 全链路按组数据流（已落地）

```
LP 报价 → market mapping (Layer 1 基准)
         ↓ StandardQuote.withExtraMarkup
         ├─ WS push: 按 session.groupCode 加点 ✅
         ├─ REST quote/symbols/history: 按 X-User-Group-Code 加点 ✅
         ├─ Kafka tick: 仍发基准价（不加点）✅
         └─ ClickHouse quote_tick / K 线: 落基准价 ✅ (TC-GM-011/012 验证)

trading-core 启动 RPC 拉全量 + 30s 增量刷新 ✅

撮合开仓:
  fillPrice = (BUY ? ask : bid) + (askExtra : bidExtra)  ✅ (TC-GM-014/014b)
  → margin / fee / liquidationPrice 全部按含 markup 价算 ✅
  → position 落库冻结 (groupCode, bidExtra, askExtra)    ✅ (TC-GM-015)

PnL 计算:
  TradingPricingSupport.calculatePositionPnl 自动应用 position 冻结 markup  ✅ (TC-GM-018)
  → caller 无需改动，所有 PnL 显示自动一致 ✅

强平判定 (QuoteDrivenEngine):
  effectiveMark = resolvePositionMarkPrice(quote, position)  ✅ (TC-GM-019)
  反向验证：base.bid 跌破 liq 但 effective 仍在上方 → 不触发 ✅ (TC-GM-019b)

平仓 settlement:
  exitPrice = resolvePositionMarkPrice(quote, position)  ✅ (TC-GM-016/016b)
  → realized PnL = (exit - entry) × qty × dir，两端含 markup → 经济正确 ✅ (TC-GM-017)
  → 持仓期间运营改 markup 不影响存量持仓 ✅ (TC-GM-017b)

挂单触发 (PendingOrderTriggerEvaluator):
  effectiveAsk = ask + order.askExtraAtCreate (冻结值)  ✅ (TC-GM-020)
  effectiveBid = bid + order.bidExtraAtCreate (冻结值)
  → 运营改加点不影响存量挂单触发条件 ✅ (TC-GM-022)
  → 反向：frozen=0 挂单不受 live markup=10 影响 ✅ (TC-GM-022b)

console 管理端:
  7 端点透传 + 错误码 90640~90642 + RBAC + bulk 上限 + reason 审计 ✅ (TC-GM-030~034)

console-frontend:
  GroupMarkupListPage 列表 + 新建/编辑/删除 Modal + 9 Vitest ✅ (TC-GM-FE-001~005)
```

---

## §7. 实施 commits（15 commit 全部在 `main` 分支）

| Commit | 内容 |
| --- | --- |
| `eb8bc09` | R2 契约 + R4 market 全套 + trading 数据层 |
| `70eaf32` | trading service 层 |
| `45b5444` | trading 8 改造点全链路 |
| `0dfcb79` | 当前开发计划同步 |
| `37db8e4` | R9 console-service 7 端点透传 |
| `4ee3a91` | R10 console-frontend 列表页 + 9 Vitest |
| `ba37fe6` | R6 UT TC-GM-UT-001~024 |
| `1301736` | R6 IT 14 个（console 8 + market admin 6） |
| `bc5a909` | R6 IT market REST 4 个 |
| `12ab8fe` | R6 IT trading 开仓 4 个 |
| `4a25c1a` | 🐛 修 close double markup + 4 IT |
| `c9bcca8` | unrealized PnL + 强平 4 IT |
| `3bdba94` | 挂单冻结 + 触发 5 IT |
| `2a50455` | ClickHouse + config 传播 4 IT |
| `6fbf1de` | ClickHouse IT 鲁棒性 |

---

## §8. 待 R7 后续会话补完

| 项 | 原因 |
| --- | --- |
| 浏览器 QA 6 截图 | 需要 admin login + 启动 dev server 手动操作（建议后续单独会话用 Playwright skill） |
| 性能压测 | 需要 LP 真接入 + JMH 基准（建议接 R7 完成后单独压测会话） |
| TC-GM-006/007 WS 多组分桶 IT | 需要 StandardWebSocketClient + session.groupCode 注入测试 |
| TC-GM-013 trading-core 真 RPC refresh 30s 端到端 IT | 需要同时启动 market + trading 服务 |
| TC-GM-021 SL_TP 单独 IT | 核心逻辑已被 TC-GM-022 evaluator 路径覆盖，可选 |

---

## §9. R7 收口结论

| 项 | 状态 |
| --- | --- |
| 三端代码闭环 | ✅ 完整 |
| 数据库迁移 | ✅ 2 个 Flyway migration（market V14 + trading V27） |
| 测试覆盖 | ✅ 64 个测试全过（market 25 + trading 31 + console 8） |
| 关键 bug 修复 | ✅ close double markup（被 TC-GM-017 IT 抓出，已修复 + 回归无影响） |
| 文档同步 | ✅ 设计稿 / 接口规范 / 测试用例 / 当前开发计划 / 本验证报告 |
| 已 push main | ✅ commit `6fbf1de`（fast-forward 合并） |

**R7 验证：PASS**。剩余项不阻断阶段收口，可在后续单独会话补完。
