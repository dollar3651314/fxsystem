# 阶段 2.1 客户管理测试用例清单（V1，2026-05-09）

> R6 二轮交付，覆盖 [`管理端接口规范`](../api/管理端接口规范.md) §3 客户管理 5 个 console REST + §4 跨服务 internal RPC（identity 2 + trading-core 1）+ 限额双写（管理端架构 §4.4）+ 1 条 E2E 链路。
>
> R10 二轮 / R9 二轮 / R4 实施前由本清单作为成功标准；实施完成后由 R6 三轮把每条 TC 落地为真实 `@Test` 方法。

---

## §1. TC 编号块

| Prefix | 编号区间 | 数量 | 验收阶段 |
| --- | --- | --- | --- |
| `TC-CONSOLE-` | 300-349 | 50 | 阶段 2.1（console 服务侧） |
| `TC-INT-CONSOLE-` | 001-006 | 6 | 阶段 2.1（business-service 接收 internal RPC 侧） |
| `TC-E2E-CONSOLE-` | 002 | 1 | 阶段 2.1 E2E（管理员调余额完整链路） |
| `FE-CONSOLE-` | 100-129 | 30 | 阶段 2.1 前端 |

合计 **87** 个用例。

---

## §2. 文档结构

| 节 | 范围 | TC 数 |
| --- | --- | --- |
| §3 客户列表（GET /admin/customers） | 跨 schema JOIN + 多筛选 + 分页 | 8 |
| §4 客户详情（GET /admin/customers/{id}） | 含资金概览 | 4 |
| §5 冻结客户（POST /freeze） | RBAC + 状态机 + token 撤销 | 8 |
| §6 解冻客户（POST /unfreeze） | 状态机 + token 不主动恢复 | 5 |
| §7 调余额（POST /balance/adjust） | 限额双写 + 跨服务调用 + 审计 | 12 |
| §8 internal RPC（business-service 侧） | identity freeze/unfreeze + trading balance/adjust | 6 |
| §9 限额双写（Redis + DB 协作） | 启动重建 / Redis 失败兜底 / 累计跨日 | 7 |
| §10 E2E 完整链路 | 1 条 | 1 |
| §11 前端用例 FE-CONSOLE-100~129 | C1-C5 5 页面 | 30 |

---

## §3. 客户列表（GET /admin/customers）

### TC-CONSOLE-300 列表跨 schema JOIN 拼装余额

- **类型**：IT
- **前置**：identity.t_user 含 3 用户；trading.t_account 含 2 个对应 balance（第三个无账户）
- **输入**：`GET /admin/customers?page=0&size=20` + 超管 token
- **预期**：HTTP 200，`code=0`，`data.items.length=3`；前两个 item 有 `balanceUSD`，第三个 `balanceUSD=null` 或 `"0.00"`（按 R9 实现决策）
- **验证**：
  - [ ] 响应字段对齐 §3.1 接口规范（uid/email/status/balanceUSD/lastLoginAt/createdAt 全部存在）
  - [ ] balance 来自 trading schema，不是 identity schema
  - [ ] 跨 schema JOIN 不写业务表（强尝试 INSERT/UPDATE 应被 console DB user 权限拦截，见 §11 跨 schema 只读）

### TC-CONSOLE-301 邮箱模糊筛选

- **输入**：`?email=alice` + 含 `alice@example.com` `aliceB@x.com` `bob@y.com` 三用户
- **预期**：仅返回 alice@... 与 aliceB@x.com 两条（LIKE %alice% 大小写不敏感）

### TC-CONSOLE-302 状态多选筛选

- **输入**：`?status=ACTIVE,FROZEN`
- **预期**：仅返回这两个状态用户；BANNED / PENDING_DEPOSIT 不返回

### TC-CONSOLE-303 时间范围筛选 from/to

- **输入**：`?from=2026-05-01T00:00:00Z&to=2026-05-08T00:00:00Z`
- **预期**：created_at 在区间内；UTC 边界正确（含 from，不含 to）

