# 阶段 9 BBook 风控运营完整化测试用例清单（V1，2026-05-15）

> 本文件是 BBook 一期 V2 §12 阶段 9 STAGE-9-RISK-OPS-COMPLETE 的 R6 Phase 0 测试用例骨架。
>
> 覆盖：§12.1 跨品种相关性组 evaluator 集成 + §12.2 审计日志查询接口 + frontend 审计页 + R7 三端验证。
> §12.3 风控运营手册已落地（129 行）+ R8 同步即可。
>
> 关联文档：
> - [`管理端接口规范 §13`](../api/管理端接口规范.md) — 审计日志查询契约
> - [`falconx-console-pages-V1 §16`](../design/falconx-console-pages-V1.md) — P18 审计日志页设计
> - [`V20__symbol_correlation_group.sql`](../../falconx-trading-core-service/src/main/resources/db/migration/V20__symbol_correlation_group.sql) — 4 内置组 seed

---

## §1. TC 编号块

| Prefix | 编号区间 | 数量 | 验收阶段 |
| --- | --- | --- | --- |
| `TC-RISK-OPS-` | 001-099 | ~24 | 阶段 9 |
| `TC-AUDIT-LOG-` | 100-199 | ~8 | 阶段 9 |
| `TC-AUDIT-FE-` | 050-079 | ~6 | 阶段 9 |
| `TC-E2E-RISK-OPS-` | 001-009 | 1 | 阶段 9 |

合计 ~39 TC（30 后端 IT + ~6 frontend Vitest + 1 E2E）。

---

## §2. §12.1 相关性组 evaluator（trading-core）

### TC-RISK-OPS-001 ~ 004 TradingSymbolCorrelationGroupRepository

- 001 findAllEnabled：仅返回 enabled=1 的组 + 成员
- 002 findGroupsBySymbol：按 symbol 反查所属组（多组可能）
- 003 startup 启动预热缓存（避免每次查 DB）
- 004 disabled 组的 symbol 查询返回空

### TC-RISK-OPS-010 ~ 015 evaluator 集成

- 010 仓位变化触发组合敞口聚合（同组多 symbol 持仓 USD 合计）
- 011 超组阈值 → 发布 TradingHedgeAlertEvent（actionType=CORRELATION_EXCEEDED）
- 012 复用 Phase 2 RISK_ACTION_TRIGGERED 通知模板 → t_notification 写入
- 013 单 symbol 多组归属：分别按各组阈值校验（一组超即触发）
- 014 weight 加权：成员 weight × 敞口 USD 求和
- 015 weight=0 成员视为不影响该组聚合

### TC-RISK-OPS-020 ~ 023 边界

- 020 空持仓 → 组合敞口为 0，不触发
- 021 组阈值动态调整：DB UPDATE 后 evaluator 下一轮 quote 读取最新值
- 022 跨方向多仓 + 空仓互抵：组合敞口 = |多仓 USD - 空仓 USD|
- 023 单 symbol 持仓但不在任何组：不参与组合聚合（不影响其他逻辑）

---

## §3. §12.2 审计日志查询（console-service）

> 测试类：`AdminAuditLogEndpointIntegrationTests`（MockMvc + 真 MySQL）

### TC-AUDIT-LOG-100 ~ 107 admin API

- 100 GET 列表默认分页 + 字段完整
- 101 按 adminUserId 过滤
- 102 按 permissionCode 过滤
- 103 按 targetType + targetId 过滤
- 104 按 riskLevel 过滤
- 105 时间范围 fromOccurredAt + toOccurredAt 过滤
- 106 GET detail 按 id 查询
- 107 detail id 不存在 → 90900

---

## §4. console-frontend Vitest

> 测试文件：`auditLogApi.test.ts` + `AuditLogListPage.test.tsx`

### TC-AUDIT-FE-050 ~ 055

- 050 auditLogApi.list 透传 6 query 参数
- 051 默认分页 page=1 size=20
- 052 detail 路径含 id
- 053 渲染 + Table 行 + riskLevel Tag 着色
- 054 详情 Drawer 打开 + before/after JSON diff
- 055 缺 audit-log:view → 路由 403

---

## §5. 三端 E2E

### TC-E2E-RISK-OPS-001 跨品种组合敞口触发整链

**步骤**：
1. 用户 A 同时持有 EURUSD / EURGBP / EURJPY 多仓（USD 合计接近 EUR_GROUP threshold_usd=3M）
2. quote tick 更新 → 组合敞口超阈值 → trading-core 发 TradingHedgeAlertEvent
3. TradingHedgeAlertEventListener Phase 2 已注册 → send("RISK_ACTION_TRIGGERED", ...) 写 t_notification + WS 推送
4. 管理端 `/admin/notifications?type=RISK_ACTION_TRIGGERED` 见新条目
5. 管理端 `/admin/audit-logs?permissionCode=...` 见相应风控动作审计

---

## §6. 已知不覆盖项（R6 显式记录）

- correlation group 管理端 CRUD UI（V2 一期 DB seed 维护，无管理端入口）
- 审计日志删除 / 编辑（设计禁止）
- 审计日志 CSV 导出（V2 一期不做，DB 查询 + 工具导出）

---

## §7. R6 落地状态

> 完整收口状态详见 [当前开发计划 §1](../setup/当前开发计划.md)。本节仅维护测试类清单 + 真测试结果。

| 测试类 | 计划 TC | 已落地 | 状态 |
| --- | --- | --- | --- |
| TradingSymbolCorrelationGroupRepository IT | 4 | — | ⏳ R6 二轮 |
| correlation evaluator IT | 6 | — | ⏳ R6 二轮 |
| evaluator 边界 IT | 4 | — | ⏳ R6 二轮 |
| AdminAuditLogEndpointIntegrationTests | 8 | — | ⏳ R6 二轮 |
| frontend Vitest (auditLogApi) | 3 | **3 / 3 pass** | ✅ Phase 3（TC-AUDIT-FE-050/051/052） |
| frontend Vitest (UI render) | 3 | — | ⏳ R6 二轮（TC-AUDIT-FE-053/054/055） |
| `TC-E2E-RISK-OPS-001` | 1 | — | ⏳ 浏览器 QA 环境就绪后 |

**合计 ~29 TC 计划，3 已落地（frontend auditLogApi）**。完整收口含 commit/日期/已知不阻断项详见 [当前开发计划 §1 STAGE-9-RISK-OPS-COMPLETE 条目](../setup/当前开发计划.md)。
