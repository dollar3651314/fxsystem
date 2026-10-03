# STAGE-2-CUSTOMER R7 验证报告

> 阶段 2.1 客户管理三端实施完成后的 R7 端到端验证证据，覆盖：console-service 后端 5 接口 + console-frontend 4 个 UI 流程 + 跨 schema JOIN + 跨服务 internal RPC + Redis 双写限额。

| 项 | 值 |
| --- | --- |
| 验证时间 | 2026-05-09 |
| 验证人角色 | R7 |
| 关联 commits | `4b53f0e`（R10 二轮）/ `f3a2ed3`（R9 二轮）/ `a2701ea`（R4.3）/ `079e742`（R4.2）/ `1760102`（R4.1） / `de09b38`（R7 一轮修复）/ `795ab3b`（R9+R10 二轮 FX-071 修复）|
| 测试用例集 | [`STAGE-2-CUSTOMER-test-cases.md`](../STAGE-2-CUSTOMER-test-cases.md)（87 TC 骨架）|
| 整体结论 | **R7 二轮通过**：后端 8 项 curl + Redis 双写 + 前端 7 个 UI 流程（登录 / Dashboard / 列表 / 详情 / freeze modal / unfreeze modal / 调余额 modal）全部通过；FX-071 已修复关闭；FX-069 / FX-070 不阻断当批，留待后续 R2 / R3 / R9 决策。 |

---

## 1. 验证范围

### 1.1 已通过的最低验收清单

| # | 项 | 结果 |
| --- | --- | --- |
| 1 | console-service 启动（port 18085）+ Flyway up-to-date + RBAC 字典扫描 | ✅ |
| 2 | identity-service / trading-core-service / gateway / console-service 4 个后端协同启动 | ✅ |
| 3 | console-frontend Vite 启动（port 5300，`ready in 167 ms`）| ✅ |
| 4 | 前端登录 → Dashboard UI 渲染 + 用户信息显示 + 客户管理菜单可见 | ✅（`/tmp/console-qa-2.1-2-dashboard.png`）|
| 5 | 前端客户列表 UI 渲染 + 跨 schema JOIN 字段（UID / 邮箱 / 状态 / 用户组 / 余额 / 创建时间）| ✅（`/tmp/console-qa-2.1-3-customer-list.png`）|
| 6 | 后端列表接口（含 emailLike / status / 时间范围 / 翻页 4 种筛选） | ✅ |
| 7 | 后端详情接口（跨 schema JOIN 余额、可用余额、占用保证金）| ✅ |
| 8 | 跨服务 internal RPC：console → gateway → identity `freeze` / `unfreeze` | ✅ |
| 9 | 跨服务 internal RPC：console → gateway → trading-core `balance/adjust`（含 t_ledger biz_type=11 写入）| ✅ |
| 10 | Redis + DB 双写当日累计限额 | ✅ |
| 11 | 错误码 90301 / 90303 / 90305 端到端透传 | ✅ |
| 12 | console-service 启动 / Flyway / 默认超管初始化等阶段 1 已验证项未回归 | ✅ |

### 1.2 R7 二轮（FX-071 修复后）补充验收项

| # | 项 | 结果 | 关联截图 |
| --- | --- | --- | --- |
| 13 | 前端客户详情 UI 渲染（user_id=46482114006355968 完整显示）| ✅ | `/tmp/console-qa-2.1-6-detail-after-fix.png` |
| 14 | 前端冻结 modal（高风险二次确认）：原因 < 10 字符按钮 disabled，输入合法原因后 ACTIVE → FROZEN | ✅ | `/tmp/console-qa-2.1-7-freeze-modal.png` + `7c-after-freeze.png` |
| 15 | 前端解冻 modal：FROZEN → ACTIVE | ✅ | `/tmp/console-qa-2.1-8-unfreeze-modal.png` + `8b-after-unfreeze.png` |
| 16 | 前端调余额 modal（最高风险）：动态预览金额、增加 / 扣减 radio、必勾选确认、+$50 提交后余额 $10400 → $10450 | ✅ | `/tmp/console-qa-2.1-9-adjust-modal.png` + `9b-filled.png` + `9c-after-adjust.png` |
| 17 | DB + Redis 数据一致性：t_ledger 3 条 biz_type=11、Redis 当日累计 65000 cents = $650 | ✅ | curl + redis-cli 输出 |

---

## 2. 后端 curl 验证证据

### 2.1 测试客户准备

