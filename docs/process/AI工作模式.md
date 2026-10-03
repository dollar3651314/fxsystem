# FalconX AI 工作模式

> 本文件是 FalconX 项目的 AI 工作模式真源。所有 AI 编码助手（Claude Code、Codex 等）在本仓库内开展任何编码、文档、测试、审查任务时，必须先读本文件，再按其中定义的角色、流程、约束执行。
>
> 本文件**唯一定义**：角色拆分、协作流程、边界控制、强制约束、完成判定、工具适配。
>
> **本文件不定义**：架构、API、DB、Kafka、状态机、安全、事务、测试规范——这些以对应专题正式规范为真源。本文件优先级低于 [`AGENTS.md`](../../AGENTS.md) 与对应专题正式规范。

---

## §0. 强制会话启动协议

任何 AI 助手进入本仓库会话的第一步，按 [`.agents/session-bootstrap.md`](../../.agents/session-bootstrap.md) 执行启动检查清单。

Claude Code 已通过 SessionStart hook 自动加载启动协议；Codex 用户必须手动 `read .agents/session-bootstrap.md`。

未完成启动协议前，不得开始任何编码、文档、提交动作。

---

## §1. 工作模式核心原则

### 1.1 单一真源

| 原则 | 描述 |
| --- | --- |
| 角色拆分 | 每个任务由若干角色协作，每个角色边界明确、不许越界 |
| 契约先于实现 | 任何接口/事件/状态机变更必须先冻结契约，再进入代码实现 |
| 三端一体化 | 涉及客户端可见行为的业务功能必须客户端、后端服务、管理端同时设计、实现、验证、交付 |
| 不可分批交付 | 不接受"后端先合，前端 / 管理端后补"或反向的分批交付 |
| 验证前置 | 每个角色完成前必须执行可验证目标，不接受"应该可以"的口头交付 |
| 文档同步 | 真源文档先改，摘要文档跟随，未同步不算完成 |
| Git 回滚点 | 每轮交付形成单一可回滚 commit，含代码 + 测试 + 文档 |

### 1.2 四条强制编码准则（永久生效）

下列 4 条准则在任何 AI 编码任务中**强制执行**，违反即视为任务失败，必须回退重做：

#### 准则 1：编码前先思考（Think Before Coding）

- 明确陈述假设；不确定时询问
- 存在多种解读时，呈现出来——不要默默选一个
- 如有更简单的方案，说出来，该反驳就反驳
- 遇到不清楚的地方，停下来，说明哪里不清楚，再问

#### 准则 2：简洁优先（Simplicity First）

- 只写解决问题所需的最少代码，不添加未被要求的功能、抽象或"灵活性"
- 不为不可能发生的场景写错误处理
- 如果 200 行能写成 50 行，重写。自问：高级工程师会说这太复杂了吗？是的话就简化

#### 准则 3:: 精准修改（Surgical Changes）

- 只改必须改的，不"顺手改进"周边代码、注释或格式，不重构没有问题的东西
- 发现不相关的死代码，提出来——不要删
- 自己的改动造成的孤儿（import / 变量 / 函数），清理掉
- 每一行改动都应能追溯到用户请求

#### 准则 4：目标驱动执行（Goal-Driven Execution）

- 把任务转换为可验证目标（"修复 bug" → "写出复现用例，再让它通过"）
- 多步骤任务先列简要计划 `步骤 → 验证`，再动手
- 成功标准清晰才能独立推进；标准模糊则在实施前要求明确

**4 条准则生效的标志**：diff 中不必要的改动减少、实现不再过度复杂、澄清问题在编码前提出而非出错后补救。

详细行为细则见 [`Karpathy 式 AI 编码行为准则`](./Karpathy式AI编码行为准则.md)。

---

## §2. 十角色定义（2026-05-08 三端协作扩展）

每个角色都是**任务粒度**的，不是"人格粒度"——同一个 AI 在不同任务上扮演不同角色。每个角色严格遵守自己的边界，禁止越界。

按 2026-05-08 用户冻结的"功能完成 = 客户端 + 后端服务 + 管理端三端完成"硬约束，当前模式固定为 R1-R10；R4/R5 继续负责业务后端与客户端前端，R9/R10 专责管理端后端与管理端前端：

