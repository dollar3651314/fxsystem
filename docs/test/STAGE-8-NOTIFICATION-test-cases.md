# 阶段 8 通知系统测试用例清单（V1，2026-05-15）

> 本文件是 BBook 一期 V2 §11 阶段 8 STAGE-8-NOTIFICATION 收口的 R6 Phase 0 测试用例骨架。
>
> 覆盖：trading-core 模板服务 + send API + 5 现有触发点迁移 + 入金/风控 2 新触发点 + console 管理端透传 + console-frontend Vitest + 三端 E2E。
>
> Phase 1 MVP 已有：t_notification 表 / TradingNotificationApplicationService.create / 6 现有触发点 / 客户端 NotificationCenter。本文件覆盖 Phase 0 R2 契约冻结后的 Phase 1-4 实施测试。
>
> 关联文档：
> - [`管理端接口规范 §12`](../api/管理端接口规范.md)
> - [`falconx-console-pages-V1 §15`](../design/falconx-console-pages-V1.md)
> - [`V22__notification_template.sql`](../../falconx-trading-core-service/src/main/resources/db/migration/V22__notification_template.sql)
> - [`Kafka 事件规范`](../event/Kafka事件规范.md)（通知不发 Kafka，所有 send 同步调 service）

---

## §1. TC 编号块

| Prefix | 编号区间 | 数量 | 验收阶段 |
| --- | --- | --- | --- |
| `TC-NOTIF-` | 001-099 | ~45 | 阶段 8 |
| `TC-E2E-NOTIF-` | 001-009 | 2 | 阶段 8 |
| `TC-NOTIF-FE-` | 050-079 | ~15 | 阶段 8 |

---

## §2. 文档结构

| 节 | 范围 | TC 数 |
| --- | --- | --- |
| §3 trading-core NotificationTemplateService（缓存 + 渲染） | `NotificationTemplateService` 单测 | 7 |
| §4 trading-core send(templateCode, params) | `TradingNotificationApplicationService.send` IT | 6 |
| §5 trading-core 5 现有触发点迁移（向后兼容） | 价格告警/强平/TP/SL/KYC/出金 6 类型迁移到 send | 7 |
| §6 trading-core 入金 + 风控 2 新触发点 | DEPOSIT_CREDITED / RISK_ACTION_TRIGGERED | 4 |
| §7 trading-core 模板 admin internal RPC | 5 CRUD + 2 list/detail + 1 send | 10 |
| §8 trading-core NotificationChannelDispatcher SPI | IN_APP / EMAIL stub / TELEGRAM stub | 4 |
| §9 console-service 透传 + RBAC + 审计 | `AdminNotificationController` | 8 |
| §10 console-frontend Vitest | API 层 + 3 页面 + Modal | ~15 |
| §11 三端 E2E | 触发 → 落库 → 推 WS → 客户端展示 → 运营查看 | 2 |

合计 ~63 个 TC（46 后端 IT + ~15 frontend Vitest + 2 E2E）。

---

## §3. trading-core NotificationTemplateService（缓存 + 渲染）

> 测试类：`NotificationTemplateServiceTests`（UT，无 DB；mock TemplateRepository）

### TC-NOTIF-001 缓存命中：第二次 getByCode 不查 DB

- **前置**：mock repository.findByCode 返回模板
- **动作**：连续调 2 次 `getByCode("PRICE_ALERT_TRIGGERED")`
- **预期**：repository.findByCode 仅被调 1 次（caffeine cache 命中）

### TC-NOTIF-002 缓存失效：updateTemplate 后清缓存

- **动作**：getByCode → updateTemplate → getByCode
- **预期**：repository.findByCode 被调 2 次（update 触发 evict）

### TC-NOTIF-003 渲染：${var} 全部替换

- **输入**：template `"价格告警: ${symbol} 触发价 ${price}"`，params `{symbol: BTCUSD, price: 100000}`
- **预期**：`"价格告警: BTCUSD 触发价 100000"`

### TC-NOTIF-004 渲染：未提供的变量保留原样 + log warn

- **输入**：template `"${a} ${b}"`，params `{a: "foo"}`
- **预期**：返回 `"foo ${b}"`，log warn 命中 "notification.template.render.missing-param var=b template=..."

### TC-NOTIF-005 渲染：空 params Map

