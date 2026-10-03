# FalconX 全栈与 Figma 协作流程

## 1. 目的

本文件把 FalconX 现有后端规范流程、前端设计流程、Figma MCP、`figma-implement-design`、gstack 设计审查与浏览器 QA 固化为一条可复用的全栈开发路径。

新会话处理全栈任务时，必须先读 `AGENTS.md` 和 `SKILLS.md`，再按本文件把后端能力设计、前端页面设计、Figma 定稿、React 实现、接口对接和验证串起来执行。

## 2. 适用范围

适用于：

- 新增或修改一个业务功能，并需要前端页面承载。
- 根据 Figma 设计实现 `falconx-frontend` 页面或组件。
- 后端 REST / WebSocket / 事件能力完成后，需要前端对接。
- 需要同时验证后端行为、前端交互、响应式和浏览器运行状态。

不适用于：

- 纯后端内部重构。
- 纯文案或 typo 修订。
- 只在 Figma 内编辑设计稿且不落代码的任务。

## 3. 文档与契约优先级

前端和 Figma 不改变 FalconX 后端正式边界。

当出现冲突时，按下列顺序处理：

1. `AGENTS.md`、`SKILLS.md`、`docs/process/完成定义.md`
2. 后端正式规范：架构、REST、WebSocket、安全、Kafka、状态机、事务、数据库、日志、测试
3. `docs/api/FalconX统一接口文档.md`
4. 本文件
5. `docs/design/falconx-frontend-react-implementation-spec.md`
6. Figma 截图、Figma 节点、gstack 设计输出、页面草图

Figma 是视觉、布局、组件状态、动效和交互的真源；接口路径、字段、错误码、状态机、数据库和事件 payload 仍以后端正式规范为真源。

## 4. 默认全栈流程

### 4.1 启动与拆分

1. 读取 `SKILLS.md`。
2. 判断任务涉及的后端 Skill：REST、WebSocket、Kafka、DB、Redis、状态机、测试、接口文档等。
3. 判断任务涉及的前端 Skill：前端设计、Figma 落地、接口对接、前端 QA。
4. 明确本轮任务显示名称、写入范围、禁止事项、验证命令和回滚点要求。
5. 若用户明确要求多智能体，Commander 才能派发 subagent；每个 subagent 必须有固定读写范围，禁止自行拍板契约或设计方向。

### 4.2 后端能力先冻结

涉及接口或实时能力时，先完成后端边界冻结：

1. 明确 owner 服务、数据来源、认证方式、状态机影响和幂等要求。
2. 若需要新增或修改 `API / DB / Kafka / WebSocket / 错误码 / 状态机 / owner`，必须先向用户确认。
3. 后端实现必须按对应 Skill 执行。
4. 后端完成后同步 `docs/api/FalconX统一接口文档.md`；涉及 Kafka、DB、状态机时同步对应正式规范。
5. 前端只能基于已冻结文档和实际后端代码对接，不得先虚构生产接口。

### 4.3 前端设计路径

默认设计路径：

```text
design-consultation
  -> design-shotgun
  -> 用户确认方向
  -> Figma 定稿或用户提供 Figma 节点
  -> figma-implement-design
  -> design-review + browse/qa
```

执行规则：

- `design-consultation` 用于明确产品目标、用户流程、信息层级、视觉方向和动效口径。
- `design-shotgun` 用于生成多套方向供用户选择。
- 用户确认方向后，进入 Figma 定稿；可以由用户在 Figma 中设计，也可以先由 AI 生成方案再转入 Figma。
- 用户提供 Figma URL 时，必须指向具体 Frame / Node，而不是只给文件首页。
- 没有 Figma URL 但用户在 Figma Desktop 中选中了节点时，必须确认当前选中节点就是实现目标。

### 4.4 Figma 到 React 实现路径

使用 `figma-implement-design` 时必须按顺序执行：

1. 提取或确认 `fileKey` 和 `nodeId`。
2. 调用 Figma MCP `get_design_context` 获取结构化设计上下文。
3. 调用 Figma MCP `get_screenshot` 获取视觉对照图。
4. 若上下文过大，先用 `get_metadata` 获取节点结构，再按子节点分批读取。
5. 下载或引用 Figma 返回的必要资产；Figma 返回 `localhost` 资产地址时直接使用，不得用占位图替换。
6. 将 Figma 输出翻译到 `falconx-frontend` 现有模式：
   - React + TypeScript
   - `src/styles/tokens.css` 与 `src/styles/global.css`
   - `src/components/` 复用组件
   - `src/features/{domain}/` 业务页面、状态、接口适配和测试
   - React Query 处理服务端数据
   - Zustand 处理轻量客户端状态
7. 编码完成后用浏览器截图与 Figma 截图对照，至少覆盖桌面和移动端。