### TC-CONSOLE-304 分页 page/size 参数

- **输入**：`?page=1&size=5` + 12 用户
- **预期**：返回第 6-10 条；`total=12` `page=1` `size=5`

### TC-CONSOLE-305 size 上限保护

- **输入**：`?size=200`
- **预期**：实际返回 100 条（按 §3.1 上限）+ size 字段强制为 100

### TC-CONSOLE-306 无 customer:view 权限

- **前置**：管理员仅有 `customer:freeze` 权限
- **预期**：HTTP 403 `code=90004` ADMIN_PERMISSION_DENIED

### TC-CONSOLE-307 SUPER_ADMIN 直接放行

- **前置**：超管登录
- **预期**：返回所有客户（无权限拦截）

---

## §4. 客户详情（GET /admin/customers/{userId}）

### TC-CONSOLE-310 详情含完整字段

- **输入**：`GET /admin/customers/{userId}` 已有客户
- **预期**：`data` 含 §3.2 全部字段（balance.totalUSD/availableUSD/marginUsedUSD + depositSummary.totalDepositUSD/lastDepositAt）

### TC-CONSOLE-311 客户不存在

- **输入**：不存在 userId
- **预期**：HTTP 404 `code=90303` ADMIN_CUSTOMER_NOT_FOUND

### TC-CONSOLE-312 PENDING_DEPOSIT 历史用户兼容

- **前置**：identity.t_user.status=0 (legacy)
- **预期**：详情返回 `status="PENDING_DEPOSIT"`；balance/depositSummary 均为 0

### TC-CONSOLE-313 无账户用户余额为 0

- **前置**：identity 有用户 + trading 无 t_account 行
- **预期**：`balance.totalUSD="0.00"` `availableUSD="0.00"` `marginUsedUSD="0.00"`，不抛 404

---

## §5. 冻结客户（POST /admin/customers/{userId}/freeze）

### TC-CONSOLE-315 正常冻结流程

- **前置**：客户 ACTIVE 状态 + 当前有 1 个有效 access token
- **输入**：`{ "reason": "测试违规交易行为" }` (≥10 字符)
- **预期**：
  - HTTP 200，`code=0`，`data={previousStatus:"ACTIVE",newStatus:"FROZEN"}`
  - identity.t_user.status=2 (FROZEN)
  - 该用户 access token 加入 Redis 黑名单
  - 该用户 refresh_token_session.used=true
  - t_admin_operation_log 新增一条 risk_level=HIGH_RISK 记录

### TC-CONSOLE-316 reason < 10 字符拒绝

- **输入**：`{ "reason": "短" }`
- **预期**：HTTP 400 `code=90304` ADMIN_REASON_TOO_SHORT

### TC-CONSOLE-317 reason 缺失字段

- **输入**：`{}`
- **预期**：HTTP 400（字段校验失败）

### TC-CONSOLE-318 客户已 FROZEN

- **前置**：客户已 FROZEN
- **预期**：HTTP 409 `code=90305` ADMIN_CUSTOMER_ALREADY_FROZEN

### TC-CONSOLE-319 客户已 BANNED 拒绝冻结

- **前置**：客户已 BANNED
- **预期**：HTTP 409 `code=90306` ADMIN_CUSTOMER_TERMINAL_STATUS

### TC-CONSOLE-320 无 customer:freeze 权限

- **前置**：管理员仅 `customer:view`
- **预期**：HTTP 403 `code=90004`

### TC-CONSOLE-321 客户不存在

- **预期**：HTTP 404 `code=90303` ADMIN_CUSTOMER_NOT_FOUND

### TC-CONSOLE-322 internal RPC 失败时 console 失败

- **前置**：模拟 identity-service 5xx
- **预期**：console 返回 5xx 或专用错误码；不写 `t_admin_operation_log`（按 R9.8 AOP @AfterReturning 仅成功后写）；客户状态保持 ACTIVE

