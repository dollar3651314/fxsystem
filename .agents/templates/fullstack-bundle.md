# 三端任务打包模板（fullstack-bundle）

> ⚠️ **2026-05-08 范围调整后已升级为三端模式**
>
> 按 [`完成定义`](../../docs/process/完成定义.md) §4.A 与 [`AI工作模式`](../../docs/process/AI工作模式.md) §2 的"三端任务完成条件"硬约束：
>
> - 当前三端 = **客户端 + 后端服务 + 管理端**，每个业务功能必须三端齐全才算完成
> - 现行角色路由固定为 `R1 → R2 → R3 → R6 → (R4 ∥ R5 ∥ R9 ∥ R10) → R7 → R8`
> - R9 = 管理端后端实施者（falconx-console-service），R10 = 管理端前端实施者（falconx-console-frontend）
>
> 本文件正文已按三端任务更新；R9/R10 的细化边界见 [`role-admin-backend-implementer.md`](./role-admin-backend-implementer.md) 与 [`role-admin-frontend-implementer.md`](./role-admin-frontend-implementer.md)。

> 本模板用于业务功能任务的完整工单组装。涉及客户端可见行为的任务（新增/修改 REST 接口、WebSocket 消息体、推送字段、错误码、用户可触达功能）必须使用本模板，并按"三端齐全"原则组装。
>
> **任意一项未完成均不得标记完成**——这是不可妥协的硬约束。

---

## 任务概览

- 任务编号：
- 任务显示名称：
- 当前阶段口径：
- 任务来源：（如 `BBook一期完成执行路径` 阶段 X）
- 关联的执行路径任务：

---

## 角色路由（三端业务功能标准路径）

```
R1 → R2 → R3 → R6 → (R4 ∥ R5 ∥ R9 ∥ R10) → R7 → R8 → R1 (commit)
```

工具执行方式：

- Claude Code：R2 / R3 / R6 完成后，R4 / R5 / R9 / R10 可按独立读写范围并行 Agent 派发。
- Codex：不使用并行；按 `R4 → R5 → R9 → R10` 顺序执行，逐角色完成 handoff。

| 角色 | 任务模板 | 输出 |
| --- | --- | --- |
| R1 Commander | [`role-commander.md`](./role-commander.md) | 任务路由、回收报告、最终 commit |
| R2 Contract Designer | [`role-contract-designer.md`](./role-contract-designer.md) | 契约 spec、错误码、状态机迁移 |
| R3 UI/UX Designer | [`role-uiux-designer.md`](./role-uiux-designer.md) | 设计方案、组件状态表、Figma 节点 |
| R6 Test Designer | [`role-test-designer.md`](./role-test-designer.md) | 测试用例骨架、E2E 用例 |
| R4 Business Backend Implementer | [`role-backend-implementer.md`](./role-backend-implementer.md) | 业务后端代码 + 测试 |
| R5 Client Frontend Implementer | [`role-frontend-implementer.md`](./role-frontend-implementer.md) | 客户端前端代码 + 浏览器 QA 截图 |
| R9 Admin Backend Implementer | [`role-admin-backend-implementer.md`](./role-admin-backend-implementer.md) | 管理端后端代码 + RBAC / 审计 / 测试 |
| R10 Admin Frontend Implementer | [`role-admin-frontend-implementer.md`](./role-admin-frontend-implementer.md) | 管理端前端代码 + 按钮级 RBAC + 浏览器 QA 截图 |
| R7 QA Verifier | [`role-qa-verifier.md`](./role-qa-verifier.md) | 验证报告 |
| R8 Doc Synchronizer | [`role-doc-synchronizer.md`](./role-doc-synchronizer.md) | 文档同步 diff |

---

## 三端任务完成判定（硬约束）

**任意一项未完成均不得标记完成**：

- [ ] R2 输出的契约已被用户确认
- [ ] R3 输出的客户端 + 管理端双端设计方案已被 R1 接收
- [ ] R6 测试用例先于实现写出（含三端 E2E）
- [ ] R4 后端实现 + 单元测试 + 集成测试通过
- [ ] R5 客户端前端实现 + `npm run test` + `npm run lint` + `npm run build` 通过
- [ ] R5 客户端浏览器桌面 + 移动端 QA 截图齐全
- [ ] R9 管理端后端实现 + 测试通过
- [ ] R10 管理端前端实现 + `npm run test` + `npm run lint` + `npm run build` 通过
- [ ] R10 管理端浏览器桌面 + 移动端 QA 截图齐全
- [ ] 至少一条 E2E 测试覆盖客户端调用 → 后端 → 管理端可见整链
- [ ] R8 完成文档同步：`FalconX 统一接口文档` + 涉及的专题正式规范
- [ ] R7 最终验证报告完整
- [ ] 形成单一 Git commit（含三端代码 + 文档 + 测试）

