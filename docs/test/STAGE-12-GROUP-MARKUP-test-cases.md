# STAGE-12-GROUP-MARKUP 测试用例清单（R6 骨架）

> 任务卡：`STAGE-12-GROUP-MARKUP`
> 配套设计稿：[STAGE-12-GROUP-MARKUP-design](../design/STAGE-12-GROUP-MARKUP-design.md)
> 状态：R6 一轮骨架（用例占位），R6 二轮落代码。

---

## 1. 单元测试（UT）

### 1.1 market-service

| TC | 类 | 用例 | 覆盖点 |
|---|---|---|---|
| TC-GM-UT-001 | `DefaultMarketGroupMarkupServiceTests` | `find_hit` | (group, symbol) 命中返回配置 |
| TC-GM-UT-002 | `DefaultMarketGroupMarkupServiceTests` | `find_miss_returns_empty` | 缺行返回空 |
| TC-GM-UT-003 | `DefaultMarketGroupMarkupServiceTests` | `applyMarkup_zero_extras_returns_same` | 0 加点直返原对象（零分配） |
| TC-GM-UT-004 | `DefaultMarketGroupMarkupServiceTests` | `applyMarkup_positive_extras` | bid+bidExtra / ask+askExtra |
| TC-GM-UT-005 | `DefaultMarketGroupMarkupServiceTests` | `applyMarkup_negative_extras` | 让利场景（双向） |
| TC-GM-UT-006 | `DefaultMarketGroupMarkupServiceTests` | `refresh_full_then_incremental` | 全量 + 增量刷新 |
| TC-GM-UT-007 | `DefaultMarketGroupMarkupServiceTests` | `refresh_incremental_removes_disabled` | enabled=0 从内存快照剔除 |
| TC-GM-UT-008 | `MarketGroupMarkupAdminApplicationServiceTests` | `validate_invalid_range_throws_90641` | bidExtra > 1M 拒绝 |
| TC-GM-UT-009 | `MarketGroupMarkupAdminApplicationServiceTests` | `create_duplicate_throws_90642` | 主键冲突 |
| TC-GM-UT-010 | `MarketGroupMarkupAdminApplicationServiceTests` | `update_missing_throws_90640` | 不存在 |
| TC-GM-UT-011 | `StandardQuoteTests` | `withExtraMarkup_zero_returns_same_instance` | 不分配新对象 |
| TC-GM-UT-012 | `StandardQuoteTests` | `withExtraMarkup_recomputes_mid_and_mark` | mid = (bid + ask) / 2，mark 跟 mid |

### 1.2 trading-core

| TC | 类 | 用例 | 覆盖点 |
|---|---|---|---|
| TC-GM-UT-020 | `TradingGroupMarkupServiceTests` | `find_hit` | (group, symbol) 命中 |
| TC-GM-UT-021 | `TradingGroupMarkupServiceTests` | `refresh_from_market_rpc` | RPC client 拉取 + 缓存替换 |
| TC-GM-UT-022 | `TradingMarkupApplierTests` | `applyToFillPrice_buy_uses_ask_plus_askExtra` | BUY 用 ask+askExtra |
| TC-GM-UT-023 | `TradingMarkupApplierTests` | `applyToFillPrice_sell_uses_bid_plus_bidExtra` | SELL 用 bid+bidExtra |
| TC-GM-UT-024 | `TradingMarkupApplierTests` | `applyToExitPrice_long_uses_bid_plus_bidExtra` | LONG 平仓用 bid+bidExtra |

## 2. 集成测试（IT）

### 2.1 market-service

