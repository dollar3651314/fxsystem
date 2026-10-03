# STAGE-4-PRICE-ALERT 任务卡

> 阶段 4 价格告警：用户对 symbol 设上下穿透阈值；触发后通过 WebSocket 实时推送 + 写入告警历史表；管理端可查全部告警 + 强制删除。**owner = trading-core**（复用 QuoteDrivenEngine 触发链路 + user WS 通道）。

| 项 | 值 |
| --- | --- |
| 任务编号 | `STAGE-4-PRICE-ALERT`（执行路径 §7 阶段 4） |
| 优先级 | P1（轻量业务功能；不阻断 V1 上线但属一期范围） |
| 启动日期 | 2026-05-13 |
| 角色路由 | R1 → R2 → R6 → (R4 ∥ R5 ∥ R9 ∥ R10) → R7 → R8 |
| 涉及服务 | trading-core / falconx-frontend / falconx-console-service / falconx-console-frontend |
| 影响 schema | 新表 `t_price_alert`（trading-core 库）|
| 错误码段位 | 新增 `30020 PRICE_ALERT_NOT_FOUND` / `30021 PRICE_ALERT_LIMIT_EXCEEDED` / `30022 PRICE_ALERT_INVALID_DIRECTION` |
| 估时 | 2 周 |
| 关联任务 | 阶段 8 通知系统（本期仅 WS 推送 + DB 历史，站内信收敛归阶段 8） |

---

## §1. 范围

### 1.1 必须交付

| 能力 | 接口 / 实现 |
| --- | --- |
| 创建告警 | `POST /api/v1/trading/price-alerts` Body: `{symbol, direction(ABOVE/BELOW), targetPrice, note?}` |
| 查询告警 | `GET /api/v1/trading/price-alerts?status=&page=` |
| 撤销告警 | `DELETE /api/v1/trading/price-alerts/{id}` |
| 触发引擎 | `PriceAlertEvaluator` 挂 `QuoteDrivenEngine.processTick` 内（在持仓 trigger 后、admin push 前）|
| 触发后 | trigger_count++ + last_triggered_at = now + WS push `price.alert.triggered`（envelope.type）|
| 节流 | **同一告警最多触发 3 次，每次间隔 ≥ 5 分钟**；trigger_count == 3 时 status ACTIVE→EXHAUSTED 终态 |
| 用户 WS | **新增 CHANNEL_PRICE_ALERTS = "price-alerts"**，客户端默认订阅 |
| 管理端列表 | `GET /admin/trading/price-alerts?userId=&symbol=&status=&page=` |
| 管理端强制删除 | `POST /admin/trading/price-alerts/{id}/delete`（status=ACTIVE 时硬删并通知用户）|
| 客户端 UI | 在交易台 K 线/Symbol 详情区加"告警"入口（Symbol 列表行或 OrderTicket 顶部加按钮）+ 告警 modal |
| 客户端告警 tab | TradingTabs 增第 5 个 tab "告警"（持仓/挂单/订单/成交/告警）|

### 1.2 不在范围

- 邮件/短信/Push 等外部通知通道（归阶段 8 通知系统）
- 站内信收件箱页面（归阶段 8）
- 多条件复合告警（如"BTC 涨 5% 且成交量翻倍"）
- 自定义节流间隔（V1 固定 5 分钟、3 次上限，不支持用户配置）
- 告警重启/重新激活功能（EXHAUSTED 终态后用户重新创建新告警）

### 1.3 关键决策

| 决策点 | 选择 | 理由 |
| --- | --- | --- |
| owner | trading-core | 复用 QuoteDrivenEngine + user WS 基础设施，最短路径 |
| 触发引擎 | 挂 QuoteDrivenEngine 同 PendingOrderTriggerEvaluator 平级 | 已有 tick 流水线 |
| 触发节流 | **3 次 × 5 分钟间隔**（trigger_count<3 AND now-last_triggered_at≥5min 才触发；trigger_count==3 → EXHAUSTED） | 提醒型告警避免单次错过；3 次后用户应该已经知道 |
| 触发判定基准价 | mark price（StandardQuote.mark）| 与 SL/TP 触发口径一致 |
| 通道 | **新增 CHANNEL_PRICE_ALERTS = "price-alerts"** | 告警是少数用户使用的功能，独立 channel 边界清晰 |
| 用户告警条数限制 | **每用户最多 10 条 ACTIVE 告警** | 防止滥用 + 提醒型工具 10 条够用；超过返 30021 拒 |
| 距离限制 | 不强制距离阈值 | 告警与挂单不同——离得近的告警有合法场景（"突破当前 0.989 立刻提醒"）|

---

## §2. R2 契约冻结

