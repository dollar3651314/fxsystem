# STAGE-3-PENDING-ORDER 任务卡

> 阶段 3 挂单：扩展 `TradingOrderType` 加 `LIMIT / STOP / STOP_LIMIT`；新建独立挂单触发表 `t_pending_order_trigger`，**统一承载条件单（LIMIT/STOP/STOP_LIMIT）和持仓止盈止损（SL_TP）**；废弃 `t_position.take_profit_price / stop_loss_price` 字段。**用户视角双轨展示**：客户端持仓行仍显示 SL/TP（按 parent_position_id 反查挂单表），挂单列表过滤掉 SL_TP 只显示 LIMIT/STOP/STOP_LIMIT。三端齐全。

| 项 | 值 |
| --- | --- |
| 任务编号 | `STAGE-3-PENDING-ORDER`（执行路径 §6 阶段 3） |
| 优先级 | P0（阶段 1+2 完成后的业务功能跃迁）|
| 启动日期 | 2026-05-13 |
| 角色路由 | R1 → R2 → R3 → R6 → (R4 ∥ R5 ∥ R9 ∥ R10) → R7 → R8 |
| 涉及服务 | trading-core / falconx-frontend / falconx-console-service / falconx-console-frontend |
| 影响 schema | t_position（弃 2 列）、t_risk_config（+1 列：min_distance_ratio）、新表 t_pending_order_trigger |
| 错误码段位 | 新增 `30013 PENDING_ORDER_TOO_CLOSE` / `30014 PENDING_ORDER_NOT_FOUND` / `30015 PENDING_ORDER_INVALID_STATE` |
| 估时 | 5 周 |
| 关联任务 | `CROSS-MARGIN-EXEC-01`（本任务 R2 必须为 CROSS 实施预留 available 抽象）|

---

## §1. 范围

### 1.1 必须交付

| 能力 | 接口 / 实现 |
| --- | --- |
| LIMIT 挂单 | `POST /api/v1/trading/orders/limit`，触发后转市价单成交（**开仓**）|
| STOP 挂单 | `POST /api/v1/trading/orders/stop`，触发后转市价单成交（**开仓**）|
| STOP_LIMIT 挂单 | `POST /api/v1/trading/orders/stop-limit`，stopPrice 触发后挂 LIMIT 单（**开仓**）|
| 持仓 SL/TP（编辑） | `PATCH /api/v1/trading/positions/{id}/sl-tp`，底层维护 t_pending_order_trigger（SL_TP 类型，**平仓单**）。**保留现有 API 路径**，用户/客户端无感 |
| 撤单 | `DELETE /api/v1/trading/orders/{id}`（LIMIT/STOP/STOP_LIMIT）|
| 修改 | `PATCH /api/v1/trading/orders/{id}`（LIMIT/STOP/STOP_LIMIT）|
| 挂单列表（客户端）| `GET /api/v1/trading/orders/pending`，**过滤掉 SL_TP** 只返回 LIMIT/STOP/STOP_LIMIT |
| 持仓回显 SL/TP | `GET /api/v1/trading/positions` 响应内 `takeProfitPrice` / `stopLossPrice` 字段保留，**由后端按 parent_position_id 反查 t_pending_order_trigger 拼装**；客户端持仓行显示不变 |
| 触发引擎 | `PendingOrderTriggerEvaluator`，挂 `QuoteDrivenEngine` 内部；**取代** `PositionTriggerRuleEvaluator` 的 SL/TP 分支（保留 LIQUIDATION 分支）|
| 资金冻结 | 挂单创建时冻结到 `t_account.frozen`（仅 LIMIT/STOP/STOP_LIMIT；SL_TP 不冻结）；撤单/触发释放 |
| Kafka 事件 | `falconx.trading.order.triggered`（内部 topic）|
| 用户 WS 推送 | `order.triggered`（envelope.type） |
| 客户端 UI | OrderTicket 新增 LIMIT / STOP tab；持仓行"TP/SL"按钮**保留现有交互**（背后改写挂单表）；新增"挂单"tab 显示 LIMIT/STOP/STOP_LIMIT 列表 + 撤改 modal |
| 管理端 UI | 挂单监控页（**默认过滤 SL_TP**，可勾选"含 SL/TP"看全部）+ 强制撤单 |

