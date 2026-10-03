# 任务卡：Symbol 三表管理端完整化

## 任务概览

- 任务编号：`STAGE-2-SYMBOL-THREE-TABLE-ADMIN`
- 任务显示名称：Symbol 三表管理端完整化
- 当前阶段口径：BBook V2 阶段 2.3 行情品种管理补充收口
- 任务来源：用户要求在管理后台把 Symbol 相关功能先实现完毕，覆盖 `t_symbol`、`t_symbol_group_visibility`、`t_symbol_quote_mapping`
- 任务类型：管理端纯实现 + 业务后端 internal RPC 补齐
- 角色路由：`R1 -> R2 -> R3 -> R6 -> R4 -> R9 -> R10 -> R7 -> R8 -> R1`
- 端别覆盖：业务后端服务 `market-service`、管理端后端 `console-service`、管理端前端 `console-frontend`
- 客户端说明：`falconx-frontend` 不新增页面；必须通过 R7 验证现有客户端 Symbol 列表和报价订阅能读取本任务配置结果

## 背景与边界

阶段 2.3 `STAGE-2-SYMBOL` 已完成 R7 收口，覆盖 `t_symbol` 基础字段编辑、暂停/恢复、Swap Rate 和 Trading Hours 只读查看。本任务不推翻该完成结论，而是在此基础上补齐 Symbol 管理后台对三张核心表的完整运营能力。

三表关系必须按下列口径实现：

- `t_symbol` 是上游 LP 源 symbol 主表，保存 LP 源 symbol 的基础配置和状态。
- `t_symbol_quote_mapping` 是 FalconX 平台展示和交易 symbol 列表；页面展示 `platform_symbol`，后端向 LP 订阅 `source_symbol`。
- `t_symbol_group_visibility` 是用户组可见性配置；当前用户组来自 gateway 透传的 `X-User-Group-Code`，market-service 只返回该组可见的 `platform_symbol`。
- 管理端接口不按 `.p / .c / .f` 或其他后缀做特殊限制；所有 Symbol 统一以数据库三表记录、启停状态、映射配置和用户组可见性为准。

## 必须读取

- `AGENTS.md`
- `SKILLS.md` Skill 1、Skill 4、Skill 10、Skill 11、Skill 12、Skill 16、Skill 17
- `docs/process/AI工作模式.md`
- `docs/process/AI工作模式-Claude适配.md` 或 `docs/process/AI工作模式-Codex适配.md`
- `docs/process/完成定义.md`
- `docs/process/BBook一期完成执行路径.md` §5.3
- `docs/setup/当前开发计划.md` 中阶段 2.3
- `docs/database/falconx一期数据库设计.md` 中 market owner 和 Symbol 三表说明
- `docs/api/管理端接口规范.md` §6
- `docs/api/REST接口规范.md` 行情查询语义
- `docs/api/WebSocket接口规范.md` 行情订阅语义
- `docs/event/Kafka事件规范.md` 行情事件 symbol 语义
- `docs/market/LP自建行情源接入契约.md`
- `docs/test/STAGE-2-SYMBOL-test-cases.md`

## R2 契约冻结要求

R2 必须先冻结以下契约，并取得用户确认后再进入实现：

1. `t_symbol` 管理范围
   - 保留已完成的列表、详情、编辑、暂停、恢复能力。
   - 明确是否允许新增 LP 源 symbol。默认不允许管理端手工新增 LP 源 symbol，只允许从 LP 快照导入或恢复已存在源 symbol。
   - 明确是否允许硬删除。默认不允许硬删除，只允许暂停或禁用相关映射。

2. `t_symbol_quote_mapping` 管理范围
   - 列表：按 `platformSymbol / sourceSymbol / enabled / lpSubscribeEnabled / sourceProvider` 查询。
   - 详情：展示 platform symbol、source symbol、乘数、Bid/Ask 加点、是否启用、是否订阅 LP、最近更新时间、源 symbol 状态。
   - 新建：创建平台展示 symbol 到 LP 源 symbol 的映射。
   - 编辑：修改 `source_symbol / price_multiplier / bid_adjustment / ask_adjustment / enabled / lp_subscribe_enabled`。
   - 停用/启用：高风险操作，必须填写原因。
  - 校验：`source_symbol` 必须存在于 `t_symbol`；`platform_symbol` 唯一；接口不得按后缀额外拒绝合法数据库记录。`source_symbol` 当前是否实际进入 LP 订阅由运行时查询 `t_symbol.status=1 AND mapping.enabled=1 AND lp_subscribe_enabled=1` 决定，管理端写入接口不因 source 当前 suspended 而拒绝保存。

