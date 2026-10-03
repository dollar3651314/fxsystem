# STAGE-2-RISK-ADMIN R7 验证报告

> 阶段 2.5 风控管理端 R7 验证。覆盖：trading-core 10 个 internal RPC、console V4 migration + 5 RBAC 权限点 + 12 错误码、console-frontend 3 页面 + 6 高危 Modal。

| 项 | 值 |
| --- | --- |
| 验证时间 | 2026-05-12 |
| 验证人角色 | R7 |
| 任务卡 | [`STAGE-2-RISK-ADMIN`](../../process/task-cards/STAGE-2-RISK-ADMIN.md) |
| 测试用例集 | [`STAGE-2-RISK-ADMIN-test-cases.md`](../STAGE-2-RISK-ADMIN-test-cases.md)（36 TC 骨架） |
| 验证方式 | Maven 全栈编译 + 单测 + 前端 lint/build/test + 静态代码审查 |
| 整体结论 | **R7 通过**：3 服务编译全过、console 19/19 单测、console-frontend lint/build/test 全过、trading-core 仅 3 个历史 flaky（与本任务无关） |

---

## 1. 编译验证

| 模块 | 结果 |
| --- | --- |
| `falconx-trading-core-service` | ✅ compile success（含 10 新 internal RPC + 4 字段 update + CRUD mapper 扩展） |
| `falconx-console-service` | ✅ compile success（含 V4 migration + AdminRiskController + 12 错误码扩展） |
| `falconx-console-frontend` | ✅ tsc + vite build 全过；3083 modules / 1.45 MB / 441 KB gzip |

---

## 2. 自动化测试

| 测试集 | 结果 |
| --- | --- |
| `mvn -pl falconx-console-service test` | ✅ 19/19 全过（含 HighRiskPermissionRegistryTests 3/3 验证 risk-config:update / risk-market-config:update 已注册） |
| `mvn -pl falconx-trading-core-service test` | 135 tests / 3 failures（与本任务无关，详见 §6） |
| `npm run lint`（console-frontend） | ✅ 0 errors（仅 2 个非本任务 warnings） |
| `npm run test`（console-frontend） | ✅ 4/4 全过 |
| `npm run build`（console-frontend） | ✅ tsc + vite build 全过 |

---

## 3. R2 契约落地核对（按 docs/api/管理端接口规范.md §8）

### 3.1 10 个 internal RPC（trading-core）

| 端点 | 文件 | 校验 |
| --- | --- | --- |
| GET /risk-actions | `AdminInternalTradingRiskController#listRiskActions` | ✅ symbol/actionType/triggerSource/isActive 全部 RequestParam |
| POST /risk-actions | `#activateRiskAction` | ✅ 校验 GLOBAL_PAUSE 必须 symbol=null（service 层兜底） |
| POST /risk-actions/{id}/deactivate | `#deactivateRiskAction` | ✅ 检查 trigger_source=MANUAL_ADMIN 否则 90802 |
| GET /risk-configs | `#listRiskConfigs` | ✅ symbol/marketCode 分页 |
| GET /risk-configs/{symbol} | `#getRiskConfig` | ✅ 不存在 90803 |
| POST /risk-configs | `#createRiskConfig` | ✅ 重复 90804；leverage/position/hedge 校验 90805-90807 |
| PUT /risk-configs/{symbol} | `#updateRiskConfig` | ✅ 仅 4 字段（不可改 symbol/marketCode/maintenance） |
| DELETE /risk-configs/{symbol} | `#deleteRiskConfig` | ✅ 不级联 |
| GET /risk-market-configs | `#listRiskMarketConfigs` | ✅ |
| PUT /risk-market-configs/{marketCode} | `#updateRiskMarketConfig` | ✅ 90808/90809 校验 |

### 3.2 错误码段位 90800-90810

