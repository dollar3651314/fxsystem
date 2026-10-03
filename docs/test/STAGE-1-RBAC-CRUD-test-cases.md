# 阶段 1 P2-P5 RBAC 自管测试用例清单（V1，2026-05-09）

> R6 二轮交付，覆盖 [`管理端接口规范`](../api/管理端接口规范.md) §5 阶段 1 RBAC 自管 4 模块 19 个端点 + 错误码块 90200-90299。
>
> R10 二轮 / R9 二轮实施前由本清单作为成功标准；实施完成后由 R6 三轮把每条 TC 落地为真实 `@Test` 方法。

---

## §1. TC 编号块

| Prefix | 编号区间 | 数量 | 验收阶段 |
| --- | --- | --- | --- |
| `TC-CONSOLE-` | 100-179 | 60 | 阶段 1 P2-P5（console 服务侧） |
| `FE-CONSOLE-` | 200-249 | 50 | 阶段 1 P2-P5 前端 |

合计 **110** 个用例。

---

## §2. 文档结构

| 节 | 范围 | TC 数（后端 / 前端） |
| --- | --- | --- |
| §3 P2 admin-users (7 端点) | 列表 / 详情 / 新建 / 编辑 / 启停用 / 重置密码 / 删除 | 22 / 16 |
| §4 P3 admin-roles (6 端点) | 列表 / 新建 / 编辑 / 删除 / 权限分配 | 18 / 14 |
| §5 P4 admin-menus (4 端点) | 树形查询 / CRUD / 排序 | 12 / 12 |
| §6 P5 admin-permissions (2 端点) | 字典查询 / 关联角色 | 8 / 8 |

---

## §3. P2 管理员管理（admin-users）

### §3.1 列表 (GET /admin/admin-users) — 5 TC

- **TC-CONSOLE-100**：分页查询返回字段对齐 §5.1.1（id/username/realName/roles/status/lastLoginAt 等）
- **TC-CONSOLE-101**：username 模糊筛选（LIKE %x%）
- **TC-CONSOLE-102**：status 筛选（ACTIVE/DISABLED）
- **TC-CONSOLE-103**：roleCode 筛选（JOIN t_admin_user_role + t_admin_role.code）
- **TC-CONSOLE-104**：无 admin-user:view 权限 → 90004

### §3.2 详情 (GET /admin/admin-users/{id}) — 2 TC

- **TC-CONSOLE-105**：详情返回单项 + 含 roles[]
- **TC-CONSOLE-106**：不存在 ID → 90211

### §3.3 新建 (POST /admin/admin-users) — 6 TC

- **TC-CONSOLE-107**：正常新建（含 password 显式给出）→ 200，t_admin_user INSERT + t_admin_user_role INSERT
- **TC-CONSOLE-108**：password=null 时自动生成 16 位随机密码 → 响应 generatedPassword 仅本次返回
- **TC-CONSOLE-109**：username 重复 → 90210（t_admin_user.username UNIQUE）
- **TC-CONSOLE-110**：username 格式不符（含空格 / 中文）→ 90212
- **TC-CONSOLE-111**：roleIds 含 SUPER_ADMIN role_id=1 → 90213
- **TC-CONSOLE-112**：password 显式给出但不符合策略 → 90100

### §3.4 编辑 (PUT /admin/admin-users/{id}) — 3 TC

- **TC-CONSOLE-113**：正常编辑 realName + roleIds → 200
- **TC-CONSOLE-114**：编辑 SUPER_ADMIN 用户 + 移除 SUPER_ADMIN role → 90214（角色锁定）
- **TC-CONSOLE-115**：请求体含 username → 后端忽略（不允许改）

### §3.5 启用/禁用 (POST /disable + /enable) — 4 TC

- **TC-CONSOLE-116**：禁用其他管理员 → 200；t_admin_user.status=2；该用户 token 全部黑名单
- **TC-CONSOLE-117**：禁用自己 → 90215 ADMIN_USER_CANNOT_DISABLE_SELF
- **TC-CONSOLE-118**：reason < 10 → 90304（共用客户管理段）
- **TC-CONSOLE-119**：启用 DISABLED 用户 → 200；token 不主动恢复

### §3.6 重置密码 (POST /reset-password) — 3 TC

- **TC-CONSOLE-120**：重置成功 → 旧 token 全部黑名单 + must_change_password=1
- **TC-CONSOLE-121**：password=null 自动生成
- **TC-CONSOLE-122**：reason < 10 → 90304

### §3.7 删除 (DELETE) — 4 TC