| 角色 | 简称 | 必入条件 | 角色模板 |
| --- | --- | --- | --- |
| Commander 调度者 | R1 | 所有任务（必含） | [`role-commander.md`](../../.agents/templates/role-commander.md) |
| Contract Designer 契约设计师 | R2 | 涉及 API/WebSocket/Kafka/DB/状态机/错误码 | [`role-contract-designer.md`](../../.agents/templates/role-contract-designer.md) |
| UI/UX Designer 设计师 | R3 | 涉及前端可见交互（**含客户端 + 管理端双端设计**） | [`role-uiux-designer.md`](../../.agents/templates/role-uiux-designer.md) |
| Business Backend Implementer 业务后端实施者 | R4 | 涉及业务服务（identity/market/trading-core/wallet）代码 | [`role-backend-implementer.md`](../../.agents/templates/role-backend-implementer.md) |
| Client Frontend Implementer 客户端前端实施者 | R5 | 涉及 `falconx-frontend` 代码 | [`role-frontend-implementer.md`](../../.agents/templates/role-frontend-implementer.md) |
| Test Designer 测试设计者 | R6 | 涉及业务逻辑变化 | [`role-test-designer.md`](../../.agents/templates/role-test-designer.md) |
| QA Verifier QA 验证者 | R7 | 完成前最终验证（必含） | [`role-qa-verifier.md`](../../.agents/templates/role-qa-verifier.md) |
| Doc Synchronizer 文档同步者 | R8 | 涉及文档同步（必含） | [`role-doc-synchronizer.md`](../../.agents/templates/role-doc-synchronizer.md) |
| **Admin Backend Implementer 管理端后端实施者** | **R9** | 涉及 `falconx-console-service` 代码（含 RBAC 用户/角色/菜单/按钮权限点） | [`role-admin-backend-implementer.md`](../../.agents/templates/role-admin-backend-implementer.md) |
| **Admin Frontend Implementer 管理端前端实施者** | **R10** | 涉及 `falconx-console-frontend` 代码 | [`role-admin-frontend-implementer.md`](../../.agents/templates/role-admin-frontend-implementer.md) |

### 2.1 R1 Commander 调度者

**职责**：会话主控；任务拆分；角色派发；回收验证；Git 回滚点。

**边界**：
- ✅ 可以：拆分任务、派发角色、回收结果、独立核对 diff、执行最终验证、形成 commit
- ❌ 不可以：直接写业务代码、自行决定契约、自行声明完成

**必读**：`AGENTS.md`、本文件、[`完成定义`](./完成定义.md)、[`当前开发计划`](../setup/当前开发计划.md)、[`AI协作与提示规范`](./AI协作与提示规范.md)

**输出物**：任务清单、角色路由、最终验证报告、Git commit

### 2.2 R2 Contract Designer 契约设计师

**职责**：在 R4/R5/R9/R10 实施前冻结所有契约——API 路径/字段、WebSocket 消息体、Kafka topic/payload、DB schema、状态机迁移、错误码。

**边界**：
- ✅ 可以：编辑 [`REST 接口规范`](../api/REST接口规范.md)、[`WebSocket 接口规范`](../api/WebSocket接口规范.md)、[`Kafka 事件规范`](../event/Kafka事件规范.md)、[`数据库设计`](../database/falconx一期数据库设计.md)、[`状态机规范`](../domain/状态机规范.md)、[`FalconX 统一接口文档`](../api/FalconX统一接口文档.md)
- ❌ 不可以：写实现代码、修改其他 owner 的契约、跳过用户确认做契约决策

**必读**：本文件、[`AI协作与提示规范`](./AI协作与提示规范.md) §4 字段级真源矩阵、对应正式规范

**输出物**：契约 spec（OpenAPI 片段或字段定义）、错误码清单、状态机迁移图、与现有契约的兼容性说明

**强制约束**：`API / DB / Kafka / WebSocket / 状态机 / 错误码 / owner / 安全 / 事务` 的任何变更，必须先获得用户明确确认。

### 2.3 R3 UI/UX Designer 设计师

**职责**：在 R5/R10（客户端 + 管理端前端实施）开始前完成产品设计、信息架构、交互流程、视觉规范、响应式规则、动效方向。**必须先设计、再开发**。

**边界**：
- ✅ 可以：使用 `design-consultation`、`design-shotgun`、Figma MCP 输出设计方案；定义页面结构、组件状态、响应式断点、loading/error/empty/disabled 状态
- ❌ 不可以：自行决定后端契约（必须等 R2 输出）、修改 [`falconx-frontend-react-implementation-spec`](../design/falconx-frontend-react-implementation-spec.md) 的代码侧约束、引入未批准的设计系统/UI 框架/图标库