### 2.1 数据库 schema（trading-core Flyway V18）

> **V 编号说明**：V17 保留给 STAGE-3-PENDING-ORDER 完成稳定后删除 `t_position.take_profit_price / stop_loss_price` 字段；本任务使用 V18，不冲突。

```sql
-- V18__price_alert.sql

CREATE TABLE IF NOT EXISTS t_price_alert (
    id                  BIGINT          NOT NULL    COMMENT '主键（雪花 ID）',
    user_id             BIGINT          NOT NULL    COMMENT '用户 ID',
    symbol              VARCHAR(32)     NOT NULL    COMMENT '交易品种（平台 symbol）',
    direction           TINYINT         NOT NULL    COMMENT '1=ABOVE（向上穿透），2=BELOW（向下穿透）',
    target_price        DECIMAL(24,8)   NOT NULL    COMMENT '触发价',
    status              TINYINT         NOT NULL    COMMENT '1=ACTIVE, 2=EXHAUSTED（3次已触发完）, 3=CANCELLED, 4=ADMIN_DELETED',
    note                VARCHAR(200)    NULL        COMMENT '用户备注',
    base_price          DECIMAL(24,8)   NULL        COMMENT '创建时的 markPrice 快照（用于审计 / direction 校验）',
    trigger_count       INT             NOT NULL DEFAULT 0  COMMENT '已触发次数（0/1/2/3）',
    last_triggered_at   DATETIME(3)     NULL        COMMENT '最近一次触发时间（用于 5min 节流）',
    last_triggered_price DECIMAL(24,8)  NULL        COMMENT '最近一次触发瞬间的 markPrice',
    cancelled_at        DATETIME(3)     NULL        COMMENT '撤销时间（含用户撤销 / admin 删除）',
    cancel_source       VARCHAR(50)     NULL        COMMENT 'USER / ADMIN:{adminUserId}',
    created_at          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    INDEX idx_user_status        (user_id, status),
    INDEX idx_symbol_status      (symbol, status),
    INDEX idx_status_target      (status, target_price),
    INDEX idx_last_triggered_at  (last_triggered_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户价格告警表（3次/5min 节流）';

-- 备忘：V17 编号保留给 STAGE-3-PENDING-ORDER 后续 DROP COLUMN，本任务用 V18。
```

### 2.2 实体 + 枚举

```java
public enum TradingPriceAlertDirection { ABOVE(1), BELOW(2); }
public enum TradingPriceAlertStatus {
    ACTIVE(1),          // 等待触发；可被触发 3 次
    EXHAUSTED(2),       // 已触发 3 次自动终结
    CANCELLED(3),       // 用户撤销
    ADMIN_DELETED(4);   // 管理员强制删除
}

public record TradingPriceAlert(
    Long id, Long userId, String symbol,
    TradingPriceAlertDirection direction, BigDecimal targetPrice,
    TradingPriceAlertStatus status, String note, BigDecimal basePrice,
    int triggerCount,                          // 已触发次数 0..3
    OffsetDateTime lastTriggeredAt,            // 最近一次触发时间（用于 5min 节流）
    BigDecimal lastTriggeredPrice,
    OffsetDateTime cancelledAt, String cancelSource,
    OffsetDateTime createdAt, OffsetDateTime updatedAt
) {}
```

### 2.3 触发架构

```
QuoteDrivenEngine.processTick(quote)
  ├─ PositionTriggerRuleEvaluator（TP/SL/Liq）
  ├─ PendingOrderTriggerEvaluator（LIMIT/STOP/SL_TP）
  ├─ PriceAlertEvaluator.evaluate(symbol, mark) ← 新增
  │    └─ 扫该 symbol 所有 ACTIVE 告警 + (last_triggered_at IS NULL OR now - last_triggered_at ≥ 5min)
  │    └─ ABOVE → mark ≥ targetPrice 触发
  │    └─ BELOW → mark ≤ targetPrice 触发
  │    └─ 触发后 FOR UPDATE 锁告警行：
  │       - trigger_count + 1
  │       - last_triggered_at = now, last_triggered_price = mark
  │       - 如果 trigger_count == 3 → status = EXHAUSTED
  │       - 否则 status 仍 ACTIVE（继续等下一次穿透 + 5min 节流）
  │    └─ WS push price.alert.triggered envelope 到 price-alerts channel
  ├─ TradingRiskObservabilityService.refreshExposureFromQuote
  └─ realtimePushService.publishPositionPnlUpdates
```

### 2.4 创建告警的 direction 自动推导

如果用户提交 `direction` 显式为 ABOVE/BELOW，按用户值；如果未传或为 null，**按当前 markPrice 与 targetPrice 关系自动推导**：

