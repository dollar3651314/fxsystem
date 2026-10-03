# 阶段 1 管理端测试用例清单（V1，2026-05-08）

> 本文件是 BBook 一期 V2 §4 阶段 1 STAGE-1-CONSOLE-FOUNDATION 的 R6 测试用例骨架（后端部分）。前端骨架见 [`falconx-console-frontend-test-skeleton.md`](./falconx-console-frontend-test-skeleton.md)。
>
> 用例覆盖 R2 已冻结的 7 个接口（[`管理端接口规范`](../api/管理端接口规范.md) §2）+ RBAC 注解扫描 + 跨 schema 只读 + IP 白名单 + 审计日志 + 1 条端到端链路。
>
> R10 / R9 实施完成后，R6 二轮把每个 TC 落地为真实 `@Test` 方法（路径 `falconx-console-service/src/test/java/...`），并在本文件「实施落地」列填入测试类:方法名。

---

## §1. TC 编号块

| Prefix | 编号区间 | 数量 | 验收阶段 |
| --- | --- | --- | --- |
| `TC-CONSOLE-` | 001-099 | 51 | 阶段 1 |
| `TC-E2E-CONSOLE-` | 001-009 | 1 | 阶段 1 |

后续阶段（阶段 2-9）的管理端用例延用 `TC-CONSOLE-` 前缀，编号 100+ 增量。

## §2. 文档结构

| 节 | 范围 | TC 数 |
| --- | --- | --- |
| §3 登录 | POST /admin/auth/login | 9 |
| §4 Token 刷新 | POST /admin/auth/refresh | 5 |
| §5 登出 | POST /admin/auth/logout | 4 |
| §6 修改密码 | POST /admin/auth/change-password | 7 |
| §7 当前管理员信息 | GET /admin/me | 3 |
| §8 当前权限点集合 | GET /admin/me/permissions | 4 |
| §9 当前菜单树 | GET /admin/me/menus | 5 |
| §10 RBAC 注解扫描与鉴权 | @RequiresPermission | 4 |
| §11 IP 白名单 | gateway + console 双层 | 3 |
| §12 跨 schema 只读 | console DB user | 3 |
| §13 审计日志 | t_admin_operation_log | 4 |
| §14 E2E | 跨 RBAC 链路 | 1 |

合计 52 个用例（51 IT + 1 E2E）。

## §3. 登录链路（POST /admin/auth/login）

### TC-CONSOLE-001 正常登录返回 token

- **类型**：IT
- **模块**：falconx-console-service
- **前置**：默认超管已通过 V1 init schema 写入；未锁定
- **输入**：`{ "username": "superadmin", "password": "<正确密码>" }`
- **预期**：`code=0`，data 含 `adminUserId / username / realName / roles[] / mustChangePassword / accessToken / refreshToken / accessTokenExpiresIn / refreshTokenExpiresIn`
- **验证**：
  - [ ] HTTP 200，`code=0`
  - [ ] `data.mustChangePassword=true`（首次登录）
  - [ ] `data.accessTokenExpiresIn=1800`（30 分钟）
  - [ ] `data.refreshTokenExpiresIn=28800`（8 小时）
  - [ ] `t_admin_user.last_login_at` 已更新
  - [ ] `t_admin_user.last_login_ip` 已更新（来自请求 IP）
  - [ ] 响应日志不含完整 token（按 [安全规范](../security/安全规范.md)）

### TC-CONSOLE-002 用户名错误 → 90001

- **输入**：`{ "username": "nonexistent", "password": "any" }`
- **预期**：HTTP 401，`code=90001`，`data=null`
- **验证**：
  - [ ] 错误码 `90001`
  - [ ] 失败计数 +1（按 username）
  - [ ] 不区分"用户名不存在"和"密码错误"（防枚举攻击）

### TC-CONSOLE-003 密码错误 → 90001

- **输入**：`{ "username": "superadmin", "password": "wrong" }`
- **预期**：HTTP 401，`code=90001`
- **验证**：
  - [ ] 错误码 `90001`
  - [ ] 失败计数 +1
  - [ ] `t_admin_user.last_login_at` 不变

### TC-CONSOLE-004 连续失败达到阈值 → 90006 锁定

- **前置**：清理失败计数；锁定阈值 5 次（待 R9 配置确认）
- **输入**：连续 5 次错误密码
- **预期**：第 5 次返回 `90006`，`message="账号已锁定，请 30 分钟后重试"`
- **验证**：
  - [ ] 第 5 次错误码 `90006`
  - [ ] Redis（或 DB）有锁定记录，TTL ≈ 30 分钟
  - [ ] 锁定标记按 username 隔离（影响该 username 后续登录）