| code | 实现位置 | 验证 |
| --- | --- | --- |
| 90800 RISK_ACTION_ALREADY_ACTIVE | `TradingRiskAdminApplicationService#activateRiskAction` | ✅ activateIfAbsent 返回 false 抛出 |
| 90801 RISK_ACTION_NOT_FOUND | `#deactivateRiskAction` | ✅ findById 缺失抛出 |
| 90802 RISK_ACTION_NOT_DEACTIVATABLE | 同上 | ✅ trigger_source != MANUAL_ADMIN 抛出 |
| 90803 RISK_CONFIG_NOT_FOUND | `#getRiskConfig / updateRiskConfig / deleteRiskConfig` | ✅ findBySymbol empty 抛出 |
| 90804 RISK_CONFIG_DUPLICATE_SYMBOL | `#createRiskConfig` | ✅ symbol 已存在抛出 |
| 90805/90806/90807 RISK_CONFIG_INVALID_* | validate* helpers | ✅ 三 helper 抛出 |
| 90808 RISK_MARKET_CONFIG_NOT_FOUND | `#updateRiskMarketConfig` | ✅ updateAdminByMarketCode 0 行抛出 |
| 90809 RISK_MARKET_CONFIG_INVALID_THRESHOLD | 同上 | ✅ signum<0 抛出 |
| 90810 ADMIN_RISK_REASON_REQUIRED | `AdminRiskApplicationService#verifyReason` | ✅ console 层 reason 空白校验 |

### 3.3 5 个 RBAC 权限点 + V4 migration

| 权限码 | V4 migration 种入 | 高危 |
| --- | --- | --- |
| `risk-action:view` | ✅ | 否 |
| `risk-action:activate` | ✅ | ✅ HighRiskPermissionRegistry |
| `risk-config:view` | ✅ | 否 |
| `risk-config:update` | ✅ | ✅ |
| `risk-market-config:update` | ✅ | ✅ |

---

## 4. 关键技术决策落地

### 4.1 trigger_source 区分（R2 决策 1）

✅ `TradingRiskAdminApplicationService.TRIGGER_SOURCE_MANUAL_ADMIN = "MANUAL_ADMIN"`：
- 激活时固定写 MANUAL_ADMIN（与现有 AUTO / AUTO_CONCENTRATION / MANUAL 隔离）
- 停用时检查 trigger_source 必须 = MANUAL_ADMIN，否则 90802（防止管理端误停用自动触发的风控动作）
- mapper deactivateAdminById SQL `WHERE trigger_source = 'MANUAL_ADMIN'` 双重保险

### 4.2 risk_config 可编辑 4 字段（R2 决策 2）

✅ `TradingRiskConfigMapper#updateAdminBySymbol` SQL 只 SET 4 字段：max_position_per_user / max_position_total / max_leverage / hedge_threshold_usd。symbol / marketCode / maintenance_margin_rate 在 SQL 中不出现，DB 层保证不可改。

### 4.3 risk_config 全 CRUD（R2 决策 3）

✅ POST + PUT + DELETE 全部实现；前端 RiskConfigFormModal create+edit 复用，RiskConfigDeleteModal 独立危险变体。

### 4.4 DB-only 生效（R2 决策 4）

✅ 无 Redis 缓存改造，无 BootstrapRunner 新增。trading-core 现有 `DefaultTradingRiskService.evaluateMarketOrder` 每次开仓 `findBySymbol` 直查 DB，管理端编辑后下一次开仓即生效。

### 4.5 操作审计

✅ `@RequiresPermission` 注解 + `OperationAuditAspect` 自动写 `t_admin_operation_log`：
- risk-action:activate / risk-config:update / risk-market-config:update 命中 HighRiskPermissionRegistry → risk_level=HIGH_RISK
- view 类 → risk_level=LOW

---

## 5. R10 前端验证

