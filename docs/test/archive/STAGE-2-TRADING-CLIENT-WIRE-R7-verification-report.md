# STAGE-2-TRADING-CLIENT-WIRE R7 验证报告

> 把 V1 已完成的 trading-core 客户端 REST + WebSocket 接入 falconx-frontend：OrderTicketPreview disabled 预览 → 真实下单 + 三 Tab 持仓/订单/成交 + 3 Modal + WS 实时刷新。

| 项 | 值 |
| --- | --- |
| 验证时间 | 2026-05-12 18:00-18:10 |
| 验证人角色 | R7 |
| 任务卡 | [`STAGE-2-TRADING-CLIENT-WIRE`](../../process/task-cards/STAGE-2-TRADING-CLIENT-WIRE.md) |
| 验证方式 | 浏览器实操下单 → 持仓显示 → TP/SL 修改 → 平仓全链路 + DB 验证 |
| 整体结论 | **R7 通过**：客户端三件套（lint/build/test）全过；浏览器实操下单 → 持仓 → TP/SL → 平仓 全链路通；附带修复 PATCH risk-controls 字段类型 bug |

---

## 1. 客户端三件套

| 测试集 | 结果 |
| --- | --- |
| `npm run lint` | ✅ 0 errors / 0 warnings |
| `npm run build` | ✅ vite 2224 modules / 585 KB / 184 KB gzip |
| `npm run test` | ✅ 46/46（12 test files） |

---

## 2. 浏览器实操验证（TC-CW-01 ~ 05 + 07）

### 2.1 用户准备

- 注册新用户 `trader+r71778579872@falconx.local`（FalconxR7@2026）
- 通过 console-service `POST /admin/customers/{userId}/balance/adjust` 加 1000 USDT 余额
- userId=47623273160249344

### 2.2 测试序列

| TC | 场景 | 结果 |
| --- | --- | --- |
| 登录 + 进入交易终端 | 邮箱密码登录 + 默认 AUDCAD 图表 | ✅ /tmp/falconx-r7-live/qa/cw-01-landing.png |
| TC-CW-01a | 下 AUDCAD BUY 100 (NOTIONAL_BELOW_MIN) | ✅ 红色 toast `40002 Order Rejected`，DB 写入 rejected order |
| TC-CW-01b | 下 AUDCAD BUY 10 (QTY_BELOW_MIN < min=100) | ✅ 红色 toast |
| TC-CW-01c | 下 AUDCAD BUY 200 成交 | ✅ 绿色 toast `已成交 AUDCAD BUY 200 @ 0.98881`；DB t_position 写入 OPEN |
| TC-CW-07 | WS OrderFilled 触发持仓 panel refetch | ✅ 持仓行自动出现，无需手动刷新 |
| TC-CW-05 | TP/SL Modal 设 1.05/0.95 | ✅ DB take_profit_price=1.05000000, stop_loss_price=0.95000000 |
| TC-CW-04 | 平仓 Modal 确认 | ✅ DB position.status=2 CLOSED, close_reason=1 MANUAL, close_price=0.98883, realized_pnl=+0.004 |
| WS PositionClosed | 平仓后持仓 panel 自动清空 | ✅ 持仓 Tab 显示「暂无持仓」 |
| 订单 Tab | 全部订单（含失败+成功）渲染 | ✅ cw-10-orders-tab.png |
| 成交 Tab | 2 行成交（开仓 + 平仓） | ✅ cw-11-trades-tab.png |

### 2.3 截图归档

| # | 文件 | 内容 |
| --- | --- | --- |
| 1 | cw-01-landing.png | 登录后首屏：1571 symbols 列表 + AUDCAD 图表 + OrderTicket 下单面板 + 三 Tab 持仓空 |
| 2 | cw-02-order-placed.png | 第一次下单失败：`40002 Order Rejected` 红色 toast |
| 3 | cw-03-order-filled.png | 第二次仍失败（QTY_BELOW_MIN） |
| 4 | cw-04-order-success.png | 200 AUDCAD 下单成功：持仓行 + 绿色 toast |
| 5 | cw-05-tpsl-modal.png | TP/SL Modal 打开（含持仓 ID/Symbol/方向/开仓价/强平价 + TP/SL 输入） |
| 6 | cw-07-tpsl-saved.png | TP/SL 保存后 Modal 关闭 |
| 7 | cw-08-close-modal.png | 平仓二次确认 Modal |
| 8 | cw-09-closed.png | 平仓后持仓 panel 自动清空 |
| 9 | cw-10-orders-tab.png | 订单 Tab：5 行订单（成交 + 拒绝混合） |
| 10 | cw-11-trades-tab.png | 成交 Tab：2 行 AUDCAD trade |

---

## 3. 附带修复

### 3.1 PATCH /api/v1/trading/positions/{id} 字段类型

**问题**：客户端首次 PATCH 调用 TP/SL 修改返回 400 Bad Request。

**根因**：trading-core `TradingPositionController#parsePositiveNullableDecimal` 用 `instanceof Number` 校验字段类型；客户端 JSON.stringify 传 string `"1.05"` 反序列化后是 String，校验失败抛 `TradingRequestValidationException`。

**修复**：`tradingApi.ts#updateRiskControls` 构造 body 时显式 `Number(value)` 转换；`addMargin` 同样修正 `amount` 字段。

**结果**：PATCH 200 OK，DB take_profit_price=1.05000000, stop_loss_price=0.95000000 正确写入。

### 3.2 V3/V4/V5 console migration 显式 id（继承自 R7 live 回归）

本次会话直接使用了已修复的 V3/V4/V5 migration（commit `648c840`），无新增修复。

---

## 4. WebSocket 接入验证

- 路径：`ws://localhost:5200/ws/v1/trading?token=...`（vite proxy → 18080 gateway → trading-core 18083）
- 事件：OrderFilled 触发 refetch 持仓 + 订单（持仓行自动出现）
- 事件：PositionClosed 触发 refetch（持仓行自动消失）
- 早期 console error `WebSocket is closed before the connection is established` 出现在登录前；登录后正常握手

---

## 5. 完成判定

| 判定项 | 结果 |
| --- | --- |
| R1 任务卡完整 | ✅ commit (本次) |
| R2 契约速查（任务卡 §3）| ✅ |
| R3+R6 设计 + TC 骨架（任务卡 §1+§5） | ✅ |
| R5 客户端：12 个文件（tradingApi/types/WS/OrderTicket/3 Modal/3 Table/Tabs/CSS/TradingTerminal 接入） | ✅ |
| R7 实操下单 + WS + TP/SL + 平仓 全链路 | ✅ 本报告 |
| R8 当前开发计划 + 任务卡完成 | ✅ |
| 单一发布 commit + push | ⏳ 下一步 |

---

## 6. 与阶段 3 STAGE-3-PENDING-ORDERS 衔接预期

OrderTicket 当前是单一「市价单」面板；TradingTabs 是三 Tab；这两个组件设计保留扩展点：
- 阶段 3 在 OrderTicket 头部加 `市价 / LIMIT / STOP` segment，下方表单 conditionally 显示挂单条件输入
- 阶段 3 在 TradingTabs 加第 4 个 Tab「挂单」，复用 PositionsTable 的 actions 列模板

零返工切换。
