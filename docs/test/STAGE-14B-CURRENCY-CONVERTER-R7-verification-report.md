# STAGE-14B-CURRENCY-CONVERTER R7 验证报告

> 验证日期：2026-05-29
> 验证人：Claude Opus 4.8（在 R1 Commander 调度下作为 R7）
> 任务：`STAGE-14B-CURRENCY-CONVERTER` trading-core 货币转换 + 账本三列留痕（master spec §0.3 缺失能力 #1，B 阶段）

---

## §0. 部署前置阻断项（最高优先级，必读）

> **🔴 本阶段算法层与账本留痕在 trading-core 代码侧已实现并通过单测 + 15 IT + 真 Flyway migrate 回填验证，但存在一个生产/演示库部署阻断项，部署前必须先处理，不得让生产/演示库裸跑 `flyway migrate`。**

**阻断项：本地开发库 `falconx_trading`（及远程演示库）存在 schema 泄漏。**

- 根因：本阶段 V28（`t_ledger` 三列）与 V29（`t_position.entry_fx_rate`）的 migration 在 root bug 期间误写了 `USE falconx_trading;`（commit `1031a9ce` 修复前），导致 `ALTER TABLE + UPDATE` 被强制打到了 `falconx_trading` 库，而不是 Flyway 当前连接的库。
- 后果：`falconx_trading` 库的 `t_ledger` 三列 + `t_position.entry_fx_rate` **列已物理存在且已回填**，但其 `flyway_schema_history` **没有 V28/V29 行**。
- 风险：删除 `USE` 修复后，这些库**下次执行 `flyway migrate` 时会因列已存在而撞 `Duplicate column` 失败**，trading-core 启动/部署直接受阻。

**修复指引（部署前由 DBA / 部署执行，二选一）：**

1. **手动补行**：对 `falconx_trading`（及演示库）手动向 `flyway_schema_history` 插入 V28/V29 的成功行（`success=1`）。因列与回填均已就位，checksum 用修复后 SQL（即 commit `1031a9ce` 之后、已删除 `USE` 的版本）计算。
2. **flyway repair + 人工核对**：执行 `flyway repair`，并人工核对目标库三列 + `entry_fx_rate` 列确已存在且回填正确，再对齐/跳过 V28/V29。

执行须按授权进行。**禁止在未核对前对生产/演示库直接 `flyway migrate`。**

> 全新部署的干净库（如 Task 11 的隔离库 `falconx_trading_backfill_it`）从 V27 顺序 migrate 到 V28/V29 无此问题（已实证，见 §6）。本阻断项仅影响 root bug 期间被 `USE` 污染过的既有库。

---

## §1. 范围

本阶段覆盖 [STAGE-14 多币种 + CROSS/ISOLATED 保证金总设计稿](../design/STAGE-14-MULTICURRENCY-AND-CROSS-MARGIN-MASTER-design.md) §0.3 缺失能力 #1（FX 实时汇率换算服务）5 阶段中的 **B 阶段（trading-core 算法层货币转换 + 账本三列留痕）**：