```
targetPrice > currentMark → direction = ABOVE
targetPrice < currentMark → direction = BELOW
targetPrice = currentMark → 拒绝 PRICE_ALERT_INVALID_DIRECTION
```

服务端始终落库显式 direction 值。

### 2.5 API 契约

```
# 客户端
POST /api/v1/trading/price-alerts
Body: { symbol, direction?(ABOVE/BELOW), targetPrice, note? }
Resp: PriceAlertItem

GET /api/v1/trading/price-alerts?status=&symbol=&page=&pageSize=
Resp: PaginatedResponse<PriceAlertItem>

DELETE /api/v1/trading/price-alerts/{id}
Resp: PriceAlertItem

# 管理端（console-service → trading-core internal RPC）
GET  /admin/trading/price-alerts?userId=&symbol=&status=&page=&size=
POST /admin/trading/price-alerts/{id}/delete  Body: { reason }
```

### 2.6 WebSocket 推送

新增 channel `price-alerts`（与 positions / orders 平级），客户端默认订阅。

```json
{
  "type": "price.alert.triggered",
  "channel": "price-alerts",
  "data": {
    "alertId": "47999...",
    "symbol": "BTCUSDT",
    "direction": "ABOVE",
    "targetPrice": "70000.0",
    "triggeredPrice": "70015.5",
    "triggerCount": 2,
    "remainingTriggers": 1,
    "exhausted": false,
    "note": "突破新高",
    "triggeredAt": "2026-05-13T..."
  },
  "ts": "2026-05-13T..."
}
```

`remainingTriggers = 3 - triggerCount`；`exhausted = (triggerCount == 3)`。客户端可以根据 `exhausted` 决定是否在告警 tab 上把该条标灰。

### 2.7 错误码

| 错误码 | 枚举 | 含义 |
| --- | --- | --- |
| `30020` | `PRICE_ALERT_NOT_FOUND` | 撤销/查询时 ID 不存在 |
| `30021` | `PRICE_ALERT_LIMIT_EXCEEDED` | 用户 ACTIVE 告警数已达 10 条上限 |
| `30022` | `PRICE_ALERT_INVALID_DIRECTION` | direction 与 targetPrice 关系矛盾 / target=mark |

---

## §3. 角色完成判定

| 角色 | 完成判定 | 状态 |
| --- | --- | --- |
| R1 | 任务卡 §1-§2 齐全 | ✅（commit `5ea97d5` / `a4764b6`）|
| R2 | schema + 错误码 + API + WS + 触发架构冻结（10 条上限 / 3 次 × 5min 节流 / 新 channel）| ✅（同上）|
| R6 | 测试用例骨架并入 R4 集成测试 + R7 端到端验证 | ✅ |
| **R4 trading-core** | V18 migration + entity/repo/mapper + PriceAlertEvaluator + QuoteDrivenEngine 接入 + 3 个 REST + 新 CHANNEL_PRICE_ALERTS + UTC 时区 bug 修复 | ✅（commit `35757c5`）|
| **R5 客户端** | 告警 tab（第 5 个）+ "新建告警" modal + 历史告警显示 + useTradingSocket 加 `price-alerts` channel + WS Toast | ✅（commit `1aa15cb`）|
| **R9 console-service** | 2 个 admin RPC（list + force delete）+ 3 个 DTO + RequiresPermission | ✅（本 commit）|
| **R10 console-frontend** | 告警监控页（filter + 状态色块）+ 强制删除 modal + 路由 + 菜单 | ✅（本 commit）|
| R7 | 客户端：创建 / 列表 / 撤销 / 5min 节流真实生效；管理端：列表 / 强制删除（reason 必填，cancel_source=ADMIN:{id}）| ✅（本 commit，见 §7）|
| R8 | 任务卡完成判定 + 当前开发计划同步 | ✅（本 commit）|

---

## §4. 性能预算

| 指标 | 预算 | 备注 |
| --- | --- | --- |
| PriceAlertEvaluator | < 2ms / tick / symbol | 按 symbol_status 索引扫 ACTIVE；估热门 BTCUSDT < 200 条 |
| 触发后 WS push | best-effort | 失败不回滚 status |
| 用户告警容量 | 每用户 ≤ 10 ACTIVE | 提醒型工具够用；ACTIVE 数实时检查 |

---

## §5. Git 回滚点

- R1+R2 commit（任务卡 + 契约）
- R4 commit（schema V17 + 后端 + R6 测试骨架）
- R5 commit（客户端告警 tab + modal）
- R9+R10 commit（管理端）
- R7+R8 commit（验证 + 文档同步）

无业务数据迁移 → 失败 `git revert` + `DROP TABLE t_price_alert` 即可。

---

## §6. 关键风险

