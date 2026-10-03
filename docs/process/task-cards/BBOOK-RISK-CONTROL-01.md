# BBOOK-RISK-CONTROL-01 任务卡

> BBook 自营风控闭环：补齐方向集中度、用户级敞口、盈利用户独立阈值、平台总敞口 4 个能力，与现有 symbol 净敞口 + 跨品种集中度 + 4 种动作枚举共同形成完整的"配置 → 触发 → 执行 → 审计 → 恢复"闭环。

| 项 | 值 |
| --- | --- |
| 任务编号 | `BBOOK-RISK-CONTROL-01`（一期 BBook 阻断项之一）|
| 优先级 | P0（BBook V2 阶段 2.8）|
| 启动日期 | 2026-05-13 |
| 角色路由 | R1 → R2 → R3 → R6 → (R4 ∥ R9 ∥ R10) → R7 → R8 |
| 涉及服务 | trading-core / falconx-console-service / falconx-console-frontend |
| 影响 schema | `t_risk_config`（+2 列）、新表 `t_user_risk_threshold`、`t_risk_config` 全局行（symbol=NULL） |
| 错误码段位 | 复用 `30007 BBOOK_RISK_OPEN_REJECTED`；新增 `30010 BBOOK_RISK_USER_EXPOSURE_LIMIT`、`30011 BBOOK_RISK_DIRECTION_IMBALANCE`、`30012 BBOOK_RISK_PLATFORM_EXPOSURE_LIMIT` |

---

## §1. 范围

### 1.1 已落地（不在本任务范围）

- 4 种风控动作枚举：`REJECT_OPEN < REDUCE_ONLY < SUSPEND_SYMBOL < GLOBAL_PAUSE`
- `t_risk_control_action` 持久化（symbol / action_type / is_active / trigger_source / reason / hedge_log_id）
- 下单 service 4 种动作 → 4 种拒单原因映射（`DefaultTradingRiskService.evaluateRiskControlRejection`）
- 单 symbol 净敞口阈值自动激活 / 停用 `REJECT_OPEN`（`hedge_threshold_usd`）
- 跨品种集中度（FX / CRYPTO / COMMODITY 分类）自动触发 `REJECT_OPEN`
- 手动激活 / 停用风控动作（admin RPC + console UI）
- 风控开关 `auto_liquidate.enabled` kill switch + admin push
- 风控动作 / 开关的实时 WS 推送（STAGE-2-REALTIME-DATA Phase 2 已收口）

### 1.2 本任务必须交付

| 切片 | 能力 | 触发 | 动作 |
| --- | --- | --- | --- |
| **A** | 方向集中度（多/空 USD 失衡比阈值） | quote tick refresh 后 | 失衡比 > 阈值 + 总敞口 > 最小触发额 → `REJECT_OPEN`（symbol 级）|
| **B1** | 单用户净敞口 USD 阈值 | 每次下单前 + 周期性扫描（可选） | 超阈 → `REJECT_OPEN`（user 级 / 全 symbol，扩展动作 scope）|
| **B2** | 盈利用户独立阈值 | 同 B1，但用更严格阈值 | 同 B1，区分普通 / 盈利两套阈值 |
| **C** | 平台总净敞口阈值 | 周期性聚合（5s 节流）+ tick 后 | 超阈 → `GLOBAL_PAUSE` 自动激活 / 停用 |
| **审计** | 全部新触发源记录 trigger_source `AUTO_*` + reason | - | - |

### 1.3 不在范围

- 用户胜率 / 历史 PnL 评估"盈利用户"的复杂模型 — 本期用 `is_profitable_user` 管理员手动标记
- 用户级动作的恢复路径自动化 — 本期手动 deactivate
- 实时 push 风控配置变更（admin 改阈值不需要 WS 广播给客户端）
- CROSS 保证金风控分支 — 归 `CROSS-MARGIN-EXEC-01`

---

## §2. R2 契约冻结

### 2.1 数据库 schema（trading-core Flyway V11）