### TC-CONSOLE-005 锁定期内即使凭证正确仍 90006

- **前置**：账号已锁定
- **输入**：`{ "username": "...", "password": "<正确>" }`
- **预期**：`code=90006`
- **验证**：
  - [ ] 锁定优先于凭证校验
  - [ ] 不更新 `last_login_at`

### TC-CONSOLE-006 锁定窗口过期后可正常登录

- **前置**：模拟时间推进 ≥ 30 分钟
- **输入**：正确凭证
- **预期**：`code=0`，登录成功
- **验证**：
  - [ ] 锁定记录已清理
  - [ ] 失败计数清零

### TC-CONSOLE-007 status=DISABLED 账号 → 90008

- **前置**：UPDATE t_admin_user SET status=2 WHERE id=1
- **输入**：正确凭证
- **预期**：`code=90008`
- **验证**：
  - [ ] 错误码 `90008`
  - [ ] 不签发 token

### TC-CONSOLE-008 must_change_password=1 仍签发 token

- **前置**：默认超管 must_change_password=1
- **输入**：正确凭证
- **预期**：`code=0`，`data.mustChangePassword=true`，token 正常签发
- **验证**：
  - [ ] token 可访问 `/admin/auth/change-password`
  - [ ] token 访问其他业务接口被拒绝（90007 或前端引导，待 R9 决策）

### TC-CONSOLE-009 登录成功更新 last_login

- **输入**：正确凭证
- **预期**：`t_admin_user.last_login_at` 与请求时间偏差 < 5 秒，`last_login_ip` 等于请求 IP
- **验证**：
  - [ ] `last_login_ip` 来自 `X-Forwarded-For`（gateway 透传）或 RemoteAddr，按规范优先 X-Forwarded-For 第一段
  - [ ] 多次登录会持续更新

---

## §4. Token 刷新（POST /admin/auth/refresh）

### TC-CONSOLE-010 有效 refresh 换新 access + 新 refresh（一次性轮换）

- **输入**：`{ "refreshToken": "<有效 refresh>" }`
- **预期**：`code=0`，返回新 access + 新 refresh，旧 refresh 立即失效
- **验证**：
  - [ ] 新 refresh ≠ 旧 refresh
  - [ ] 新 access 可访问受保护接口
  - [ ] 旧 refresh 第二次使用必须失败（见 TC-CONSOLE-011）

### TC-CONSOLE-011 旧 refresh 第二次使用 → 90002

- **前置**：执行 TC-CONSOLE-010 后
- **输入**：`{ "refreshToken": "<旧 refresh>" }`
- **预期**：`code=90002`
- **验证**：
  - [ ] 错误码 `90002`
  - [ ] Redis 有该旧 refresh 的"已消费"标记或一次性 jti 已黑名单

### TC-CONSOLE-012 过期 refresh → 90002

- **前置**：模拟时间推进至 refresh 过期后
- **输入**：过期 refresh
- **预期**：`code=90002`

### TC-CONSOLE-013 伪造 refresh → 90003

- **输入**：随机字符串或签名错误的 JWT
- **预期**：`code=90003`
- **验证**：
  - [ ] 错误码 `90003`（区分于 90002）
  - [ ] 异常日志不打印完整 token

### TC-CONSOLE-014 已黑名单 jti 的 refresh → 90003

- **前置**：执行登出（TC-CONSOLE-015）
- **输入**：登出前的 refresh
- **预期**：`code=90003`

---

## §5. 登出（POST /admin/auth/logout）

### TC-CONSOLE-015 logout 加入 jti 黑名单

- **前置**：已登录
- **输入**：`Authorization: Bearer <access>`
- **预期**：`code=0`
- **验证**：
  - [ ] Redis 有该 access 的 jti 黑名单 key（admin 专用 prefix）
  - [ ] TTL ≈ access 剩余有效期

### TC-CONSOLE-016 已登出的 access 二次访问 → 90002/90003

- **前置**：已 logout
- **输入**：旧 access 访问 `/admin/me`
- **预期**：HTTP 401，`code=90002` 或 `90003`（按规范是黑名单 → 90003 invalid）

### TC-CONSOLE-017 logout 仅影响当前 jti

