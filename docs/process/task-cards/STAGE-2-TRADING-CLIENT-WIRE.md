# STAGE-2-TRADING-CLIENT-WIRE 任务卡

> 把 V1 已完成的 trading-core 客户端 REST + WebSocket 接入 falconx-frontend：把 OrderTicketPreview disabled 预览升级为真实市价下单 + 底部三 Tab 持仓/订单/成交 + 持仓行内 平仓/TP-SL/补保证金 Modal + WS 实时推送。

| 项 | 值 |
| --- | --- |
| 任务编号 | STAGE-2-TRADING-CLIENT-WIRE |
| 所属阶段 | 阶段 2.6（V1 客户端接入收口，紧接阶段 3 之前） |
| 启动日期 | 2026-05-12 |
| 角色路由 | R1 → R2 → R3 → R6 → **R5** → R7 → R8 |
| 涉及服务 | **仅客户端 falconx-frontend**（trading-core / gateway 已就绪，零后端改动） |
| 影响 schema | 无 |
| 错误码段位 | 无新增（透传 trading-core 现有错误码） |

---

## §1. 范围

### 1.1 必须交付

| 能力 | 终态 |
| --- | --- |
| 市价下单 | OrderTicket 面板：symbol（市场列表选）/买卖/数量/杠杆/保证金模式/可选 TP/SL → POST /api/v1/trading/orders/market |
| 持仓列表 | 底部 Tab1：所有 OPEN 持仓，含实时盈亏列、行内「平仓」+「TP/SL」+「补保证金」按钮 |
| 订单列表 | 底部 Tab2：全部订单（已成交 / 已拒绝 / 已撤销） |
| 成交列表 | 底部 Tab3：全部 trade 记录 |
| 平仓 Modal | 二次确认 + POST /api/v1/trading/positions/{id}/close |
| TP/SL Modal | 修改 TP/SL → PATCH /api/v1/trading/positions/{id} |
| 补保证金 Modal | 输入金额 → POST /api/v1/trading/positions/{id}/margin |
| WS 推送 | 接入 /ws/v1/trading：OrderFilled / OrderRejected / PositionClosed 后自动 refetch 持仓 + 订单 |

### 1.2 不在范围

- LIMIT / STOP 挂单（留阶段 3 STAGE-3-PENDING-ORDERS）
- 强平 / Swap 列表（已有 trading-core REST，本会话不接 UI）
- 余额 / 账本展示（profile 页已有，本会话不动）
- WebSocket 重连退避（沿用 useMarketSocket 模式）

---

## §2. R2 契约关键决策（已冻结 2026-05-12 R2 一轮）

| 决策点 | 选择 | 理由 |
| --- | --- | --- |
| 下单面板 UI | 原地改造 OrderTicketPreview | 设计已有；TradingTerminal 右侧 panel 位置保留；不破坏现有布局 |
| 持仓/订单/成交 panel 位置 | TradingTerminal 底部三 Tab 区 | 与单页交易终端设计一致；用户不切页就能看持仓 |
| WebSocket | 本会话接入 /ws/v1/trading | 后端 TradingUserWebSocketHandler 已就绪；refetch 优于 setInterval 轮询 |
| TP/SL 修改 | 开仓时一次性设 + 持仓行 Modal 修改 | 与阶段 3 BBook 设计（统一挂单表）演进路径兼容 |

---

## §3. 调用的 trading-core 现有端点（全部 V1 已实现）

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| POST | /api/v1/trading/orders/market | 市价开仓 |
| GET | /api/v1/trading/orders?page&pageSize | 订单列表 |
| GET | /api/v1/trading/trades?page&pageSize | 成交列表 |
| GET | /api/v1/trading/positions?page&pageSize | 持仓列表 |
| POST | /api/v1/trading/positions/{positionId}/close | 平仓 |
| POST | /api/v1/trading/positions/{positionId}/margin | 补保证金 |
| PATCH | /api/v1/trading/positions/{positionId} | 修改 TP/SL |
| GET | /api/v1/trading/accounts/me | 账户余额（下单面板显示可用） |
| WS | /ws/v1/trading?token=... | 用户实时事件推送 |

---

## §4. 角色完成判定

| 角色 | 完成判定 | 状态 |
| --- | --- | --- |
| R1 | 任务卡 §1-§3 齐全 | ⏳ |
| R2 | 现有 trading-core API 速查文档（本任务卡 §3） | ⏳ |
| R3 | 下单 + 三 Tab + 3 Modal UI 设计（本任务卡内描述） | ⏳ |
| R6 | 测试用例骨架（合并入任务卡） | ⏳ |
| **R5** | 客户端：tradingApi + useTradingSocket + OrderTicket + PositionsTable + OrdersTable + TradesTable + 3 Modal + lint/build/test 全过 | ⏳ |
| R7 | 浏览器实操下市价单成功 + WS 推送触发 refetch + 平仓 happy + TP/SL Modal | ⏳ |
| R8 | 当前开发计划新条目 + 任务卡完成 + push | ⏳ |

---

## §5. 测试用例骨架（合并 R6）

| ID | 场景 | 期望 |
| --- | --- | --- |
| TC-CW-01 | 下市价单 BTCUSDT 买 0.001@100x | 200 + positions 列表新增一行 |
| TC-CW-02 | 数量超出 spec.maxQty | 业务错误码 + 红 toast |
| TC-CW-03 | 杠杆 > spec.maxLeverage | 业务错误码 + 红 toast |
| TC-CW-04 | 持仓行平仓 | Modal 二次确认 → 持仓消失 + 成交列表新增 |
| TC-CW-05 | TP/SL Modal 修改 | 持仓行更新 + toast |
| TC-CW-06 | 补保证金 | balance 减少 + 持仓 margin 增加 |
| TC-CW-07 | WS 接通：开仓后 OrderFilled 推送 → refetch | 持仓自动出现 |
| TC-CW-08 | WS 断线重连 | useMarketSocket 模板已有 |

R7 必跑：TC-CW-01 + 04 + 05 + 07（4 个核心）。

---

## §6. Git 回滚点

单一发布 commit（流程产物 + R5 实施 + R7+R8 合并 push）。无 DB / 后端改动 → 失败回滚仅需 git revert 即可。

---

## §7. 与阶段 3 STAGE-3-PENDING-ORDERS 的衔接

本任务完成后客户端能下市价单 + 设 TP/SL（V1 模式：写 t_position.take_profit_price/stop_loss_price）。
阶段 3 启动后：
- 后端：t_pending_order_trigger 表 + 触发引擎 + LIMIT/STOP REST + V1 SL/TP 迁移到挂单表
- 客户端：OrderTicket 加 LIMIT/STOP tab；底部 Tabs 加「挂单」第 4 个 Tab
- 管理端：挂单监控页

本任务的 OrderTicket 和 PositionsTable 组件设计保留扩展点（type 字段 / actions 列）便于阶段 3 直接加 tab，不返工。