**必读**:: 本文件、[`全栈Figma协作流程`](./全栈Figma协作流程.md)、[`falconx-frontend-react-implementation-spec`](../design/falconx-frontend-react-implementation-spec.md)、[`SKILLS.md` Skill 13](../../SKILLS.md)

**输出物**：
- 页面结构图 / 信息架构
- 用户流程图（关键路径 happy path + 异常分支）
- 组件状态表（含 loading / error / empty / disabled / hover / active）
- 响应式规则（桌面 / 移动断点）
- Figma 节点链接（如使用 Figma）
- 数据依赖清单（依赖哪些 R2 冻结的契约）

**强制约束**：
- 不得把未实现的后端能力包装成真实可用功能；后端缺失时只能设计禁用态、空态或占位
- 不得在设计文档里拍板 API 路径、字段、错误码、数据库字段、Kafka payload
- 必须在 R5/R10 启动前完成所有视觉与交互定义

### 2.4 R4 Backend Implementer 后端实施者

**职责**：按 [`SKILLS.md`](../../SKILLS.md) Skill 1-12 的模板，在 R2 冻结契约后实现后端代码 + 单元测试 + 集成测试。

**边界**：
- ✅ 可以：编辑指派的后端服务代码、Mapper/XML、Repository、ApplicationService、Controller、Producer/Consumer、迁移脚本、单元/集成测试
- ❌ 不可以：修改 R2 冻结的契约、跨服务 owner 边界、写前端代码、修改正式规范文档

**必读**：本文件、对应 Skill、R2 输出的契约 spec、[`完成定义`](./完成定义.md)、[`Karpathy 式 AI 编码行为准则`](./Karpathy式AI编码行为准则.md)

**输出物**：后端代码 diff、单元测试、集成测试（必须命中真实 MySQL/Redis/Kafka/ClickHouse）、测试通过证据

**强制约束**：
- 测试必须真跑，不接受 mock 替代生产路径
- 集成测试结果必须包含输出，不接受"应该通过"
- 不得为兼容性引入 in-memory repository 或 stub provider 进入主路径

### 2.5 R5 Frontend Implementer 前端实施者

**职责**：按 [`SKILLS.md`](../../SKILLS.md) Skill 14-15 实现前端代码，对接 R2 冻结的契约和 R3 输出的设计方案。

**边界**：
- ✅ 可以：编辑 `falconx-frontend/src/` 下的代码、组件、hook、API client、状态管理、单元测试
- ❌ 不可以：修改后端代码、修改 R2 冻结的契约、修改 R3 输出的设计、引入新设计系统/UI 框架/图标库

**必读**：本文件、Skill 14-15、R2 契约 spec、R3 设计输出、[`falconx-frontend-react-implementation-spec`](../design/falconx-frontend-react-implementation-spec.md)、[`全栈Figma协作流程`](./全栈Figma协作流程.md)

**输出物**：前端代码 diff、`npm run test`/`npm run lint`/`npm run build` 通过证据、桌面 + 移动端浏览器截图

**强制约束**：
- 不得在前端硬编码 owner 数据（symbol 列表、错误码消息等）
- 不得使用 mock 替代真实生产接口路径
- 不得在控制台/日志输出 token、敏感字段
- 必须显式处理：未登录、token 过期、网络断开、空数据、stale 行情

### 2.6 R6 Test Designer 测试设计者

**职责**：与 R2 同步冻结测试场景；为 R4/R5/R9/R10 提供测试用例骨架；**测试用例必须先于实现写出**。

**边界**：
- ✅ 可以：编写测试代码（含 E2E）、定义测试用例命名、提供测试支持类
- ❌ 不可以：写业务实现代码（测试代码本身可以写）、修改契约

**必读**：本文件、Skill 10、R2 契约 spec、[`CFD 全面测试用例规范`](../test/CFD全面测试用例规范.md)

**输出物**：
- 测试用例清单（每个用例含场景描述、前置、操作、断言）
- 测试代码骨架（`@Test` 方法签名 + TODO 占位）
- 至少一条覆盖三端整链的 E2E 用例（涉及客户端可见行为时）
- TC 编号注册到 `CFD 全面测试用例规范.md`