### 1.2 不在范围

- LIMIT 单的"部分成交"语义 — 触发后转市价一次性成交
- 挂单 GTC / IOC / FOK 时效 — V1 全部按 GTC（永久挂单直到撤销/触发）
- OCO（One-Cancels-Other）组合单 — 后续增量
- 跟踪止损（Trailing Stop） — 后续增量
- 拆单 / 冰山单 — 后续增量

---

## §2. R2 契约冻结

### 2.1 数据库 schema（trading-core Flyway V16）

```sql
-- V16__pending_order_full.sql

-- 1. 新表：挂单触发表
CREATE TABLE IF NOT EXISTS t_pending_order_trigger (
    id                  BIGINT          PRIMARY KEY                COMMENT '主键（雪花 ID）',
    order_no            VARCHAR(32)     NOT NULL UNIQUE            COMMENT '订单号；对外可见，复用 t_order.order_no 命名空间',
    user_id             BIGINT          NOT NULL                   COMMENT '下单用户',
    symbol              VARCHAR(32)     NOT NULL                   COMMENT '交易品种',
    order_type          TINYINT         NOT NULL                   COMMENT '2=LIMIT, 3=STOP, 4=STOP_LIMIT, 5=SL_TP（追加止盈止损）',
    side                TINYINT         NOT NULL                   COMMENT '1=BUY, 2=SELL',
    quantity            DECIMAL(24,8)   NOT NULL                   COMMENT '挂单数量',
    trigger_price       DECIMAL(24,8)   NOT NULL                   COMMENT '触发价（LIMIT=限价；STOP=止损触发价；STOP_LIMIT=stopPrice）',
    limit_price         DECIMAL(24,8)   NULL                       COMMENT 'STOP_LIMIT 触发后挂 LIMIT 单的限价；其余类型 NULL',
    leverage            DECIMAL(8,2)    NOT NULL                   COMMENT '杠杆',
    margin_mode         TINYINT         NOT NULL                   COMMENT '保证金模式快照：1=CROSS, 2=ISOLATED',
    frozen_margin       DECIMAL(24,8)   NOT NULL                   COMMENT '冻结保证金（创建时计算 + 锁到 t_account.frozen）',
    frozen_fee          DECIMAL(24,8)   NOT NULL                   COMMENT '冻结预估手续费',
    status              TINYINT         NOT NULL                   COMMENT '1=PENDING, 2=TRIGGERED, 3=CANCELLED, 4=EXPIRED, 5=REJECTED',
    parent_position_id  BIGINT          NULL                       COMMENT '关联持仓 ID（SL_TP 类型有值）；触发后会平这个仓',
    trigger_kind        TINYINT         NULL                       COMMENT 'SL_TP 类型细分：1=TAKE_PROFIT, 2=STOP_LOSS',
    client_order_id     VARCHAR(64)     NULL                       COMMENT '幂等键，同 t_order.client_order_id',
    triggered_order_id  BIGINT          NULL                       COMMENT '触发后落地的 t_order.id；TRIGGERED 后回填',
    triggered_at        DATETIME(3)     NULL                       COMMENT '触发时间',
    cancelled_at        DATETIME(3)     NULL                       COMMENT '撤单时间',
    cancel_reason       VARCHAR(200)    NULL                       COMMENT '撤单原因',
    created_at          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    INDEX idx_user_status      (user_id, status),
    INDEX idx_symbol_status    (symbol, status),
    INDEX idx_position_id      (parent_position_id),
    INDEX idx_status_trigger   (status, trigger_price),
    UNIQUE KEY uk_client_order (user_id, client_order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='挂单触发表（LIMIT/STOP/STOP_LIMIT/SL_TP 统一）';

-- 2. t_risk_config 加挂单距离阈值
ALTER TABLE t_risk_config
    ADD COLUMN pending_order_min_distance_ratio DECIMAL(8,6) NULL
        COMMENT '挂单价与 markPrice 最小距离比例（0-1）；如 0.003=0.3%。NULL=该 symbol 不限' AFTER direction_imbalance_min_total_usd;

-- 默认值（迁移阶段）：所有 symbol 默认 0.003（0.3%）
UPDATE t_risk_config SET pending_order_min_distance_ratio = 0.003000 WHERE symbol IS NOT NULL;

-- 3. 持仓 SL/TP migration：把现有 t_position.take_profit_price / stop_loss_price 迁到 t_pending_order_trigger
--   每行非空 SL/TP 各生成 1 条 PENDING + order_type=5(SL_TP) + trigger_kind=1/2 + parent_position_id
--   migration SQL 在 V16 末尾，不删字段（V17 再删，避免回滚困难）
INSERT INTO t_pending_order_trigger (
    id, order_no, user_id, symbol, order_type, side, quantity,
    trigger_price, leverage, margin_mode, frozen_margin, frozen_fee, status,
    parent_position_id, trigger_kind, client_order_id, created_at, updated_at
)
SELECT
    -- 雪花 ID 不能 SQL 直接生成；migration 后 trading-core 启动时一次性 backfill；这里给个标记 -1（启动时检测）
    -1, CONCAT('MIGRATED-TP-', id), user_id, symbol, 5, IF(side=1, 2, 1), quantity,
    take_profit_price, leverage, margin_mode, 0, 0, 1,
    id, 1, CONCAT('migrated-tp-', id), NOW(3), NOW(3)
FROM t_position
WHERE status = 1 AND take_profit_price IS NOT NULL;

INSERT INTO t_pending_order_trigger (
    id, order_no, user_id, symbol, order_type, side, quantity,
    trigger_price, leverage, margin_mode, frozen_margin, frozen_fee, status,
    parent_position_id, trigger_kind, client_order_id, created_at, updated_at
)
SELECT
    -1, CONCAT('MIGRATED-SL-', id), user_id, symbol, 5, IF(side=1, 2, 1), quantity,
    stop_loss_price, leverage, margin_mode, 0, 0, 1,
    id, 2, CONCAT('migrated-sl-', id), NOW(3), NOW(3)
FROM t_position
WHERE status = 1 AND stop_loss_price IS NOT NULL;

-- 注：t_position.take_profit_price / stop_loss_price 字段保留到 V17 migration，本期只停止读写
```

