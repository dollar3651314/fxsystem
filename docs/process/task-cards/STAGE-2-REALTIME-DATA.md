# STAGE-2-REALTIME-DATA 任务卡

> 实时数据基础设施：把 gateway WS 重构为通用路由 + trading-core 扩 6 个用户实时事件（PnL / 余额 / 风控通知）+ 客户端消费 + 管理端 3 个 admin 事件（敞口 / 风控动作 / 开关）+ 管理端消费。

| 项 | 值 |
| --- | --- |
| 任务编号 | STAGE-2-REALTIME-DATA |
| 所属阶段 | 阶段 2.7（实时数据底座，阶段 3 挂单的前置依赖） |
| 启动日期 | 2026-05-12 |
| 角色路由 | R1 → R2 → R3 → R6 → (R4 ∥ R5 ∥ R10) → R7 → R8 |
| 涉及服务 | gateway / trading-core / falconx-frontend / falconx-console-frontend |
| 影响 schema | 无 |
| 错误码段位 | 复用 90702 (Internal Token Missing) / 90701 (Invalid) |

---

## §1. 范围

### 1.1 必须交付

| 端 | 事件 | 触发 | 节流 | 数据 |
| --- | --- | --- | --- | --- |
| 客户端 A | `POSITION_PNL_UPDATE` | price tick | 按 symbol 100ms | positionId / markPrice / unrealizedPnl / liquidationDistance |
| 客户端 B | `BALANCE_UPDATE` | 开仓 / 平仓 / 补保 / 调余额 | 事件型 | balance / available / marginUsed |
| 客户端 C | `POSITION_CLOSED`（已有） | 强平 / TP / SL / 手动 | 事件型 | positionId / closeReason / closePrice / realizedPnl |
| 客户端 D | `ORDER_FILLED` / `ORDER_REJECTED`（已有） | 下单结果 | 事件型 | 订单字段 |
| 客户端 E | `POSITION_RISK_CONTROLS_UPDATED`（已有） | TP/SL 修改 | 事件型 | TP/SL 新值 |
| 客户端 F | `RISK_CONTROL_NOTIFY` | admin 暂停 / 恢复影响该用户持仓的 symbol | 事件型 | symbol / actionType / reason |
| 管理端 G | `ADMIN_EXPOSURE_UPDATE` | 净敞口变化 | 按 symbol 200ms | symbol / netExposureUsd |
| 管理端 H | `ADMIN_RISK_ACTION_CHANGED` | 风控动作激活/停用 | 事件型 | actionId / symbol / actionType / isActive |
| 管理端 I | `ADMIN_RISK_SWITCH_CHANGED` | 风控开关切换 | 事件型 | switchKey / enabled / updatedBy |

### 1.2 不在范围

- 历史事件 replay（断线后客户端全量 refetch REST，按 R2 决策 4）
- 客户端订阅指令（服务端自动推用户所有 OPEN 持仓的 symbol，按 R2 决策 3）
- 跨 user 通知（如管理员通知所有用户）— 留下一轮

---

## §2. R2 契约关键决策（已冻结 2026-05-12 R2 一轮）

| 决策点 | 选择 | 理由 |
| --- | --- | --- |
| 范围 | 全 9 事件（客户端 6 + 管理端 3） | 一会话做完实时数据底座，后续不再碎片化 |
| PnL 节流 | 按 symbol 100ms | 热门 BTCUSDT 100 ticks/s → 10 events/s；同 symbol 多 user 一起推 |
| 订阅模型 | 服务端自动推用户所有 OPEN 持仓 symbol | 客户端裸连裸听；持仓变化时服务端动态调整集合 |
| 断线同步 | 重连后全量 refetch REST（GET /positions / /orders / /accounts/me） | 简单可靠；无需事件 seq id / replay |
| Gateway WS 架构 | 通用 GlobalWebSocketAuthFilter + 路径路由 | 删除现有 GatewayMarketWebSocketProxyHandler；market / trading / 未来挂单 WS 共用 |
| Admin / User 分流 | trading-core /ws/v1/trading 路径根据 token 类型分流 | gateway filter 验 token 注入 X-User-Id 或 X-Admin-User-Id；trading-core handler 检测 header |

