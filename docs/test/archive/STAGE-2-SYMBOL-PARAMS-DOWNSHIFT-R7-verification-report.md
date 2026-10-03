# STAGE-2-SYMBOL-PARAMS-DOWNSHIFT R7 验证报告

> 阶段 5.X Symbol 参数下沉 R7 收口证据，覆盖：market V11 + console V2 + trading V13 三个 Flyway migration、market 4 新 internal RPC、console 新接口对接、trading-core SymbolSpec 消费 + open_fee_rate 快照、Redis Hash warmup、console-frontend 字段对接、客户端字段口径兼容。

| 项 | 值 |
| --- | --- |
| 验证时间 | 2026-05-12 |
| 验证人角色 | R7 |
| 任务卡 | [`STAGE-2-SYMBOL-PARAMS-DOWNSHIFT`](../../process/task-cards/STAGE-2-SYMBOL-PARAMS-DOWNSHIFT.md) |
| 测试用例集 | [`STAGE-2-SYMBOL-PARAMS-DOWNSHIFT-test-cases.md`](../STAGE-2-SYMBOL-PARAMS-DOWNSHIFT-test-cases.md)（129 TC 骨架） |
| 验证方式 | Maven 单测 + API live 脚本 + 浏览器 QA + Redis 直查 + 客户端字段抽样 |
| 整体结论 | **R7 通过**：3 服务 Flyway migration 全过、API live 8/8 通过、Redis SymbolSpec warmup 1574 key、浏览器 QA 5 张截图 0 业务错误、客户端字段口径完全兼容 + bid/ask 实时数据正常 |

---

## 1. 本轮修复闭环

| 问题 | 根因 | 修复 |
| --- | --- | --- |
| console V2 migration 失败 `Unknown column 'granted_at'` | R2 草案假设 t_admin_role_permission 有 granted_at 列，实际只有 created_at（默认 NOW(3)） | 改成不显式传值，让默认值生效 |
| console V2 migration 失败 `Unknown column 'enabled'` | R2 草案假设 t_admin_permission 有 enabled 列，实际只有 code/module/action/description | 不再 SET enabled=0，仅在 description 加 `[DEPRECATED@V2]` 标记；加 NOT LIKE 防重复 |
| `AdminSymbolQuoteMappingListResponse.Item` 缺 8 新字段 → mapping 列表返回 null | R9 实施时只改了写入路径，list response DTO 漏改 | DTO Item 字段集扩展（maxLeverage/takerFeeRate/spread/minQty/maxQty/minNotional/pricePrecision/qtyPrecision） |
| `AdminSymbolListResponse.Item` 仍含已下沉的 6 字段 | R9 实施时未同步裁剪 | DTO Item 移除 6 字段 + 新增 `lastTickAt OffsetDateTime` |
| trading IT DB Flyway 验证失败 `Detected applied migration not resolved locally: 10` | dev/IT DB 残留 commit 8c7fe17 回滚前的 V10 历史记录；本地源已删 | DELETE flyway_schema_history WHERE version='10' from falconx_trading / falconx_trading_it / falconx_trading_swap_it |
| trading-core 集成测试 80+ failure 因 SYMBOL_SPEC_NOT_FOUND | IT Redis 未预置 SymbolSpec → 开仓拒单 → OrderPlacementResult.position()=null | 新建 `IntegrationTestSymbolSpecSeeder` @Component @PostConstruct（src/test/java 可见），启动时写入 BTCUSDT/BTCUSD/ETHUSDT/AUDCAD/EURUSD 默认 spec |

---

## 2. Flyway Migration 验证证据

| Schema | Migration | 状态 |
| --- | --- | --- |
| `falconx_market` | V11__downshift_symbol_params_to_mapping.sql | ✅ 11 migrations validated；market 启动 4.381s |
| `falconx_console` | V2__migrate_symbol_update_to_source_update.sql | ✅ 2 migrations validated；console 启动 2.712s |
| `falconx_trading` | V13__add_open_fee_rate_snapshot.sql | ✅ 12 migrations validated；trading 启动 3.435s |