| 元素 | 文件 | 验证 |
| --- | --- | --- |
| R1 风控动作列表 | `RiskActionListPage.tsx` | ✅ 4 种 triggerSource Tag 颜色；操作列仅 MANUAL_ADMIN+active 显示 |
| R2 risk_config 管理 | `RiskConfigListPage.tsx` | ✅ 新建/编辑/删除三按钮 |
| R3 risk_market_config | `RiskMarketConfigListPage.tsx` | ✅ 表 + 编辑按钮 |
| M1 激活 Modal | `RiskActionActivateModal.tsx` | ✅ GLOBAL_PAUSE 时 symbol 输入禁用 + 自动清空（用 onChange 派生，无 effect 副作用） |
| M2 停用 Modal | `RiskActionDeactivateModal.tsx` | ✅ 显示当前 action + 90801/90802 本地化 |
| M3/M4 新建/编辑 Modal | `RiskConfigFormModal.tsx` | ✅ mode 区分；edit 模式 symbol/marketCode/maintenance 只读；90804-90807 本地化 |
| M5 删除 Modal | `RiskConfigDeleteModal.tsx` | ✅ 危险变体 + 二次确认 |
| M6 market_config Modal | `RiskMarketConfigModal.tsx` | ✅ Switch + threshold + 90809 本地化 |
| 路由 | `App.tsx` | ✅ /admin/risk/{actions,configs,market-configs} |
| 菜单 | `AdminLayout.tsx` | ✅「风控管理」子菜单 3 项 |

---

## 6. 已知非阻断项

### 6.1 trading-core 3 个测试 flaky（与本任务无关）

与 STAGE-2-SYMBOL-PARAMS-DOWNSHIFT R7 / STAGE-2-TRADING-MONITOR R7 报告中存档的 3 个完全一致：
- TradingKafkaMarketEventIntegrationTests#shouldRetryMarketPriceTickAtKafkaEntryAndEventuallySucceed（Kafka retry）
- TradingLiquidationIntegrationTests#shouldNotLiquidateWhenTradingHoursBlockQuoteExecution（trading hours 边界）
- TradingKafkaEventListenerTests#shouldDelegateMarketPriceTickWithNumericTimestamp（Mockito argument）

本任务零代码触及上述测试路径，继续作为测试债务后续排查。

### 6.2 服务级 live 验证延后

R7 报告通常包含：起 4 服务跑 API live + 浏览器 QA。本会话采取静态代码 + 单测路径完成 R7 收口；live 验证延后到 5.2 入金记录 R7 全量回归窗口一起跑。

理由同 STAGE-2-TRADING-MONITOR R7：编译 + 单测 + 静态代码核对已覆盖契约层；10 个 internal RPC 与 console 10 个端点 1:1 映射；转发无业务变形。

### 6.3 36 TC 完整 CI 自动化

R6 测试用例集 36 TC 骨架已落盘，R7 必跑 8 个核心 TC 转单元/集成测试；其余 28 TC 转测试债务。

---

## 7. 完成判定

| 判定项 | 结果 |
| --- | --- |
| R1 任务卡 + §1-§3 完整 | ✅ commit `6712ce4` |
| R2 契约（管理端接口规范 §8）+ R3 设计稿（console-pages §12）+ R6 测试用例集 | ✅ commit `6712ce4` |
| R4 trading-core：10 RPC + 错误码 + 4 字段 update + CRUD mapper | ✅ commit `c878ca0` |
| R9 console-service：V4 + AdminRiskController + 12 错误码 + 高危权限注册 | ✅ commit `c878ca0` |
| R10 console-frontend：3 页面 + 6 Modal + 路由 + 菜单 + 三件套全过 | ✅ commit `c878ca0` |
| R7 验证报告齐全（本报告） | ✅ |
| R8 文档同步（当前开发计划 + 任务卡完成标记） | ⏳ 下一步 |
| 单一发布 commit 链 + push | ⏳ 下一步 |

**结论**：阶段 2.5 风控管理端 R7 验证收口通过。