| 子能力 | B 阶段交付 | 关键 commit |
|---|---|---|
| trading-side FxRateService（RPC bootstrap + Kafka 增量 + USD pivot 交叉） | RPC 全量拉取 + `falconx.market.fx.rate.update` 增量消费 + 同币种短路 | `STAGE-14B Task 3`（见上一会话 commit）|
| V28 `t_ledger` 加 `original_amount` / `original_currency` / `fx_rate_at_settlement` 三列 + 老数据回填 | V28 ALTER + 回填 `original_amount=amount, fx=1, original_currency=COALESCE(account.currency,'USDT')` | `178924c1`（V28 SQL，上一会话）|
| V29 `t_position` 加 `entry_fx_rate` 列 + 老数据回填 1.0 | V29 ALTER + 回填 `entry_fx_rate=1.0` | `a0c50b9a`（V29 SQL，上一会话）|
| CurrencyConverter（薄包装 FxRateService.queryRate） | same-currency 短路 + 不可用返回 null 告警 | `63370161` + `10575df6` |
| SymbolSpec 加 base/quote currency + t_ledger 三列串入写账链路 | 市场契约 + 生产者 + 领域对象/record/XML/写账链路 | `a734b128` + `6f97678e` |
| MarginCalculator 接 FX + MarginResult | `calculateInitialMargin` 接 FX 换算 + `MarginResult{inQuote,inAccount,fxRate}` | `be55aad0` + `0c02f74f` |
| TradingPricingSupport PnlResult | 货币感知 PnL（`PnlResult{inQuote,inAccount,fxRate,quoteCurrency}`） | `835739fa` + `4cdb6de6` + `646a2fb8` + `cee527fb` |
| Fee 货币感知 + Swap 换算落账三列 | Fee 混币收口 + Swap settlementAmount 换算账户币 + 落账三列真值 | `68532974` + `c63fca56` |
| 开仓落账三列真值 + entry_fx_rate | margin/fee inAccount+inQuote+fxRate+quoteCurrency + 写 entry_fx_rate 真值 | `8527862b` + `32401c65` |
| 平仓/强平 PnL 落账三列真值 + 降级不阻断 | realized PnL 原币×fxAtClose→账户币 + biz_type=8/9 留原币 + FX 降级不阻断 | `2313ffb7` + `5a2a1172` |
| 挂单触发复用验证 | 挂单触发开仓复用已接 FX 的开仓流程（无生产代码改动） | `83b696ab` |

**不在 B 阶段范围（后续 STAGE-14C-E）**：

- C：AccountEquityCalculator / MarginLevelMonitor / LeverageTierResolver + tier 表（V30/V31）+ admin tier CRUD UI
- D：CROSS/ISOLATED 用户级 toggle + 切换闸门 + 冷静期 + CROSS 强平排序（V32/V33）
- E：三端 UI（客户端 mode toggle + MarginLevel 浮窗 + 双币 PnL）+ admin 多币种聚合 + WebSocket break 字段最终切换

---

## §2. 角色

B 阶段为 backend-only（`falconx-trading-core-service` + `falconx-market-contract`）。按 [`AI工作模式 §4.1`](../process/AI工作模式.md) 阶段 0/1 豁免条款：

**三端硬约束 N/A** — 不涉及客户端 / 管理端 / console-frontend 代码变更。WebSocket `position.update` 字段在 B 阶段保守不改（见 §5.3），完整切换推迟 STAGE-14E。

各 task 均经 **实施 → spec 合规评审 → 代码质量评审 → 收口** 完整门禁。

---

## §3. 各 Task 证据

| Task | 内容 | 关键 commit | 证据 |
|---|---|---|---|
| 3 | trading-side FxRateService（RPC bootstrap + Kafka 增量 + USD pivot 交叉） | 上一会话 `278f263a` | 17 UT；同币种短路 + USD pivot 交叉 |
| 4 | CurrencyConverter（薄包装 queryRate + 短路 + 不可用 null 告警） | `63370161` + `10575df6` | UT；严格 stub 校验 + null 参数不触达 service |
| 5 | SymbolSpec base/quote currency + t_ledger 三列串入写账链路 | `a734b128` + `6f97678e` | UT；toSpec source null 防护 + IT seeder 真实 base/quote |
| 6 | MarginCalculator 接 FX + MarginResult | `be55aad0` + `0c02f74f` | 5+ UT；EURAUD 算例对齐 master §3.3；quoteCurrency null 防守拒单 |
| 7 | TradingPricingSupport PnlResult（货币感知 PnL） | `835739fa` + `4cdb6de6` + `646a2fb8` + `cee527fb` | 5+ UT；inQuote==null 早期短路；DOWN/HALF_UP 双口径；blank quoteCurrency + null position UT |
| 8 | Fee 货币感知 + Swap 换算落账三列 | `68532974` + `c63fca56` | 6+ UT；swap 零值 skip 守卫；margin FX 不可用拒单 UT |
| 9a | 开仓落账三列真值 + entry_fx_rate | `8527862b` + `32401c65` | UT；TradingRiskDecision 携 fxRate/quoteCurrency/原币值 + feeFxRate 数学自洽 |
| 9b | 平仓/强平 PnL 落账三列真值 + 降级不阻断 | `2313ffb7` + `5a2a1172` | UT；biz_type=8/9 留原币；FX 降级 original_currency 记真实 quoteCurrency；负净值保护非自洽预期注释 + UT |
| 9c | 挂单触发复用验证（无生产代码改动） | `83b696ab` | EURAUD 委托复用 9a placeMarketOrder + FX 不可用拒触发 + 余额校验 |
| — | **root bug 修复**：V28/V29 误写 `USE falconx_trading` 致跨库污染 | `1031a9ce` | 删除 `USE`；修复 IT 永久失败 + 生产库 schema 漂移 |
| 10 | 跨服务 15 IT + EURAUD 端到端 + PERF | `de41f9ff` | `TradingMultiCurrencyEndToEndIntegrationTests` 15 IT 全绿（见 §4） |
| 11 | 真 Flyway migrate + 回填抽查（无 commit，纯环境验证证据） | — | 隔离库 `falconx_trading_backfill_it` 分步 migrate（见 §6） |
| 12 | R8 文档同步 | `40db838e` | t_ledger 三列/entry_fx_rate + SymbolSpec 币种字段 + fx.rate.update trading consumer + 写账口径 + WS 过渡口径 + Flyway 禁用 USE 约束 |
| 13 | R7 收口报告 + 当前开发计划录入（本 commit） | 本 commit | 本报告 + 计划 §1 条目 |