**例外**：纯运维 / 内部维护任务若无客户端与管理端入口，由 R1 在任务启动时显式声明豁免，并写入任务工单。阶段 0 / 阶段 1 按 [`AI工作模式`](../../docs/process/AI工作模式.md) §4.1 例外条款处理。

---

## 4 条 Karpathy 准则（永久生效，所有角色都必须遵守）

每个角色完成前自检：

- [ ] **编码前先思考**：明确陈述假设；不确定时询问；存在多种解读时呈现而非默选
- [ ] **简洁优先**：只写解决问题所需的最少代码，不添加未被要求的功能、抽象、灵活性
- [ ] **精准修改**：每行改动都能追溯到用户请求，不顺手重构周边代码、注释或格式
- [ ] **目标驱动执行**：把任务转换为可验证目标，多步骤先列计划再动手

---

## 角色 handoff 协议

| 上游 → 下游 | 必须传递 | 接收方检查 |
| --- | --- | --- |
| R2 → R3 | 字段含义 + 错误码 + 状态枚举 | 是否包含设计所需所有信息 |
| R2 → R4 | 完整契约 + DB DDL + 状态机 | 是否包含实现所需所有信息 |
| R2 → R5 | API 路径 + 请求/响应字段 + WS 消息体 + 错误码 | 是否包含前端对接所需所有信息 |
| R2 → R6 | 所有需要测试的字段、状态、错误场景 | 是否覆盖所有测试维度 |
| R2 → R9 | 管理端 API + internal RPC + RBAC 权限点 + 错误码 | 是否包含管理端后端实现所需所有信息 |
| R2 → R10 | 管理端 API + 错误码 + 权限点 | 是否包含管理端前端对接所需所有信息 |
| R3 → R5 | 设计方案、组件状态、响应式规则、Figma 节点 | 是否包含所有交互状态 |
| R3 → R10 | 管理端设计方案、菜单结构、按钮权限点、响应式规则 | 是否包含所有管理端交互状态 |
| R6 → R4 | 后端测试用例骨架 | 用例是否可编译通过 |
| R6 → R5 | 前端测试用例骨架 | 用例是否可执行 |
| R6 → R9 | 管理端后端测试用例骨架 | 用例是否可编译通过 |
| R6 → R10 | 管理端前端测试用例骨架 | 用例是否可执行 |
| R4 → R7 | 后端实现 + 测试通过证据 | 测试输出是否完整 |
| R5 → R7 | 前端实现 + 浏览器 QA | 截图是否覆盖桌面 + 移动 |
| R9 → R10 | 管理端后端实际 API / DTO / 错误码 / RBAC 权限点 | API client、按钮权限和错误态是否与后端实现一致 |
| R9 → R7 | 管理端后端实现 + 测试通过证据 | RBAC / 审计 / internal RPC 覆盖是否完整 |
| R10 → R7 | 管理端前端实现 + 浏览器 QA | 截图是否覆盖桌面 + 移动，权限态是否验证 |
| R7 → R8 | 验证报告 | 验证范围是否清晰 |
| R8 → R1 | 文档同步证据 | 文档与代码是否一致 |

---

## 越界红旗（任何角色发现立即停止）

- ❌ R3 / R4 / R5 / R6 / R9 / R10 自行修改 R2 已冻结的契约
- ❌ R4 / R9 写前端代码，或 R5 / R10 写后端代码
- ❌ R5 写管理端前端代码，或 R10 写客户端前端代码
- ❌ R4 写管理端后端代码，或 R9 写业务后端代码
- ❌ R9 绕过 internal RPC / gateway 直接写业务服务 owner 表
- ❌ R10 用本地 mock、硬编码权限码或静态 owner 数据伪装管理端能力已完成
- ❌ R7 修改代码（应只报告）
- ❌ R8 修改业务代码或决定阶段结论
- ❌ 任何角色绕过 R2 直接改 API/DB/Kafka/状态机
- ❌ R1 直接写业务代码而不派发 R2-R10
- ❌ 任意角色跳过自己的"必读"清单