- **TC-CONSOLE-123**：正常删除 → t_admin_user 物理删除 + t_admin_user_role 级联
- **TC-CONSOLE-124**：删除 SUPER_ADMIN 用户 → 90216
- **TC-CONSOLE-125**：删除自己 → 90217
- **TC-CONSOLE-126**：t_admin_operation_log 历史不级联删除（审计保留）

### §3.8 跨用例审计 — 2 TC

- **TC-CONSOLE-127**：每个写操作均触发 AOP 审计写 t_admin_operation_log
- **TC-CONSOLE-128**：disable / reset-password / delete 三高风险动作 risk_level=HIGH_RISK

---

## §4. P3 角色管理（admin-roles）

### §4.1 列表 + 详情 — 4 TC

- **TC-CONSOLE-130**：分页查询含 memberCount + permissionCount
- **TC-CONSOLE-131**：按 code 模糊查询
- **TC-CONSOLE-132**：按 isSystem 筛选
- **TC-CONSOLE-133**：无 admin-role:view → 90004

### §4.2 新建 / 编辑 / 删除 — 8 TC

- **TC-CONSOLE-134**：新建角色 → 200；t_admin_role INSERT
- **TC-CONSOLE-135**：code 重复 → 90220
- **TC-CONSOLE-136**：code 格式不符（含小写 / 短横）→ 400
- **TC-CONSOLE-137**：编辑角色 name + description → 200
- **TC-CONSOLE-138**：编辑 is_system=1 角色（SUPER_ADMIN）→ 90222
- **TC-CONSOLE-139**：删除有成员的角色 → 90223 ADMIN_ROLE_HAS_MEMBERS_CANNOT_DELETE
- **TC-CONSOLE-140**：删除 SUPER_ADMIN → 90222（系统内置）
- **TC-CONSOLE-141**：删除空角色 → 200；t_admin_role 删除 + t_admin_role_permission 级联

### §4.3 权限分配 — 6 TC

- **TC-CONSOLE-145**：GET 角色权限 → 返回 permissionCodes 数组
- **TC-CONSOLE-146**：PUT 全量替换权限 → t_admin_role_permission DELETE + INSERT；before/after JSON 审计 added/removed
- **TC-CONSOLE-147**：PUT permissionCodes 含字典外的 code → 90225
- **TC-CONSOLE-148**：PUT SUPER_ADMIN 角色权限 → 90224 ADMIN_ROLE_SUPER_ADMIN_PERMISSIONS_LOCKED
- **TC-CONSOLE-149**：PUT 后立即生效（无需 token 重签）— 同 TC-CONSOLE-041
- **TC-CONSOLE-150**：reason < 10 → 90304

---

## §5. P4 菜单管理（admin-menus）

### §5.1 树形查询 — 3 TC

- **TC-CONSOLE-155**：tree=true 返回完整树（不按权限过滤）+ children 递归
- **TC-CONSOLE-156**：tree=false 返回扁平列表
- **TC-CONSOLE-157**：响应字段命名驼峰（sortOrder/isVisible），非 snake_case

### §5.2 新建 / 编辑 / 删除 — 6 TC

- **TC-CONSOLE-158**：新建菜单 → 200；t_admin_menu INSERT
- **TC-CONSOLE-159**：code 重复 → 90230
- **TC-CONSOLE-160**：permissionCode 不存在于字典 → 90233
- **TC-CONSOLE-161**：编辑菜单（不可改 code）→ 200
- **TC-CONSOLE-162**：删除有子菜单的父菜单 → 90232 ADMIN_MENU_HAS_CHILDREN_CANNOT_DELETE
- **TC-CONSOLE-163**：删除叶子菜单 → 200

### §5.3 排序 — 3 TC

- **TC-CONSOLE-164**：PATCH /sort direction=up → 与同级前一个交换 sortOrder
- **TC-CONSOLE-165**：PATCH 同级第一个 direction=up → 90234 ADMIN_MENU_SORT_BOUNDARY
- **TC-CONSOLE-166**：PATCH 同级最后 direction=down → 90234

---

## §6. P5 权限点字典（admin-permissions）

### §6.1 列表 — 5 TC

- **TC-CONSOLE-170**：分页查询返回 isHighRisk 标记
- **TC-CONSOLE-171**：highRiskOnly=true 筛选（按 HighRiskPermissionRegistry 列表）
- **TC-CONSOLE-172**：module 筛选（如 customer）
- **TC-CONSOLE-173**：roleCount 字段为关联角色数（COUNT(*) JOIN role_permission）
- **TC-CONSOLE-174**：无 admin-permission:view → 90004