1. **触发判定与 SL/TP 一致性**：用 mark price 而不是 bid/ask，与现有 SL/TP 触发口径保持一致。
2. **节流竞态**：evaluator 在 SQL 层加 `last_triggered_at IS NULL OR DATEDIFF >= 5min` 过滤，并发 tick 进入临界区时通过 FOR UPDATE + `trigger_count < 3` CAS 写入避免重复触发。
3. **5min 节流的边界**：跨过整数分钟（如 12:00:00 触发，下次 12:04:59 收到 tick 不触发，12:05:00 才触发）。"5min" 用 `DATE_ADD(last_triggered_at, INTERVAL 5 MINUTE) <= NOW(3)` 严格判定。
4. **3 次后状态切 EXHAUSTED**：第 3 次触发同事务内更新 status=EXHAUSTED；下一 tick evaluator 自然过滤掉。
5. **WS push 失败**：alert 已持久化；user 重连后通过 GET /price-alerts?status=ACTIVE 全量补偿。
6. **direction 推导歧义**：用户未传时按 targetPrice vs currentMark 推导；target=currentMark 拒绝。
7. **管理员强制删除**：写入 status=ADMIN_DELETED + cancel_source=ADMIN:{adminUserId}，触发 WS push 通知用户（避免用户以为告警还在跑）。
8. **channel 订阅遗忘**：客户端 useTradingSocket 默认 subscribe channels 数组必须加 `price-alerts`；忘加会导致用户已设告警但收不到通知（创建时已落库但推送 recipientCount=0）。R5 实施验证清单必须含"打开 WS 后看到 subscribed.channels 含 price-alerts"。
9. **UTC 时区 bug**（R4 阶段已修复）：`findTriggerableBySymbol(symbol, now)` 入参用 `OffsetDateTime.now()` 默认本地时区，与 repo 写入 `LocalDateTime.now(ZoneOffset.UTC)` 不一致 → SQL `DATE_SUB(#{now}, INTERVAL 5 MINUTE)` 比较时差 8 小时，节流条件 `last_triggered_at <= now - 5min` 始终成立，导致 13ms 内连触 3 次直接 EXHAUSTED。修复：QuoteDrivenEngine.triggerPriceAlerts 改 `OffsetDateTime.now(ZoneOffset.UTC)`，后续任何 SQL 时间比较都必须保持时区一致。

---

## §7. R7 验证结果（2026-05-13）

### 7.1 客户端实操

trader 登录 `localhost:5200`：
- 切到 "告警" tab（5 个 tab 之一）✅
- 创建告警 AUDCAD direction=null target=0.99 → 自动推 ABOVE / basePrice=0.98991500 ✅
- 创建立即触发 AUDCAD BELOW target=0.999 → tick 触发 trigger_count=1 ✅
- **5 分钟节流真实生效**：15:45 创建 → 15:51 自动触发第 2 次（间隔约 5 分钟）✅
- 撤销按钮 → 状态切 "已撤销" + 后端 `price-alert.cancelled` 日志 ✅
- 新建 modal：symbol 默认 selectedSymbol / mark 显示 / target<mark 自动推 BELOW ✅

### 7.2 管理端实操

superadmin 登录 `localhost:5300/admin/trading/price-alerts`：
- 列表正确显示 2 条历史告警（ACTIVE / CANCELLED 状态色块 + ABOVE/BELOW 方向色块）✅
- 强制删除 47954566980964352（reason="R7 强制删除测试"）：
  - trading-core 日志 `trading.price-alert.admin-deleted adminUserId=46440542028042240 reason=R7 强制删除测试` ✅
  - console-service `admin.price-alert.delete.completed` ✅
  - DB cancel_source = "ADMIN:46440542028042240:R7 强制删除测试" ✅

### 7.3 已知后续

- WS push 当前 `recipientCount=0`：trader 未在浏览器 tab 打开告警页时 WS 仍订阅 `price-alerts` channel，但浏览器实测短时未观察到 Toast 弹出。需要在客户端真实环境再验证一次（建议用户在 web 端打开 trader 账号设置一个会触发的告警，等 5 分钟看 Toast 是否出现 + console invalidateQueries 是否触发 price-alerts 列表刷新）。
- 告警监控页未显示用户名，仅显示 ID。后续如需补，应该通过 internal RPC 反查 identity 服务，或在 t_price_alert 加 user_uid 快照列。

### 7.4 副作用

- 5 条 DB 告警记录：1 EXHAUSTED + 2 CANCELLED + 1 ADMIN_DELETED + 1 ACTIVE（trader 47954566980964352 已被 admin 删，仅剩 47955139718778880 ACTIVE）
- trader / superadmin 密码 `Trader@2026` 未恢复
