# FalconX Agent Assets

本目录保存 FalconX 当前在用的 agentic 协作资产。它不是业务规范来源，不覆盖 [`AGENTS.md`](../AGENTS.md)、[`SKILLS.md`](../SKILLS.md)、[`AI工作模式`](../docs/process/AI工作模式.md)、[`AI协作与提示规范`](../docs/process/AI协作与提示规范.md) 或专题正式规范。

## 当前保留文件

| 路径 | 用途 |
| --- | --- |
| `session-bootstrap.md` | 会话启动协议（任何会话第一步必读） |
| `templates/role-commander.md` | R1 Commander 调度者任务模板 |
| `templates/role-contract-designer.md` | R2 Contract Designer 契约设计师任务模板 |
| `templates/role-uiux-designer.md` | R3 UI/UX Designer 设计师任务模板（含客户端 + 管理端双端） |
| `templates/role-backend-implementer.md` | R4 Business Backend Implementer 业务后端实施者 |
| `templates/role-frontend-implementer.md` | R5 Client Frontend Implementer 客户端前端实施者（falconx-frontend）|
| `templates/role-test-designer.md` | R6 Test Designer 测试设计者任务模板 |
| `templates/role-qa-verifier.md` | R7 QA Verifier QA 验证者任务模板 |
| `templates/role-doc-synchronizer.md` | R8 Doc Synchronizer 文档同步者任务模板 |
| `templates/role-admin-backend-implementer.md` | **R9 Admin Backend Implementer 管理端后端实施者**（falconx-console-service）|
| `templates/role-admin-frontend-implementer.md` | **R10 Admin Frontend Implementer 管理端前端实施者**（falconx-console-frontend）|
| `templates/fullstack-bundle.md` | 三端任务打包模板（涉及客户端可见行为时强制使用，含管理端联动）|

## 10 角色体系 + 任务路由

> 角色定义、职责边界、必入条件、任务类型路由表的**唯一真源**：[`AI工作模式 §2`](../docs/process/AI工作模式.md)（角色定义）+ [`§4.1`](../docs/process/AI工作模式.md)（任务路由）。本目录的 `templates/role-*.md` 是任务派发格式，不重复角色职责定义。

## gstack 路由

详见 [`AI工作模式`](../docs/process/AI工作模式.md) §7。

| 用户意图 | gstack 技能 | 由哪个角色使用 |
| --- | --- | --- |
| 审计划 / 多视角看计划 | `autoplan`、`plan-ceo-review`、`plan-eng-review` | R1 |
| 调查 bug / 失败根因 | `investigate` | R7 |
| QA 报告 | `qa-only` | R7 |
| QA 并修复 | `qa` | R7 → 转 R4/R5 修复 |
| 提交前 review | `review` | R1 |
| 保存 / 恢复上下文 | `context-save` / `context-restore` | R1 |
| 质量盘点 | `health` | R1 |
| push / PR / ship / deploy | `ship` | R1（仅用户明确要求时） |

gstack 输出必须由 R1 回到 FalconX 正式规范核对。

## gstack 使用方式

仓库当前不 vendoring gstack 二进制、`node_modules`、浏览器构建产物或本机绝对路径 symlink。调用方必须使用本机已安装的 gstack skill，并在执行前确认可用。

未来若把 gstack vendoring 到仓库，必须同时满足：

1. `.agents/skills/gstack/bin/` 可执行
2. `.agents/skills/gstack/SKILL.md` 存在
3. 所有引用路径可移植，不依赖 `/Users/<name>/...`
4. 不提交密钥、cookies、浏览器用户数据或本地分析数据

## 使用规则

1. R1 必须先读取仓库正式规范（按启动协议），再使用本目录模板
2. 每个角色任务必须固定任务编号、任务显示名称、读写边界、禁止事项、验证命令和返回清单；任务显示名称必须是本轮修改内容的概要描述，不得只写编号
3. R3 / R4 / R5 / R6 / R7 / R8 不得自行决定 API、DB、Kafka、状态机、错误码、owner 边界或阶段结论
4. gstack 输出必须由 R1 回到 FalconX 正式规范核对
5. 本目录不得存放密钥、环境变量、API token、私钥、助记词或本地机器专用凭据

## 工具适配

- Claude Code 用户：详见 [`AI工作模式-Claude适配`](../docs/process/AI工作模式-Claude适配.md)
- Codex 用户：详见 [`AI工作模式-Codex适配`](../docs/process/AI工作模式-Codex适配.md)

无论使用哪种工具，本目录的角色定义、流程、约束、完成判定**强制一致**。
