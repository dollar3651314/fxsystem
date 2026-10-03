# R5 Frontend Implementer 前端实施者任务模板

> 本模板用于 R5 角色的任务派发。R5 在 R2 冻结契约 + R3 完成设计 + R6 输出测试用例骨架后，按 SKILLS.md Skill 14-15 实现前端代码。

---

## 任务身份

- 任务编号：
- 任务显示名称：
- 当前阶段口径：
- 角色：R5 Frontend Implementer 前端实施者
- 上游：R2（契约 spec）+ R3（设计方案）+ R6（测试用例骨架）
- 下游：R7（QA 验证）

---

## 边界自检（执行前必读，全部勾选才能开始）

```
我当前的角色是：R5 Frontend Implementer
我即将做的事是：________________________

边界自检：
[ ] 这件事在 R5 的"✅ 可以"列表内：编辑 falconx-frontend/src/ 下的代码、组件、hook、API client、状态管理、单元测试
[ ] 不在 R5 的"❌ 不可以"列表内：修改后端代码 / 修改 R2 冻结的契约 / 修改 R3 输出的设计 / 引入新设计系统/UI 框架/图标库
[ ] R2 输出的契约 spec 已就绪
[ ] R3 输出的设计方案已就绪
[ ] R6 输出的测试用例骨架已就绪
[ ] 已读取必读文档（见下）
[ ] 已限定本轮修改文件范围
```

任意一项不满足 → 暂停 → 返回 R1。

---

## 必读文档

- [`AI工作模式`](../../docs/process/AI工作模式.md) §2.5 R5 角色定义
- [`Karpathy 式 AI 编码行为准则`](../../docs/process/Karpathy式AI编码行为准则.md) 4 条强制准则
- [`SKILLS.md`](../../SKILLS.md) Skill 14（Figma 落地）+ Skill 15（接口对接）
- [`falconx-frontend-react-implementation-spec`](../../docs/design/falconx-frontend-react-implementation-spec.md)
- [`全栈Figma协作流程`](../../docs/process/全栈Figma协作流程.md)
- [`安全规范`](../../docs/security/安全规范.md)
- R2 输出的契约 spec
- R3 输出的设计方案 + Figma 节点
- R6 输出的测试用例骨架

---

## 允许修改

按任务派发时 R1 指定的范围。常见允许修改：

- `falconx-frontend/src/components/`（复用 UI 组件）
- `falconx-frontend/src/features/{domain}/`（业务页面、状态、接口）
- `falconx-frontend/src/lib/`（工具）
- `falconx-frontend/src/styles/tokens.css`（仅在 R3 设计明确要求新增 token 时）
- 单元测试文件

---

## 禁止修改

- 任何后端代码或契约文档
- R3 输出的设计方案（视觉/交互/响应式规则）
- `falconx-frontend/src/styles/global.css` 的现有结构（除非 R3 明确要求）
- 引入新 UI 框架、图标库、设计系统（除非用户明确批准）

---

## 实施步骤

### 1. 读取上下文

- [ ] R1 任务派发说明
- [ ] R2 契约 spec：API 路径、字段、错误码、WS 消息体
- [ ] R3 设计方案：组件状态表、响应式规则、Figma 节点、数据依赖清单
- [ ] R6 测试用例骨架
- [ ] [`SKILLS.md`](../../SKILLS.md) Skill 14 / Skill 15 操作步骤与禁止事项
- [ ] 现有前端代码模式（参考 `falconx-frontend/src/features/` 已有 feature）

### 2. Figma 实施（如使用 Figma）

按 [`SKILLS.md`](../../SKILLS.md) Skill 14 顺序：

- [ ] 提取 fileKey + nodeId
- [ ] `mcp__claude_ai_Figma__get_design_context` 获取结构化上下文
- [ ] `mcp__claude_ai_Figma__get_screenshot` 获取视觉对照图
- [ ] 上下文过大时先 `get_metadata`，再分批读子节点
- [ ] 翻译为 React + TypeScript + tokens.css，**不直接搬运 Tailwind / 内联样式**

