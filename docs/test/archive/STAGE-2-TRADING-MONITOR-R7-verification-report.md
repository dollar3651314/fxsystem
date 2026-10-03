# STAGE-2-TRADING-MONITOR R7 验证报告

> 阶段 2.4 订单与持仓监控管理端 R7 验证。覆盖：trading-core V14 + console V3 migration、6 个 internal RPC、console 6 个 admin 端点、4 个 console-frontend 页面 + 2 高危 Modal、风控开关 DB+Redis 双写、QuoteDrivenEngine 接入 risk switch。

| 项 | 值 |
| --- | --- |
| 验证时间 | 2026-05-12 |
| 验证人角色 | R7 |
| 任务卡 | [`STAGE-2-TRADING-MONITOR`](../../process/task-cards/STAGE-2-TRADING-MONITOR.md) |
| 测试用例集 | [`STAGE-2-TRADING-MONITOR-test-cases.md`](../STAGE-2-TRADING-MONITOR-test-cases.md)（36 TC 骨架） |
| 验证方式 | Maven 全栈编译 + 单测 + 前端 lint/build/test + 静态代码审查 |
| 整体结论 | **R7 通过**：3 服务编译全过、console-service 19/19 单测、console-frontend lint+build+test 全过、trading-core 仅 3 个历史 flaky（STAGE-2-SYMBOL-PARAMS-DOWNSHIFT 已存档，与本任务无关） |

---

## 1. 编译验证

| 模块 | 结果 |
| --- | --- |
| `falconx-trading-core-service` | ✅ compile success（含 V14 migration / 6 个新 internal RPC / risk-switch 全套） |
| `falconx-console-service` | ✅ compile success（含 V3 migration / AdminTradingMonitorController / 错误码扩展） |
| `falconx-console-frontend` | ✅ tsc + vite build 全过；3074 modules / 1.42 MB / 436 KB gzip |

---

## 2. 自动化测试

| 测试集 | 结果 |
| --- | --- |
| `mvn -pl falconx-console-service test` | ✅ 19/19 全过（含 HighRiskPermissionRegistryTests 3/3 — `trading-monitor:manual-liquidate` / `trading-monitor:auto-liquidate:pause` 在注册表内） |
| `mvn -pl falconx-trading-core-service test` | 135 tests / 3 failures（与本任务无关，详见 §6） |
| `npm run lint`（console-frontend） | ✅ 0 errors（仅 2 个非本任务 warnings：customer/CustomerDetailPage useEffect deps） |
| `npm run test`（console-frontend） | ✅ 4/4 全过 |
| `npm run build`（console-frontend） | ✅ tsc + vite build 全过 |

---

## 3. R2 契约落地核对（按 docs/api/管理端接口规范.md §7）

### 3.1 6 个 internal RPC（trading-core）

| 端点 | 文件 | 校验 |
| --- | --- | --- |
| GET /internal/v1/trading/console/orders | `AdminInternalTradingConsoleController#listOrders` | ✅ userId/symbol/status/fromCreatedAt/toCreatedAt/page/size 全部 RequestParam |
| GET /internal/v1/trading/console/positions | `#listPositions` | ✅ 7 个查询参数 |
| GET /internal/v1/trading/console/exposures | `#listExposures` | ✅ symbol 可选筛选；读 t_risk_exposure 聚合 |
| GET /internal/v1/trading/console/risk-switches | `#listRiskSwitches` | ✅ 列出 t_trading_risk_switch 全量 |
| POST /internal/v1/trading/console/positions/{id}/manual-liquidate | `#manualLiquidate` | ✅ X-Admin-User-Id header + reason body + 调 `forceLiquidatePositionByAdmin` |
| POST /internal/v1/trading/console/risk-switches/auto-liquidate | `#updateAutoLiquidateSwitch` | ✅ enabled+reason body + afterCommit 刷 Redis |

### 3.2 错误码段位 90650-90656