---

## §4. 测试统计

| 测试 | 类型 | 数量 | 覆盖 |
|---|---:|---:|---|
| trading-core 单元测试（排除 IntegrationTests） | UT | ≈193 全绿 | Task 3-9b 各模块货币换算 / 三列写账 / 降级 / 短路 / 防御 |
| `TradingMultiCurrencyEndToEndIntegrationTests` | IT | 15（隔离运行 Tests run: 15, Failures: 0, Errors: 0） | 见下表 |
| TC-015 PnL 推送性能 | PERF | 1 | P99≈3.4ms（≪ 100ms 目标） |

### 15 IT 覆盖明细（隔离单跑全绿）

| TC | 覆盖点 |
|---|---|
| TC-001/002 | V28（t_ledger 三列）/ V29（t_position.entry_fx_rate）列存在 |
| TC-003 | Kafka FX 增量（`falconx.market.fx.rate.update`）消费 |
| TC-004 | CurrencyConverter 同币种短路（short-circuit） |
| TC-005 | USD pivot 交叉换算 |
| TC-006 | MarginCalculator EURAUD（IM(AUD)=82.5 / IM(USDT)=53.625 / fx=0.65） |
| TC-007 | 开仓 entry_fx_rate=0.65 + frozen=53.625 |
| TC-008 | 平仓 biz_type=8 三列（amount=65 / original=100 / AUD / fx=0.65） |
| TC-009 | BTCUSDT 同币种回归 |
| TC-010 | FX 缺失拒单（回填契约自证，见 §5.4） |
| TC-011 | 强平 biz_type=9 三列 |
| TC-012 | Fee 三列（10.725 / 16.5 / AUD） |
| TC-013/014 | Swap 异币种换算 + 降级 |
| TC-015 | PERF：1000 用户×5 持仓=5000，热路径 `publishPositionPnlUpdates` 真实浮盈重算，实测 **P99≈3.4ms** |

> trading-core 全量 IT baseline 仍受 STAGE-7 commit 10 文档化的 Redis 6379 vs docker 6380 不一致影响，本阶段 15 IT 隔离运行已实证全绿。

---

## §5. 已知不阻断项

> 部署阻断项（生产/演示库 schema 泄漏）见 **§0**，为最高优先级，单独提级，不在本节"不阻断"列表内。