---

## §6. 解冻客户（POST /admin/customers/{userId}/unfreeze）

### TC-CONSOLE-325 正常解冻流程

- **前置**：客户 FROZEN
- **输入**：`{ "reason": "客户已澄清交易合规" }`
- **预期**：identity.t_user.status=1 (ACTIVE)；token 不主动恢复（用户必须重新登录获取新 token）

### TC-CONSOLE-326 客户当前 ACTIVE 拒绝解冻

- **预期**：HTTP 409（identity 错误码 `10015` `IDENTITY_USER_NOT_FROZEN` 或类似，待 R4 实施时分配）

### TC-CONSOLE-327 reason < 10 拒绝

- **预期**：HTTP 400 `code=90304`

### TC-CONSOLE-328 BANNED 用户不可解冻

- **前置**：客户 BANNED（终态）
- **预期**：HTTP 409 `code=90306`

### TC-CONSOLE-329 无 customer:unfreeze 权限

- **预期**：HTTP 403 `code=90004`

---

## §7. 调余额（POST /admin/customers/{userId}/balance/adjust）

### TC-CONSOLE-330 正常增加余额

- **前置**：客户 balance=$1234.56
- **输入**：`{ "deltaUSD": "100.00", "reason": "客户充值奖励补发" }`
- **预期**：
  - HTTP 200，`balanceBefore="1234.56" balanceAfter="1334.56"`
  - trading.t_account.balance 已更新（FOR UPDATE 行锁）
  - trading.t_ledger 新增一行 biz_type=ADMIN_BALANCE_ADJUST，memo=reason
  - Redis `falconx:admin:balance-adjust:daily:{adminUserId}:{yyyyMMdd}` += 100
  - t_admin_operation_log risk_level=HIGH_RISK，before/after JSON 含 deltaUSD/balanceBefore/balanceAfter

### TC-CONSOLE-331 正常扣减余额

- **输入**：`{ "deltaUSD": "-50.00", "reason": "..." }`
- **预期**：trading t_account.balance 减 50；t_ledger 新增（amount 为负）

### TC-CONSOLE-332 单次超 $5000 拒绝

- **输入**：`{ "deltaUSD": "5001.00", "reason": "..." }`
- **预期**：HTTP 400 `code=90301` ADMIN_BALANCE_ADJUST_SINGLE_LIMIT_EXCEEDED；不调用 trading-core；不写审计

### TC-CONSOLE-333 单次超 $5000（扣减方向）

- **输入**：`{ "deltaUSD": "-5001.00", "reason": "..." }`
- **预期**：同上 `90301`（abs(deltaUSD) ≤ 5000）

### TC-CONSOLE-334 单日累计超 $20000 拒绝

- **前置**：Redis 当日累计已 18000；DB t_admin_operation_log 累计也 18000
- **输入**：`{ "deltaUSD": "3000.00", "reason": "..." }`
- **预期**：HTTP 400 `code=90302` ADMIN_BALANCE_ADJUST_DAILY_LIMIT_EXCEEDED；Redis 不 INCRBY；不调用 trading-core

### TC-CONSOLE-335 余额不足以扣减

- **前置**：balance=$50
- **输入**：`{ "deltaUSD": "-100.00", "reason": "..." }`
- **预期**：HTTP 400 `code=90307` ADMIN_BALANCE_INSUFFICIENT_FOR_NEGATIVE_ADJUST 或 trading-core `30031`；t_account 不变

### TC-CONSOLE-336 reason < 10 拒绝

- **预期**：HTTP 400 `code=90304`

### TC-CONSOLE-337 客户不存在

- **预期**：HTTP 404 `code=90303` 或 trading `30030` TRADING_ACCOUNT_NOT_FOUND（视实现）

### TC-CONSOLE-338 无 customer:balance:adjust 权限

- **预期**：HTTP 403 `code=90004`

### TC-CONSOLE-339 deltaUSD 精度超 2 位小数拒绝