- **前置**：同一管理员两个设备登录（两次 login）
- **输入**：设备 A logout
- **预期**：设备 A access 失效，设备 B access 仍有效
- **验证**：
  - [ ] 仅设备 A 的 jti 进入黑名单

### TC-CONSOLE-018 已过期 access 仍能 logout（兜底）

- **前置**：access 已过期
- **输入**：过期 access logout
- **预期**：`code=0`（接受，不抛 401）
- **理由**：避免客户端发起 logout 时已被刷新拦截，导致用户感知"登出失败"

---

## §6. 修改密码（POST /admin/auth/change-password）

### TC-CONSOLE-019 旧密码 + 新密码成功

- **输入**：`{ "oldPassword": "...", "newPassword": "Abc123!@#$XYZ" }`
- **预期**：`code=0`
- **验证**：
  - [ ] `t_admin_user.password_hash` 已更新（BCrypt 新值）
  - [ ] `t_admin_user.must_change_password=0`
  - [ ] 该用户**所有** jti（包括其他设备）进入黑名单（详见 TC-CONSOLE-025）
  - [ ] 该用户的所有 refresh 全部失效

### TC-CONSOLE-020 旧密码错误

- **输入**：错误旧密码
- **预期**：业务错误码（待 R2 第二轮明确，建议 `90004` 或专用 `90009`）
- **验证**：
  - [ ] `password_hash` 不变
  - [ ] `must_change_password` 不变

### TC-CONSOLE-021 新密码不符合策略

- **输入**：新密码 < 12 字符 / 缺大写 / 缺小写 / 缺数字 / 缺特殊字符（5 个子用例合并）
- **预期**：业务错误码（建议 `90010`），`message` 含具体不符合点
- **验证**：
  - [ ] 前端策略与后端策略一致（[`管理端 5 页面方案`](../design/falconx-console-pages-V1.md) §1.3）

### TC-CONSOLE-022 新密码与旧密码相同

- **输入**：新旧密码都是当前密码
- **预期**：拒绝
- **验证**：
  - [ ] BCrypt 比对失败时拒绝（避免哈希盐值碰撞误判）

### TC-CONSOLE-023 新密码与用户名相同

- **输入**：`{ "oldPassword": "...", "newPassword": "superadmin" }`
- **预期**：拒绝

### TC-CONSOLE-024 强制改密用户改密后 must_change_password=0

- **前置**：默认超管首次登录
- **流程**：login → change-password → 重新 login
- **预期**：第二次登录 `data.mustChangePassword=false`

### TC-CONSOLE-025 改密后所有设备 token 全部失效

- **前置**：同一管理员两个设备登录
- **输入**：设备 A 改密
- **预期**：设备 B 的 access/refresh 全部失效
- **验证**：
  - [ ] 设备 B 访问 `/admin/me` → 90003

---

## §7. 当前管理员信息（GET /admin/me）

### TC-CONSOLE-026 已登录返回完整信息

- **输入**：`Authorization: Bearer <access>`
- **预期**：`code=0`，data 含 `adminUserId / username / realName / roles[{code,name}] / mustChangePassword / lastLoginAt / lastLoginIp`
- **验证**：
  - [ ] roles 数组每项含 `code` 与 `name`（不只是 code）

### TC-CONSOLE-027 未登录 → 90003

- **输入**：无 Authorization header
- **预期**：HTTP 401，`code=90003`

### TC-CONSOLE-028 access 过期 → 90002

- **输入**：过期 access
- **预期**：`code=90002`

---

## §8. 当前权限点集合（GET /admin/me/permissions）

### TC-CONSOLE-029 SUPER_ADMIN 返回字典全集

- **前置**：默认超管
- **预期**：`data.isSuperAdmin=true`，`data.permissions` 等于 `t_admin_permission.code` 全集
- **验证**：
  - [ ] permissions 长度 = `SELECT COUNT(*) FROM t_admin_permission`

### TC-CONSOLE-030 普通角色返回已分配权限码

- **前置**：创建角色 FINANCE，分配 `customer:view, customer:freeze`；创建管理员绑定该角色
- **预期**：`data.isSuperAdmin=false`，`data.permissions=["customer:view","customer:freeze"]`
- **验证**：
  - [ ] 不返回未分配的权限点

### TC-CONSOLE-031 多角色用户返回权限并集

- **前置**：管理员绑定 FINANCE + KYC_REVIEWER 两角色
- **预期**：permissions 是两角色权限的并集（去重）

### TC-CONSOLE-032 无角色用户返回空数组