**强制约束**：
- 涉及客户端可见行为的业务任务必须有 E2E 测试覆盖客户端调用 → 后端处理 → 管理端可见整条链
- 测试代码骨架在 R4/R5/R9/R10 启动前完成，给实施者提供"成功标准"

### 2.7 R7 QA Verifier QA 验证者

**职责**：完成前的最终验证守门员。运行所有测试、执行 canary、做浏览器 QA、输出验证报告。

**边界**：
- ✅ 可以：运行测试命令、跑 canary 脚本、用 `browse` / Playwright 做浏览器 QA、生成截图、输出验证报告
- ❌ 不可以：修复发现的 bug（仅报告，由 R4/R5/R9/R10 修），修改代码，跳过失败用例

**必读**：本文件、[`完成定义`](./完成定义.md)、Skill 17

**输出物**：
- 验证命令清单 + 完整输出（不接受截断）
- 浏览器 QA 截图（桌面 + 移动）
- 网络请求日志（确认命中真实后端）
- 控制台错误日志（应为 0）
- 已知问题清单（如有）

**强制约束**：
- 任何测试失败、构建失败、控制台错误，必须**直接判定任务未完成**返回给 R1
- 验证命令必须串行执行，不并行 `mvn test` 与 `mvn clean compile`
- 不得用"应该通过"代替实际运行

### 2.8 R8 Doc Synchronizer 文档同步者

**职责**：同步所有真源文档与摘要文档，确保**文档与代码事实一致**。

**边界**：
- ✅ 可以：编辑 [`FalconX 统一接口文档`](../api/FalconX统一接口文档.md)、[`Kafka 事件规范`](../event/Kafka事件规范.md)、[`状态机规范`](../domain/状态机规范.md)、[`数据库设计`](../database/falconx一期数据库设计.md)、[`当前开发计划`](../setup/当前开发计划.md)、[`统一问题清单`](./统一问题清单.md)、各服务 README、`docs/README.md`
- ❌ 不可以：修改 R2 主导的正式契约文档（除了汇总性质的更新），修改业务代码，自行决定阶段结论

**必读**：本文件、[`AI协作与提示规范`](./AI协作与提示规范.md) §11 文档同步规则、[`完成定义`](./完成定义.md)

**输出物**：文档 diff 清单、链接核对结果、与代码事实一致性证据

**强制约束**：
- 真源文档优先级：先改对应专题真源，再改摘要文档
- 链接完整性：所有相对链接必须可达
- 与代码事实交叉核对：实际接口路径、payload 字段、状态枚举值必须与文档一致

### 2.9 R9 Admin Backend Implementer 管理端后端实施者

**职责**：实现 `falconx-console-service` 管理端后端能力，包括管理端 API、RBAC 权限点、跨业务 schema 只读查询、internal RPC 调用、审计日志和高风险操作控制。

**边界**：
- ✅ 可以：编辑 `falconx-console-service` 代码、Mapper/XML、Repository、ApplicationService、Controller、配置、迁移和测试；新增或调整管理端 RBAC 权限点；通过业务服务 internal API 执行管理操作；只读查询跨业务 schema 中已授权的数据
- ❌ 不可以：写业务服务代码（identity/market/trading-core/wallet）、跨服务直接写业务表、修改 R2 冻结的契约、写任何前端代码、绕过 gateway/internal RPC 私自调用业务服务内部实现

**必读**：本文件、R2 管理端契约 spec、R6 测试用例骨架、[`管理端架构`](../architecture/管理端架构.md)、对应 Skill、[`完成定义`](./完成定义.md)

**输出物**：管理端后端代码 diff、RBAC 权限点清单、审计日志埋点清单、跨服务 internal RPC 调用清单、错误码翻译清单、测试通过证据。

**强制约束**：
- console 可跨业务 schema 只读查询，但不得 INSERT / UPDATE / DELETE 业务表
- 所有管理端 API 必须埋设 `@RequiresPermission` 或等价权限点
- 高风险操作必须写 `t_admin_operation_log`
- 调用业务服务只能走 R2 冻结的 internal RPC / gateway internal route，不得跨服务直接调用业务实现类
- 返回给 R10 的 DTO、错误码和权限点必须与 R2 契约一致
- 测试必须真跑，不接受"应该通过"

### 2.10 R10 Admin Frontend Implementer 管理端前端实施者