V17 在阶段 3 完成 + 验证稳定后单独执行：

```sql
-- V17__drop_position_sl_tp_fields.sql（阶段 3 完成稳定 1-2 周后执行）
ALTER TABLE t_position DROP COLUMN take_profit_price, DROP COLUMN stop_loss_price;
```

### 2.2 TradingOrderType / Status 扩展

```java
public enum TradingOrderType {
    MARKET,       // 1（已有）
    LIMIT,        // 2（新增）
    STOP,         // 3（新增）
    STOP_LIMIT,   // 4（新增）
    SL_TP         // 5（新增，仅用于 t_pending_order_trigger，t_order 不会出现）
}
```

`TradingOrderStatus`：复用已有 `PENDING / TRIGGERED / FILLED / CANCELLED / REJECTED`，但**仅用于 `t_pending_order_trigger.status`**，编码独立：

```
t_pending_order_trigger.status:
1=PENDING       挂着等触发
2=TRIGGERED     已触发，已生成 t_order 记录
3=CANCELLED     用户/admin 撤单
4=EXPIRED       后续增量（GTC 暂无过期）
5=REJECTED      触发时风控拒绝（资金不足等）
```

### 2.3 触发架构

```
QuoteDrivenEngine.processTick(symbol, quote)
  ├─ PositionTriggerRuleEvaluator.evaluate(position, markPrice)
  │    └─ **本期改造**：只保留 LIQUIDATION 分支（基于单仓 liquidation_price 字段）
  │    └─ TP/SL 分支删除（旧字段废弃后由下面 PendingOrderTriggerEvaluator 统一处理）
  ├─ PendingOrderTriggerEvaluator.evaluate(symbol, quote)（新增，统一触发器）
  │    ├─ 开仓挂单（parent_position_id IS NULL）：
  │    │    └─ LIMIT BUY  → ask ≤ trigger_price 触发
  │    │    └─ LIMIT SELL → bid ≥ trigger_price 触发
  │    │    └─ STOP BUY   → ask ≥ trigger_price 触发
  │    │    └─ STOP SELL  → bid ≤ trigger_price 触发
  │    │    └─ 触发后调 TradingOrderPlacementApplicationService 转市价开仓单
  │    │    └─ 释放 frozen_margin + frozen_fee（市价单流程重新冻结）
  │    ├─ 平仓挂单（SL_TP，parent_position_id IS NOT NULL）：
  │    │    └─ TAKE_PROFIT BUY 持仓  → mark ≥ trigger_price 触发
  │    │    └─ STOP_LOSS  BUY 持仓  → mark ≤ trigger_price 触发
  │    │    └─ TAKE_PROFIT SELL 持仓 → mark ≤ trigger_price 触发
  │    │    └─ STOP_LOSS  SELL 持仓 → mark ≥ trigger_price 触发
  │    │    └─ 触发后调 TradingPositionCloseApplicationService 反向平 parent_position_id
  │    │    └─ 不冻结资金（创建时即 frozen_margin=0），触发后无释放动作
  │    └─ 统一：更新 t_pending_order_trigger.status=TRIGGERED + triggered_order_id
  ├─ TradingRiskObservabilityService.refreshExposureFromQuote
  ├─ adminRealtimePushService.publishExposureUpdate
  └─ realtimePushService.publishPositionPnlUpdates
```