- **输入**：template `"${a}"`，params `Map.of()`
- **预期**：返回 `"${a}"` 原样 + log warn

### TC-NOTIF-006 渲染：null params

- **输入**：template `"${a}"`，params null
- **预期**：返回 `"${a}"` 原样（防 NPE）

### TC-NOTIF-007 模板不存在 → 抛 NOTIFICATION_TEMPLATE_NOT_FOUND

- **动作**：`getByCode("UNKNOWN")` 返回 Optional.empty
- **预期**：调用方（send API）需抛 `TradingErrorCode.NOTIFICATION_TEMPLATE_NOT_FOUND (30060)`

---

## §4. trading-core send(templateCode, params) API

> 测试类：`TradingNotificationApplicationServiceSendIntegrationTests`（IT，真 MySQL + 真 WS mock）

### TC-NOTIF-010 send 正常路径：渲染 + 写库 + 推 WS

- **前置**：seed 模板 `TEST_TEMPLATE`
- **动作**：`send("TEST_TEMPLATE", userId, params)`
- **预期**：
  - [ ] t_notification 写入 1 行 + template_code 字段填充
  - [ ] title/body 已渲染（${var} 已替换）
  - [ ] WS publishNotificationCreated 被调 1 次
  - [ ] 返回 TradingNotification 实体

### TC-NOTIF-011 send 模板 disabled → 抛 NOTIFICATION_TEMPLATE_NOT_FOUND

- **前置**：模板 enabled=0
- **预期**：抛 30060；t_notification 不写入

### TC-NOTIF-012 send 模板不存在 → 抛 30060

### TC-NOTIF-013 send 多 channels：IN_APP + EMAIL stub 都调 dispatcher

- **前置**：模板 channels=`["IN_APP","EMAIL"]`
- **预期**：InAppDispatcher 写库 + StubEmailDispatcher.dispatch 被调 1 次（仅 log，不真发）

### TC-NOTIF-014 send WS 推送失败不阻塞落库

- **前置**：mock WS 抛 RuntimeException
- **预期**：t_notification 仍写入；返回正常实体；log warn

### TC-NOTIF-015 旧 create() API 向后兼容保留

- **预期**：直接调旧 create(userId, type, level, title, body, ...)，t_notification 写入，template_code 为 null

---

## §5. trading-core 5 现有触发点迁移到 send

> 测试类：`Notification[Trigger]IntegrationTests`（每个触发点 1 个 IT 类）

### TC-NOTIF-020 价格告警 → 调 send("PRICE_ALERT_TRIGGERED", ...)

- **前置**：用户设价格告警；行情 tick 触发
- **预期**：`TradingPriceAlertApplicationService.onTrigger` 调 send 而非 create；t_notification.template_code = "PRICE_ALERT_TRIGGERED"

### TC-NOTIF-021 持仓 LIQUIDATED → 调 send("POSITION_LIQUIDATED", ...)
### TC-NOTIF-022 持仓 TP 触发 → 调 send("POSITION_TP_HIT", ...)
### TC-NOTIF-023 持仓 SL 触发 → 调 send("POSITION_SL_HIT", ...)
### TC-NOTIF-024 KYC APPROVED → 调 send("KYC_APPROVED", ...)；REJECTED → "KYC_REJECTED"
### TC-NOTIF-025 出金 CONFIRMED → send("WITHDRAW_COMPLETED", ...)
### TC-NOTIF-026 出金 FAILED → send("WITHDRAW_FAILED", ...)

每个 TC 断言：
- [ ] 旧 create() 不再被调（向后兼容路径仅在 fallback）
- [ ] template_code 字段填充正确
- [ ] title/body 与模板渲染一致

---

## §6. trading-core 入金 + 风控 2 新触发点

### TC-NOTIF-030 入金到账 → DepositCreditedEventConsumer 调 send("DEPOSIT_CREDITED", ...)

- **前置**：发布 `falconx.trading.deposit.credited` Kafka 事件
- **预期**：t_notification 新增 1 行，template_code="DEPOSIT_CREDITED"，title=`"入金到账"`，body 含 amount + currency + chain + txHash

### TC-NOTIF-031 入金幂等：同 walletTxId 重复消费不重发通知

### TC-NOTIF-032 风控动作触发 → TradingHedgeAlertEvent listener 调 send("RISK_ACTION_TRIGGERED", ...)