- **输入**：`{ "deltaUSD": "100.123", "reason": "..." }`
- **预期**：HTTP 400（字段校验失败）

### TC-CONSOLE-340 deltaUSD = 0 拒绝

- **输入**：`{ "deltaUSD": "0.00", "reason": "..." }`
- **预期**：HTTP 400（业务无意义；R9 实施时显式拒绝）

### TC-CONSOLE-341 trading-core internal RPC 失败 → console 回滚

- **前置**：trading-core 5xx
- **预期**：console 返回 5xx；Redis 已 INCRBY 的需 DECRBY 回滚（保证 Redis 与 DB 一致；按 R9 实施补偿逻辑）；不写 t_admin_operation_log

---

## §8. internal RPC（business-service 接收侧）

### TC-INT-CONSOLE-001 identity freeze 内部接口正常

- **类型**：IT（identity-service）
- **输入**：`POST /internal/v1/identity/users/{id}/freeze` + `X-Internal-Token: <valid>` + `X-Admin-User-Id: 1` + body
- **预期**：HTTP 200；t_user.status=2；token 全部撤销
- **验证**：
  - [ ] gateway 路由 `/internal/v1/identity/**` 注入 X-Internal-Token
  - [ ] identity-service filter 校验 token 通过
  - [ ] 业务行为正确

### TC-INT-CONSOLE-002 internal token 缺失

- **输入**：不含 `X-Internal-Token` header（直连绕过 gateway）
- **预期**：HTTP 401 `code=90702` INTERNAL_TOKEN_MISSING

### TC-INT-CONSOLE-003 internal token 无效

- **输入**：`X-Internal-Token: wrong-token`
- **预期**：HTTP 401 `code=90701` INTERNAL_TOKEN_INVALID

### TC-INT-CONSOLE-004 X-Admin-User-Id 缺失

- **预期**：HTTP 400 `code=90703` INTERNAL_ADMIN_USER_ID_MISSING

### TC-INT-CONSOLE-005 trading-core balance/adjust 内部接口

- **输入**：合法 internal token + admin id + body
- **预期**：t_account UPDATE FOR UPDATE + t_ledger INSERT 同事务

### TC-INT-CONSOLE-006 trading-core 余额不足

- **输入**：`{ "deltaUSD": "-9999999" }` 客户 balance=$10
- **预期**：HTTP 409 `code=30031` TRADING_BALANCE_INSUFFICIENT；t_account 不变；t_ledger 不写

---

## §9. 限额双写（Redis + DB 协作）

### TC-CONSOLE-345 启动时从 DB 重建当日 Redis 累计

- **前置**：Redis 空；DB t_admin_operation_log 当日已有 3 条 customer:balance:adjust 累计 $5000
- **操作**：console-service 启动 → 启动 hook 读 DB SUM → 写 Redis
- **预期**：Redis key `falconx:admin:balance-adjust:daily:{adminUserId}:{today}` = 5000，TTL 25h

### TC-CONSOLE-346 Redis miss 时降级查 DB SUM

- **前置**：Redis 该 key 不存在（TTL 过期 / 启动 hook 未跑完）
- **预期**：调余额时降级 SQL 查询 + 重建 Redis；业务正常返回

### TC-CONSOLE-347 Redis 失败时业务继续

- **前置**：Redis 写入抛异常（连接断开）
- **操作**：调余额 $100
- **预期**：业务返回 200；t_admin_operation_log 写入；下次启动从 DB 重建

### TC-CONSOLE-348 跨 UTC 日累计重置

- **前置**：Redis 含昨日 key 累计 $19000；今日 key 不存在
- **输入**：今日调余额 $5000
- **预期**：今日 key 新建 = 5000，不受昨日影响；昨日 key TTL 自然过期

### TC-CONSOLE-349 多管理员独立累计

- **前置**：admin A 当日 $19000；admin B 当日 $0
- **输入**：admin B 调余额 $5000
- **预期**：admin B 通过；admin A 调 $2000 拒绝