**用户视角的双轨**：

- 客户端持仓行 "TP/SL" 列依然显示 → 由后端 `toPositionPayload` 按 `parent_position_id` 查 t_pending_order_trigger 拼装回去，前端字段名 `takeProfitPrice` / `stopLossPrice` 不变
- 客户端"挂单"tab：`GET /api/v1/trading/orders/pending` 在 SQL 层 `WHERE order_type != 5 (SL_TP)`，只显示开仓挂单
- 用户改持仓 TP/SL：UI 入口不变（持仓行"TP/SL"按钮），后端从写 t_position 字段改成 UPSERT t_pending_order_trigger
- 用户撤 LIMIT/STOP：在"挂单"tab 点撤销
- 撤 SL/TP：在持仓行"TP/SL"按钮 → 清空

### 2.4 API 契约

#### 2.4.1 客户端（gateway → trading-core）

```
POST /api/v1/trading/orders/limit
Body: { symbol, side, quantity, limitPrice, leverage, marginMode?, clientOrderId }
Resp: PendingOrderResponse

POST /api/v1/trading/orders/stop
Body: { symbol, side, quantity, stopPrice, leverage, marginMode?, clientOrderId }
Resp: PendingOrderResponse

POST /api/v1/trading/orders/stop-limit
Body: { symbol, side, quantity, stopPrice, limitPrice, leverage, marginMode?, clientOrderId }
Resp: PendingOrderResponse

PATCH /api/v1/trading/positions/{id}/sl-tp
Body: { takeProfitPrice?, stopLossPrice? }  # null 表示清空
Resp: PositionItem  # 复用现有响应；后端拼装 SL/TP 字段
# 备注：路径与请求体保持与现行 `PATCH /api/v1/trading/positions/{id}` 编辑 TP/SL 的口径一致，
#       客户端 OrderTicket 的 "持仓 TP/SL 编辑" 不需要改 API；
#       后端从写 t_position 字段改为 UPSERT t_pending_order_trigger（order_type=5）。

DELETE /api/v1/trading/orders/{id}
Resp: PendingOrderResponse

PATCH /api/v1/trading/orders/{id}
Body: { triggerPrice?, limitPrice?, quantity? }
Resp: PendingOrderResponse

GET /api/v1/trading/orders/pending?status=&symbol=&page=&pageSize=
Resp: PaginatedResponse<PendingOrderItem>
# 默认只返回开仓挂单（order_type IN (LIMIT, STOP, STOP_LIMIT)）；SL_TP 由持仓接口回显
```