```sql
-- V11__bbook_risk_control_full.sql

-- 1. t_risk_config 新增方向集中度字段
ALTER TABLE t_risk_config
    ADD COLUMN direction_imbalance_ratio_threshold DECIMAL(8,4) NULL
        COMMENT '多/空 USD 失衡比阈值（0-1）；如 0.7 = 任一侧占比 ≥70% 触发' AFTER hedge_threshold_usd,
    ADD COLUMN direction_imbalance_min_total_usd DECIMAL(24,8) NULL
        COMMENT '触发的最小总敞口（USD）；小于此值不触发，避免小盘失衡误伤' AFTER direction_imbalance_ratio_threshold;

-- 2. 用户级风控阈值表
CREATE TABLE IF NOT EXISTS t_user_risk_threshold (
    user_id                                BIGINT          NOT NULL    COMMENT 'identity.t_user.id',
    net_exposure_threshold_usd             DECIMAL(24,8)   NULL        COMMENT '普通用户单用户净敞口阈值（USD）',
    profitable_net_exposure_threshold_usd  DECIMAL(24,8)   NULL        COMMENT '盈利用户单用户敞口阈值（USD）',
    is_profitable_user                     TINYINT(1)      NOT NULL DEFAULT 0 COMMENT '管理员手动标记的盈利用户',
    updated_by                             VARCHAR(64)     NULL        COMMENT '最近修改的 admin user id',
    updated_reason                         VARCHAR(200)    NULL        COMMENT '修改原因（审计）',
    created_at                             DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at                             DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户级 BBook 风控阈值表';

-- 3. 平台总净敞口阈值：在 t_risk_config 用 symbol=NULL 表示全局行
-- （现有列 hedge_threshold_usd 复用为平台总阈值，约束 symbol IS NULL 时其他字段允许 NULL）
ALTER TABLE t_risk_config MODIFY COLUMN symbol VARCHAR(32) NULL COMMENT 'symbol；NULL = 平台全局行';
ALTER TABLE t_risk_config MODIFY COLUMN max_position_per_user DECIMAL(24,8) NULL;
ALTER TABLE t_risk_config MODIFY COLUMN max_position_total DECIMAL(24,8) NULL;
ALTER TABLE t_risk_config MODIFY COLUMN maintenance_margin_rate DECIMAL(10,8) NULL;
ALTER TABLE t_risk_config MODIFY COLUMN max_leverage INT NULL;

INSERT INTO t_risk_config (id, symbol, hedge_threshold_usd, created_at, updated_at)
    VALUES (0, NULL, 10000000.00, NOW(3), NOW(3))
    ON DUPLICATE KEY UPDATE updated_at = NOW(3);
```

### 2.2 风控动作 scope 扩展（应用层语义，不改 enum）

`t_risk_control_action.symbol` 字段新增第三种值含义：

| `symbol` 值 | 现状语义 | 本任务新增 |
| --- | --- | --- |
| 具体 symbol（如 BTCUSDT） | symbol 级动作 | - |
| `NULL` | 全局动作（`GLOBAL_PAUSE`）| 平台总敞口超阈触发 |
| 形如 `USER:{userId}` | 不支持 | user 级动作（B1/B2 触发） |

应用层约束：
- `evaluateRiskControlRejection(symbol, userId)`：先查 `USER:{userId}` 是否激活，再查 symbol 行，再查全局行。
- mapper 增加 `findActiveByUserScope(userId)` 查询。

### 2.3 错误码新增（`TradingErrorCode`）

| 错误码 | 枚举 | 含义 |
| --- | --- | --- |
| `30010` | `BBOOK_RISK_USER_EXPOSURE_LIMIT` | 用户级敞口超阈 |
| `30011` | `BBOOK_RISK_DIRECTION_IMBALANCE` | symbol 方向集中度超阈（视为 REJECT_OPEN 子原因） |
| `30012` | `BBOOK_RISK_PLATFORM_EXPOSURE_LIMIT` | 平台总敞口超阈触发 GLOBAL_PAUSE |

注：方向集中度触发后激活的是普通 `REJECT_OPEN`，下单拒单原因依然返回 `BBOOK_RISK_OPEN_REJECTED`；`30011` 仅用于 `t_risk_control_action.trigger_reason` 审计字段。

### 2.4 触发架构