- **前置**：触发 GLOBAL_PAUSE 或 EXPOSURE_LIMIT
- **预期**：所有受影响用户每人 1 行 t_notification

### TC-NOTIF-033 风控 disabled 模板时 → send 抛 30060 + listener log warn，不影响风控主路径

---

## §7. trading-core 模板 admin internal RPC

> 测试类：`AdminNotificationTemplateInternalEndpointIntegrationTests`（MockMvc + 真 MySQL）

### TC-NOTIF-040 GET list 默认分页 + 字段
### TC-NOTIF-041 GET list 按 enabled/level 过滤
### TC-NOTIF-042 GET detail by code，不存在 → 30060
### TC-NOTIF-043 POST create 成功：写入 + 缓存失效
### TC-NOTIF-044 POST create code 重复 → 30061
### TC-NOTIF-045 POST create code 格式不合法 → 校验失败
### TC-NOTIF-046 PUT update 成功 + 缓存失效
### TC-NOTIF-047 DELETE 内置模板 → 30062
### TC-NOTIF-048 DELETE 自定义模板 → 软删 enabled=0
### TC-NOTIF-049 POST send 集成（模板 + params + userId）→ 返回 t_notification 实体

---

## §8. trading-core NotificationChannelDispatcher SPI

> 测试类：`NotificationDispatcherSpiTests`（UT）

### TC-NOTIF-060 InAppDispatcher.supports(IN_APP)=true，其他=false
### TC-NOTIF-061 StubEmailDispatcher.dispatch 仅 log，不抛异常
### TC-NOTIF-062 StubTelegramDispatcher 同上
### TC-NOTIF-063 send() 遍历 channels：每个支持的 dispatcher 都被调一次

---

## §9. console-service 透传 + RBAC + 审计

> 测试类：`AdminNotificationEndpointIntegrationTests`（MockMvc + WireMock for trading-core）

### TC-NOTIF-070 GET /admin/notifications 透传 trading-core
### TC-NOTIF-071 GET /admin/notification-templates 透传
### TC-NOTIF-072 POST /admin/notification-templates 透传 + 30061 → 90881 错误码翻译
### TC-NOTIF-073 PUT /admin/notification-templates/{code} 透传 + 30060 → 90880
### TC-NOTIF-074 DELETE /admin/notification-templates/{code} 透传 + 30062 → 90882
### TC-NOTIF-075 POST /admin/notifications/send reason 空 → 99004 前置校验（Bean Validation）
### TC-NOTIF-076 POST /admin/notifications/send 透传成功 + 审计 AOP 写 t_admin_operation_log
### TC-NOTIF-077 RBAC：缺 notification:view → 403；缺 notification:template:manage → 403；缺 notification:send → 403

---

## §10. console-frontend Vitest

> 测试文件：`notificationApi.test.ts` + `NotificationListPage.test.tsx` + `NotificationTemplateListPage.test.tsx` + `SendNotificationModal.test.tsx`

### TC-NOTIF-FE-050 ~ FE-052 notificationApi
- list buildQuery 透传 7 参数（userId/type/templateCode/level/status/from/to）
- listTemplates buildQuery 透传 enabled / level
- send POST body 含 userId + templateCode + params + reason

### TC-NOTIF-FE-053 ~ FE-056 NotificationListPage
- 渲染标题 + Table 行 + 状态 Tag + 等级 Tag
- 调用 listNotifications 走 /admin/notifications 默认分页
- 点击「详情」打开 Drawer + payloadJson JsonView
- 「+ 手动发送通知」按钮缺 notification:send → disabled + Tooltip

### TC-NOTIF-FE-057 ~ FE-060 NotificationTemplateListPage
- 渲染 + Channels 多 Tag 显示
- 内置模板「删除」按钮 disabled + Tooltip
- 点击「编辑」打开 Drawer + code 只读
- 点击「+ 新建」打开 Drawer + code 可输入 + 正则提示

### TC-NOTIF-FE-061 ~ FE-064 SendNotificationModal
- 选模板后 params 字段动态生成（${var} 解析）
- reason 留空 + 提交 → message.warning + 不发请求
- confirm checkbox 未勾 + 提交 → message.warning + 不发请求
- 成功提交 → message.success + 关闭 modal + reload list