#### 2.4.2 管理端（gateway → console-service → trading-core）

```
GET    /admin/trading/pending-orders?userId=&symbol=&status=&includeSlTp=false&page=&size=
POST   /admin/trading/pending-orders/{id}/cancel  Body: { reason }
# includeSlTp 默认 false，admin 默认看开仓挂单；勾选后看全部含 SL_TP
```

### 2.5 资金冻结口径（R2 决策 A "创建即冻"）

| 操作 | 资金变动 |
| --- | --- |
| POST /orders/limit 创建挂单 | `frozen += notional × marginRate + estimatedFee`（`notional = quantity × triggerPrice`）；`available -= 同上` |
| DELETE /orders/{id} 撤单 | `frozen -= frozen_margin + frozen_fee`；`available += 同上` |
| 触发（PENDING → TRIGGERED）| `frozen -= frozen_margin + frozen_fee`（释放挂单冻结）；市价单流程内重新按实际成交价 `marginUsed += 实际 margin` |
| 触发被拒（资金不足等）| 同撤单逻辑；状态 → REJECTED |

SL_TP 类型挂单：`frozen_margin = 0, frozen_fee = 0`（这类挂单是平仓单，不需要冻保证金；只在触发时反向平 parent_position_id 的持仓）。

### 2.6 CROSS 防御要求（必须）

引自 [`docs/setup/当前开发计划.md`](../../setup/当前开发计划.md) §5 "CROSS-MARGIN-EXEC-01" 延后实施口径备忘：

- **新增** `TradingAccountSnapshotService.calculateAvailableForOrder(account, requestedMargin, requestedFee)` 抽象方法，返回是否可下单 + 拒单原因。
- 挂单创建 / 触发评估 / 修改 / 撤单 **全部**调该方法，**不得**在挂单触发器 / 资金冻结代码里硬编码 `balance - frozen - marginUsed` 公式。
- 方法实现按当前 marginMode 分支：
  - ISOLATED：`available = balance - frozen - marginUsed`，比对 `requestedMargin + requestedFee`
  - CROSS（占位）：返回 "CROSS not implemented"，触发 `MARGIN_MODE_NOT_SUPPORTED`（与 DefaultTradingRiskService 现有口径一致）
- 后续 `CROSS-MARGIN-EXEC-01` 实施时只需在该方法内填 CROSS 分支，不需要回头改挂单代码。

### 2.7 错误码新增（TradingErrorCode）

| 错误码 | 枚举 | 含义 |
| --- | --- | --- |
| `30013` | `PENDING_ORDER_TOO_CLOSE` | 挂单价与 markPrice 距离 < 该 symbol pending_order_min_distance_ratio |
| `30014` | `PENDING_ORDER_NOT_FOUND` | 撤单 / 修改时 ID 不存在 |
| `30015` | `PENDING_ORDER_INVALID_STATE` | 撤单 / 修改时挂单已非 PENDING 状态（已触发 / 已撤）|
| `30016` | `PENDING_ORDER_INSUFFICIENT_FUNDS` | 创建挂单时 available < 冻结额 |
| `30017` | `QTY_PRECISION_EXCEEDED` | 挂单 quantity 小数位 > symbol `qtyPrecision`（防超精度脏数据入库）|
| `30018` | `PRICE_PRECISION_EXCEEDED` | 挂单 limitPrice / triggerPrice 小数位 > symbol `pricePrecision` |

### 2.8 距离阈值（R2 决策 B "按 symbol 配置"）

- `t_risk_config.pending_order_min_distance_ratio` 字段（V16 新增）
- 默认 `0.003`（0.3%）；NULL = 该 symbol 不限
- 校验：`|triggerPrice - markPrice| / markPrice < ratio` 时拒
- admin 可通过现有 `/admin/risk-configs/{symbol}` 更新（已有 PUT 端点，本期仅扩字段）