| TC | 类 | 用例 | 覆盖点 |
|---|---|---|---|
| TC-GM-001 | `MarketGroupMarkupInternalControllerIntegrationTests` | `crud_full_flow` | create / detail / list / update / delete |
| TC-GM-002 | `MarketGroupMarkupInternalControllerIntegrationTests` | `bulk_upsert_500_limit_enforced` | 批量上限校验 |
| TC-GM-003 | `MarketGroupMarkupInternalControllerIntegrationTests` | `changes_since_returns_incremental` | 增量端点 |
| TC-GM-004 | `MarketGroupMarkupInternalControllerIntegrationTests` | `invalid_platform_symbol_throws_90613` | mapping 中 platformSymbol 不存在 |
| TC-GM-005 | `MarketGroupMarkupInternalControllerIntegrationTests` | `out_of_range_extras_throws_90641` | CHECK 拦截 |
| TC-GM-006 | `MarketWebSocketGroupMarkupIntegrationTests` | `multi_group_sessions_get_different_prices` | 两个 group session 订阅同 symbol，price.tick bid/ask 不同 |
| TC-GM-007 | `MarketWebSocketGroupMarkupIntegrationTests` | `default_group_session_unchanged` | default 组无配置 → 推送基准价 |
| TC-GM-008 | `MarketQueryControllerGroupMarkupIntegrationTests` | `rest_quote_endpoint_applies_markup` | `X-User-Group-Code=vip` 时 REST `bid/ask/mid` 含加点 |
| TC-GM-009 | `MarketQueryControllerGroupMarkupIntegrationTests` | `rest_history_endpoint_applies_markup_to_all_items` | 历史 tick 列表全部按组加点 |
| TC-GM-010 | `MarketQueryControllerGroupMarkupIntegrationTests` | `rest_symbols_list_applies_markup_to_quote_fields` | `/symbols` 响应中 quote 字段含加点 |
| TC-GM-011 | `MarketAnalyticsClickHouseGroupMarkupIntegrationTests` | `quote_tick_persists_baseline_price` | ClickHouse quote_tick 落基准价（不含组加点）|
| TC-GM-012 | `MarketAnalyticsClickHouseGroupMarkupIntegrationTests` | `kline_persists_baseline_ohlc` | K 线 OHLC 是基准价 |

### 2.2 trading-core

| TC | 类 | 用例 | 覆盖点 |
|---|---|---|---|
| TC-GM-013 | `TradingGroupMarkupRpcRefreshIntegrationTests` | `startup_loads_full_then_30s_incremental` | 启动 + 30s 增量 |
| TC-GM-014 | `TradingOpenPositionGroupMarkupIntegrationTests` | `vip_group_fill_price_includes_ask_extra` | VIP 组开多 fillPrice = base.ask + askExtra |
| TC-GM-015 | `TradingOpenPositionGroupMarkupIntegrationTests` | `position_freezes_group_code_bid_ask_extra` | t_position 落 group_code_at_open / bid_extra_at_open / ask_extra_at_open |
| TC-GM-016 | `TradingClosePositionGroupMarkupIntegrationTests` | `close_uses_frozen_bid_extra_for_long` | 平多用 position.bidExtraAtOpen |
| TC-GM-017 | `TradingClosePositionGroupMarkupIntegrationTests` | `close_realized_pnl_includes_full_round_trip_markup` | 已实现 PnL 含开仓+平仓双向加点差 |
| TC-GM-018 | `TradingUnrealizedPnLGroupMarkupIntegrationTests` | `unrealized_pnl_uses_frozen_markup_in_quote_engine` | QuoteDrivenEngine 推送的 effectiveMark 含冻结值 |
| TC-GM-019 | `TradingLiquidationGroupMarkupIntegrationTests` | `liquidation_triggers_on_effective_mark` | 强平判定按 effectiveMark（含冻结 markup） |
| TC-GM-020 | `TradingPendingOrderGroupMarkupIntegrationTests` | `limit_stop_freezes_markup_at_create` | LIMIT/STOP 创建时冻结 |
| TC-GM-021 | `TradingPendingOrderGroupMarkupIntegrationTests` | `sl_tp_inherits_position_freeze` | SL/TP 继承持仓冻结值 |
| TC-GM-022 | `TradingPendingOrderGroupMarkupIntegrationTests` | `trigger_uses_frozen_markup_not_live_config` | 触发判定按冻结值（管理端改加点不影响存量挂单） |
| TC-GM-023 | `TradingGroupMarkupConfigChangePropagationIntegrationTests` | `config_change_30s_propagates_to_new_positions` | admin 改后 30s 内新开仓用新值 |
| TC-GM-024 | `TradingGroupMarkupConfigChangePropagationIntegrationTests` | `config_change_does_not_affect_existing_positions` | 存量持仓 PnL 不变 |