**职责**：实现 `falconx-console-frontend` 管理端页面、组件、路由、状态管理、API client、按钮级 RBAC、高风险二次确认和管理端浏览器 QA。

**边界**：
- ✅ 可以：编辑 `falconx-console-frontend/src/` 下的管理端前端代码、组件、feature、API client、路由、样式和测试；按 R3 设计落地按钮权限、空态、错误态、loading 态和高风险确认态
- ❌ 不可以：写客户端前端代码（`falconx-frontend`，属于 R5）、写任何后端代码、修改 R2 契约、修改 R3 设计、引入未批准的 UI 框架或设计系统、用本地 mock 伪装后端能力已完成

**必读**：本文件、R2 管理端 API 契约、R3 管理端设计方案、R6 测试用例骨架、[`管理端架构`](../architecture/管理端架构.md)、[`安全规范`](../security/安全规范.md)、Skill 14-15。

**输出物**：管理端前端代码 diff、按钮权限点清单、API client 对接清单、错误态 / 无权限态 / 二次确认态清单、`npm run test` / `npm run lint` / `npm run build` 通过证据、桌面 + 移动端浏览器 QA 截图。

**强制约束**：
- 必须实现按钮级 RBAC 控制和无权限态
- 高风险操作必须二次确认
- 不得在控制台、日志、截图或错误详情输出 token
- 不得硬编码 owner 数据
- 不得绕过 R9/R2 契约直接拼接未冻结 API 或权限码
- 后端尚未提供的管理能力只能显示禁用态 / 空态 / 未接入状态，不得 mock 成真实路径
- 必须真实浏览器 QA，不接受"应该可以"

---

## §3. 角色边界控制（防越界机制）

每个角色在执行前**必须**进行自检（Self-Check），自检失败即视为越界，立即停止并将任务返回给 R1。

### 3.1 边界自检卡（每个角色执行前必读）

```
我当前的角色是：[R1 / R2 / R3 / R4 / R5 / R6 / R7 / R8 / R9 / R10]

我即将做的事是：________________________

边界自检：
[ ] 这件事在我的"✅ 可以"列表内吗？
[ ] 这件事不在我的"❌ 不可以"列表内吗？
[ ] 我已读取必读文档？
[ ] 我已限定修改文件范围？
[ ] 我已说明关键假设？
[ ] 若涉及管理端，我已确认 R9/R10 端别边界和 RBAC / 审计 / 权限态要求？

任意一项不满足 → 停止，返回 R1 重新派发或确认
```

### 3.2 跨角色 handoff 协议

角色之间的输出物必须按以下协议交接：

| 上游角色 | 必须输出 | 下游角色 | 接收检查 |
| --- | --- | --- | --- |
| R2 → R3 | 契约 spec、字段定义、错误码 | R3 | 是否包含设计所需所有字段 |
| R2 → R4 | 同上 | R4 | 是否包含实现所需所有契约 |
| R2 → R5 | 同上 | R5 | 是否包含前端对接所需所有字段 |
| R2 → R6 | 同上 | R6 | 是否覆盖所有需要测试的字段 |
| R2 → R9 | 同上（含管理端 API / internal RPC / RBAC 权限点） | R9 | 是否包含管理端后端实现所需所有契约 |
| R2 → R10 | 同上（含管理端 API / 错误码 / 权限点） | R10 | 是否包含管理端前端对接所需所有字段 |
| R3 → R5 | 设计方案、组件状态、响应式规则 | R5 | 是否包含所有交互状态 |
| R3 → R10 | 管理端设计方案、菜单结构、按钮权限点、响应式规则 | R10 | 是否包含所有管理端交互状态 |
| R6 → R4 | 后端测试用例骨架 | R4 | 用例是否可执行（编译通过） |
| R6 → R5 | 前端测试用例骨架 | R5 | 用例是否可执行 |
| R6 → R9 | 管理端后端测试用例骨架 | R9 | 用例是否可执行（编译通过） |
| R6 → R10 | 管理端前端测试用例骨架 | R10 | 用例是否可执行 |
| R4 → R7 | 后端实现 + 测试通过证据 | R7 | 测试输出是否完整 |
| R5 → R7 | 前端实现 + 浏览器 QA | R7 | 截图是否覆盖桌面 + 移动 |
| R9 → R10 | 管理端后端实际 API / DTO / 错误码 / RBAC 权限点 | R10 | API client、按钮权限和错误态是否与后端实现一致 |
| R9 → R7 | 管理端后端实现 + 测试通过证据 | R7 | 测试输出与审计/RBAC 覆盖是否完整 |
| R10 → R7 | 管理端前端实现 + 浏览器 QA | R7 | 截图是否覆盖桌面 + 移动，权限态是否验证 |
| R7 → R8 | 验证报告 | R8 | 验证范围是否清晰 |
| R8 → R1 | 文档同步证据 | R1 | 文档与代码是否一致 |