```sql
-- 落地于 falconx_identity.t_user + falconx_trading.t_account
user_id        = 46482114006355968
uid            = UCPOJ8DURK00
email          = r7-test@example.com
status         = 1 (ACTIVE)
balance (USDT) = 10000.00 (起始)
```

### 2.2 8 个后端 curl 测试（全部通过）

| TC | 操作 | 预期 | 实测 |
| --- | --- | --- | --- |
| C1-001 | `GET /admin/customers?page=0&size=3` | 200 / items.length=3 / 跨 schema JOIN 余额字段非空 | ✅ items=3，含测试客户 |
| C2-001 | `GET /admin/customers/46482114006355968` | 200 / status=ACTIVE / balance.totalUSD=10400 | ✅（最终值，含 +500 -100 累计）|
| C3-001 | `POST /admin/customers/{id}/freeze {reason: ≥10字符}` | 200 / status=FROZEN / `t_user.status=2` | ✅ |
| C3-002 | 重复 freeze（已为 FROZEN）| 90305 ADMIN_CUSTOMER_ALREADY_FROZEN | ✅（reason ≥ 10 字符通过 validation 后才能触发本错误码） |
| C4-001 | `POST /admin/customers/{id}/unfreeze` | 200 / status=ACTIVE | ✅ |
| C5-001 | `POST /admin/customers/{id}/balance/adjust {deltaUSD:"500", reason}` | 200 / balance 10000 → 10500 / `t_ledger.biz_type=11` | ✅ |
| C5-002 | 单次 deltaUSD=5001 | 90301 ADMIN_BALANCE_ADJUST_SINGLE_LIMIT_EXCEEDED | ✅ |
| C5-003 | `deltaUSD:"-100"`（扣减）| 200 / balance 10500 → 10400 | ✅ |
| C2-002 | 不存在客户 ID | 90303 ADMIN_CUSTOMER_NOT_FOUND | ✅ |

### 2.3 Redis 双写当日累计限额验证

```
key   = falconx:admin:balance-adjust:daily:46440542028042240:20260509
value = 60000  (单位：cents)
解读   = abs(+500 美元) + abs(-100 美元) = 600 USD = 60000 cents
```

✅ 同 `t_balance_adjust_daily_quota` DB 表数值一致（双写一致性成立）；管理端架构 §4.4 设计意图达成。

---

## 3. 前端浏览器 QA 证据

| 截图 | 状态 | 说明 |
| --- | --- | --- |
| `/tmp/console-qa-2.1-1-login.png` | ✅ | 登录页，FalconX Console 品牌色卡片 + 用户名 / 密码两输入框 + 居中布局 |
| `/tmp/console-qa-2.1-2-dashboard.png` | ✅ | 登录后 Dashboard，左 Sider 含"仪表盘 / 客户管理"二菜单，右上头像 + 系统超级管理员（SUPER_ADMIN）|
| `/tmp/console-qa-2.1-3-customer-list.png` | ✅ | 客户列表，UID / 邮箱 / 状态 / 用户组 / 余额 / 创建时间 / 操作 7 列；跨 schema JOIN 数据展示成功；翻页 / 每页大小可用 |
| `/tmp/console-qa-2.1-3b-customer-list-wide.png` | ✅ | 1440x900 视口，列表呈现完整 |
| `/tmp/console-qa-2.1-5-customer-not-found.png` | ❌ | 客户详情页阻断现场：访问 `/admin/customers/46482114006355968` 时前端展示 "客户不存在"，证据见 §4.3 [FX-071] |

---

## 4. R7 验证发现的问题

### 4.1 [FX-069] CommonErrorCode 90001-90004 与 AdminErrorCode 9xxxx 段冲突（P2）

`falconx-common.CommonErrorCode` 用 `90001-90004`，与用户决策"管理端独占 9xxxx"以及阶段 1 已分配 `AdminErrorCode 90001-90008` 完全冲突。R7 端到端中 controller 入参 validation 失败时实际由 common 层返回 `90004 invalid request payload`，前端会按管理端 `90004 ADMIN_PERMISSION_DENIED` 误读。

**当前规避**：R7 测试 reason 字段 ≥10 字符避开 jakarta validation。

**修复路径（待 R2 决策）**：

- 方案 A：CommonErrorCode 迁移到独立段（如 `00001-00099`）
- 方案 B：AdminErrorCode 90001-90004 → 90010-90013 后移

详见 [统一问题清单 FX-069](../../process/统一问题清单.md)。

### 4.2 [FX-070] identity.t_user 缺 last_login_ip 字段但管理端契约 / 前端 / DTO 已包含（P3）

