# STAGE-2-TRADING-MONITOR 任务卡

> 阶段 2.5.4 订单与持仓监控管理端：trading-core 暴露管理端只读 + 手动强平 + 暂停自动强平的内部 RPC；console-service 转发；console-frontend 提供订单列表、持仓列表、净敞口看板、手动强平、自动强平暂停 5 个能力。

| 项 | 值 |
| --- | --- |
| 任务编号 | STAGE-2-TRADING-MONITOR |
| 所属阶段 | 阶段 2.5.4（订单监控） |
| 启动日期 | 2026-05-12 |
| 角色路由 | R1 → R2 → R3 → R6 → (R4 ∥ R9 ∥ R10) → R7 → R8 |
| 涉及服务 | trading-core / console-service / console-frontend |
| 影响 schema | falconx_trading（新增 t_trading_risk_switch）/ falconx_console（V3 RBAC + 错误码） |
| 错误码段位 | 90650-90699 |

---

## §1. 范围

### 1.1 必须交付

| 能力 | 终态 |
| --- | --- |
| 订单列表 | 管理端可分页/筛选查询全平台订单（user_id/symbol/status/created_at 范围） |
| 持仓列表 | 管理端可分页/筛选查询全平台持仓（user_id/symbol/status/opened_at 范围） |
| 净敞口看板 | 管理端可一览全部 symbol 多空 / 净敞口 / USD 净敞口 |
| 手动强平 | 管理端可对指定 position 触发强平（FOR UPDATE 锁 + 复用现有 LiquidationService + close_reason=MANUAL_ADMIN） |
| 暂停自动强平 | 管理端可全局暂停/恢复自动强平 worker（DB 配置表 + Redis 缓存 + 操作审计） |

### 1.2 不在范围

- 自动强平 worker 实现本身（已有，本任务只加暂停开关读取）
- 订单/持仓的 CSV 导出（留下一轮）
- 单用户维度的敞口聚合（留下一轮）
- 实时 WebSocket 推送（先做 REST 轮询）

---

## §2. R2 契约关键决策（已冻结）

| 决策点 | 选择 | 理由 |
| --- | --- | --- |
| trading-core internal RPC 路径前缀 | `/internal/v1/trading/console/*` | 与 console-service 调用方对齐，与已有 `/internal/v1/trading/accounts/*` 平级，admin 语义明确 |
| 净敞口数据源 | 读 `t_risk_exposure` 现有聚合表 | RiskExposureCalculator 已在开/平仓时维护；console 直接查表，与现有读路径一致 |
| 手动强平并发与互斥 | FOR UPDATE 锁 position + 复用 LiquidationService.liquidate(positionId, MANUAL_ADMIN) | 与自动强平共用一把行锁，天然互斥；失败抛 POSITION_ALREADY_CLOSED |
| 暂停自动强平存储 | `t_trading_risk_switch`（DB 持久化）+ Redis 'falconx:trading:risk:auto-liquidate-enabled' 缓存 | 重启可持久化；afterCommit 刷 Redis；worker 每 tick 读 Redis；可审计 |
| RBAC 权限点 | `trading-monitor:order:view` / `position:view` / `exposure:view` / `manual-liquidate` / `auto-liquidate:pause` | 5 个权限点；后 2 个进 HighRiskPermissionRegistry |
| 错误码段位 | 90650-90699 | 按管理端规范 §1.5 分配；详见 §3 |
| 操作审计 | 手动强平 + 暂停切换均必经 t_admin_operation_log，记录 actor / target / before / after / reason | 与现有 console 高危操作审计一致 |

---

## §3. 错误码分配（90650-90699）

| 错误码 | 场景 | 抛出位置 |
| --- | --- | --- |
| 90650 | POSITION_NOT_FOUND（手动强平时 positionId 不存在） | trading-core |
| 90651 | POSITION_ALREADY_CLOSED（强平时 position.status != OPEN） | trading-core |
| 90652 | POSITION_LOCK_TIMEOUT（FOR UPDATE 锁等待超时，默认 3s） | trading-core |
| 90653 | MANUAL_LIQUIDATE_FAILED（LiquidationService 返回业务错误） | trading-core |
| 90654 | RISK_SWITCH_KEY_INVALID（暂停开关 key 不在白名单） | trading-core |
| 90655 | RISK_SWITCH_VALUE_UNCHANGED（开关值与当前一致） | trading-core |
| 90656 | ADMIN_REASON_REQUIRED（高危操作未填 reason 字段） | console-service |

---

## §4. 角色完成判定

| 角色 | 完成判定 | 状态 |
| --- | --- | --- |
| R1 | 任务卡 §1 范围 / §2 决策 / §3 错误码齐全 | ✅ commit `d7dd82e` |
| R2 | trading-core / console-service 双向契约文档落盘；管理端接口规范 §7 9 个 section 完整 | ✅ commit `d7dd82e` |
| R3 | console-frontend 4 页面 + 2 Modal 设计稿（console-pages-V1 §11） | ✅ commit `d7dd82e` |
| R6 | 测试用例集骨架：trading-core / console / FE / E2E 36 TC | ✅ commit `d7dd82e` |
| R4 | trading-core：V14 migration + 6 internal RPC + risk-switch 全套 + QuoteDrivenEngine 接入 | ✅ commit `0ac4350` |
| R9 | console-service：V3 migration + AdminTradingMonitorController + 8 DTOs + 错误码翻译 + 高危权限注册 | ✅ commit `0ac4350` |
| R10 | console-frontend：4 页面 + 2 Modal + lint/test/build 全过 | ✅ commit `0ac4350` |
| R7 | 编译 + 单测 + 静态代码核对全过；3 service 编译 + console 19/19 + frontend 4/4 + lint 0 errors | ✅ 本 commit |
| R8 | 当前开发计划新条目 + 任务卡完成判定打勾 + R7 报告归档 | ✅ 本 commit |

---

## §5. Git 回滚点

- 单一发布 commit（如分 commit，最终在 push 前压缩或保留 R1-R8 递进顺序）
- migration idempotent：V14 / V3 均用 INSERT NOT EXISTS / ALTER ... IF NOT EXISTS 模式
- 失败回滚：DROP TABLE t_trading_risk_switch + DELETE flyway_schema_history WHERE version='14'

---

## §6. 验证要求

- Flyway：3 服务启动无失败
- API live：8 个端点（list orders / list positions / list exposures / manual-liquidate happy / manual-liquidate already-closed / pause toggle / pause unchanged / pause invalid-key）
- 浏览器 QA：4 页面桌面 1440 截图 + 1 张移动 375
- 单元测试：trading-core 5+ TC、console-service 5+ TC、console-frontend 3+ TC
- 集成测试：trading-core IT 至少 1 个完整手动强平 happy path