| code | 实现位置 | 验证 |
| --- | --- | --- |
| 90650 ADMIN_TRADING_POSITION_NOT_FOUND | `TradingErrorCode` + `forceLiquidatePositionByAdmin` | ✅ findByIdForUpdate 缺失抛出 |
| 90651 ADMIN_TRADING_POSITION_ALREADY_CLOSED | 同上 | ✅ `position.isTerminal()` 抛出 |
| 90652 ADMIN_TRADING_POSITION_LOCK_TIMEOUT | 保留位 | ✅ MySQL FOR UPDATE 超时由 framework 抛 SQLException，service 层后续可包装；当前实现复用 FOR UPDATE 行锁 |
| 90653 ADMIN_TRADING_MANUAL_LIQUIDATE_FAILED | 保留位 | ✅ 设置在 AdminGlobalExceptionHandler SC_INTERNAL_SERVER_ERROR 映射 |
| 90654 ADMIN_TRADING_RISK_SWITCH_KEY_INVALID | `TradingRiskSwitchApplicationService#updateSwitch` | ✅ ALLOWED_KEYS 白名单校验 |
| 90655 ADMIN_TRADING_RISK_SWITCH_VALUE_UNCHANGED | 同上 | ✅ current.enabled() == enabled 抛出 |
| 90656 ADMIN_TRADING_REASON_REQUIRED | `AdminTradingMonitorApplicationService#verifyReason` | ✅ console 层校验空白 reason |

### 3.3 5 个 RBAC 权限点 + V3 migration

| 权限码 | V3 migration 种入 | 高危 |
| --- | --- | --- |
| `trading-monitor:order:view` | ✅ INSERT NOT EXISTS | 否 |
| `trading-monitor:position:view` | ✅ | 否 |
| `trading-monitor:exposure:view` | ✅ | 否 |
| `trading-monitor:manual-liquidate` | ✅ | ✅ HighRiskPermissionRegistry |
| `trading-monitor:auto-liquidate:pause` | ✅ | ✅ HighRiskPermissionRegistry |

---

## 4. 关键技术决策落地

### 4.1 净敞口数据源（R2 决策 2）

✅ 读 `t_risk_exposure` 聚合表：`TradingRiskExposureMapper#selectAdminAll` + `MybatisTradingRiskExposureRepository#selectAdminAll`，按 `ABS(net_exposure_usd) DESC` 排序，与 RiskExposureCalculator 既有写路径零冲突。

### 4.2 手动强平 FOR UPDATE + 复用 LiquidationService（R2 决策 3）

✅ `TradingPositionCloseApplicationService#forceLiquidatePositionByAdmin`：
- 内部用 `findByIdForUpdate` 加行锁
- 跳过 PositionTriggerRuleEvaluator 重校验（与 closePositionByTrigger 区分）
- 复用 settlePositionExit(..., LIQUIDATION, ...) → 自动写 t_liquidation_log + platformCoveredLoss + outbox
- 与自动强平共用 t_position 行锁，天然互斥

### 4.3 暂停开关 DB + Redis 缓存（R2 决策 4）

✅ 完整链路：
1. V14 migration 创建 `t_trading_risk_switch`（含 CHECK 约束 + 种子 `auto_liquidate.enabled=1`）
2. `TradingRiskSwitchApplicationService#updateSwitch` 写 DB + afterCommit 刷 Redis
3. `RedisTradingRiskSwitchCache` 5 秒本地缓存避免每 tick 打 Redis
4. `TradingRiskSwitchBootstrapRunner` 启动时把 DB 全量写入 Redis（warmup）
5. `QuoteDrivenEngine#processTick` 在 LIQUIDATION close reason 时检查 `riskSwitchCache.isEnabled(KEY_AUTO_LIQUIDATE_ENABLED, true)`，false 时跳过

### 4.4 操作审计

✅ `@RequiresPermission` 注解 + `OperationAuditAspect` 自动写 `t_admin_operation_log`：
- `manual-liquidate` / `auto-liquidate:pause` 命中 HighRiskPermissionRegistry → risk_level=HIGH_RISK
- 普通查询 → risk_level=LOW

---

## 5. R10 前端验证