发现红旗 → 立即停止 → 返回 R1 → R1 重新评估任务边界。

---

## 实施流程（R1 主导）

### Phase 1：任务启动

1. R1 读取启动协议（[`session-bootstrap.md`](../session-bootstrap.md)）
2. R1 检查工作区 git status，识别无关改动
3. R1 判断任务类型 = 三端业务任务（有客户端可见入口）
4. R1 应用本模板

### Phase 2：契约 + 设计冻结

1. R1 派发 R2，R2 输出契约 spec
2. R1 接收 R2 输出，向用户确认（涉及 API/DB/Kafka/状态机/错误码必须确认）
3. R1 派发 R3，R3 输出设计方案
4. R1 接收 R3 输出

### Phase 3：测试设计

1. R1 派发 R6，R6 输出测试用例骨架
2. R1 接收 R6 输出

### Phase 4：实现（R4 + R5 + R9 + R10）

1. R1 派发 R4 + R5 + R9 + R10（Claude Code 可并行 Agent；Codex 串行执行 `R4 → R5 → R9 → R10`）
2. R4 实现业务后端 + 测试通过
3. R5 实现客户端前端 + 浏览器 QA 截图
4. R9 实现管理端后端 + 测试通过
5. R10 实现管理端前端 + 浏览器 QA 截图

### Phase 5：验证

1. R1 派发 R7
2. R7 跑测试 + canary + 浏览器 QA + 输出验证报告
3. 任一失败 → 返回对应实现角色（R4 / R5 / R9 / R10）

### Phase 6：文档同步

1. R1 派发 R8
2. R8 同步真源 + 摘要 + 链接完整性

### Phase 7：Git 回滚点

1. R1 接收 R8 输出
2. R1 完成最终验证清单（见上方"三端任务完成判定"）
3. R1 形成单一 commit

---

## 工单填写示例（任务启动时由 R1 填写）

```markdown
# 任务工单：阶段 1.3 用户实时推送扩展 CROSS 字段

## 任务概览
- 任务编号：CROSS-MARGIN-EXEC-01-C
- 任务显示名称：用户实时推送扩展 CROSS 字段
- 当前阶段口径：BBook 一期完成执行路径阶段 1
- 任务来源：docs/process/BBook一期完成执行路径.md §4.3

## 角色路由
本任务影响客户端可见行为（WebSocket 推送字段变化），按三端路径：
R1 → R2 → R3 → R6 → (R4 ∥ R5 ∥ R9 ∥ R10) → R7 → R8 → R1 (commit)

## 各角色子任务

### R2 Contract Designer
- 修改 docs/api/WebSocket接口规范.md：补充 CROSS 字段
- 输出 TradingAccountUpdatePayload 新增字段：marginMode、equity、crossMaintenance、crossMarginRatio
- 错误码无新增

### R3 UI/UX Designer
- 在前端账户面板设计 CROSS 模式的展示
- 区分 ISOLATED 与 CROSS 的视觉差异

### R6 Test Designer
- 后端测试用例：TradingUserWebSocketIntegrationTests 新增 CROSS 推送断言
- 前端测试用例：账户面板组件展示 CROSS 字段
- E2E 用例：开 CROSS 仓 → 推送 → 前端展示

### R4 Backend Implementer
- 修改：TradingAccountUpdatePayload.java、DefaultTradingUserRealtimePushService.java
- 测试：通过 R6 提供的测试骨架

### R5 Frontend Implementer
- 修改：falconx-frontend/src/features/account/AccountPanel.tsx
- 浏览器 QA：CROSS 模式与 ISOLATED 模式的对比截图

### R9 Admin Backend Implementer
- 修改：falconx-console-service 中对应管理端 API / internal RPC 调用
- 测试：通过 R6 提供的管理端后端测试骨架

### R10 Admin Frontend Implementer
- 修改：falconx-console-frontend 对应管理端页面
- 浏览器 QA：管理端桌面 + 移动降级截图

### R7 QA Verifier
- 串行验证后端测试 + 双前端三件套 + 浏览器 QA + 三端 E2E

### R8 Doc Synchronizer
- 同步：FalconX 统一接口文档、当前开发计划、BBook 一期完成执行路径
```

---

## 一句话执行原则

**三端业务任务按 R1-R10 介入角色执行；契约先于实现，客户端 + 后端服务 + 管理端一体交付；任意一项未完成即返工；4 条 Karpathy 准则永久生效。**
