# 2026-05-19 全栈端到端测试报告

> 范围：客户端（5 个左侧菜单 + 4 个 topbar drawer）+ 管理端（9 个左侧菜单）+ 关键业务旅程 +
> 安全索问。本次重点验证「页面渲染 + 用户交互 + 安全护栏 + 业务逻辑可达」，**不**做长链路压测。
>
> 工具：Playwright 1.49 MCP + chromium-1223 + fonts-noto-cjk（修字体后）。
> 测试账号：`qa-e2e-20260519@example.com / QaE2e@2026`（新注册）+ `superadmin / FalconXAdmin@2026`（admin）。
>
> 已有覆盖：客户端 Vitest 14 file / 56 test 通过，console-frontend Vitest 11 file / 56 test 通过，
> trading-core IT 49 ↗ 受 V22 baseline 影响，console-service IT 14/14 通过。本报告不重复，仅补端到端 + 视觉。

---

## L1 烟雾测试（21 页面）

### admin（9 / 9 通过）

[l1-smoke/admin-01-dashboard.png](l1-smoke/admin-01-dashboard.png) 仪表盘
[l1-smoke/admin-02-customers.png](l1-smoke/admin-02-customers.png) 客户管理
[l1-smoke/admin-03-deposits.png](l1-smoke/admin-03-deposits.png) 入金记录
[l1-smoke/admin-04-symbols.png](l1-smoke/admin-04-symbols.png) 行情品种
[l1-smoke/admin-05-trading-positions.png](l1-smoke/admin-05-trading-positions.png) 持仓监控
[l1-smoke/admin-06-risk-actions.png](l1-smoke/admin-06-risk-actions.png) 风控动作
[l1-smoke/admin-07-rbac-users.png](l1-smoke/admin-07-rbac-users.png) RBAC 自管
[l1-smoke/admin-08-audit-logs.png](l1-smoke/admin-08-audit-logs.png) 审计日志
[l1-smoke/admin-09-reconciliation.png](l1-smoke/admin-09-reconciliation.png) 入金对账

**console errors**：所有 9 页 0 业务错误，仅 1 个 AntD v5 / React 19 兼容警告（已知，不影响功能）。

### client（5 菜单 + 4 drawer，9 / 9 通过）

[l1-smoke/client-00-landing.png](l1-smoke/client-00-landing.png) 登录入口
[l1-smoke/client-01-dashboard.png](l1-smoke/client-01-dashboard.png) 首页
[l1-smoke/client-02-market.png](l1-smoke/client-02-market.png) 市场
[l1-smoke/client-03-activity.png](l1-smoke/client-03-activity.png) 活动
[l1-smoke/client-04-wallet.png](l1-smoke/client-04-wallet.png) 钱包
[l1-smoke/client-05-settings.png](l1-smoke/client-05-settings.png) 设置
[l1-smoke/client-06-notification-drawer.png](l1-smoke/client-06-notification-drawer.png) 通知 drawer
[l1-smoke/client-07-kyc-drawer.png](l1-smoke/client-07-kyc-drawer.png) KYC drawer
[l1-smoke/client-08-withdraw-drawer.png](l1-smoke/client-08-withdraw-drawer.png) 出金 drawer
[l1-smoke/client-09-profile-drawer.png](l1-smoke/client-09-profile-drawer.png) 个人资料 drawer

---

## L2 关键业务旅程（4 / 5 通过，1 bug）

| # | 旅程 | 结果 |
| - | - | - |
| 1 | 客户端注册（POST `/api/v1/auth/register`）| ✅ HTTP 200，用户 50107541312638976 创建 |
| 2 | 注册后 wallet-service Kafka consumer 派生 USDT 入金地址 | ✅ ERC20 + TRC20 各 1，wallet log 14:29:28 ensure.completed |
| 3 | 客户端登录 + 仪表盘 + 5 个菜单导航 | ✅（L1 已覆盖）|
| 4 | 钱包页拉余额 + 入金地址（POST `/api/v1/wallet/deposit-addresses/ensure`）| ✅ 14:30:01 ensure.completed |
| 5 | 出金 drawer 拉白名单（GET `/api/v1/me/withdraw/whitelist`）| ✅（修 BUG-01 后 200 + 空白名单）|

[l2-customer/wallet-deposit-addresses.png](l2-customer/wallet-deposit-addresses.png)

---

## L3 管理端关键路径

8 个高危 modal 三重门 + 错误码兜底已在今日早些时候完整验证：详见
[`docs/test/screenshots/admin-high-risk-modals/`](../screenshots/admin-high-risk-modals/README.md) +
[`docs/design/admin高危操作风控档位.md`](../../design/admin高危操作风控档位.md)。