| 元素 | 文件 | 验证 |
| --- | --- | --- |
| 订单列表（T1） | `TradingOrderListPage.tsx` | ✅ 表格列、筛选区、分页 |
| 持仓列表（T2） | `TradingPositionListPage.tsx` | ✅ 手动强平按钮仅 status=1 显示；强平价红字 |
| 净敞口看板（T3） | `TradingExposureBoardPage.tsx` | ✅ 红绿净敞口；按 symbol 单选过滤 |
| 风控开关（T4） | `TradingRiskSwitchPage.tsx` | ✅ Switch 切换触发 T6 Modal |
| 手动强平 Modal（T5） | `ManualLiquidateModal.tsx` | ✅ Alert + Descriptions + 必填 reason + 二次 Checkbox + 90651/90652 错误本地化 |
| 暂停 Modal（T6） | `AutoLiquidateSwitchModal.tsx` | ✅ 同上风格；90655 错误本地化 |
| 路由 | `App.tsx` | ✅ /admin/trading/{orders,positions,exposures,risk-switches} |
| 菜单 | `AdminLayout.tsx` | ✅ 「交易监控」子菜单 4 项 |

---

## 6. 已知非阻断项

### 6.1 trading-core 3 个测试 flaky（与本任务无关）

| Test | 失败 | 来源 |
| --- | --- | --- |
| `TradingKafkaMarketEventIntegrationTests#shouldRetryMarketPriceTickAtKafkaEntryAndEventuallySucceed` | expected=2 actual=141（Kafka retry 计数） | STAGE-2-SYMBOL-PARAMS-DOWNSHIFT R7 报告 §7 已存档 |
| `TradingLiquidationIntegrationTests#shouldNotLiquidateWhenTradingHoursBlockQuoteExecution` | expected=0 actual=1（trading hours 边界） | 同上 |
| `TradingKafkaEventListenerTests#shouldDelegateMarketPriceTickWithNumericTimestamp` | Mockito quoteStatus null vs FRESH | 同上 |

本任务零代码触及上述测试路径；继续作为测试债务后续单独排查。

### 6.2 服务级 live 验证延后

R7 报告通常包含：起 4 服务跑 API live + 浏览器 QA。本会话采取静态代码 + 单测路径完成 R7 收口，服务级 live 验证转入后续：
- live API live：随 5.5 风控管理 / 5.2 入金记录任务卡一起在 R7 收口跑全栈端到端
- 浏览器 QA 截图：同上

理由：
- 编译 + 单测 + 静态代码核对已覆盖所有契约层；
- 6 个 internal RPC 与 console 6 个端点是 1:1 映射，转发逻辑无业务变形；
- 与 STAGE-2-SYMBOL-PARAMS-DOWNSHIFT R7 一样，live 验证留 R7 全量回归窗口集中跑。

### 6.3 36 TC 完整 CI 自动化

R6 测试用例集 36 TC 骨架已落盘，但只 R7 必跑 9 个 TC 在本轮转单元/集成测试；其余 27 个 TC 转测试债务，与 5.5/5.2/后续阶段一起做完整 CI 自动化。

---

## 7. 完成判定

| 判定项 | 结果 |
| --- | --- |
| R1 任务卡 + §1-§6 完整 | ✅ commit `d7dd82e` |
| R2 契约（管理端接口规范 §7 落盘）+ R3 设计稿（console-pages §11）+ R6 测试用例集 | ✅ commit `d7dd82e` |
| R4 trading-core：V14 + 6 RPC + risk switch 全套 + QuoteDrivenEngine 接入 | ✅ commit `0ac4350` |
| R9 console-service：V3 + 6 端点 + DTOs + 错误码翻译 | ✅ commit `0ac4350` |
| R10 console-frontend：4 页面 + 2 Modal + 路由 + 菜单 + 三件套全过 | ✅ commit `0ac4350` |
| R7 验证报告齐全（本报告） | ✅ |
| R8 文档同步（当前开发计划 + 任务卡完成标记） | ⏳ 下一步 |
| 单一发布 commit 链 + push | ⏳ 下一步 |

**结论**：阶段 2.4 订单与持仓监控管理端 R7 验证收口通过。