### TC-CONSOLE-350 单次预校验在 Redis 累计前

- **前置**：Redis 当日 $0
- **输入**：单次 `deltaUSD=$5001`
- **预期**：HTTP 400 `90301`；Redis 不 INCRBY（不污染当日累计）

### TC-CONSOLE-351 累计超限回退 DECRBY

- **前置**：Redis 当日 $19500
- **输入**：`deltaUSD=$1000`（INCRBY 后 = 20500 超限）
- **预期**：发现超限后 DECRBY 1000 回退；HTTP 400 `90302`；Redis 当日累计仍 = 19500

---

## §10. E2E 完整链路

### TC-E2E-CONSOLE-002 管理员调余额完整链路

- **类型**：E2E
- **依赖**：gateway + console-service + identity-service + trading-core-service + MySQL + Redis
- **流程**：
  1. 超管登录 console → 获得 access token
  2. 创建测试客户（identity-service 注册接口走 C 端流程）
  3. 客户初始 balance=$0（trading-core 自动开户）
  4. POST `/admin/customers/{userId}/balance/adjust` `{deltaUSD:"500.00"}` → 200
  5. 验证：
     - trading.t_account.balance=500
     - trading.t_ledger 新增 ADMIN_BALANCE_ADJUST
     - falconx_console.t_admin_operation_log 新增 HIGH_RISK
     - Redis `falconx:admin:balance-adjust:daily:1:{today}=500`
  6. POST 第二次 `{deltaUSD:"4500.00"}` → 200，balance=5000，Redis=5000
  7. POST 第三次 `{deltaUSD:"15001.00"}` → 400 单次超限
  8. POST 第三次 `{deltaUSD:"15000.00"}` → 400 当日累计超限（5000+15000=20000，再加超限）
  9. POST 第三次 `{deltaUSD:"15000.00"}` → 200（5000+15000=20000 等于上限，按上限策略：≤ 20000 通过）
- **验证**：
  - [ ] 9 步全部通过 / 按预期失败
  - [ ] 日志全程不含完整 access token / X-Internal-Token

---

## §11. 前端用例（FE-CONSOLE-100~129）

### §11.1 C1 客户列表 (FE-CONSOLE-100~109)

- **FE-CONSOLE-100**：列表加载完成渲染所有列定义（uid/email/status/balanceUSD/createdAt）
- **FE-CONSOLE-101**：状态多选筛选拼接 `?status=ACTIVE,FROZEN`
- **FE-CONSOLE-102**：邮箱模糊输入 debounce 500ms 后请求
- **FE-CONSOLE-103**：分页切换调用 API + URL query 同步
- **FE-CONSOLE-104**：状态 Tag 颜色映射（ACTIVE green / FROZEN red / BANNED default / PENDING_DEPOSIT orange）
- **FE-CONSOLE-105**：balanceUSD 负数显示 colorError
- **FE-CONSOLE-106**：响应式 < 1024px Table scroll
- **FE-CONSOLE-107**：无 customer:view 权限路由前置 403
- **FE-CONSOLE-108**：列表为空显示 Empty
- **FE-CONSOLE-109**：列表加载失败显示 Result + 重试按钮

### §11.2 C2 客户详情 (FE-CONSOLE-110~115)

- **FE-CONSOLE-110**：详情加载渲染 Descriptions 全部字段
- **FE-CONSOLE-111**：ACTIVE 状态显示 [冻结] danger 按钮
- **FE-CONSOLE-112**：FROZEN 状态显示 [解冻] primary 按钮
- **FE-CONSOLE-113**：BANNED 状态所有操作按钮 disabled + tooltip
- **FE-CONSOLE-114**：按权限码 RequiresPermission 包裹按钮
- **FE-CONSOLE-115**：404 客户不存在跳转列表 + message 提示

### §11.3 C3-C5 高风险 Modal (FE-CONSOLE-116~129)