### 3. 接口对接（按 Skill 15）

#### 3.1 API client 封装

- 路径：`src/features/{domain}/{domain}Api.ts`
- 复用 `src/lib/api.ts` 通用封装
- 类型必须从 R2 契约 spec 派生

#### 3.2 状态管理

- 服务端数据：React Query
- 客户端轻量状态：Zustand
- WebSocket：封装在业务域 hook 或 service，不在组件里散落

#### 3.3 错误与边缘状态

显式处理（按 R3 设计的状态表）：

- [ ] 未登录
- [ ] Access Token 过期
- [ ] Refresh Token 过期或刷新失败
- [ ] 后端返回业务错误码（按 R2 错误码清单）
- [ ] WebSocket 断开 / 重连 / 鉴权失败
- [ ] 数据为空、产品不可交易、行情缺失、行情 stale

### 4. 关键实施原则（4 条 Karpathy 准则）

- **思考前先**：写代码前明确"我即将实现 R3 的哪个组件 / 接入 R2 的哪个接口"
- **简洁优先**：使用现有 token、组件、hook 模式；不引入新设计系统
- **精准修改**：每行改动能追溯到 R2 契约 / R3 设计 / R6 测试
- **目标驱动**：跑过 R6 测试 + 浏览器 QA 才算完成

### 5. 安全约束

按 [`安全规范`](../../docs/security/安全规范.md) 与 [`AI工作模式`](../../docs/process/AI工作模式.md) §2.5：

- [ ] 不在控制台 / 日志输出 token
- [ ] 不主动发送 `X-Trace-Id`（由 gateway 注入）
- [ ] WebSocket token 在 URL query 时不输出完整 URL
- [ ] 不硬编码 owner 数据（symbol 列表 / 错误消息 / 产品元数据）
- [ ] 不用本地 mock 替代真实生产路径

### 6. 测试覆盖

- [ ] R6 提供的单元测试 / 组件测试用例骨架必须全部通过
- [ ] 浏览器 QA 截图（桌面 + 移动）

### 7. 验证命令

```bash
cd falconx-frontend
npm run test    # 单元 / 组件测试
npm run lint    # 代码风格
npm run build   # 构建检查

# 浏览器 QA（启动 dev server 后用 Playwright 或手动截图）
npm run dev
```

### 8. 自检

- [ ] 是否引入了 R3 未设计的视觉 / 交互？（如有 → 返回 R1）
- [ ] 是否修改了不在允许范围的文件？
- [ ] 是否硬编码了 owner 数据？
- [ ] 是否在控制台输出了 token？
- [ ] 是否所有 loading / error / empty / disabled 状态都已实现？

---

## 必须返回的交付清单

1. **修改文件清单**
2. **关键实现说明**：每个新组件 / 大改文件的目的
3. **测试通过证据**：`npm run test` / `npm run lint` / `npm run build` 完整输出
4. **浏览器 QA 截图**：桌面 + 移动端，覆盖主要状态
5. **网络请求验证**：浏览器开发工具截图，确认命中真实后端
6. **控制台错误检查**：应为 0
7. **未完成项 / 风险**

---

## 强制约束（4 条 Karpathy 准则在 R5 的体现）

- **思考前先**：实现前能复述"R2 接口我接哪个 / R3 设计我做哪个组件"
- **简洁优先**：复用 `src/components/` 和 `tokens.css`；不引入平行 token 或组件库
- **精准修改**：每行改动追溯到 R2 契约 / R3 设计 / R6 测试
- **目标驱动**：跑过 R6 测试 + npm 三件套 + 浏览器 QA，才算完成

其他强制：

- **不得**修改后端代码或 R2 契约
- **不得**修改 R3 设计（必须返回 R1）
- **不得**引入新 UI 框架 / 图标库 / 设计系统
- **不得**硬编码 owner 数据
- **不得**用 mock 替代生产路径
- **不得**说"应该可以"——必须真跑测试 + 真做浏览器 QA + 真截图