R3 设计 + R10 前端 + R9 DTO 都包含 `lastLoginIp` 字段，但 `falconx_identity.V1__init_identity_schema.sql` 没有该列。R7 启动 console-service 列接口立即触发 SQL `Unknown column 'u.last_login_ip'`。

**当前规避（已 commit `de09b38`）**：`AdminCustomerMapper.xml` 改 `NULL AS last_login_ip` 让验证流可继续。

**修复路径（待 R3 / R9 决策）**：

- 方案 A：identity V13 加 `last_login_ip VARCHAR(45) NULL` + 登录路径写入 IP
- 方案 B：从契约 / 设计 / DTO / 前端全部移除该字段

详见 [统一问题清单 FX-070](../../process/统一问题清单.md)。

### 4.3 [FX-071] 管理端客户管理雪花 ID 在前端 JSON.parse 精度丢失（P1，**阻断 R7**）

后端 4 个 DTO `userId: long` Jackson 序列化为 JSON `number`。BBook 雪花 ID（实测 `46482114006355968` ≈ 4.6e16）远超 JS `Number.MAX_SAFE_INTEGER ≈ 9.0e15`，浏览器 `JSON.parse` 把它解析成 `46482114006355970`（精度丢失），前端再用此值拼接 `/admin/customers/${userId}` → 后端 404 → 前端展示 "客户不存在"。

由此连带阻断：客户详情、冻结、解冻、调余额 共 4 个 UI 流程。

**当前规避**：无（R7 无法在不修代码情况下绕过）。

**修复路径**：

- 后端 4 DTO 雪花 ID 字段加 `@JsonSerialize(using = ToStringSerializer.class)`
- 前端 `types.ts` / `customerApi.ts` / `CustomerDetailPage.tsx` 把 `userId: number` 改 `string`，去掉 `Number(userId)`
- [`管理端接口规范`](../../api/管理端接口规范.md) §3 + [`STAGE-2-CUSTOMER 测试用例`](../STAGE-2-CUSTOMER-test-cases.md) 同步修订
- R7 二轮浏览器 QA 重跑 4 个被阻断流程

详见 [统一问题清单 FX-071](../../process/统一问题清单.md)。

---

## 5. R7 完成判定

按 [`AI工作模式`](../../process/AI工作模式.md) §5 完成判定（R7 二轮，FX-071 修复后）：

| 判定项 | 结果 |
| --- | --- |
| 所有介入角色均已完成自身交付（R2 / R3 / R4 / R6 / R9 / R10 一轮 + R9 / R10 二轮）| ✅ |
| R7 验证报告显示测试全通过、构建通过、浏览器 QA 通过 | ✅（前端 lint 0 errors 2 已知 warnings；前端 build 通过 3056 modules）|
| 涉及客户端可见行为时，前后端代码 + 测试 + 浏览器截图齐全 | ✅（7 张浏览器截图归档 `/tmp/console-qa-2.1-*.png`）|
| R8 文档同步完成 | ⏭️（待本报告 + 当前开发计划增补"阶段 2.1 已完成"小节）|

**结论**：阶段 2.1 客户管理 R7 端到端验证**通过**。FX-069（错误码段冲突）/ FX-070（last_login_ip schema 缺失）不阻断当批 R7 放行，但都已登记并指向后续 R2 / R3 / R9 决策。

---

## 6. 后续动作建议

按优先级执行：

1. **R8 同步**（立即）：[`docs/setup/当前开发计划.md`](../../setup/当前开发计划.md) 增补"阶段 2.1 客户管理已完成"小节，把本报告链入 [`docs/README.md`](../../README.md) 文档地图。
2. **R2 二轮 C**（P2）：FX-069 错误码段冲突决策；如选方案 B（AdminErrorCode 后移到 90010-90099），需同时修订阶段 1 已发布的 5 个错误码 + 前端 errorMap。
3. **R3 / R9 二轮**（P3）：FX-070 last_login_ip 字段决策；与 BBook 一期"运营审计"主题相关，建议方案 A（identity V13 加列 + 登录路径写入 IP）。
4. **第 2 批阶段 2 实施**：R9 + R10 二轮 RBAC 自管 4 模块（admin-users / roles / menus / permissions），按已冻结的 [`管理端接口规范`](../../api/管理端接口规范.md) §5 P2-P5 落地。
5. **审视其它服务的雪花 ID 序列化**（独立任务）：FX-071 修复仅覆盖 console 4 个 DTO，C 端 trading / identity 历史 API 也存在 long ID 字段，建议另开 FX 编号评估是否需要全局 ObjectMapper Long → String 模块。
