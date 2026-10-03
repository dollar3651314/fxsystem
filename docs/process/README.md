# docs/process 文档索引

> 本文件是 `docs/process/` **子目录详细索引**（按文档分层 + 按任务类型推荐阅读顺序）。仓库级文档总入口请看 [`docs/README.md`](../README.md)。两者互补不重复：主 README 列各目录、本文件深入 process 目录细节。

## 1. 目的

本目录用于承载 FalconX 的实施过程规范、当前专项方案和审计归档。

为避免 AI 或人工协作时把"正式规范""专项设计""历史分析记录"混在一起使用，本目录统一按下面分层理解。

## 2. 文档分层

| 类别 | 文档 | 用途 | 是否属于正式规范来源 |
| --- | --- | --- | --- |
| 正式规范 | [AI工作模式](./AI工作模式.md) | 10 角色（R1-R10）三端协作真源；定义角色边界、流程、完成判定 | 是 |
| 正式规范 | [AI工作模式-Claude适配](./AI工作模式-Claude适配.md) | Claude Code 工具映射 | 是 |
| 正式规范 | [AI工作模式-Codex适配](./AI工作模式-Codex适配.md) | Codex 工具映射 | 是 |
| 正式规范 | [AI协作与提示规范](./AI协作与提示规范.md) | 约束 AI 的执行方式、最小阅读集和验证深度 | 是 |
| 正式规范 | [Karpathy 式 AI 编码行为准则](./Karpathy式AI编码行为准则.md) | 约束编码前澄清、简洁优先、精准修改和目标驱动验证 | 是 |
| 正式规范 | [全栈与 Figma 协作流程](./全栈Figma协作流程.md) | 约束后端契约、Figma、React 实现、接口对接和浏览器 QA 的全栈流程 | 是 |
| 正式规范 | [完成定义](./完成定义.md) | 定义代码、文档、审计类任务何时可标记为完成 | 是 |
| 正式规范 | [统一问题清单](./统一问题清单.md) | 统一收敛当前仍需处理的活跃问题、状态和修复记录 | 是 |
| 当前专项方案 | [逐仓模式改造方案](./逐仓模式改造方案.md) | 逐仓保证金模式改造的设计与实施路径 | 否，属于专项实施方案 |
| 当前任务卡 | [Symbol 三表管理端完整化](./task-cards/STAGE-2-SYMBOL-THREE-TABLE-ADMIN.md) | 阶段 2.3 行情品种管理补充收口，覆盖 `t_symbol`、`t_symbol_group_visibility`、`t_symbol_quote_mapping` | 否，属于任务执行卡 |
| 审计归档 | [统一问题清单（已归档）](./archive/统一问题清单-已归档.md) | 已完成 / 不成立问题的审计留痕 | 否，仅为审计归档 |
| 审计归档 | [archive/README](./archive/README.md) | 历史分析记录、阶段性审阅材料归档入口 | 否，仅为审计归档 |
| 历史专项归档 | [Jackson3迁移计划](./archive/Jackson3迁移计划.md) | 已完成的 JSON 技术栈迁移专项证据 | 否，仅为历史归档 |

## 3. 使用规则

1. 若文档之间发生冲突，以 `AGENTS.md` 和各专题正式规范为准。
2. 常见场景入口统一维护在 [`docs/README.md`](../README.md)，不得另建重复速查表。
3. `专项方案` 可以冻结设计路径，但若触及 API、数据库 schema、Kafka payload、错误码或状态机契约，仍需服从正式规范和用户授权边界。
4. `审计归档` 用于保留分析证据，不作为默认现行规则来源。

## 4. 推荐阅读顺序

### 4.1 纯文档 / 提示规范 / 计划类任务

1. `.agents/session-bootstrap.md`
2. `AGENTS.md`
3. [AI工作模式](./AI工作模式.md)
4. [AI协作与提示规范](./AI协作与提示规范.md)
5. [Karpathy 式 AI 编码行为准则](./Karpathy式AI编码行为准则.md)
6. [完成定义](./完成定义.md)
7. 目标文档

### 4.2 代码 / 接口 / 数据库 / 事件类任务

1. `.agents/session-bootstrap.md`
2. `AGENTS.md`
3. `SKILLS.md`
4. [AI工作模式](./AI工作模式.md)
5. 相关正式专题规范
6. [AI协作与提示规范](./AI协作与提示规范.md)
7. [Karpathy 式 AI 编码行为准则](./Karpathy式AI编码行为准则.md)
8. [完成定义](./完成定义.md)
9. 对应专项方案或问题清单

### 4.3 10 角色三端协作 / Superpowers / gstack 任务

1. `.agents/session-bootstrap.md`
2. `AGENTS.md`
3. `SKILLS.md`
4. [AI工作模式](./AI工作模式.md)（角色定义、流程、完成判定）
5. 工具适配（按工具二选一）：[AI工作模式-Claude适配](./AI工作模式-Claude适配.md) 或 [AI工作模式-Codex适配](./AI工作模式-Codex适配.md)
6. [AI协作与提示规范](./AI协作与提示规范.md)
7. [Karpathy 式 AI 编码行为准则](./Karpathy式AI编码行为准则.md)
8. `.agents/README.md` + 对应角色模板
9. 与任务直接相关的正式专题规范

### 4.4 全栈 / Figma / 前端对接任务

1. `.agents/session-bootstrap.md`
2. `AGENTS.md`
3. `SKILLS.md`
4. [全栈与 Figma 协作流程](./全栈Figma协作流程.md)
5. [AI工作模式](./AI工作模式.md)（特别关注 §2.3 R3、§2.5 R5 角色定义）
6. `.agents/templates/fullstack-bundle.md`
7. `docs/design/falconx-frontend-react-implementation-spec.md`
8. 相关 REST / WebSocket / 安全 / 状态机 / 数据库正式规范
9. Figma URL / Node / 截图或本轮设计方案

### 4.5 问题审计 / 整改类任务

1. [统一问题清单](./统一问题清单.md)
2. 相关专题正式规范
3. 若目标问题已关闭，再读 [统一问题清单（已归档）](./archive/统一问题清单-已归档.md)
4. 对应已归档专项文档（若存在）

## 5. 维护约定

1. 新的正式规范应直接放在本目录根下，并在本文件登记。
2. 新的当前专项设计文档应放在本目录根下，并明确其不等同于正式契约。
3. 单次分析、审计、调研材料和已完成专项默认放入 `archive/`，避免与现行规范并列。
4. `统一问题清单` 仅保留活跃问题；关闭问题应移入 `archive/统一问题清单-已归档.md`。