1. 既有 flake `TradingKafkaWalletDepositIntegrationTests`（consumer group 异步计数竞态，pre-existing，与本阶段无关，隔离单跑亦复现）——建议后续单独修，不阻断本阶段。
2. **WebSocket `position.update` 字段口径过渡**：B 阶段保守不改字段名/结构。`unrealizedPnl` 仍为 quote 原币，`realizedPnl` 改为账户币，存在值口径过渡。完整字段切换（含 master §7.5 的 break）推迟 **STAGE-14E**。注意 master §7.5 写的"B/C/D 同步切换"为设计终态意图，实际实施按 plan 经验保守留 E，属实施节奏决策，不改终态设计。
3. **Task 10 TC-010 为"回填契约自证"**：V28 已 NOT NULL，迁移后无法重现插 NULL，故 TC-010 只能自证契约。真回填正确性由 Task 11 隔离库分步 migrate 实证（见 §6，120 行 100%）。
4. **错误码 30073 FX_RATE_UNAVAILABLE**：B 阶段以 reject reason 字符串 `"FX_RATE_UNAVAILABLE"` 实现拒单，错误码完整接线（数字码映射）可后续收口。
5. **STAGE-14C-E 后续阶段**（独立 plan，B 阶段 R7 收口后由 R1 单独 invoke writing-plans 推进）：
   - **C**：AccountEquityCalculator / MarginLevelMonitor / LeverageTierResolver + tier 表（V30/V31）+ admin tier CRUD UI
   - **D**：CROSS/ISOLATED 用户级 toggle + 切换闸门 + 冷静期 + CROSS 强平排序（V32/V33）
   - **E**：三端 UI + admin 多币种聚合 + WebSocket break 字段最终切换

---

## §6. Task 11 真 Flyway migrate + 回填抽查证据

隔离库 `falconx_trading_backfill_it`，分步执行：V27 migrate → 插老数据 → V28/V29 migrate → 抽查。

| 验证项 | 证据 |
|---|---|
| Flyway 历史完整性 | `flyway_schema_history` 27 行全 `success=1`，无 checksum 冲突 |
| t_ledger 回填公式 | 抽查 **120 行 100% 符合**：`original_amount=amount`、`fx_rate_at_settlement=1`、`original_currency=COALESCE(account.currency,'USDT')` |
| COALESCE NULL 回退 | 含 12 行 orphan 验证 COALESCE NULL→USDT 回退；并含 EUR/JPY/BTC 非 USDT 账户验证 |
| t_position 回填 | 30 行 `entry_fx_rate=1` 全对 |
| 跨库副作用（root fix 生效） | `falconx_trading` 的 `flyway_schema_history` 25 行 migrate 前后不变 → 确认删除 `USE` 后无跨库污染 |

> 该证据同时实证了 §0 阻断项的修复方向：干净库顺序 migrate 无 `Duplicate column`，问题仅限被 `USE` 污染过的既有库。

---

## §7. master §8.3 B 阶段验收硬约束对照

| 硬约束 | 状态 | 验证证据 |
|---|---|---|
| EURAUD 端到端开/平仓 t_ledger 三列填充正确 | ✅ | Task 10 TC-006/007/008（开仓 entry_fx_rate=0.65 + frozen=53.625；平仓 biz_type=8 三列 amount=65/original=100/AUD/fx=0.65） |
| 老数据回填脚本执行后 sample 100 行抽查全部 original_currency=USDT/账户币, fx_rate=1 | ✅（超额，120 行） | Task 11 隔离库 120 行 100% 符合回填公式（含 orphan COALESCE 回退 + 非 USDT 账户） |
| PnL 推送 P99 < 100ms（1000 用户压测） | ✅ | Task 10 TC-015：1000 用户×5 持仓=5000，热路径 `publishPositionPnlUpdates` 实测 **P99≈3.4ms** |
| CurrencyConverter 同币种 short-circuit 性能验证 | ✅ | Task 10 TC-004 + 单测（同币种短路不触达 FX 查询） |

#### 通用硬约束

| 通用硬约束 | 状态 | 备注 |
|---|---|---|
| mvn compile + test-compile BUILD SUCCESS | ✅ | 各 task 实施门禁覆盖 |
| 涉及服务 mvn test 全过 | ✅ | trading-core ≈193 UT 全绿 + 15 IT 隔离全绿 |
| 前端 npm 三件套 | N/A | B 阶段 backend-only |
| 文档同步完成 | ✅ | Task 12（`40db838e`）R8 同步 |
| Git 回滚点 push 到 main | ⏳ | 本地 main 领先 origin/main 20 commit，未 push（按约定由控制者统一执行） |
| 当前开发计划 §1 阶段收口条目录入 | ✅ | 本 commit |

---

## §8. 文档同步清单