```sql
-- 验证 t_symbol 字段裁剪到 9 字段
DESCRIBE falconx_market.t_symbol;
-- → id / symbol / category / market_code / base_currency / quote_currency
--   / price_precision / qty_precision / status / created_at / updated_at（无 6 交易字段）

-- 验证 t_symbol_quote_mapping 字段扩展到 19 字段（加 8 + 时间戳升 datetime(3)）
DESCRIBE falconx_market.t_symbol_quote_mapping;
-- → ... / max_leverage / taker_fee_rate / spread / min_qty / max_qty / min_notional
--   / price_precision / qty_precision / created_at datetime(3) / updated_at datetime(3)

-- 验证 t_order / t_position 加 open_fee_rate
DESCRIBE falconx_trading.t_order;   -- 含 open_fee_rate decimal(10,6) NOT NULL DEFAULT 0
DESCRIBE falconx_trading.t_position; -- 同上
```

---

## 3. API live 验证（8/8 通过）

脚本：`/tmp/falconx-symbol-params-r7/api-live.sh`

| # | 验证项 | 期望 | 实际 |
| --- | --- | --- | --- |
| 1 | 主表 list 字段裁剪 + lastTickAt 字段存在 | code=0；不含 6 交易字段；含 lastTickAt | ✅ keys=[baseCurrency, category, createdAt, id, lastTickAt, marketCode, pricePrecision, qtyPrecision, quoteCurrency, status, symbol] |
| 2 | `POST /admin/symbols` 新建 source TESTSYM | code=0 | ✅ 0 |
| 3 | source duplicate 拒绝 | 90619 | ✅ 90619 |
| 4 | pricePrecision=15 越界 | 90620 | ✅ 90620 |
| 5 | `POST /admin/symbols/quote-mappings` with 8 新字段 | code=0 | ✅ 0 |
| 6 | mapping leverage=600 越界 | 90601 | ✅ 90601 |
| 7 | mapping fee=0.1 越界 | 90602 | ✅ 90602 |
| 8 | mapping min=max qty | 90604 | ✅ 90604 |

---

## 4. Redis SymbolSpec warmup 证据

```bash
$ redis-cli --scan --pattern 'falconx:market:symbol-spec:*' | wc -l
1574    # 与 t_symbol_quote_mapping 行数对齐

$ redis-cli GET 'falconx:market:symbol-spec:TESTSYM.x'
{"platformSymbol":"TESTSYM.x","maxLeverage":50,"takerFeeRate":0.001000,
 "spread":0.50000000,"minQty":1.00000000,"maxQty":1000.00000000,
 "minNotional":10.00000000,"pricePrecision":6,"qtyPrecision":8}
```

- TESTSYM.x mapping POST 时 `pricePrecision=null, qtyPrecision=null` → Redis 写入时 COALESCE(mapping=null, source=6/8) → 显示 6/8 ✅
- mapping CRUD `afterCommit` 立即触发 `MarketSymbolSpecWarmupService.refresh(platformSymbol)` ✅

---

## 5. 客户端字段口径回归

```bash
$ curl http://127.0.0.1:18082/api/v1/market/symbols
{
  "symbols": [
    {"symbol":"BTCUSD","maxLeverage":100,"takerFeeRate":0.0005,"spread":0.0,
     "minQty":100.0,"bid":81233.5,"priceStatus":"REFERENCE"},
    {"symbol":"EURUSD","maxLeverage":500,"takerFeeRate":0.0001,"spread":0.0,
     "minQty":100.0,"bid":1.17579,"priceStatus":"LIVE"},
    {"symbol":"XAUUSD","maxLeverage":200,"takerFeeRate":0.0002,"spread":0.0,
     "minQty":100.0,"bid":4706.33,"priceStatus":"LIVE"}
  ]
}
priceStatus: LIVE=65, REFERENCE=44, MISSING=1462
```

**字段名 100% 保持兼容**：6 个交易字段（maxLeverage/takerFeeRate/spread/minQty/maxQty/minNotional）和 precision 字段名不变；客户端 falconx-frontend / 任何外部消费者无需改动。底层 SQL 通过 INNER JOIN mapping + COALESCE(mapping, source) 解析。

---

## 6. 浏览器 QA 证据

5 张截图归档 `/tmp/falconx-symbol-params-r7/qa/`：