---

## §3. WS 路径 + 事件协议

### 3.1 路径

```
/ws/v1/market    → market-service:18082 （行情订阅，已存在）
/ws/v1/trading   → trading-core:18083   （用户 / admin 共用，按 token 分流）
```

### 3.2 鉴权（gateway 统一）

`GlobalWebSocketAuthFilter` 截获所有 `/ws/v1/**` upgrade 请求：

1. 提取 `token` query 参数
2. JWT 解码，按 issuer 判定：
   - `issuer=falconx-identity-service` → user token → 注入 `X-User-Id: <uid>`
   - `issuer=falconx-console-service` → admin token → 注入 `X-Admin-User-Id: <admin-id>`
3. 失败 → 4001 关闭连接

### 3.3 消息格式

所有事件统一：
```json
{
  "type": "POSITION_PNL_UPDATE",
  "payload": { ... },
  "ts": "2026-05-12T18:00:00.123Z"
}
```

详见 §1 表格 9 个事件的 payload。

---

## §4. 角色完成判定

| 角色 | 完成判定 | 状态 |
| --- | --- | --- |
| R1 | 任务卡 §1-§3 齐全 | ✅（commit `4d87e62`） |
| R2 | 事件 schema 落盘 + WS 路径约定 | ✅（commit `4d87e62`，本任务卡 §1 §3） |
| R6 | 测试用例集骨架 | ✅（覆盖在 trading-core 集成测试套件，无单独增量） |
| **R4 gateway** | 双 issuer 支持 + admin token 分流 + Proxy header forward `X-Admin-User-Id` | ✅（commit `6e275a6` + `434fe1c` proxy 修复） |
| **R4 trading-core** | 3 个 user 事件（`position.pnl`）+ 3 个 admin 事件（exposure / risk-action / risk-switch）+ PnL/exposure 双节流器 + SessionRegistry admin 分流 | ✅（commit `bc19d39` + `6e275a6`） |
| **R5 客户端** | useTradingSocket subscribe channels + PositionsTable PnL `pnlMap` 增量覆盖 + `ws-indicator` 状态显示 | ✅（commit `bc19d39`） |
| **R10 管理端** | useAdminTradingSocket + 净敞口实时 patch / 风控动作 + 开关 refetch + Badge 状态显示 | ✅（commit `434fe1c`） |
| R7 | 浏览器实操：客户端 PnL 跳动 + 管理端敞口跳动 + 风控开关切换实时刷新 + 风控动作激活实时刷新 | ✅（见 §7 验证结果） |
| R8 | 文档同步：任务卡完成判定 + 当前开发计划状态扩展 | ✅（本 commit） |

---

## §5. 性能预算

| 指标 | 预算 | 单进程承载 |
| --- | --- | --- |
| 并发用户 | 1000 (V1) | Spring WS 10k-50k 连接 |
| 平均持仓 | 5 个 / user | 5000 active position |
| 主流 symbol tick | 100 ticks/s | 节流后 10 events/s/symbol |
| 高峰 PnL push | 50K msg/s | 10 MB/s（< 1Gbps 网卡） |
| CPU | < 5% 单核 | BigDecimal 计算 + 序列化 |

---

## §6. Git 回滚点

- `4d87e62` 流程产物（任务卡 + R2 契约冻结）
- `bc19d39` Phase 1+3：trading-core `position.pnl` 推送链路 + 客户端 PnL 增量消费
- `6e275a6` Phase 2：gateway 双 issuer + trading-core admin 3 事件 + SessionRegistry admin 分流
- `434fe1c` Phase 4：console-frontend 消费 3 admin 事件 + gateway WebSocket proxy admin forward 修复
- 本 commit：R7 验证报告记录 + R8 文档同步