3. `t_symbol_group_visibility` 管理范围
   - 列表：按 `groupCode / platformSymbol / visible` 查询。
   - 单项设置：为某用户组设置某平台 symbol 可见或隐藏。
   - 批量设置：为某用户组批量覆盖可见 symbol 集合。
   - 校验：目标 symbol 必须存在于 `t_symbol_quote_mapping.platform_symbol`；默认组 `default` 不允许删除；隐藏后对客户端查询按不可见处理。

4. 热刷新要求
   - 修改 mapping 或 visibility 后，market-service 运行时白名单、用户组可见 symbol 查询、LP 订阅关系和平台 symbol 推送映射必须热刷新。
   - 不允许要求重启 market-service 才生效。
   - 若 LP 订阅增删无法做到即时重订阅，R2 必须冻结明确的刷新机制、延迟窗口和验证方式。

5. 权限点和审计
   - 新增或复用 RBAC 权限点必须在 `symbol` 模块下，按钮级渲染必须覆盖。
   - 所有会影响客户端可见性、报价源映射或 LP 订阅关系的操作都是 `HIGH_RISK`。
   - 高风险操作必须写入 `t_admin_operation_log`，包含操作人、目标 symbol、前后差异和 reason。

6. 错误码
   - 继续使用阶段 2.3 `90600-90649` 段；若不够，R2 必须先重排并同步 `管理端接口规范`。
   - 禁止复用 `90701-90703` internal token 错误码。

## R3 管理端设计要求

R3 必须输出管理端页面设计方案，至少包含：

- Symbol 详情页增加三个 Tab：
  - `基础配置`：现有 `t_symbol` 字段。
  - `报价映射`：`t_symbol_quote_mapping` 当前映射与编辑入口。
  - `用户组可见性`：`t_symbol_group_visibility` 可见性矩阵或列表。
- 独立的报价映射列表页或 Drawer：
  - 支持 platform/source 搜索、enabled、lpSubscribeEnabled 筛选。
  - 新建/编辑 mapping 使用二次确认。
- 用户组可见性批量编辑：
  - 支持按 groupCode 查看可见 symbol。
  - 批量变更前展示新增、隐藏数量和高风险确认。
- 所有高风险 Modal 必须包含 reason 输入、影响说明、确认按钮禁用态和成功/失败反馈。
- 移动端可降级为列表 + Drawer，不要求大矩阵横向完整展示。

## R6 测试设计要求

R6 必须在实现前新增或扩展测试用例清单。建议编号：

- `TC-CONSOLE-380 ~ 399`：console-service mapping / visibility REST。
- `TC-MARKET-220 ~ 239`：market-service internal RPC。
- `FE-CONSOLE-280 ~ 309`：console-frontend 三表管理页面。
- `TC-E2E-CONSOLE-004`：新增 platform symbol mapping 后，客户端 Symbol 列表显示 platform symbol，LP 订阅使用 source symbol，WebSocket 推送映射回 platform symbol。
- `TC-E2E-CONSOLE-005`：隐藏某 group 的 platform symbol 后，该 group 客户端列表和订阅均不可见，其他 group 不受影响。

最低测试必须覆盖：

- `.p / .c / .f` 等带后缀命名不被接口特殊拒绝；只要数据库状态、mapping 和 visibility 合法即可写入和生效。
- `source_symbol` 不存在时写入失败；`source_symbol` suspended 时允许保存配置，但不会进入运行时 LP 订阅白名单。
- `platform_symbol` 重复失败。
- mapping 修改后 Redis 最新价 / WebSocket 推送使用 platform symbol。
- visibility 修改后 `/api/v1/market/symbols` 即时体现。
- 高风险操作审计等级为 `HIGH_RISK`。
- 权限缺失时按钮隐藏，接口返回 403。

## R4 market-service 实现范围

允许修改：

- `falconx-market-service/src/main/java/com/falconx/market/**`
- `falconx-market-service/src/main/resources/mapper/**`
- `falconx-market-service/src/test/**`
- 必要的 market-service 文档同步片段

必须实现：

- 三表 owner 查询和写入通过 MyBatis Mapper + XML + Repository 链路完成。
- 新增 internal RPC，供 console-service 管理 mapping 和 visibility。
- 写操作后触发运行时热刷新：
  - LP 订阅白名单。
  - source symbol 到 platform symbol 的推送映射。
  - 用户组可见 symbol 查询结果。
  - 必要的 Redis 快照。
- 不得跨服务读取 identity 用户表；groupCode 只作为字符串配置处理。
- 不得在运行时代码硬编码 symbol 列表、group 列表或 mapping 列表。

## R9 console-service 实现范围

允许修改：

- `falconx-console-service/src/main/java/com/falconx/console/**`
- `falconx-console-service/src/test/**`
- 管理端接口文档相关片段