| 触发点 | 频率 | 数据源 | 动作 |
| --- | --- | --- | --- |
| **方向集中度** | 每次 `refreshExposureFromQuote(symbol)` | `t_risk_exposure.totalLongQty` × bid + `totalShortQty` × ask（USD 化）| 失衡比超阈 → activate `REJECT_OPEN` 在该 symbol |
| **用户级敞口** | 每次下单时实时计算 | 用户全部 OPEN 持仓 `quantity × markPrice` 总和 USD | 超阈 → 不写 t_risk_control_action（动作太短暂），直接 evaluate 阶段返回 `BBOOK_RISK_USER_EXPOSURE_LIMIT` |
| **平台总敞口** | 新增 scheduler 每 5s 跑 | `t_risk_exposure` 全表 `netExposureUsd` 累计 | 超阈 → activate `GLOBAL_PAUSE`（symbol=NULL）；恢复 → deactivate |

### 2.5 API 契约

#### 2.5.1 admin internal RPC 新增

```
POST /internal/v1/trading/console/risk-config/platform
Body: { "hedgeThresholdUsd": 10000000.00, "reason": "..." }
Resp: { "hedgeThresholdUsd", "updatedAt", "updatedBy" }

POST /internal/v1/trading/console/risk-config/{symbol}/direction-imbalance
Body: { "ratioThreshold": 0.7, "minTotalUsd": 100000.00, "reason": "..." }
Resp: { "symbol", "ratioThreshold", "minTotalUsd", "updatedAt", "updatedBy" }

GET  /internal/v1/trading/console/user-risk-thresholds?userId=&page=&size=
Resp: { "items": [{ userId, netExposureThresholdUsd, profitableNetExposureThresholdUsd, isProfitableUser, updatedAt, updatedBy }] }

POST /internal/v1/trading/console/user-risk-thresholds
Body: { "userId", "netExposureThresholdUsd?", "profitableNetExposureThresholdUsd?", "isProfitableUser?", "reason" }
Resp: 同上单 item

DELETE /internal/v1/trading/console/user-risk-thresholds/{userId}
Resp: 空
```

#### 2.5.2 console 转发层（`/admin/trading/risk-*`）

console-service 转发到 trading-core internal RPC，鉴权方式同其它管理端 RPC（X-Internal-Token + admin token → X-Admin-User-Id 注入）。

### 2.6 推送契约

不新增 WS 推送类型。复用已有：
- `admin.risk-action.changed`（GLOBAL_PAUSE 自动激活时触发）
- `admin.risk-switch.changed`（无新开关）

---

## §3. 角色完成判定

| 角色 | 完成判定 | 状态 |
| --- | --- | --- |
| R1 | 任务卡 §1-§2 齐全 | ✅（commit `85e4619`）|
| R2 | schema + 错误码 + API + 触发架构冻结 | ✅（commit `85e4619`）|
| R6 | 集成测试用例骨架（含 19 个 trading-core 集成 + 8 个 RiskControl 单元 + 2 个 Observability 单元，并入 R4 实施 commit） | ✅（commit `233b918`）|
| **R4 trading-core** | V15 migration + 新 entity/repository/mapper（TradingRiskConfig +2列 / TradingUserRiskThreshold 全新）+ DefaultTradingRiskObservabilityService.checkDirectionImbalance + DefaultTradingRiskService.evaluateUserExposureLimit + PlatformExposureGuardScheduler 5s + 4 个 admin RPC + admin push 同步触发 | ✅（commit `233b918`）|
| **R9 console-service** | 4 个 admin 转发端点（POST /risk-config/platform, /risk-config/{symbol}/direction-imbalance, /user-risk-thresholds + DELETE /user-risk-thresholds/{userId}）+ DTO 5 件套 + RequiresPermission 标注 | ✅（本 commit）|
| **R10 console-frontend** | 新 PlatformRiskConfigPage + UserRiskThresholdListPage + 菜单"风控管理"加 2 项 + 路由注册 | ✅（本 commit）|
| R7 | 浏览器实操：平台阈值提交端到端 → trading-core 日志确认；用户级阈值新增 → trader 下单 AUDCAD 拒单 `BBOOK_RISK_USER_EXPOSURE_LIMIT`；方向集中度自动激活 XAUUSD REJECT_OPEN 单空失衡 ratio=1.0 > 0.7 | ✅（本 commit）|
| R8 | 任务卡完成判定 + 当前开发计划同步 + BBook 一期完成开发计划状态推进 | ✅（本 commit）|

---

## §4. 性能预算