### 2.3 console-service

| TC | 类 | 用例 | 覆盖点 |
|---|---|---|---|
| TC-GM-030 | `AdminSymbolGroupMarkupEndpointIntegrationTests` | `list_proxies_market_rpc_with_filters` | 透传过滤 |
| TC-GM-031 | `AdminSymbolGroupMarkupEndpointIntegrationTests` | `create_proxies_with_audit_log` | 写审计 t_admin_operation_log |
| TC-GM-032 | `AdminSymbolGroupMarkupEndpointIntegrationTests` | `update_translates_90640_to_admin_code` | 错误码翻译 |
| TC-GM-033 | `AdminSymbolGroupMarkupEndpointIntegrationTests` | `delete_requires_symbol_group_markup_update_permission` | RBAC |
| TC-GM-034 | `AdminSymbolGroupMarkupEndpointIntegrationTests` | `bulk_upsert_502_items_rejected` | 批量上限 |

## 3. 前端测试（Vitest）

| TC | 文件 | 用例 | 覆盖 |
|---|---|---|---|
| TC-GM-FE-050 | `GroupMarkupListPage.test.tsx` | `renders_list_with_filters` | 列表 + 过滤 |
| TC-GM-FE-051 | `GroupMarkupListPage.test.tsx` | `delete_requires_high_risk_confirmation` | 删除二次确认 |
| TC-GM-FE-052 | `GroupMarkupEditModal.test.tsx` | `preview_shows_computed_bid_ask` | 输入加点后预览计算结果 |
| TC-GM-FE-053 | `GroupMarkupEditModal.test.tsx` | `range_validation_blocks_invalid_input` | 前端范围校验 |
| TC-GM-FE-054 | `groupMarkupApi.test.ts` | `list_query_builds_correct_params` | API client |

## 4. E2E（R7 范围）

| TC | 描述 |
|---|---|
| TC-GM-E2E-001 | 管理端 admin 配置 VIP 组 XAUUSD bid_extra=-0.05 → 等 30s → VIP 用户 REST `/quote/XAUUSD` 返回 bid 比 default 组小 0.05 |
| TC-GM-E2E-002 | VIP 用户开多 1 手 → DB 落 group_code_at_open='vip' / bid_extra_at_open=-0.05 → 持仓 PnL 显示按冻结值算 → 平仓 exitPrice = base.bid + (-0.05) |
| TC-GM-E2E-003 | admin 改 VIP 组 bid_extra=0 → 30s 后 → 存量 VIP 持仓 PnL 不变（冻结） |
| TC-GM-E2E-004 | VIP 用户挂 LIMIT 单 → admin 改加点 → 触发条件按挂单冻结值比对 |

## 5. 性能测试（R7 范围）

| 用例 | 指标 | 验收 |
|---|---|---|
| PERF-GM-001 | WS：1000 session × 4 组 × 100 tick/s | p99 延迟 ≤ 100ms |
| PERF-GM-002 | REST `/quote`：QPS 1000 | p99 ≤ 50ms |
| PERF-GM-003 | 撮合开仓：单笔 e2e | p99 ≤ 200ms |
| PERF-GM-004 | QuoteDrivenEngine processTick：100 open position × 100 tick/s | 无积压 |
| PERF-GM-005 | 配置刷新：1000 个组 × 100 symbol = 100k 配置 | 启动加载 < 5s，增量刷新 < 200ms |

## 6. 验收硬约束

R7 验证报告必须显式说明以下硬约束的达成状态：

- ✅ K 线持续为基准价（TC-GM-012）
- ✅ ClickHouse quote_tick 持续为基准价（TC-GM-011）
- ✅ Kafka price.tick 持续为基准价（TC-GM-011 中可同时核实）
- ✅ 用户换组后存量持仓 PnL 口径不变（TC-GM-024）
- ✅ 管理员视角看到基准价（TC-GM-018 反面验证：admin endpoint 不应用加点）