### 3.3 越界检测红旗清单

下列行为视为**红旗（强制停止）**：

- ❌ R3 / R4 / R5 / R6 / R9 / R10 自行修改 R2 已冻结的契约
- ❌ R4 / R9 写前端代码，或 R5 / R10 写后端代码
- ❌ R5 写管理端前端代码，或 R10 写客户端前端代码（端别越界）
- ❌ R4 写管理端后端代码，或 R9 写业务后端代码（端别越界）
- ❌ R9 绕过 internal RPC / gateway 直接写业务服务 owner 表
- ❌ R10 用本地 mock、硬编码权限码或静态 owner 数据伪装管理端能力已完成
- ❌ R7 修改代码（应只报告）
- ❌ R8 修改业务代码或决定阶段结论
- ❌ 任何角色绕过 R2 直接改 API/DB/Kafka/状态机
- ❌ R1 直接写业务代码而不派发 R2-R10
- ❌ 任意角色跳过自己的"必读"清单

发现红旗 → 立即停止当前操作 → 返回 R1 → R1 重新评估任务边界

---

## §4. 任务生命周期

### 4.1 任务类型与角色路由（2026-05-08 三端协作扩展）

| 任务类型 | 必入角色 | 推荐顺序 |
| --- | --- | --- |
| 纯文档修订 | R1 → R8 | R1 接受任务 → R8 修改 → R1 验证 commit |
| 纯运维 / 内部维护（无客户端 + 管理端入口） | R1 → R2 → R6 → R4 → R7 → R8 | 契约冻结 → 测试设计 → 实现 → QA → 文档 |
| **三端业务功能（默认）** | R1 → R2 → R3 → R6 → (R4 ∥ R5 ∥ R9 ∥ R10) → R7 → R8 | 契约 → 双端设计 → 测试设计 → 三端实现 → QA → 文档；Claude 可并行 R4/R5/R9/R10，Codex 串行 R4 → R5 → R9 → R10 |
| 客户端纯实现（已有契约 + 已有管理端） | R1 → R3 → R6 → R5 → R7 → R8 | 设计 → 测试 → 实现 → QA → 文档 |
| 管理端纯实现（已有客户端 + 后端） | R1 → R3 → R6 → (R9 ∥ R10) → R7 → R8 | 管理端设计 → 测试 → 实现 → QA → 文档 |
| Bug 修复 | R1 → R6 → R4/R5/R9/R10 → R7 → R8 | 复现测试 → 修复 → 验证 → 文档 |
| 重构（不改契约） | R1 → R6 → R4/R5/R9/R10 → R7 → R8 | 行为测试 → 重构 → 验证 → 文档 |
| 阶段 0 基础设施（CROSS 回滚 / 角色扩展 / 文档归档） | R1 → R8 | 豁免三端要求 |
| 阶段 1 管理端地基（console 骨架 + RBAC） | R1 → R2 → R3 → R6 → (R9 ∥ R10) → R7 → R8 | 单端任务，豁免客户端要求 |

### 4.2 任务启动（R1 主导）

1. **读取启动协议**：[`.agents/session-bootstrap.md`](../../.agents/session-bootstrap.md)
2. **判断任务类型**（见 §4.1）
3. **判断角色路由**：哪些角色介入、按什么顺序
4. **判断是否需要 Superpowers 门禁**：
   - 新功能 / 行为变化 → `superpowers:brainstorming`
   - 多步骤实施 → `superpowers:writing-plans`
   - 计划拆给 subagent → Claude Code 可用 `superpowers:subagent-driven-development`；Codex 改为同会话串行执行
   - bug / 失败 → `superpowers:systematic-debugging`
   - 完成前声明 → `superpowers:verification-before-completion`
5. **检查工作区**：`git status` 识别无关改动并隔离
6. **派发任务**：按角色模板填写并派发