- **前置**：管理员未绑定任何角色（边界情况）
- **预期**：`data.isSuperAdmin=false`，`data.permissions=[]`

---

## §9. 当前菜单树（GET /admin/me/menus）

### TC-CONSOLE-033 SUPER_ADMIN 返回完整菜单树

- **前置**：t_admin_menu 含若干菜单（部分顶级 + 部分子菜单）
- **预期**：返回完整树结构

### TC-CONSOLE-034 普通角色按权限过滤

- **前置**：管理员仅有 `customer:view` 权限
- **预期**：菜单树仅含 `permission_code IN ('customer:view', NULL)` 的节点（NULL 是父节点）
- **验证**：
  - [ ] 无权限菜单不返回
  - [ ] 父节点保留（即使 permission_code 为 NULL）

### TC-CONSOLE-035 父节点权限符合但所有子节点无权限 → 父节点不返回

- **前置**：父菜单 `客户管理`（permission_code=NULL）下两个子菜单都需 `customer:freeze`；管理员无该权限
- **预期**：父节点也不返回（避免空目录）
- **理由**：UI 不应显示空白父菜单

### TC-CONSOLE-036 sort_order 升序排列

- **预期**：同一 parent_id 下子节点按 `sort_order` 升序

### TC-CONSOLE-037 is_visible=0 的菜单不返回

- **前置**：某菜单 `is_visible=0`
- **预期**：该菜单不出现在树中（即使权限符合）

> ⚠️ **R3 / R2 字段命名不一致**：[`管理端 5 页面方案`](../design/falconx-console-pages-V1.md) §4 列定义使用「sort」作为列名；DB schema 实际字段为 `sort_order`。R10 实施前必须以 schema 真源为准（field 名 `sortOrder` / DB column `sort_order`）。

---

## §10. RBAC 注解扫描与鉴权（@RequiresPermission）

### TC-CONSOLE-038 启动扫描同步权限点字典

- **前置**：清空 t_admin_permission；启动 console-service
- **预期**：`t_admin_permission` 已被填充，code 集合等于代码中所有 `@RequiresPermission(...)` 注解的并集
- **验证**：
  - [ ] 已删除的注解（代码中无此 code）→ 旧记录是否清理（按 R9 实施策略：保留以便审计 / 物理删除，待决策）

### TC-CONSOLE-039 调用受保护接口无权限 → 90004

- **前置**：管理员仅有 `customer:view`，调用需要 `customer:freeze` 的接口
- **预期**：HTTP 403，`code=90004`
- **验证**：
  - [ ] 不返回业务数据
  - [ ] 拒绝时仍写审计日志（标记 `denied=true`，待 R9 实施决策）

### TC-CONSOLE-040 SUPER_ADMIN 直接放行

- **前置**：默认超管访问任意 `@RequiresPermission` 接口
- **预期**：放行，不查 `t_admin_role_permission`
- **理由**：超管不在 t_admin_role_permission 中显式枚举（V1__init_console_schema 注释已说明）

### TC-CONSOLE-041 角色权限被移除立即生效

- **前置**：管理员有 FINANCE 角色含 `customer:freeze` → 调用接口成功
- **操作**：从 FINANCE 移除 `customer:freeze` → 同一 token 再次调用
- **预期**：第二次调用 `code=90004`
- **验证**：
  - [ ] 不依赖 token 重签
  - [ ] 实现注：权限点检查每次实时查 DB（或 Redis 短 TTL 缓存，待 R9 实施决策）

---

## §11. IP 白名单

### TC-CONSOLE-042 IP 不在白名单 → 90005

- **前置**：白名单仅含 `10.0.0.0/8`
- **输入**：来自 `8.8.8.8` 的请求
- **预期**：HTTP 403，`code=90005`
- **验证**：
  - [ ] gateway 路由级拦截：请求未到达 console-service
  - [ ] console-service 应用级兜底：即使 gateway 配错也拒绝

### TC-CONSOLE-043 dev profile 禁用白名单

- **前置**：profile=dev，`falconx.console.security.ip-whitelist-enabled=false`
- **输入**：来自任意 IP 的请求
- **预期**：放行
- **验证**：
  - [ ] 仅 dev profile 生效；prod / staging 强制启用

### TC-CONSOLE-044 白名单热刷新（可选用例，视 R9 实现）

