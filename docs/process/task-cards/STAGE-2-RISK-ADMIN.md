# STAGE-2-RISK-ADMIN 任务卡

> 阶段 2.5 风控管理端：trading-core 暴露管理端风控动作 / risk_config / risk_market_config 内部 RPC；console-service 转发；console-frontend 提供 3 页面 + 激活 / 停用 / 编辑 / 新建 / 删除多套高危 Modal。

| 项 | 值 |
| --- | --- |
| 任务编号 | STAGE-2-RISK-ADMIN |
| 所属阶段 | 阶段 2.5（风控管理） |
| 启动日期 | 2026-05-12 |
| 角色路由 | R1 → R2 → R3 → R6 → (R4 ∥ R9 ∥ R10) → R7 → R8 |
| 涉及服务 | trading-core / console-service / console-frontend |
| 影响 schema | falconx_trading（无 schema 变更，仅 trigger_source 枚举值扩展）/ falconx_console（V4 RBAC + 错误码） |
| 错误码段位 | 90800-90849 |

---

## §1. 范围

### 1.1 必须交付

| 能力 | 终态 |
| --- | --- |
| 风控动作列表 | 管理端可查看全平台 t_risk_control_action（含 is_active 过滤 + symbol 筛选 + trigger_source 筛选） |
| 手动激活风控动作 | 管理端可对指定 symbol（或全局）激活 REJECT_OPEN / REDUCE_ONLY / SUSPEND_SYMBOL / GLOBAL_PAUSE，trigger_source=MANUAL_ADMIN |
| 手动停用风控动作 | 管理端可停用 trigger_source=MANUAL_ADMIN 的激活记录（不影响自动触发） |
| risk_config 列表 / 详情 | 管理端可分页查询 t_risk_config 全部行 / 按 symbol 查详情 |
| risk_config 编辑 | 管理端可编辑 4 个间接影响字段：maxLeverage / maxPositionPerUser / maxPositionTotal / hedgeThresholdUsd |
| risk_config 新建 | 管理端可新建 risk_config 行（symbol 唯一约束） |
| risk_config 删除 | 管理端可删除 risk_config 行（不级联） |
| risk_market_config 列表 / 编辑 | 管理端可列出 t_risk_market_config + 编辑 concentration_threshold_usd / is_enabled |

### 1.2 不在范围

- 自动风控触发 / 集中度检查的逻辑修改（已在 V1 实现，本任务只暴露管理端）
- maintenance_margin_rate / market_code 编辑（保留 R2 决策，后续手动 SQL 路径）
- 风控动作历史回溯查询 / 时序图（留下一轮）
- 跨 symbol 批量激活 / 停用（按单 symbol 操作）

---

## §2. R2 契约关键决策（已冻结 2026-05-12 R2 一轮）

| 决策点 | 选择 | 理由 |
| --- | --- | --- |
| 手动激活的 trigger_source | 新增 `MANUAL_ADMIN` 枚举值，与现有 AUTO / AUTO_CONCENTRATION / MANUAL 并列 | 与未来其他 MANUAL 渠道隔离，便于审计追踪和"管理端只能停用 MANUAL_ADMIN 的激活"安全语义 |
| risk_config 可编辑字段 | maxLeverage / maxPositionPerUser / maxPositionTotal / hedgeThresholdUsd（4 字段） | symbol/marketCode 是身份字段；maintenance_margin_rate 影响所有持仓强平价重算，留手动 SQL 路径 |
| risk_config CRUD 范围 | 全 CRUD（含 POST 新建 + DELETE 删除） | 运营有完整 owner 权力；DELETE 不级联，操作员承担后果 |
| 生效机制 | DB-only 直查（不引入 Redis 缓存） | 现有 DefaultTradingRiskService.evaluateMarketOrder 已每次开仓 findBySymbol 直查 DB；改 SQL 立即生效；无运行时架构变更 |
| RBAC 权限点 | `risk-action:view` / `risk-action:activate` / `risk-config:view` / `risk-config:update` / `risk-market-config:update` | 后 3 个进 HighRiskPermissionRegistry（activate / config update / market update 均影响开仓行为） |
| 错误码段位 | 90800-90849（按管理端规范 §1.5 已分配） | 详见 §3 |
| 操作审计 | 所有高危写操作必经 t_admin_operation_log（actor / target / before / after / reason） | 与现有 console 高危审计统一 |