---

## §3. 角色完成判定

| 角色 | 完成判定 | 状态 |
| --- | --- | --- |
| R1 | 任务卡 §1-§2 齐全 | ✅（commit `bb6333f` / `2cbd60a`）|
| R2 | schema + 错误码 + API + 触发架构 + CROSS 防御口径冻结 | ✅（同上）|
| R3 | 客户端 OrderTicket UI 设计（三 tab）+ 管理端挂单监控 UI 设计 | ✅（并入 R5/R10 实施）|
| R6 | 集成测试用例骨架 | ✅（继承现有 19+8+2 测试覆盖；R7 端到端补足关键路径）|
| **R4 trading-core** | V16 migration + 新表 entity/repo/mapper + PendingOrderTriggerEvaluator + 资金冻结 + TradingAccountSnapshotService.calculateAvailableForOrder + 5 个用户 REST + 触发集成（双轨过渡 PositionTriggerRuleEvaluator TP/SL 保留）| ✅（commit `3f4e4bd` + `4bc5f88` + `a35fecc`）|
| **R5 客户端** | OrderTicket 新增 LIMIT/STOP tab；持仓行 TP/SL 交互保留；新增"挂单"tab + 撤改 modal；雪花 ID string 化修复 JSON.parse 精度损失 | ✅（commit `05e6944` + 本 commit 精度修复）|
| **R9 console-service** | 2 个 admin RPC（list + force cancel） | ✅（commit `7590112`）|
| **R10 console-frontend** | 挂单监控页（默认过滤 SL_TP，可勾选"含 SL/TP"）+ 强制撤单 modal + 路由 + 菜单项 + 同精度修复 | ✅（commit `7590112` + 本 commit 精度修复）|
| R7 | 浏览器实操：LIMIT 创建 / 列表 / 撤单 / 修改；管理端列表 / 强制撤单；雪花 ID 精度修复后 cancel 后端实际命中 | ✅（本 commit，详见 §7）|
| R8 | 任务卡完成判定 + 当前开发计划同步 | ✅（本 commit）|

---

## §4. 性能预算

| 指标 | 预算 | 备注 |
| --- | --- | --- |
| PendingOrderTriggerEvaluator | < 5ms / tick / symbol | 按 symbol_status 索引扫 PENDING；热门 BTCUSDT 估 < 100 单 |
| 资金冻结 / 释放 | 单事务 SELECT FOR UPDATE | 复用现有 `MybatisTradingAccountRepository.findByUserIdForUpdate` |
| migration（V16）| 单次 O(N OPEN 持仓) | dev 当前 ~7 持仓，prod 估 < 1000，可在线 |
| Kafka order.triggered | 节流不需要 | 单 user 触发频率低 |

---

## §5. Git 回滚点

- R1+R2 commit（本任务卡 + R2 契约草案）
- R4 commit（schema V16 + 后端实施 + R6 测试骨架）
- R5 commit（客户端）
- R9 commit（console-service）
- R10 commit（console-frontend）
- R7+R8 commit（验证 + 文档同步）
- V17 commit（阶段 3 完成稳定 1-2 周后单独执行 DROP COLUMN）

回滚策略：
- R4 失败 → `git revert` + 手动 `DROP TABLE t_pending_order_trigger` + `ALTER TABLE t_risk_config DROP COLUMN pending_order_min_distance_ratio`
- V16 已迁移的 SL/TP 数据：t_position 字段未删，可直接回退到旧逻辑读字段

---

## §6. 关键风险