- **操作**：修改 `falconx.gateway.security.admin-ip-whitelist` 配置后通过 Spring Actuator `refresh` 端点（如启用）触发刷新
- **预期**：无需重启即可生效
- **决策**：此用例视 R9 实施时是否启用 Spring Cloud Config / Actuator refresh；不启用则降级为重启生效，本用例标 `@Disabled`

---

## §12. 跨 schema 只读

### TC-CONSOLE-045 console DB user 对业务 schema 有 SELECT 权限

- **前置**：console DB user 已配置 `GRANT SELECT ON falconx_identity.* TO 'console'@'%'`（同 market/trading/wallet）
- **预期**：`SELECT * FROM falconx_identity.t_user LIMIT 1` 成功
- **验证**：
  - [ ] 4 个业务 schema 都可读

### TC-CONSOLE-046 console DB user 对业务 schema 无写权限

- **输入**：`UPDATE falconx_identity.t_user SET status=2 WHERE id=1`
- **预期**：MySQL 抛 `ER_TABLEACCESS_DENIED_ERROR`
- **验证**：
  - [ ] 4 个业务 schema 的 INSERT/UPDATE/DELETE 全部被拒
  - [ ] 测试覆盖至少 1 个 INSERT + 1 个 UPDATE + 1 个 DELETE

### TC-CONSOLE-047 console DB user 对 falconx_console 有完全权限

- **预期**：`falconx_console` schema 内 INSERT/UPDATE/DELETE/SELECT 全部成功

---

## §13. 审计日志（t_admin_operation_log）

### TC-CONSOLE-048 任意权限点接口写审计

- **前置**：调用 `POST /admin/users/{id}/freeze`（待 R2 第二轮冻结）
- **预期**：`t_admin_operation_log` 新增一条记录
- **验证**：
  - [ ] `admin_user_id` = 当前管理员 ID
  - [ ] `permission_code` = 触发的权限码
  - [ ] `target_type` / `target_id` = 操作目标
  - [ ] `ip` 来自请求
  - [ ] `user_agent` 来自请求
  - [ ] `occurred_at` 与请求时间偏差 < 5 秒

### TC-CONSOLE-049 高风险接口 risk_level=HIGH_RISK