本 lane 不重复覆盖。

---

## L4 安全索问（5 / 6 通过，1 bug）

| # | 探测 | 期望 | 实际 | 结果 |
| - | - | - | - | - |
| 1 | 无 `X-Admin-User-Id` 头访问 `internal/v1/trading/withdraws` | 400 90703 | 400 90703 | ✅ |
| 2 | 无 admin token 访问 `/admin/withdraws` | 401 90003 | 401 90003 | ✅ |
| 3 | 无用户 token 访问 `/api/v1/trading/accounts/me` | 401 10001 | 401 10001 | ✅ |
| 4 | 篡改 token `Bearer invalid.token.here` | 401 10001 | 401 10001 | ✅ |
| 5 | SQL 注入 `?status=' OR 1=1 --` | 401 / 400 | 401（auth 优先于 service）| ✅ |
| 6 | 用户 A token 访问用户 B 的 `withdraw/900000001` | 30047 WITHDRAW_NOT_FOUND | 30047（修 BUG-02 后） | ✅ |

---

## 🐛 缺陷清单（本轮新发现 2 项，已修复）

### BUG-01：`GET /api/v1/me/withdraw/whitelist` 返回 500 ✅ 已修复

- **频次**：100%，对老用户 syy@1.com（14:06）+ 新用户 qa-e2e（14:31）均复现
- **影响**：客户端打开「出金」drawer 时白名单 tab 无法加载，但 drawer 仍能渲染（API 失败被 catch）
- **gateway 日志**：
  ```
  14:31:06 path=/api/v1/me/withdraw/whitelist userId=50107541312638976 status=ACTIVE
  14:31:06 path=/api/v1/me/withdraw/whitelist status=500 INTERNAL_SERVER_ERROR
  ```
- **根因**：`falconx-gateway` 路由表只有 `/api/v1/me/**` → identity-route，把 `/api/v1/me/withdraw/**` 一并转发给 identity；identity 没有该 controller，落到 `IdentityGlobalExceptionHandler` 抛 `99001 internal error`
- **修复**：`GatewayConfiguration.java` 在 `identity-me-route` **之前**插入 `trading-me-withdraw-route`，仅匹配 `/api/v1/me/withdraw/**` 转发到 trading-core；Spring Cloud Gateway 按声明顺序匹配，first match wins
- **回归验证**：
  ```
  GET /api/v1/me/withdraw/whitelist → 200 {"code":"0","data":[]}
  ```

### BUG-02：跨用户访问出金详情返回 500 而非 30047 ✅ 已修复

- **频次**：100%
- **场景**：用户 A token 访问用户 B 的 `/api/v1/me/withdraw/900000001`
- **期望**：30047 WITHDRAW_NOT_FOUND（与契约 `REST 接口规范 §9.2.4` 一致）
- **实际**：500 99001 internal error
- **根因**：与 BUG-01 同源 —— 该请求被错路由到 identity；identity 上无对应 controller 抛 500 而非业务码
- **修复**：同 BUG-01（同一处 gateway 路由插入即解决两个 bug）
- **回归验证**：
  ```
  GET /api/v1/me/withdraw/900000001 → 200 {"code":"30047","message":"Withdraw Not Found"}
  ```
- **保护性测试**：`GatewayRoutingIntegrationTests` 新增 `shouldRouteMeWithdrawToTradingNotIdentity` + `shouldStillRouteOtherMePathsToIdentity` 两个回归用例，锁路由顺序

---

## 已知不阻断项（baseline，不在本轮发现）

- trading-core IT 49 个用例受 V22 Flyway placeholder 影响无法跑（STAGE-7 commit 10 已记录，与本轮无关）
- AntD v5 不支持 React 19 的兼容警告（console 唯一 ERROR-level 输出，cosmetic）
- 客户端 `/api/v1/auth/login` 默认密码与历史账号不通用（无对应 reset 入口，运营手动重置）

---

## 总览

| Lane | 检查项 | 通过 | bug | 备注 |
| - | - | - | - | - |
| L1 admin smoke | 9 | 9 | 0 | 全 0 业务错误 |
| L1 client smoke | 9（5 菜单 + 4 drawer） | 9 | 0 | 全部渲染 + 可点击 |
| L2 业务旅程 | 5 | 5 | 0 | BUG-01 已修 |
| L3 admin 关键路径 | 8 高危 modal | 8 | 0 | 早些时候已覆盖，本报告引用 |
| L4 安全索问 | 6 | 6 | 0 | BUG-02 已修 |
| **小计** | **37** | **37** | **0** | 命中率 100%（修复后） |