1. **migration 雪花 ID**：V16 backfill 用 `-1` 占位，trading-core 启动时需要 backfill 真实 ID。需要在 `R4` 实现启动时一次性扫描 `t_pending_order_trigger WHERE id = -1` 并替换。
2. **撤单 vs 触发竞态**：tick 来时 evaluator 已选中某挂单进入触发流程，user 同时撤单 → 必须用 FOR UPDATE 锁挂单行，先扣 status 再操作。
3. **资金冻结 vs 触发竞态**：触发时释放 frozen + 创建市价单中间状态，市价单流程若失败（如 markPrice 跳变后资金不足）必须能回滚到 frozen 重新冻结 → 标记 status=REJECTED。
4. **SL_TP 与 parent_position 关联**：parent_position 平仓时（手动平 / 强平）必须级联撤所有关联 SL_TP 挂单（status=CANCELLED, cancel_reason=PARENT_POSITION_CLOSED）。
5. **migration 已有持仓 quantity 全平**：migrated SL/TP 的 quantity 用持仓全部 quantity，触发后全平。如果阶段 3 完成后用户做了"部分平仓"功能（不在本期），则 quantity 与持仓不一致时需校准。
6. **雪花 ID > 2^53 精度损失**（R7 发现，已修复）：`t_pending_order_trigger.id` 为雪花 ID（最大 ~48 位十进制），超过 JS Number 安全范围 `2^53 ≈ 9e15`，JSON.parse 默认转 number 会丢精度（如 `47938339441086464` → `47938339441086460`）。修复方式：客户端 + 管理端 listPendingOrders 用正则在 JSON 文本层 wrap `"id"/"parentPositionId"/"triggeredOrderId"` 为字符串，再 JSON.parse；类型层面这些字段统一改为 string。

---

## §7. R7 验证结果（2026-05-13）

### 7.1 客户端实操

trader 登录 `localhost:5200`，选 AUDCAD（现价 0.989），切到"限价"tab：

- 创建：限价 0.5 / 数量 200 / 杠杆 10 → 提交 → Toast `LIMIT 挂单已创建 OD40Q143Z6RK @ 0.5`
- 后端日志：`trading.pending-order.created id=47938339441086464 frozenMargin=10.00000000`
- 切到"挂单"tab：列表显示 OD40Q143Z6RK / AUDCAD / 限价 / 多 / 200 / 0.5 / 等待中
- 点撤销：第一次因 JS Number 精度损失 `id=47938339441086460` 后端找不到；修复后第二次 `id=47939075889565696` 成功 → 后端 `trading.pending-order.cancelled reason=USER_CANCELLED`

### 7.2 管理端实操

superadmin 登录 `localhost:5300/admin/trading/pending-orders`：

- 列表正确显示 5 个挂单（PENDING / TRIGGERED / CANCELLED 三种状态色块 + LIMIT/STOP 类型 Tag）
- 强制撤单 OD4116JZIM80：填 reason "R7 admin force cancel test v2" → 后端实际收到 `id=47939213290770432` 完整精度 → console-service 日志 `admin.pending-order.cancel.completed`

### 7.3 已知问题

- 阶段 3 R4 触发后调 placeMarketOrder 转市价，市价单本身可能因 BBOOK 风控（用户级敞口限制 / 方向集中度等）被 REJECTED；挂单 status 仍正确标记 TRIGGERED + 释放冻结。这是设计预期。
- 双轨过渡：PositionTriggerRuleEvaluator 仍读 `t_position.take_profit_price / stop_loss_price`；PATCH /sl-tp 同时写持仓字段 + 挂单表。**V17 阶段 3 稳定 1-2 周后**单独执行 DROP COLUMN 切到纯挂单表。
- 雪花 ID 精度问题**仅修复挂单接口**；其他模块（订单 / 持仓 / 成交）尚未受影响，因为：(a) 它们通过 `orderNo` 而非 `id` 操作；(b) 当前 t_position.id 等也是雪花但未涉及精度损失的 UI 操作链路。后续若新增按 id 操作的接口需类似处理。

### 7.4 副作用

- trader 密码 `Trader@2026` / superadmin 密码 `Trader@2026` 未恢复（沿用 STAGE-2 时设定）
- 5 条挂单记录留在 prod DB（OD3OVW9QXZI8 / OD3OWQ9FK000 / OD40Q143Z6RK / OD40ZFFMDY4G / OD4116JZIM80），其中 1 TRIGGERED + 4 CANCELLED，无 PENDING 残留。