- **前置**：调用 `customer:balance:adjust` 等 [DESIGN §7.3](../design/falconx-console-DESIGN.md#73-高风险操作的视觉警告) 列表中的高风险接口
- **预期**：`t_admin_operation_log.risk_level='HIGH_RISK'`
- **验证**：
  - [ ] 普通查询接口 `risk_level='LOW'`
  - [ ] 高风险接口必须 `HIGH_RISK`

### TC-CONSOLE-050 审计含前后值快照

- **前置**：调余额 `customer:balance:adjust` 从 1000 → 1500
- **预期**：
  - `before_value = {"balance": "1000.00"}` (JSON)
  - `after_value = {"balance": "1500.00"}` (JSON)
- **验证**：
  - [ ] before/after 都是 JSON 类型
  - [ ] 调余额操作必须含金额前后值

### TC-CONSOLE-051 审计写入失败不阻塞主流程（可选）

- **前置**：模拟 `t_admin_operation_log` insert 异常（如临时 DROP 表）
- **预期**：业务接口仍返回 `code=0`；异常日志记录审计写失败
- **决策**：视 R9 实施决策；保守方案是审计写失败回滚业务（即审计与业务同事务）；激进方案是异步审计。本用例 TC 名暂列，最终以 R9 决策为准。

---

## §14. E2E：跨 RBAC 链路

### TC-E2E-CONSOLE-001 默认超管首次登录至看到全菜单完整链路

- **类型**：E2E
- **依赖**：falconx-gateway + falconx-console-service + MySQL + Redis
- **流程**：
  1. POST `/admin/auth/login` 用默认超管 + 默认密码 → `code=0` `mustChangePassword=true`
  2. 用 step1 的 access 调用任意业务接口（如 `/admin/me/permissions`）→ 应被拒（90007 或前端引导）
  3. POST `/admin/auth/change-password` → `code=0`
  4. 旧 access 访问 `/admin/me` → 90003（旧 token 已黑名单）
  5. POST `/admin/auth/login` 用默认超管 + 新密码 → `code=0` `mustChangePassword=false`
  6. GET `/admin/me/menus` → 返回完整菜单树
  7. GET `/admin/me/permissions` → `isSuperAdmin=true` + 完整权限码集合
  8. POST `/admin/auth/logout` → `code=0`
  9. step5 的 access 访问 `/admin/me` → 90003（已黑名单）
- **验证**：
  - [ ] 9 个步骤全部通过
  - [ ] 全程 `t_admin_operation_log` 至少记录 step3 / step8 两条审计
  - [ ] 全程响应日志、错误详情都不含完整 token

---

## §15. 实施落地（R10 / R9 完成后由 R6 二轮填写）

| TC 编号 | 测试类:方法名 | 状态 |
| --- | --- | --- |
| TC-CONSOLE-001 ~ 009 | _待 R9 实施后落地为 `AdminAuthLoginIntegrationTests`_ | ⏳ |
| TC-CONSOLE-010 ~ 014 | _`AdminAuthRefreshIntegrationTests`_ | ⏳ |
| TC-CONSOLE-015 ~ 018 | _`AdminAuthLogoutIntegrationTests`_ | ⏳ |
| TC-CONSOLE-019 ~ 025 | _`AdminAuthChangePasswordIntegrationTests`_ | ⏳ |
| TC-CONSOLE-026 ~ 028 | _`AdminMeControllerIntegrationTests`_ | ⏳ |
| TC-CONSOLE-029 ~ 032 | _`AdminMePermissionsIntegrationTests`_ | ⏳ |
| TC-CONSOLE-033 ~ 037 | _`AdminMeMenusIntegrationTests`_ | ⏳ |
| TC-CONSOLE-038 ~ 041 | _`PermissionGuardIntegrationTests`_ | ⏳ |
| TC-CONSOLE-042 ~ 044 | _`AdminIpWhitelistIntegrationTests` + `GatewayAdminRouteIntegrationTests`_ | ⏳ |
| TC-CONSOLE-045 ~ 047 | _`ConsoleCrossSchemaReadOnlyIntegrationTests`_ | ⏳ |
| TC-CONSOLE-048 ~ 051 | _`AdminOperationLogIntegrationTests`_ | ⏳ |
| TC-E2E-CONSOLE-001 | _`GatewayAdminFoundationE2ETests`_ | ⏳ |

---

## §16. 测试基础设施

R10 / R9 实施测试时使用：

- **数据库**：每个测试类用独立 schema（`falconx_console_test_<random>`），通过 `E2EDatabaseCleanupExtension` 复用清理模式
- **Redis**：独立 DB 编号（按测试类隔离）
- **测试夹具**：默认超管 + 一个 SUPER_ADMIN 角色由 V1 init schema 自动种入；测试中需要的额外角色 / 管理员通过 `AdminTestSupport` 工具类创建
- **外部依赖**：管理端测试不依赖 LP / 链上节点等外部服务
- **profile**：测试统一用 `test` profile，IP 白名单禁用，BCrypt strength 调低（如 4）以加速

---

## §17. 验收必须用例（Stage 1 全量）

阶段 1 完成的硬约束：本文件 §3-§14 共 52 个用例**必须全部通过**，缺一不可。

阶段 1 完成时由 R7 出验收报告，证据包含：

```bash
# 单元/集成测试
mvn -pl falconx-console-service -am -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=AdminAuthLoginIntegrationTests,AdminAuthRefreshIntegrationTests,AdminAuthLogoutIntegrationTests,AdminAuthChangePasswordIntegrationTests,AdminMeControllerIntegrationTests,AdminMePermissionsIntegrationTests,AdminMeMenusIntegrationTests,PermissionGuardIntegrationTests,AdminIpWhitelistIntegrationTests,ConsoleCrossSchemaReadOnlyIntegrationTests,AdminOperationLogIntegrationTests test

# E2E
mvn -pl falconx-gateway -am -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=GatewayAdminRouteIntegrationTests,GatewayAdminFoundationE2ETests test
```

> 命令为参考；R7 验证时按当前实际类名调整。

---

## §18. 关联文档

- [管理端架构](../architecture/管理端架构.md)
- [管理端接口规范](../api/管理端接口规范.md)
- [管理端设计系统](../design/falconx-console-DESIGN.md)
- [管理端 5 页面方案](../design/falconx-console-pages-V1.md)
- [前端测试骨架](./falconx-console-frontend-test-skeleton.md)
- [CFD 全面测试用例规范](./CFD全面测试用例规范.md) §13.5（待 R8 同步）
- [BBook 一期完成执行路径 §4 阶段 1](../process/BBook一期完成执行路径.md)
- [完成定义](../process/完成定义.md)
- [安全规范](../security/安全规范.md)
- [SKILLS Skill 10](../../SKILLS.md)