| 指标 | 预算 | 备注 |
| --- | --- | --- |
| 方向集中度计算 | < 1ms / tick | 复用已读 `t_risk_exposure` 行 |
| 用户级敞口下单评估 | < 5ms / 下单 | 按 userId 索引扫 OPEN 持仓 |
| 平台总敞口 scheduler | 5s 周期 | 全表 SUM(netExposureUsd)，配 index |
| schema 变更 | 在线 ALTER | 单表 < 1M 行（dev），prod 需评估 |

---

## §5. Git 回滚点

- 流程产物 commit（本任务卡 + R2 契约冻结）
- R4 实施 commit（trading-core schema + 触发逻辑 + admin RPC）
- R9 实施 commit（console-service 转发）
- R10 实施 commit（console-frontend 配置页）
- R7+R8 commit（验证报告 + 文档同步）

无业务数据迁移（仅 ALTER ADD COLUMN + 新表）→ 失败 `git revert` + `DROP TABLE t_user_risk_threshold` + `ALTER TABLE t_risk_config DROP COLUMN` 即可。

实际 commits：
- `85e4619` R1 + R2：任务卡 + R2 契约冻结
- `233b918` R4 trading-core：V15 migration + 13 个文件 / +893 / -7
- 本 commit R7+R8+R9+R10：console-service + console-frontend + 文档同步

---

## §7. R7 验证结果（2026-05-13）

### 7.1 自动化测试

```bash
mvn -pl falconx-trading-core-service -am test \
  -Dtest='QuoteDrivenEngineTriggerRuleTests,DefaultTradingRiskServiceRiskControlTests,DefaultTradingRiskObservabilityServiceTests,TradingAutoCloseIntegrationTests,TradingLiquidationIntegrationTests,TradingRiskObservabilityIntegrationTests,TradingUserWebSocketIntegrationTests'
```

结果：29/29 通过（包含 RiskControl 8 + RiskObservability 2 + 集成 19）。

```bash
cd falconx-console-frontend && npm run lint && npm run build
```

结果：lint 0 errors（2 个 customer 模块预存 warning 不变）、build 1475 KB / gzip 449 KB 通过。

### 7.2 端到端验证

**切片 C 平台敞口（已在 R4 阶段验证）**：阈值 1 USD → 5s scheduler 自动 activate `GLOBAL_PAUSE`（trigger_source=AUTO_PLATFORM_EXPOSURE）+ admin push `recipientCount=0` 链路通；阈值恢复 → 5s 自动 deactivate + admin push 同步。

**切片 A 方向集中度**：XAUUSD 配置 `ratioThreshold=0.7, minTotalUsd=100000` 后，trader 单边多头 100×4657≈465700 USD → ratio=1.0 > 0.7 自动激活 `REJECT_OPEN`（trigger_source=AUTO_DIRECTION_IMBALANCE）。trader 下 XAUUSD → 拒单 `BBOOK_RISK_OPEN_REJECTED`。

**切片 B 用户级阈值**：admin 给 trader (userId=47623273160249344) 设 `netExposureThresholdUsd=100` → trader 下 AUDCAD 100 lot → 拒单 `BBOOK_RISK_USER_EXPOSURE_LIMIT`（user 累计 669240 USD 远超 100 阈值）。

**console-frontend UI**：superadmin 登录 → /admin/risk/platform 提交 `hedgeThresholdUsd=15000000` → trading-core 日志 `trading.admin.risk-config.platform.updated hedgeThresholdUsd=15000000 affected=1`。/admin/risk/user-thresholds 列表 + 新增 + 编辑 + 删除全通。

### 7.3 已知后续

- console-frontend "新增用户阈值"对话框中的 User ID 使用 antd `InputNumber`，大整数（>2^53）有精度损失（如 47623273160249344 → 47623273160249340）。后续需切换为字符串 input + 后端兼容 string userId，或对话框用 `Input` + 手动 parse Long。
- 风控配置（symbol 级）的方向集中度阈值当前没有专门 UI；仅通过 admin API 直接调。后续可在 RiskConfigListPage 加方向集中度编辑列。
- 用户级阈值变更不走 admin WS 推送（任务卡 §2.6 已声明）；console-frontend 仍是 mutate → load 全量 refetch。
- 自动激活路径（方向集中度 / 平台敞口）触发的 admin push 当前 recipientCount=0；待 Phase 4 admin WS 已落地，但 console-frontend 风控动作页订阅 admin.risk-actions channel，自动触发的 GLOBAL_PAUSE 应该已经在列表 refetch 链路里更新。