---

## §11. 三端 E2E

### TC-E2E-NOTIF-001 出金完成整链 → 模板渲染 → 客户端 + 管理端可见

**步骤**：
1. 用户 A 提交出金 → 通过 → wallet 链上确认 → trading-core 消费 `wallet.withdraw.confirmed`
2. trading-core 调 `send("WITHDRAW_COMPLETED", userId=A, params={withdrawId, amount, currency})`
3. 客户端 A：NotificationCenter 铃铛 +1 + 收到 WS `notification.created` + 拉列表见新条目
4. 管理端：GET /admin/notifications?userId=A 见 1 行 template_code="WITHDRAW_COMPLETED"

**断言**：t_notification 写入 / title/body 与模板一致 / WS 推送 / 管理端可查

### TC-E2E-NOTIF-002 运营手动发送整链 → 用户客户端实时收到

**步骤**：
1. 管理端 admin 登录 → /admin/notifications 点「+ 手动发送通知」
2. 选用户 B + 模板 CUSTOM_TEST + 填 params + reason → 提交
3. 用户 B 客户端：铃铛 +1 + Toast / Drawer 展示
4. 审计日志：t_admin_operation_log 写入 1 行 permission_code=notification:send

---

## §12. 已知不覆盖项（R6 显式记录）

- **邮件 / Telegram 真实发送 E2E**：V2 一期 SPI stub log only，不验证真实送达
- **模板预览实时渲染**：R3 §15.9 明确不实现
- **用户群组批量发送**：单用户 only，群发推到 V2 后增量
- **通知撤回**：写入后不可撤
- **跨服务 Kafka 通知事件**：通知不发 Kafka（所有 send 同步调 service），Kafka 事件规范不增加新章节

---

## §13. R6 落地状态（Phase 4 frontend 已落地，2026-05-15 Phase 5 收口）

> 完整收口状态（commit SHA / 完成日期 / 已知不阻断项）详见 [当前开发计划 §1](../setup/当前开发计划.md)。本节仅维护测试类清单与真测试结果。

| 测试类 / 文件 | 计划 TC | 实际 @Test / it() | 状态 | 真测试结果 |
| --- | --- | --- | --- | --- |
| `NotificationTemplateServiceTests` | 7 | — | ⏳ R6 二轮（后续会话） | — |
| `TradingNotificationApplicationServiceSendIntegrationTests` | 6 | — | ⏳ R6 二轮 | — |
| 5 触发点迁移 IT（按触发点拆类）| 7 | — | ⏳ R6 二轮 | — |
| 入金 + 风控 2 触发点 IT | 4 | — | ⏳ R6 二轮 | — |
| `AdminNotificationTemplateInternalEndpointIntegrationTests` | 10 | — | ⏳ R6 二轮 | — |
| `NotificationDispatcherSpiTests` | 4 | — | ⏳ R6 二轮 | — |
| `AdminNotificationEndpointIntegrationTests` | 8 | — | ⏳ R6 二轮 | — |
| `notificationApi.test.ts` + `types.test.ts` | 15 计划 | **13 已落地（9 API 透传 + 4 extractPlaceholders）** | ✅ Phase 4 落地 | 13 / 13 pass |
| `NotificationListPage.test.tsx` / `NotificationTemplateListPage.test.tsx` 组件 | (含上 15) | — | ⏳ R6 二轮（受 STAGE-5 jsdom AntD Table fixed 限制启发，组件交互测试推迟）| — |
| `TC-E2E-NOTIF-001/002` | 2 | — | ⏳ R7 二轮（依赖浏览器 QA 环境）| — |

**合计 63 TC 计划，13 已落地（frontend API + util 工具层），50 待 R6 二轮 + R7 二轮落地**。

代码层 / baseline 验证（已通过）：
- `mvn -pl falconx-trading-core-service compile + test-compile` BUILD SUCCESS
- `mvn -pl falconx-console-service test` **44 / 44 全过**（含 baseline 全部 IT，无回归）
- `npm run test`（console-frontend 全量）**41 pass + 3 skip / 44**
- `npm run build` 680ms 成功

R7 验证报告：[`STAGE-8-NOTIFICATION-R7-verification-report.md`](./STAGE-8-NOTIFICATION-R7-verification-report.md)
