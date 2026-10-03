# R10 Admin Frontend Implementer 管理端前端实施者任务模板

> 本模板用于 R10 角色的任务派发。R10 在 R2 冻结契约 + R3 完成管理端设计 + R6 输出测试用例骨架后，按 SKILLS.md Skill 14-15 实现 `falconx-console-frontend` 代码。
>
> R10 与 R5（客户端前端）的边界：R5 写 `falconx-frontend`（C 端交易终端）；R10 仅写 `falconx-console-frontend`（管理后台）。两端可以共享 `tokens.css` 等基础样式，但组件库、信息架构、交互模式独立。

---

## 任务身份

- 任务编号：
- 任务显示名称：
- 当前阶段口径：
- 角色：R10 Admin Frontend Implementer 管理端前端实施者
- 上游：R2（管理端 API 契约）+ R3（管理端设计方案）+ R6（测试用例骨架）
- 下游：R7（QA 验证）

---

## 边界自检（执行前必读，全部勾选才能开始）

```
我当前的角色是：R10 Admin Frontend Implementer
我即将做的事是：________________________

边界自检：
[ ] 这件事在 R10 的"✅ 可以"列表内：编辑 falconx-console-frontend 代码 / 管理端组件 / RBAC 按钮级控制 / 管理端单元测试
[ ] 不在 R10 的"❌ 不可以"列表内：写客户端前端代码（属于 R5）/ 写管理端后端代码（属于 R9）/ 修改 R2 冻结的契约 / 修改 R3 输出的设计 / 引入新设计系统 / UI 框架
[ ] R2 输出的管理端 API 契约已就绪
[ ] R3 输出的管理端设计方案已就绪（含菜单结构、组件状态、按钮权限点）
[ ] R6 输出的测试用例骨架已就绪
[ ] 已读取必读文档（见下）
```

任意一项不满足 → 暂停 → 返回 R1。

---

## 必读文档

- [`AI工作模式`](../../docs/process/AI工作模式.md) §2 R10 角色定义
- [`Karpathy 式 AI 编码行为准则`](../../docs/process/Karpathy式AI编码行为准则.md) 4 条强制准则
- [`SKILLS.md`](../../SKILLS.md) Skill 14（设计落地）+ Skill 15（接口对接）
- `docs/architecture/管理端架构.md`（如已落地）
- [`安全规范`](../../docs/security/安全规范.md)
- R2 输出的管理端 API 契约
- R3 输出的管理端设计方案 + Figma 节点
- R6 输出的测试用例骨架

---

## 允许修改

按任务派发时 R1 指定的范围。常见允许修改：

- `falconx-console-frontend/src/components/`
- `falconx-console-frontend/src/features/{module}/`（按管理后台业务模块组织）
- `falconx-console-frontend/src/lib/`
- `falconx-console-frontend/src/styles/tokens.css`（仅在 R3 设计要求新增 token 时）
- 单元测试文件

---

## 禁止修改

- 任何客户端前端代码（`falconx-frontend/`，属于 R5 边界）
- 任何后端代码（业务后端 R4 / 管理端后端 R9）
- R2 冻结的 API 契约
- R3 输出的设计方案（视觉/交互/响应式规则）
- 引入新 UI 框架、图标库、设计系统（除非用户明确批准）

---

## 实施步骤

### 1. 读取上下文

- [ ] R1 任务派发说明
- [ ] R2 管理端 API 契约
- [ ] R3 管理端设计方案：菜单结构、按钮权限点、组件状态、响应式规则
- [ ] R6 测试用例骨架
- [ ] 现有 console-frontend 代码模式

### 2. RBAC 按钮级权限控制（强制）

R10 必须按 R3 设计的"按钮权限点"清单实现按钮级 RBAC：

```typescript
// 通过 hook 或组件包装：
<RequiresPermission code="customer:freeze">
  <Button onClick={...}>冻结账户</Button>
</RequiresPermission>
```

权限码集合从登录响应或 `/api/v1/admin/me/permissions` 加载到全局 store。
菜单显示也按权限过滤（用户只看到自己有权访问的菜单项）。

### 3. 设计落地（按 R3 输出）

按 [`SKILLS.md`](../../SKILLS.md) Skill 14：

- 提取 fileKey + nodeId
- 调用 Figma MCP `get_design_context` + `get_screenshot`
- 翻译为 React + TypeScript + tokens.css

管理端样式：
- 信息密集型布局（表格、表单、列表为主，与 C 端的交易终端风格不同）
- 桌面优先（管理端通常 1440px+）
- 移动端响应式可降级到"只读视图"

### 4. 接口对接（按 Skill 15）

- API client 封装在 `src/lib/adminApi.ts`
- 类型从 R2 契约派生
- 鉴权：管理端独立 JWT（与 C 端 JWT 隔离，避免 token 复用风险）
- 错误处理：401 → 跳登录、403 → "无权限"提示、429 → 限流提示

### 5. 必须显式处理的状态

- [ ] 未登录
- [ ] 管理员 token 过期
- [ ] 无权限（403）
- [ ] 操作高风险确认（如调余额、冻结账户必须二次确认弹窗）
- [ ] 网络错误 / 后端 5xx
- [ ] 列表分页 / 排序 / 筛选 / 空数据

### 6. 安全约束

- [ ] 不在控制台 / 日志输出 token
- [ ] 不主动发送 `X-Trace-Id`（由 gateway 注入）
- [ ] 高风险操作（调余额、冻结、出金审核通过）必须二次确认
- [ ] 不硬编码 owner 数据

### 7. 测试与验证

```bash
cd falconx-console-frontend
npm run test
npm run lint
npm run build
npm run dev  # 启动后用浏览器 QA + 截图
```

---

## 必须返回的交付清单

1. 修改文件清单
2. 新增按钮权限点清单（与 R9 的权限点字典对齐）
3. 测试通过证据：`npm run test` / `npm run lint` / `npm run build` 完整输出
4. 浏览器 QA 截图：桌面（1440px）+ 移动端降级视图
5. 控制台错误检查：应为 0
6. 网络请求验证：命中真实管理端后端
7. 未完成项 / 风险

---

## 强制约束

- **不得**写客户端前端代码（属于 R5）
- **不得**写后端代码（业务 R4 / 管理端 R9）
- **不得**跳过按钮级 RBAC 控制
- **不得**跳过高风险操作的二次确认
- **不得**引入新 UI 框架 / 图标库 / 设计系统
- **不得**说"应该可以"——必须真跑测试 + 真做浏览器 QA + 真截图