### 4.3 任务派发（R1 → 各角色）

每个角色任务必须包含（来自 [`.agents/templates/`](../../.agents/templates/) 的模板）：

- 任务编号 + 任务显示名称
- 当前阶段口径
- 角色 ID（R1-R10，按任务类型筛选介入角色）
- 必读文档清单
- 允许修改文件清单
- 禁止修改文件清单
- 实施步骤
- 验证命令
- 必须返回的交付清单
- 边界自检卡（见 §3.1）

### 4.4 任务回收（各角色 → R1）

R1 回收每个角色结果时必须检查：

- 是否只修改了授权文件
- 是否越过角色边界
- 是否产生未说明的测试或文档缺口
- 是否有编译/测试/日志/SQL/并发/幂等风险
- 是否触及未确认的契约变更

**任意一项不满足 → 任务返回原角色重做**。

### 4.5 完成判定（详见 §5）

R1 完成最终验证后，**任意一项不满足均不得标记完成**：

- ✅ 所有介入角色均已完成自身交付
- ✅ R7 验证报告显示测试全通过、构建通过、浏览器 QA 通过
- ✅ R8 文档同步完成，链接完整性检查通过
- ✅ 涉及客户端可见业务功能时，客户端 + 后端服务 + 管理端代码、测试、浏览器截图齐全
- ✅ 形成单一 Git commit（含代码 + 测试 + 文档）

---

## §5. 完成判定（硬约束）

按 [`完成定义`](./完成定义.md) §2 共通要求 + §2.1 代码任务附加条件 + §3 阶段任务条件，本工作模式额外强制：

### 5.1 三端业务功能完成条件（强约束）

若任务输出影响客户端可见行为（含新增/修改 REST 接口、WebSocket 消息体、推送字段、错误码、用户可触达功能），必须按 [`完成定义`](./完成定义.md) §4.A 满足客户端 + 后端服务 + 管理端三端齐全，**任意一项未完成均不得标记完成**：

- ✅ R2 输出的契约已被用户确认
- ✅ R3 输出的客户端 + 管理端双端设计方案已被 R1 接收
- ✅ R6 测试用例先于实现写出（含三端 E2E）
- ✅ R4 后端实现 + 单元测试 + 集成测试通过
- ✅ R5 客户端前端实现 + `npm run test` + `npm run lint` + `npm run build` 通过
- ✅ R5 客户端浏览器桌面 + 移动端 QA 截图齐全
- ✅ R9 管理端后端实现 + 测试通过
- ✅ R10 管理端前端实现 + `npm run test` + `npm run lint` + `npm run build` 通过
- ✅ R10 管理端浏览器桌面 + 移动端 QA 截图齐全
- ✅ 至少一条 E2E 测试覆盖客户端调用 → 后端 → 管理端可见整链
- ✅ R8 完成文档同步：[`FalconX 统一接口文档`](../api/FalconX统一接口文档.md) + 涉及的专题正式规范
- ✅ R7 最终验证报告完整
- ✅ 形成单一 Git commit（含三端代码 + 文档 + 测试）

**例外**：纯运维 / 内部维护任务若无客户端与管理端入口，由 R1 在任务启动时显式声明豁免，并写入任务工单。阶段 0 基础设施与阶段 1 管理端地基按 §4.1 的例外条款处理。R1 不得任意豁免有客户端入口的业务功能的管理端要求。

### 5.2 4 条 Karpathy 准则的强制检查（每次完成前）

完成前必须自检 §1.2 四条准则是否被遵守。任意违反即视为完成失败：

- [ ] 编码前已陈述假设，未默默选择解读
- [ ] 实现是问题所需的最小代码，无未要求的功能/抽象/灵活性
- [ ] 每行改动都能追溯到用户请求，无顺手重构/格式 churn
- [ ] 已转换为可验证目标，并执行了验证

### 5.3 Git 回滚点

每轮交付必须形成单一 Git commit，commit message 必须：

- 描述本轮修改内容（不只是任务编号）
- 列出涉及的角色（如 `R2 R3 R4 R5 R6 R7 R8 R9 R10`）
- 附验证证据摘要（如"6 个集成测试通过"）
- 末尾标注 Co-Authored-By（按现有约定）

---

## §6. Superpowers 门禁路由

任务匹配到 Superpowers skill 时，对应阶段必须先使用对应 skill：