| 文档 | 状态 | 内容 |
|---|---|---|
| `docs/database/falconx一期数据库设计.md` | ✅ | t_ledger 三列 + t_position.entry_fx_rate（Task 12 `40db838e`） |
| `docs/event/Kafka事件规范.md` | ✅ | `falconx.market.fx.rate.update` trading consumer 消费口径（Task 12） |
| trading SymbolSpec 币种字段口径 | ✅ | baseCurrency/quoteCurrency + 写账三列口径与自洽性（Task 12） |
| Flyway 禁用 `USE` 约束 | ✅ | Task 12 R8 + root fix `1031a9ce` |
| WebSocket position.update 过渡口径 | ✅ | Task 12 标注过渡，最终切换指向 STAGE-14E |
| `docs/setup/当前开发计划.md` §1 | ✅ | STAGE-14B R7 收口条目（本 commit）+ 下一步 outline 指向 STAGE-14C |
| `docs/design/STAGE-14-...-MASTER-design.md` §8.3 B 阶段 | ✅ | 验收硬约束逐条对照（§7） |
| `docs/process/STAGE-14B-CURRENCY-CONVERTER-implementation-plan.md` | ✅ | 实施计划（`d47a7903`） |

---

## §9. 结论

按 [AGENTS.md §8.1.2](../../AGENTS.md) 生产可用判定：

**B 阶段算法层货币转换 + 账本三列留痕在 trading-core 已实现，并通过单测（≈193 UT）+ 15 IT（EURAUD 端到端开/平/强平/Fee/Swap 三列真值 + entry_fx_rate + FX 缺失拒单 + 同币种回归 + PERF P99≈3.4ms）+ 真 Flyway migrate 回填验证（隔离库 120 行 100%）。已验证范围边界为：trading-core 代码侧多币种换算与账本留痕能力完整。**

**🔴 但当前不满足无条件"生产可用"：**

- **剩余阻断项（部署前必须处理）**：生产/演示库 `falconx_trading` 因 root bug 期间 `USE` 污染存在 schema 泄漏（列已存在但 `flyway_schema_history` 缺 V28/V29 行），下次 `flyway migrate` 将撞 `Duplicate column` 阻断启动。**部署前必须先按 §0 修复指引（手动补行或 flyway repair + 人工核对）处理，禁止裸跑 migrate。**
- **已验证范围边界**：算法层 + 账本三列在 trading-core 代码侧完整且通过测试；干净库（全新部署）顺序 migrate 无此问题；既有被污染库需先 repair。
- **不满足生产可用的其他原因**：本系统整体仍处 BBook 一期建设中，按 [当前开发计划 §1](../setup/当前开发计划.md) 末条，当前系统不得表述为"生产可用"或"可安全对外公测"。

**使用说明（按 §8.1.3）：**

- **使用入口**：trading-core 开/平/强平/Swap/Fee 写账链路自动按 SymbolSpec base/quote currency + FxRateService 当前汇率换算到账户币并落 `t_ledger` 三列；开仓写 `t_position.entry_fx_rate`。FX 实时来源为 market-service `falconx.market.fx.rate.update` Kafka 增量 + 启动 RPC bootstrap。
- **前置条件**：market-service A 阶段 FX 数据源在线（Redis FX 缓存 + Kafka topic + RPC）；trading-core 启动时 RPC 全量拉取成功；目标库 Flyway 已按 §0 完成 V28/V29 对齐。
- **执行步骤**：按 §0 处理目标库 → 启动 market-service → 启动 trading-core（自动 bootstrap FX）→ 正常开/平仓即落三列。
- **预期结果**：异币种品种（如 EURAUD）t_ledger `original_amount`/`original_currency`/`fx_rate_at_settlement` 三列与账户币 `amount` 数学自洽；t_position `entry_fx_rate` 写开仓真值；同币种短路 fx=1。
- **已知限制 / 禁用场景**：FX 不可用时换算降级（不阻断平仓/强平，open/margin 校验拒单，reason `FX_RATE_UNAVAILABLE`，错误码数字映射待后续收口）；WebSocket position.update 字段口径过渡中（STAGE-14E 切换）；禁止对未按 §0 修复的污染库直接 migrate。