禁止：

- 直接把 Figma 生成的 Tailwind / 内联样式作为最终代码风格。
- 为一个页面新增平行 token、平行组件库或新的 UI 框架。
- 新增图标包。优先使用 Figma 资产；已有 lucide 图标能满足时可复用。
- 在没有后端契约的情况下把静态 mock 写成真实业务流。

## 5. 前端工程约定

### 5.1 目录

```text
falconx-frontend/
  src/components/     复用 UI / 品牌组件
  src/features/       按业务域组织页面、状态、接口适配与测试
  src/lib/            API 客户端、环境配置、通用基础设施
  src/providers/      应用级 Provider
  src/styles/         design tokens 与全局样式
```

### 5.2 Token 与样式

- 颜色、间距、圆角、语义状态必须优先使用 `src/styles/tokens.css`。
- 全局布局和页面级样式放在 `src/styles/global.css`，新增时保持命名清晰，不引入无关全局污染。
- Figma token 与项目 token 不一致时，先映射到项目 token；确实需要新增 token 时，说明新增语义和复用范围。
- 不允许使用负 letter spacing，不允许用纯装饰渐变球或无语义背景干扰交易终端阅读。

### 5.3 状态与接口

- REST 通过 `src/lib/api.ts` 与业务域 `marketApi.ts / authApi.ts` 等封装。
- WebSocket 必须封装在业务域 hook 或 service 中，不得在组件里散落连接逻辑。
- Refresh Token 是一次性轮换，必须使用单飞刷新。
- WebSocket token 放在 URL query 时，禁止输出完整 URL。
- 浏览器不得主动发送 `X-Trace-Id`。
- 产品元数据、可交易 symbol、行情状态必须来自后端 owner 数据，不得在生产路径硬编码。

## 6. 后端与前端对接规则

### 6.1 能力矩阵

每个全栈任务必须在计划中列出能力矩阵：

| 能力 | 后端真源 | 前端入口 | 状态 |
| --- | --- | --- | --- |
| REST 查询 / 写入 | 统一接口文档 + owner 服务 | `src/features/{domain}` | 已接入 / 待接入 / 禁用 |
| WebSocket 推送 | WebSocket 规范 + gateway | hook / store | 已接入 / 待接入 / 禁用 |
| 用户侧实时状态 | 后端 WebSocket 或事件链路 | 页面状态 | 已接入 / 待接入 / 禁用 |

后端未实现的能力，前端只能显示禁用、空态或占位，不得用轮询或 mock 冒充实时能力。

### 6.2 错误与限制

前端必须显式处理：

- 未登录
- Access Token 过期
- Refresh Token 过期或刷新失败
- 后端返回业务错误码
- WebSocket 断开、重连、鉴权失败
- 数据为空、产品不可交易、行情缺失、行情 stale

## 7. 验证要求

### 7.1 后端验证

按涉及服务串行执行 Maven 验证：

```bash
mvn -pl falconx-{service} -am test
mvn clean compile
```

涉及跨服务链路时补充 gateway E2E 或相应集成测试。不得并行运行会互相影响 `target/` 的命令。

### 7.2 前端验证

在 `falconx-frontend` 下串行执行：

```bash
npm run test
npm run lint
npm run build
```

浏览器验证必须覆盖：

- 登录 / 注册入口
- 目标页面核心路径
- loading / error / empty / disabled 状态
- 桌面视口
- 移动视口
- 控制台错误
- 网络请求是否命中预期后端

### 7.3 Figma 视觉验证

来自 Figma 的实现必须保留以下证据：

- Figma URL、fileKey、nodeId
- Figma screenshot
- 实现后的桌面截图
- 实现后的移动截图
- 已知偏差及原因

## 8. 交付清单

全栈任务最终结论必须包含：

- 后端修改范围与 owner 边界
- 前端修改范围与页面入口
- Figma 节点或设计来源
- 接口文档同步状态
- 验证命令和结果
- 浏览器 QA 结果
- 已知限制和禁用场景
- Git 回滚点

## 9. 新会话启动清单

每次新窗口处理全栈开发任务，按下面顺序执行：

1. 读取 `SKILLS.md`。
2. 读取本文件。
3. 读取本轮涉及的后端正式规范。
4. 读取本轮涉及的前端设计 / 实现规格。
5. 如果有 Figma URL，读取 `figma-implement-design` skill，并通过 Figma MCP 获取设计上下文和截图。
6. 如果没有 Figma URL，但任务需要设计，先执行 `design-consultation -> design-shotgun`。
7. 冻结后端契约。
8. 实现后端。
9. 同步接口文档。
10. 实现前端。
11. 联调并完成前后端验证。
12. 形成 Git 回滚点。