### §6.2 关联角色 — 3 TC

- **TC-CONSOLE-175**：GET /{code}/roles 返回 [{roleId, roleCode, roleName}]
- **TC-CONSOLE-176**：未关联任何角色返回空数组
- **TC-CONSOLE-177**：code 不存在于字典 → 200 + 空数组（不抛 404）

---

## §7. 前端用例（FE-CONSOLE-200~249）

### §7.1 P2 管理员管理 (FE-CONSOLE-200~215, 16 TC)

- **FE-CONSOLE-200~204**：列表 5 TC（渲染 / 筛选 / 分页 / 状态 Tag / 角色多 Tag 折叠）
- **FE-CONSOLE-205~210**：新建 Drawer 6 TC（表单校验 / username 格式 / 自动生成密码一次性显示 / 角色多选 / SUPER_ADMIN 不可选 / 提交）
- **FE-CONSOLE-211~213**：编辑 Drawer 3 TC（username 只读 / SUPER_ADMIN 角色锁定 / 提交）
- **FE-CONSOLE-214~215**：高风险 Modal 2 TC（删除 + 重置密码 二次确认）

### §7.2 P3 角色管理 (FE-CONSOLE-220~233, 14 TC)

- **FE-CONSOLE-220~223**：列表 4 TC
- **FE-CONSOLE-224~227**：新建/编辑 Drawer 4 TC
- **FE-CONSOLE-228~233**：权限分配 Tree 6 TC（树渲染 / 父子级联 / 高风险标记 / 预设按钮 / diff 二次确认 / 提交）

### §7.3 P4 菜单管理 (FE-CONSOLE-235~245, 11 TC)

- **FE-CONSOLE-235~237**：树形 Table 渲染 / 展开折叠 / 排序按钮
- **FE-CONSOLE-238~241**：新建/编辑 Drawer（含图标选择器 / 权限码 Select / TreeSelect 父级菜单）
- **FE-CONSOLE-242~244**：上下移按钮 / 拒绝边界 / 切换可见性 Switch
- **FE-CONSOLE-245**：删除有子菜单时 Modal 提示

### §7.4 P5 权限点字典 (FE-CONSOLE-247~249, 3 TC)

- **FE-CONSOLE-247**：只读 Table（无 CRUD 按钮）
- **FE-CONSOLE-248**：高风险标记 Tag 显示
- **FE-CONSOLE-249**：关联角色数点击跳转角色筛选

---

## §8. 实施落地（R9 二轮 / R10 二轮完成后由 R6 三轮填写）

| TC 编号 | 测试类:方法名 | 状态 |
| --- | --- | --- |
| TC-CONSOLE-100 ~ 128 | _`AdminUsersControllerIntegrationTests`_ | ⏳ |
| TC-CONSOLE-130 ~ 150 | _`AdminRolesControllerIntegrationTests`_ | ⏳ |
| TC-CONSOLE-155 ~ 166 | _`AdminMenusControllerIntegrationTests`_ | ⏳ |
| TC-CONSOLE-170 ~ 177 | _`AdminPermissionsControllerIntegrationTests`_ | ⏳ |
| FE-CONSOLE-200 ~ 215 | _`falconx-console-frontend/src/features/admin-user/*.test.tsx`_ | ⏳ |
| FE-CONSOLE-220 ~ 233 | _`features/admin-role/*.test.tsx`_ | ⏳ |
| FE-CONSOLE-235 ~ 245 | _`features/admin-menu/*.test.tsx`_ | ⏳ |
| FE-CONSOLE-247 ~ 249 | _`features/admin-permission/*.test.tsx`_ | ⏳ |

---

## §9. 关联文档

- [STAGE-1-CONSOLE-test-cases.md](./STAGE-1-CONSOLE-test-cases.md)（R6 一轮，阶段 1 鉴权 + IP 白名单）
- [STAGE-2-CUSTOMER-test-cases.md](./STAGE-2-CUSTOMER-test-cases.md)（R6 二轮，阶段 2.1 客户管理）
- [管理端接口规范](../api/管理端接口规范.md) §5 阶段 1 P2-P5
- [管理端 5 页面方案](../design/falconx-console-pages-V1.md) §2-§5 P2-P5 设计
- [BBook 一期完成执行路径 §4 阶段 1](../process/BBook一期完成执行路径.md)
- [SKILLS Skill 10](../../SKILLS.md)