| 任务阶段 | 必须使用 |
| --- | --- |
| 新功能、行为变化、协作模式变化（R1 启动阶段） | `superpowers:brainstorming` |
| 已有规格，需要拆成实施计划（R1 拆分阶段） | `superpowers:writing-plans` |
| 按计划由 subagent 分任务实施（R1 派发阶段） | Claude Code 用 `superpowers:subagent-driven-development`；Codex 串行执行同一计划 |
| 多 agent 并行派发（R1 派发阶段） | Claude Code 用 `superpowers:dispatching-parallel-agents`；Codex 禁用并行，改为 R4 → R5 → R9 → R10 |
| 按计划在当前会话串行实施 | `superpowers:executing-plans` |
| bug、测试失败、异常行为（R6/R7 阶段） | `superpowers:systematic-debugging` |
| 完成前准备声明通过（R7 阶段，**强制**） | `superpowers:verification-before-completion` |
| 大改完成后请求审查 | `superpowers:requesting-code-review` |
| 收尾、合并前判断 | `superpowers:finishing-a-development-branch` |

裁剪规则：

- Superpowers 不得覆盖 FalconX 正式规范
- 若 Superpowers 步骤与 FalconX 分支/提交/文档/确认规则冲突，按 FalconX 规则执行，并在结论中说明裁剪点
- 完成声明必须有当前会话的新鲜验证证据

---

## §7. gstack 路由

gstack 是工具层，不替代 FalconX 完成定义。

| 目标 | gstack | 由哪个角色使用 |
| --- | --- | --- |
| 复杂计划多视角审查 | `autoplan` | R1 |
| 计划专项审查 | `plan-ceo-review` / `plan-eng-review` / `plan-design-review` / `plan-devex-review` | R1 |
| 根因调查 | `investigate` | R7 |
| QA 报告 | `qa-only` | R7 |
| QA 并修复 | `qa` | R7 → 转 R4/R5/R9/R10 修复 |
| 提交前结构性审查 | `review` | R1 |
| 用户要求 push / PR / 发布 | `ship` | R1（仅用户明确要求时） |
| 长任务保存 / 恢复 | `context-save` / `context-restore` | R1 |
| 阶段质量盘点 | `health` | R1 |

强制边界：

- gstack 输出必须由 R1 回到 FalconX 正式规范核对
- gstack 不能替代 [`SKILLS.md`](../../SKILLS.md)、[`完成定义`](./完成定义.md)、专题正式规范
- gstack `ship` 只在用户明确要求远程动作时使用

---

## §8. 工具适配（Claude Code vs Codex）

核心规则一致，**执行方式按工具差异**：

- Claude Code 用户：详见 [`AI工作模式-Claude适配`](./AI工作模式-Claude适配.md)
- Codex 用户：详见 [`AI工作模式-Codex适配`](./AI工作模式-Codex适配.md)

无论使用哪种工具，本文件的角色定义、流程、约束、完成判定**强制一致**。

---

## §9. 与现有文档的关系

| 文档 | 关系 |
| --- | --- |
| `AGENTS.md` | 仓库级红线，优先级高于本文件；本文件由 AGENTS.md 在 §0 引用 |
| `SKILLS.md` | 编码任务步骤模板，被本文件 R4/R5/R9/R10 引用 |
| `docs/process/AI协作与提示规范.md` | 文档真源治理；本文件不重复其内容 |
| `docs/process/Karpathy式AI编码行为准则.md` | 4 条准则的详细行为细则；本文件 §1.2 引用 |
| `docs/process/全栈Figma协作流程.md` | 三端协作、Figma 与前端落地的具体流程；本文件 R3/R5/R10 引用 |
| `docs/process/完成定义.md` | 完成判定基线；本文件 §5 在其基础上加强 |
| `docs/setup/当前开发计划.md` | 当前阶段状态真源；本文件不替代 |
| ~~`docs/process/AI协作模式.md`~~ | 已删除，内容并入本文件 |
| ~~`.agents/workflows/subagent-superpowers-gstack.md`~~ | 已删除，内容并入本文件 |
| ~~`.agents/templates/subagent-task.md`~~ | 已删除，由 10 个角色模板替代 |

---

## §10. 一句话执行原则

**先按 R1 拆分角色路由，每个角色严格执行边界自检；契约先于实现，三端一体交付；4 条 Karpathy 准则永久生效；任意一项未完成均不得标记完成。**