---

## §3. 错误码分配（90800-90849）

| 错误码 | 场景 | 抛出位置 |
| --- | --- | --- |
| 90800 | RISK_ACTION_ALREADY_ACTIVE（同 symbol+actionType+trigger_source 已激活） | trading-core |
| 90801 | RISK_ACTION_NOT_FOUND（停用时找不到 MANUAL_ADMIN 的激活记录） | trading-core |
| 90802 | RISK_ACTION_NOT_DEACTIVATABLE（试图停用非 MANUAL_ADMIN 的激活记录） | trading-core |
| 90803 | RISK_CONFIG_NOT_FOUND（按 symbol 查不到） | trading-core |
| 90804 | RISK_CONFIG_DUPLICATE_SYMBOL（POST 新建 symbol 已存在） | trading-core |
| 90805 | RISK_CONFIG_INVALID_LEVERAGE（maxLeverage 不在 [1, 500]） | trading-core |
| 90806 | RISK_CONFIG_INVALID_POSITION_LIMIT（maxPositionPerUser / maxPositionTotal 为负或 perUser > total） | trading-core |
| 90807 | RISK_CONFIG_INVALID_HEDGE_THRESHOLD（hedgeThresholdUsd 为负） | trading-core |
| 90808 | RISK_MARKET_CONFIG_NOT_FOUND（按 marketCode 查不到） | trading-core |
| 90809 | RISK_MARKET_CONFIG_INVALID_THRESHOLD（concentration_threshold_usd 为负） | trading-core |
| 90810 | ADMIN_RISK_REASON_REQUIRED（高危操作 reason 字段为空） | console-service |

---

## §4. 角色完成判定

| 角色 | 完成判定 | 状态 |
| --- | --- | --- |
| R1 | 任务卡 §1-§3 齐全 | ✅ commit `6712ce4` |
| R2 | 管理端接口规范 §8 落盘（12 子节 10 端点） | ✅ commit `6712ce4` |
| R3 | console-pages §12 设计稿（3 页面 + 6 Modal） | ✅ commit `6712ce4` |
| R6 | 测试用例集骨架 36 TC | ✅ commit `6712ce4` |
| R4 | trading-core：错误码 + mapper/repo CRUD + TradingRiskAdminApplicationService + 10 内部 RPC | ✅ commit `c878ca0` |
| R9 | console-service：V4 + AdminRiskController + 12 错误码 + 高危权限注册 | ✅ commit `c878ca0` |
| R10 | console-frontend：3 页面 + 6 Modal + 路由 + 菜单 + 三件套全过 | ✅ commit `c878ca0` |
| R7 | 编译 + 单测全过 + 静态代码核对 | ✅ 本 commit |
| R8 | 当前开发计划新条目 + 任务卡完成判定打勾 + R7 报告归档 | ✅ 本 commit |

---

## §5. Git 回滚点

- 流程产物 commit（R1+R2+R3+R6）+ 实施 commit（R4+R9+R10）+ R7+R8 commit
- console V4 migration idempotent：INSERT NOT EXISTS（与 V3 同模板）
- 失败回滚：DELETE flyway_schema_history WHERE version='4'（console），trading-core 无 schema 变更

---

## §6. 验证要求

- 编译：3 服务 compile success
- 单测：trading-core 不破坏现有；console-service 全过；console-frontend lint/test/build 全过
- 不强制 live API + 浏览器 QA（与 5.4 同模式）
- TC 骨架：约 30 TC，R7 必跑 8 个
