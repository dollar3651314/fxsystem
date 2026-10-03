# R3 UI/UX Designer 设计师任务模板

> 本模板用于 R3 角色的任务派发。R3 在 R5（前端实施）开始前完成产品设计、信息架构、交互流程、视觉规范、响应式规则、动效方向。**先设计，再开发**是不可妥协的硬约束。

---

## 任务身份

- 任务编号：
- 任务显示名称：
- 当前阶段口径：
- 角色：R3 UI/UX Designer 设计师
- 上游：R2（契约 spec）
- 下游：R5（前端实施）+ R6（测试设计）

---

## 边界自检（执行前必读，全部勾选才能开始）

```
我当前的角色是：R3 UI/UX Designer
我即将做的事是：________________________

边界自检：
[ ] 这件事在 R3 的"✅ 可以"列表内：使用 design-consultation/design-shotgun/Figma MCP 输出设计 / 定义页面结构、组件状态、响应式规则、loading/error/empty/disabled 状态
[ ] 不在 R3 的"❌ 不可以"列表内：自行决定后端契约 / 修改前端实现规格的代码侧约束 / 引入未批准的设计系统/UI 框架/图标库
[ ] R2 输出的契约 spec 已就绪（必须先有契约才能设计）
[ ] 已读取必读文档（见下）
[ ] 已说明关键假设
```

任意一项不满足 → 暂停 → 返回 R1。

---

## 必读文档

- [`AI工作模式`](../../docs/process/AI工作模式.md) §2.3 R3 角色定义
- [`全栈Figma协作流程`](../../docs/process/全栈Figma协作流程.md)
- [`falconx-frontend-react-implementation-spec`](../../docs/design/falconx-frontend-react-implementation-spec.md)
- [`SKILLS.md`](../../SKILLS.md) Skill 13 前端产品/页面设计
- 当前会话中 R2 输出的契约 spec（API 路径、字段、错误码、状态枚举）
- 涉及 Figma 时还需读：[`SKILLS.md`](../../SKILLS.md) Skill 14

---

## 允许修改

- 设计方案文档（任务交付物，markdown 形式）
- Figma 节点（如使用 Figma 实施设计）
- `docs/design/` 下的设计补充说明（仅在用户明确允许时）

---

## 禁止修改

- 任何后端代码或契约文档
- 任何前端代码（必须由 R5 实施）
- `docs/design/falconx-frontend-react-implementation-spec.md` 的代码侧约束
- 引入新设计系统、UI 框架、图标库（必须先与用户确认）

---

## 实施步骤

### 1. 读取上下文

- [ ] R2 输出的契约 spec：API 路径、字段、错误码、状态枚举
- [ ] [`全栈Figma协作流程`](../../docs/process/全栈Figma协作流程.md) §4 默认全栈流程
- [ ] [`falconx-frontend-react-implementation-spec`](../../docs/design/falconx-frontend-react-implementation-spec.md) 现有 token、组件、状态管理模式
- [ ] 检查现有前端代码：当前已有哪些组件可复用？

### 2. 与用户对齐方向

如任务是新功能 / 重大交互变化：

- [ ] 使用 `Skill { skill: "design-consultation" }` 与用户对齐产品目标、用户流程、信息层级、视觉方向、动效口径
- [ ] 使用 `Skill { skill: "design-shotgun" }` 生成多套方向供用户选择

如任务是已有页面的小改 / 已有 Figma 设计：

- [ ] 跳过 brainstorm，直接进入 Figma 落地

### 3. 设计输出

#### 3.1 信息架构

- 页面层级（哪些页面、嵌套关系）
- 关键导航入口（用户从哪里进入这个功能）
- 数据展示层级（一级 / 二级 / 详情）

#### 3.2 用户流程图

- Happy path：用户成功完成任务的关键步骤
- 异常分支：未登录、token 过期、网络断开、空数据、stale 行情、业务错误
- 关键决策点：用户选择不同分支时的 UI 差异

#### 3.3 组件状态表

每个组件必须列出：

| 状态 | 触发条件 | UI 表现 | 用户可操作性 |
| --- | --- | --- | --- |
| default | 初始 | … | 是 |
| loading | 异步请求中 | spinner / skeleton | 否 |
| error | 后端错误 / 网络错误 | 错误提示 + 重试按钮 | 是（重试） |
| empty | 数据为空 | 空态图 + 引导 | 是 |
| disabled | 业务规则禁用 | 灰化 + 提示原因 | 否 |
| hover / active | 鼠标交互 | 视觉变化 | 是 |

#### 3.4 响应式规则

- 桌面（>= 1024px）布局
- 平板（768-1023px）布局
- 移动（< 768px）布局
- 关键断点的 UI 差异（如导航栏折叠为汉堡菜单）

#### 3.5 数据依赖清单

| UI 元素 | 依赖的 R2 契约 | 字段映射 | 缺失时的 UI |
| --- | --- | --- | --- |
| 用户余额 | GET /api/v1/trading/accounts/me | data.balance | "—" 占位 |
| ... | ... | ... | ... |

后端能力**未实现**时，只能展示禁用态、空态或占位，**禁止用 mock 替代真实生产路径**。

#### 3.6 Figma 节点（如使用 Figma）

- Figma URL（指向具体 Frame / Node，不是文件首页）
- fileKey + nodeId
- 关键节点截图（用 Figma MCP `get_screenshot`）

### 4. 验证设计完整性

- [ ] 所有 happy path + 异常分支的 UI 都已定义？
- [ ] 所有组件状态都已列出？
- [ ] 桌面 + 移动响应式规则都已定义？
- [ ] 数据依赖与 R2 契约对齐？
- [ ] 后端能力缺失时的 UI 已明确？

### 5. 与下游 handoff

输出物必须包含 R5（前端实施）和 R6（测试设计）接收所需信息：

- R5 需要：完整设计方案（含组件状态表、响应式规则、Figma 节点）
- R6 需要：用户流程图（用于设计 E2E 测试场景）

---

## 验证命令

```bash
# 设计文档可读性检查
ls docs/design/

# 如使用 Figma MCP，确认节点可读
# （通过 Figma MCP 的 get_design_context / get_screenshot 验证）
```

---

## 必须返回的交付清单

1. **设计方案 markdown 文档**（含 §3.1-3.5 全部内容）
2. **Figma 节点链接**（如使用 Figma）
3. **组件状态表**
4. **响应式规则**
5. **数据依赖清单**（与 R2 契约对齐）
6. **未明确点 / 待用户确认点**（如有）
7. **是否需要新增 token / 通用组件 / 图标包**（如有，必须返回 R1 让用户确认）

---

## 强制约束

- **不得**自行决定后端契约（必须等 R2 输出完成）
- **不得**把未实现的后端能力包装成真实可用功能
- **不得**在设计文档里拍板 API 路径、字段、错误码、数据库字段、Kafka payload
- **不得**引入新设计系统、UI 框架、图标库（除非用户明确批准）
- **不得**在 R5 启动前留下未完成的视觉/交互定义（设计必须先于开发完成）