- **FE-CONSOLE-116**：C3 冻结 Modal 渲染 reason TextArea + 字符计数
- **FE-CONSOLE-117**：C3 reason < 10 按钮 disabled
- **FE-CONSOLE-118**：C3 提交时按钮 loading + disabled
- **FE-CONSOLE-119**：C3 90305 已冻结错误显示 notification + 关闭 Modal 刷新详情
- **FE-CONSOLE-120**：C4 解冻 Modal 文案微调（FROZEN → ACTIVE）+ okType primary
- **FE-CONSOLE-121**：C5 调余额 Modal 单次输入 $5001 按钮 disabled + 红字提示
- **FE-CONSOLE-122**：C5 增加 / 扣减 Radio 切换 + 实时预览余额变化
- **FE-CONSOLE-123**：C5 防误操作勾选框未勾选时按钮 disabled
- **FE-CONSOLE-124**：C5 实时预览：调整后余额 = 当前 + delta（前端纯展示）
- **FE-CONSOLE-125**：C5 90301 单次超限 → notification + 不关闭 Modal
- **FE-CONSOLE-126**：C5 90302 单日超限 → notification + 关闭 Modal（用户已无法操作）
- **FE-CONSOLE-127**：C5 提交成功 message.success + 关闭 Modal + 刷新详情余额
- **FE-CONSOLE-128**：3 个 Modal 操作原因 ≥ 10 字符校验一致
- **FE-CONSOLE-129**：3 个 Modal 关闭后 reason 状态清空（防数据残留）

---

## §12. 实施落地（R9 二轮 / R10 二轮 / R4 完成后由 R6 三轮填写）

| TC 编号 | 测试类:方法名 | 状态 |
| --- | --- | --- |
| TC-CONSOLE-300 ~ 313 | _`AdminCustomerQueryIntegrationTests`_ | ⏳ |
| TC-CONSOLE-315 ~ 322 | _`AdminCustomerFreezeIntegrationTests`_ | ⏳ |
| TC-CONSOLE-325 ~ 329 | _`AdminCustomerUnfreezeIntegrationTests`_ | ⏳ |
| TC-CONSOLE-330 ~ 341 | _`AdminCustomerBalanceAdjustIntegrationTests`_ | ⏳ |
| TC-INT-CONSOLE-001 ~ 004 | _`IdentityInternalApiTokenFilterTests` + `IdentityInternalUserAdminControllerTests`_ | ⏳ |
| TC-INT-CONSOLE-005 ~ 006 | _`TradingInternalAccountAdminControllerTests`_ | ⏳ |
| TC-CONSOLE-345 ~ 351 | _`AdminBalanceAdjustQuotaIntegrationTests` + `RedisDbDualWriteTests`_ | ⏳ |
| TC-E2E-CONSOLE-002 | _`GatewayAdminCustomerBalanceAdjustE2ETests`_ | ⏳ |
| FE-CONSOLE-100 ~ 129 | _`falconx-console-frontend/src/features/customer/*.test.tsx`_ | ⏳ |

---

## §13. 关联文档

- [STAGE-1-CONSOLE-test-cases.md](./STAGE-1-CONSOLE-test-cases.md)（R6 一轮，阶段 1 鉴权 + RBAC + IP 白名单）
- [STAGE-1-RBAC-CRUD-test-cases.md](./STAGE-1-RBAC-CRUD-test-cases.md)（R6 二轮 P2-P5）
- [管理端接口规范](../api/管理端接口规范.md) §3 §4 客户管理 + internal RPC
- [管理端架构](../architecture/管理端架构.md) §4.1 §4.4 internal token + 限额双写
- [管理端 5 页面方案](../design/falconx-console-pages-V1.md) §9 客户管理设计
- [BBook 一期完成执行路径 §5.1](../process/BBook一期完成执行路径.md)
- [完成定义](../process/完成定义.md)
- [SKILLS Skill 10](../../SKILLS.md)