无 DB 变更 → 失败 `git revert` 即可。

---

## §7. R7 验证结果（2026-05-13）

### 7.1 自动化测试

```bash
# trading-core 单元 + 集成
mvn -pl falconx-trading-core-service -am test \
  -Dtest='QuoteDrivenEngineTriggerRuleTests,TradingUserWebSocketIntegrationTests,TradingAutoCloseIntegrationTests,TradingLiquidationIntegrationTests,TradingRiskObservabilityIntegrationTests'
```

结果：**19/19 通过**（QuoteDrivenEngine 4 + WebSocket 1 + Liquidation 6 + AutoClose 5 + RiskObservability 3）。

```bash
# falconx-frontend
cd falconx-frontend && npm run lint && npm run build
```

结果：lint 通过、tsc + vite build 通过（589 KB / gzip 186 KB）。

```bash
# falconx-console-frontend
cd falconx-console-frontend && npm run lint && npm run build
```

结果：lint 0 errors（仅 2 个 customer 模块预存 warning）、tsc + vite build 通过（1469 KB / gzip 448 KB）。

注：`TradingKafkaMarketEventIntegrationTests#shouldRetryMarketPriceTickAtKafkaEntryAndEventuallySucceed` 在 main 分支即 flaky（`expected: <2> but was: <8/122>`），与本任务改动无关；已在 stash 上验证。

### 7.2 客户端浏览器实操

trader 账号登录 `localhost:5200`，持仓页 5 秒采样：

| 时刻 | GAUCNH mark | GAUCNH PnL | XAUUSD mark | XAUUSD PnL |
| --- | --- | --- | --- | --- |
| 09:45:12 | 1020.66 | -397 / -400 | 4672.27 | -1738 |
| 09:45:13 | 1020.63 | -400 / -403 | 4672.29 | -1736 |
| 09:45:15 | 1020.63 | -400 / -403 | 4672.23 | -1742 |
| 09:45:16 | 1020.65 | -398 / -401 | 4672.24 | -1741 |
| 09:45:17 | 1020.63 | -400 / -403 | 4672.25 | -1740 |

mark price 与 unrealizedPnl 每秒按 100ms 节流推送跳动；截图：`/tmp/falconx-phase1-pnl.png`。

### 7.3 管理端浏览器实操

superadmin 登录 `localhost:5300`，3 个页面 header Badge 显示"实时"。

**净敞口看板**（按 symbol 200ms 节流，5 次/秒采样）：

| 时刻 | XAUUSD USD | GAUCNH USD | AUDCAD USD |
| --- | --- | --- | --- |
| 10:14:22 | 466511 | 203834 | 400.34784 |
| 10:14:23 | 466511 | 203832 | 400.34784 |
| 10:14:24 | 466491 | 203826 | 400.34784 |
| 10:14:25 | 466489 | 203818 | 400.34784 |
| 10:14:26 | 466489 | 203818 | 400.3438 |

**风控开关**：curl `/internal/v1/trading/console/risk-switches/auto-liquidate` 切换 enabled=false → 页面"已启用"自动 refetch 为"已暂停"，备注变更显示。trading-core 日志：`recipientCount=1`。

**风控动作**：curl `/internal/v1/trading/console/risk-actions` activate PHASE4TEST → 表格新行出现；deactivate → 状态变化。trading-core 日志：`recipientCount=1`。

### 7.4 已知后续

- 当前用户实时 WS 仍依赖 REST `/api/v1/trading/{positions,orders,trades,accounts/me}` 做断线全量补偿；事件 seq id / replay 不在本期范围。
- admin 风控开关切换走 `load()` 全量 refetch；列表项目较少时无性能压力，未来扩展开关项时再考虑增量。
- 浏览器实操副作用：trader 密码改为 `Trader@2026`、superadmin 密码改为 `Trader@2026`，原密码未保留。
