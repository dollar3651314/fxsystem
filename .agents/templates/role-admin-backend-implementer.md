# R9 Admin Backend Implementer 管理端后端实施者任务模板

> 本模板用于 R9 角色的任务派发。R9 在 R2 冻结契约 + R6 输出测试用例骨架后，按 SKILLS.md 模板实现 `falconx-console-service` 后端代码。
>
> R9 与 R4（业务后端）的边界：R4 写业务服务（identity / market / trading-core / wallet）；R9 仅写管理端服务（`falconx-console-service`）。R9 可以**跨业务 schema 只读查询**（按 [`AI工作模式`](../../docs/process/AI工作模式.md) 中 console 的特殊架构地位），但不得跨服务直接写业务表。

---

## 任务身份

- 任务编号：
- 任务显示名称：
- 当前阶段口径：
- 角色：R9 Admin Backend Implementer 管理端后端实施者
- 上游：R2（契约 spec）+ R6（测试用例骨架）
- 下游：R7（QA 验证）

---

## 边界自检（执行前必读，全部勾选才能开始）

```
我当前的角色是：R9 Admin Backend Implementer
我即将做的事是：________________________

边界自检：
[ ] 这件事在 R9 的"✅ 可以"列表内：编辑 falconx-console-service 代码 / RBAC 权限模型 /
    跨业务 schema 只读查询 / 管理端 API / 审计日志写入
[ ] 不在 R9 的"❌ 不可以"列表内：写业务服务代码（属于 R4）/ 修改 R2 冻结的契约 /
    跨服务直接写业务表 / 写客户端前端代码 / 写管理端前端代码（属于 R10）
[ ] R2 输出的契约 spec 已就绪（含管理端 API 定义）
[ ] R6 输出的测试用例骨架已就绪
[ ] 已读取必读文档（见下）
[ ] 已限定本轮修改文件范围
```

任意一项不满足 → 暂停 → 返回 R1。

---

## 必读文档

- [`AI工作模式`](../../docs/process/AI工作模式.md) §2 R9 角色定义
- [`Karpathy 式 AI 编码行为准则`](../../docs/process/Karpathy式AI编码行为准则.md) 4 条强制准则
- [`完成定义`](../../docs/process/完成定义.md)
- [`SKILLS.md`](../../SKILLS.md) 对应 Skill（按任务类型 1-12）
- `docs/architecture/管理端架构.md`（如已落地）
- [`falconx 编码与测试规范`](../../docs/architecture/falconx编码与测试规范.md)
- [`日志打印规范`](../../docs/architecture/日志打印规范.md)
- R2 输出的管理端契约 spec
- R6 输出的测试用例骨架

---

## 允许修改

按任务派发时 R1 指定的范围。常见允许修改：

- `falconx-console-service/src/main/java/...`
- `falconx-console-service/src/main/resources/mapper/...`
- `falconx-console-service/src/main/resources/db/migration/V{N+1}__xxx.sql`
- `falconx-console-service/src/main/resources/application*.yml`
- `falconx-console-service/src/test/java/...`

---

## 禁止修改

- 任何业务服务（identity / market / trading-core / wallet）的业务代码（属于 R4 边界）
- 任何业务服务 owner 的业务表（console 仅可只读跨 schema 查询）
- R2 冻结的契约文档
- 已发布的 Flyway migration（V1, V2, ... 不可改）
- 任何前端代码（客户端属于 R5 / 管理端属于 R10）

---

## 实施步骤

### 1. 读取上下文

- [ ] R1 任务派发说明
- [ ] R2 管理端契约 spec
- [ ] R6 测试用例骨架
- [ ] 对应 SKILLS.md Skill 的"操作步骤"和"禁止事项"
- [ ] 现有 console 服务代码模式（参考 `falconx-console-service/README.md`）

### 2. RBAC 权限点埋点（强制）

R9 在每个管理端 API 上必须埋设 RBAC 权限点：

- 通过注解 `@RequiresPermission("module:action")` 或类似机制
- 权限点字典由代码扫描生成 → 写入 `t_admin_permission`
- 角色（`t_admin_role`）→ 权限点（`t_admin_role_permission`）→ 管理员（`t_admin_user_role`）三级关系
- 菜单与按钮级权限：`t_admin_menu` + `permission_code` 字段

API 鉴权 filter：从 JWT 解析 admin role → 加载权限点集合 → 校验当前接口是否在集合内 → 否则返回 403。

### 3. 跨业务 schema 只读查询规则

按 [`AI工作模式`](../../docs/process/AI工作模式.md) "Console 特殊架构地位"：

- console 的 PG/MySQL 用户拥有所有业务 schema 的**只读**权限 + console schema 写权限 + audit schema 写权限
- 不能 INSERT / UPDATE / DELETE 业务表（即使是为了管理操作，如冻结用户、调余额等）
- 这类管理操作必须通过业务服务的内部 API（business-service 暴露的 `/internal/v1/...`），由 console 调用
- 内部 API 鉴权用 `X-Admin-User-Id` + RBAC 校验

### 4. 审计日志（强制）

每次管理操作必须写入 `t_admin_operation_log`：

- 操作管理员 ID
- 操作类型（按 RBAC 权限点）
- 目标对象（target_type + target_id）
- 操作前/后值（如调余额）
- IP + User-Agent + 时间戳
- HIGH_RISK 标记（如调余额 / 冻结账户）

### 5. 测试覆盖

- [ ] 单元测试：RBAC 权限校验、跨服务调用桩
- [ ] 集成测试：管理端 API + 真实 MySQL（console schema + 业务 schema 只读）
- [ ] R6 提供的测试骨架必须全部通过

### 6. 验证

```bash
mvn -pl falconx-console-service -am compile
mvn -pl falconx-console-service -am test
```

---

## 必须返回的交付清单

1. 修改文件清单
2. 新增 RBAC 权限点清单（写入 `t_admin_permission` 字典）
3. 测试通过证据：完整命令输出
4. 跨服务调用清单（哪些业务 service 的内部 API 被 console 调用）
5. 审计日志埋点清单
6. 未完成项 / 风险

---

## 强制约束

- **不得**写业务服务代码（属于 R4）
- **不得**跨服务直接写业务表（仅可只读跨 schema）
- **不得**跳过 RBAC 权限点埋点
- **不得**跳过审计日志写入
- **不得**修改 R2 冻结的契约
- **不得**说"测试应该通过"——必须真跑并提供完整输出