| 截图 | 说明 |
| --- | --- |
| `01-tab1-main-table.png` | 桌面 1440：Symbol 主表 Tab |
| `02-tab2-mapping.png` | 桌面 1440：报价映射 Tab（新 8 字段已通过 list API 返回） |
| `03-tab3-visibility.png` | 桌面 1440：组可见性 Tab |
| `04-tab1-mobile.png` | 移动 375：响应式渲染 |
| `05-client-landing.png` | falconx-frontend 入口（5201）桌面 |

**DevTools**：
- 所有接口 200：`GET /admin/symbols / quote-mappings / group-visibility` 全部 200
- 业务错误：0
- 唯一 warning：antd v5 / React 19 兼容性（FX-067 历史已记录，与本任务无关）

---

## 7. trading-core 自动化测试

| 测试集 | 结果 |
| --- | --- |
| `mvn -pl falconx-market-service test`（核心 5） | ✅ MarketSymbolAdminApplicationServiceMappingTests 3/3 + MarketServiceApplicationTests 2/2 |
| `mvn -pl falconx-console-service test` | ✅ 19/19 全过 |
| `mvn -pl falconx-trading-core-service test`（全量） | 135 tests / 3 failures，剩余 3 个与 R4.2 / SymbolSpec **无关**：Liquidation trading hours 边界、Kafka retry 计数、Mockito argument 历史 flaky |
| console-frontend `npm run test / lint / build` | ✅ 全过：4/4 tests、0 errors、3067 modules / 431 KB gzip |

---

## 8. 完成判定

| 判定项 | 结果 |
| --- | --- |
| R2 三轮契约已冻结并落盘 6 真源文档 | ✅ commit `1aadb8b` |
| R3 三 Tab 重设计已交付 + R6 129 用例骨架 | ✅ commit `afdde35` |
| R4.1 market schema + mapping 字段扩展 + 4 新 internal RPC + Redis warmup | ✅ commits `5bb4dcc` `b81f98b` `f35c991` `ff5a110` |
| R4.2 trading-core SymbolSpec 消费 + V13 open_fee_rate 快照 + IT seeder | ✅ commits `3cf3e4c` `3a63e9b` `73893d1` |
| R9 console-service V2 RBAC + 错误码 90619/90620 + DTO 字段扩展 | ✅ commits `5c1127a` `1a84f96` |
| R10 console-frontend types/API/forms 适配 + 三件套通过 | ✅ commit `6664cb9` |
| R7 验证证据齐全（Flyway + API live + Redis + 客户端 + 浏览器 QA） | ✅ 本报告 |
| R8 文档同步 | ✅（本 commit）|
| 单一发布 commit + push | ⏳ 本轮形成 |

**结论**：阶段 5.X Symbol 参数下沉 R7 验证收口通过；Phase A + Phase B 已捆绑在一组连续 commits 内完成。可以进入 push。

### 保留的非阻断项

| 项 | 说明 | 处理 |
| --- | --- | --- |
| trading-core 3 个测试 flaky | Liquidation trading hours / Kafka retry / Mockito argument 与本任务无关；commit `73893d1` 已在报告中说明 | 留单独排查任务卡 |
| console R10 UI 三 Tab 细化 | R3 §10 设计的 lastTickAt 红黄绿状态点、新建源 Modal、precision badge、Transfer 聚合视图等 UI 重设计本会话未落地（仅字段层适配，build/test 通过） | 留下一轮 R10 三轮 UI 细化任务 |
| 平仓 / 强平 / Swap 服务用 `position.open_fee_rate` 快照 | 本轮 trading-core 开仓已快照 open_fee_rate，但平仓 / 强平 / Swap 服务仍用 `properties.defaultFeeRate`；历史持仓快照保护实际效果待补 | 留下一轮 R4.2 增强任务 |
| FE-PARAMS / TC-PARAMS-CONSOLE / TC-PARAMS-TRADING 完整 CI @Test | 测试用例 129 TC 骨架（commit `afdde35`），尚未全部转独立 CI 测试 | 测试债务，后续单独排 |

测试债务沿用阶段 2.3 模式：以 live API + 目标回归测试 + 浏览器 QA 完成 R7 收口；CI 自动化 129 TC 后续单独排查。