必须实现：

- console REST 入口转发到 market internal RPC。
- DTO 中所有雪花 ID 必须按字符串序列化。
- 新增或复用 `symbol:*` 权限点。
- mapping / visibility 写操作必须接入高风险审计 AOP。
- 对 market internal RPC 错误码做 1:1 翻译，不吞错、不改语义。

## R10 console-frontend 实现范围

允许修改：

- `falconx-console-frontend/src/features/symbol/**`
- `falconx-console-frontend/src/lib/**` 中本任务所需 API client 类型
- `falconx-console-frontend/src/test/**`

必须实现：

- `t_symbol` 基础配置、报价映射、用户组可见性三类能力在管理后台可操作。
- 按钮级 RBAC：无权限隐藏新建、编辑、启用/停用、批量可见性操作。
- 所有高风险操作 reason 不足 10 字符时按钮禁用。
- 接口错误码按 R2 契约展示到 toast 或表单错误。
- 列表、Drawer、Modal 必须覆盖 loading、empty、error、disabled、success 状态。

## R7 验证要求

R7 必须串行执行并归档结果：

- market-service 相关 Maven 测试。
- console-service 相关 Maven 测试。
- console-frontend `npm run test`、`npm run lint`、`npm run build`。
- 浏览器 QA：
  - 桌面：Symbol 详情三 Tab、mapping 新建/编辑、visibility 批量编辑。
  - 移动：mapping 列表和 visibility 编辑降级视图。
  - DevTools：`runtimeErrors=0`、`networkFailures=0`、相关接口无 5xx。
- 客户端回归：
  - 修改 visibility 后刷新 `falconx-frontend`，列表按当前 group 生效。
  - 修改 mapping 后 Tick/报价推送展示 platform symbol，source symbol 不泄露给页面。

## R8 文档同步要求

必须同步：

- `docs/api/管理端接口规范.md`
- `docs/api/REST接口规范.md`
- `docs/api/WebSocket接口规范.md`
- `docs/api/FalconX统一接口文档.md`
- `docs/database/falconx一期数据库设计.md`
- `docs/market/LP自建行情源接入契约.md`
- `docs/test/STAGE-2-SYMBOL-test-cases.md`
- `docs/setup/当前开发计划.md`
- `docs/process/BBook一期完成执行路径.md`

## 禁止事项

- 禁止把 `t_symbol_group_visibility` 当作 LP 订阅源；LP 订阅源只来自启用的 `t_symbol_quote_mapping.source_symbol`。
- 禁止把 `t_symbol.symbol` 直接当作页面展示列表的唯一来源；页面展示列表来自 `t_symbol_quote_mapping.platform_symbol` + group visibility。
- 禁止通过前端本地过滤代替后端 owner 查询。
- 禁止在接口或前端按 `.p / .c / .f` 后缀写特殊拦截逻辑；Symbol 是否可见、可订阅、可报价统一由数据库配置和状态决定。
- 禁止硬删除已有历史交易可能引用的 platform symbol；如需删除，R2 必须先冻结历史订单、持仓、K线、报价历史兼容规则并经用户确认。

## 完成判定

本任务满足以下条件才可标记完成：

- [x] R2 契约已确认（commit `663ac48`：管理端接口规范 §6 三表 console + market internal 端点 + 错误码段 `90600-90649`）
- [x] R3 管理端设计已交付（5 页面方案 + 三 Tab 信息架构）
- [x] R6 测试用例已先于实现写出（[`STAGE-2-SYMBOL-THREE-TABLE-ADMIN-test-cases.md`](../../test/STAGE-2-SYMBOL-THREE-TABLE-ADMIN-test-cases.md) 11 TC）
- [x] R4/R9/R10 实现完成并通过各自测试（commit `8bce89f`；market `MarketSymbolAdminApplicationServiceMappingTests` 3/3、console `HighRiskPermissionRegistryTests` 3/3、console-frontend 三件套全通过）
- [x] R7 API、浏览器、客户端回归验证通过（详见 [`STAGE-2-SYMBOL-THREE-TABLE-ADMIN-R7-verification-report`](../../test/archive/STAGE-2-SYMBOL-THREE-TABLE-ADMIN-R7-verification-report.md)：API live 17/17 + 12 张截图 + DevTools 0 错误 + 客户端 group filter `default=1571 / vip=0` + 字段无 `sourceSymbol` 泄露）
- [x] R8 文档同步完成（R7 验证报告 + BBook 执行路径 §5.3 + 本任务卡）
- [x] 形成单一 Git commit（R7 + R8 收口 commit）

**当前状态**：✅ 已完成（2026-05-11，R7 二轮收口 + R8 同步 commit）
