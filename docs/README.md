# FalconX 文档索引

本目录只保留当前仍有使用价值的正式规范、当前状态、活动专项方案、场景 Prompt 和审计归档。

若文档之间发生冲突，先按仓库根 [AGENTS.md](../AGENTS.md) 和 [SKILLS.md](../SKILLS.md) 执行，再回到对应专题正式规范；本索引只负责导航。

## 当前必读入口

| 场景 | 读取入口 |
| --- | --- |
| **换新电脑 / 从 GitHub clone 后接续开发** | [项目地图 + 接续开发指南](./setup/项目地图.md)（导航式总入口：真源表 / 架构 / 本地起栈 / demo 运维 / 开放项） |
| 进入任何会话第一步 | [.agents/session-bootstrap.md](../.agents/session-bootstrap.md) |
| 开始任何编码任务 | [AI工作模式](./process/AI工作模式.md)（10 角色三端协作，V2）+ [SKILLS.md](../SKILLS.md) |
| 判断当前阶段、阻断项和下一步 | [当前开发计划](./setup/当前开发计划.md)；剩余执行手册见 [BBook一期完成执行路径](./process/BBook一期完成执行路径.md)，按其中 11 个阶段顺序推进 |
| 判断 AI 最小阅读集、文档真源和验证深度 | [AI协作与提示规范](./process/AI协作与提示规范.md) |
| 执行 AI 编码行为约束 | [Karpathy 式 AI 编码行为准则](./process/Karpathy式AI编码行为准则.md) |
| Claude Code 工具适配 | [AI工作模式-Claude适配](./process/AI工作模式-Claude适配.md) |
| Codex 工具适配 | [AI工作模式-Codex适配](./process/AI工作模式-Codex适配.md) |
| 使用 Claude subagent / Codex 串行 / Superpowers / gstack | [AI工作模式](./process/AI工作模式.md) §6-§8、[Claude 适配](./process/AI工作模式-Claude适配.md)、[Codex 适配](./process/AI工作模式-Codex适配.md)、[.agents 工作流](../.agents/README.md) |
| 三端功能 / Figma 到 React / 前端对接 | [全栈与 Figma 协作流程](./process/全栈Figma协作流程.md)、[前端 React 实现规格](./design/falconx-frontend-react-implementation-spec.md)、[管理端设计系统](./design/falconx-console-DESIGN.md)、[管理端 5 页面方案](./design/falconx-console-pages-V1.md) |
| 判断交付是否完成 | [完成定义](./process/完成定义.md) |
| 修复已知问题 | [统一问题清单](./process/统一问题清单.md) |
| 事故/误报复盘出的强制工程规则（验证标准、IT 口径、bean 装配、风控参数试算） | [工程经验教训](./process/工程经验教训.md) |
| 部署/运维演示档（一键部署、运维守则、验收标准、浏览器 QA 路径） | [AWS演示档部署手册](./setup/AWS演示档部署手册.md)（§0 为当前真源） |

## 常见场景入口

| 场景 | 先看哪里 | 还要补看什么 |
| --- | --- | --- |
| 纯文档 / Prompt / 计划修订 | [AI协作与提示规范](./process/AI协作与提示规范.md) | 若触及 API / DB / 事件 / 状态机，再补读对应专题规范 |
| Claude subagent / Codex 串行 / Superpowers / gstack 协作 | [AI工作模式](./process/AI工作模式.md)、[AI工作模式-Claude适配](./process/AI工作模式-Claude适配.md)、[AI工作模式-Codex适配](./process/AI工作模式-Codex适配.md)、[.agents 工作流](../.agents/README.md) | Claude 可并行 R4/R5/R9/R10；Codex 必须串行；gstack 输出必须回到正式规范核对 |
| 单服务内部实现 / 局部修复 | [AI协作与提示规范](./process/AI协作与提示规范.md)、[Karpathy 式 AI 编码行为准则](./process/Karpathy式AI编码行为准则.md)、[完成定义](./process/完成定义.md) | 若触及缓存、交易时间、状态迁移、幂等，再补读专题规范 |
| 接口开发或修改 | [完成定义](./process/完成定义.md)、[REST接口规范](./api/REST接口规范.md)、[统一接口文档](./api/FalconX统一接口文档.md) | 同步更新统一接口文档 |
| Figma 设计落地到 React | [全栈与 Figma 协作流程](./process/全栈Figma协作流程.md)、[前端 React 实现规格](./design/falconx-frontend-react-implementation-spec.md) | 必须获取 Figma design context 与 screenshot，再用 `falconx-frontend` 现有 token / component 模式实现 |
| 三端联调 / 三端业务功能 | [全栈与 Figma 协作流程](./process/全栈Figma协作流程.md)、[AI工作模式](./process/AI工作模式.md)、[统一接口文档](./api/FalconX统一接口文档.md) | 后端契约先冻结，客户端与管理端不得虚构未实现能力 |
| 数据库 schema / migration | [完成定义](./process/完成定义.md)、[数据库设计](./database/falconx一期数据库设计.md)、[docs/sql](./sql/) | 同时核对 Flyway checksum 影响 |
| Kafka 事件 / Redis 快照 / 跨服务 JSON | [完成定义](./process/完成定义.md)、[Kafka事件规范](./event/Kafka事件规范.md)、[事务与幂等规范](./architecture/事务与幂等规范.md) | 生产者和消费者两侧都要验证 |
| 问题清单修复 | [统一问题清单](./process/统一问题清单.md) | 先核对问题是否真实存在；若目标问题已关闭，再读 [已归档问题清单](./process/archive/统一问题清单-已归档.md) |
| 专项设计实施 | 对应专项方案 | 若触及正式契约，仍回到正式规范 |

## 正式规范

| 领域 | 文档 |
| --- | --- |
| 架构与启动 | [架构方案](./architecture/falconx一期网关-服务-数据库架构方案.md)、[开发启动手册](./setup/开发启动手册.md) |
| 本地环境 | [本地基础设施启动说明](./setup/本地基础设施启动说明.md)、[服务本地启动指南](./setup/服务本地启动指南.md)、[数据库使用说明](./setup/数据库使用说明.md) |
| 数据库与存储 | [数据库设计](./database/falconx一期数据库设计.md)、[docs/sql](./sql/) |
| REST / WebSocket | [REST接口规范](./api/REST接口规范.md)、[WebSocket接口规范](./api/WebSocket接口规范.md)、[统一接口文档](./api/FalconX统一接口文档.md) |
| 安全 / 事件 / 状态 / 事务 | [安全规范](./security/安全规范.md)、[Kafka事件规范](./event/Kafka事件规范.md)、[状态机规范](./domain/状态机规范.md)、[事务与幂等规范](./architecture/事务与幂等规范.md) |
| 行情外部源 | [LP自建行情源接入契约](./market/LP自建行情源接入契约.md) |
| 编码 / 日志 / 测试 | [编码与测试规范](./architecture/falconx编码与测试规范.md)、[日志打印规范](./architecture/日志打印规范.md)、[CFD全面测试用例规范](./test/CFD全面测试用例规范.md) |
| 运维观测 / 回滚 | [日志检索手册](./operations/日志检索手册.md)、[生产观测与回滚手册](./operations/生产观测与回滚手册.md) |
| AI 协作行为 | [AI协作与提示规范](./process/AI协作与提示规范.md)、[AI工作模式](./process/AI工作模式.md)、[Karpathy 式 AI 编码行为准则](./process/Karpathy式AI编码行为准则.md)、[完成定义](./process/完成定义.md) |
| 三端 / Figma / 前端 | [全栈与 Figma 协作流程](./process/全栈Figma协作流程.md)、[前端 React 实现规格](./design/falconx-frontend-react-implementation-spec.md) |
| 管理端 / RBAC / 设计 | [管理端架构](./architecture/管理端架构.md)、[管理端接口规范](./api/管理端接口规范.md)、[管理端设计系统](./design/falconx-console-DESIGN.md)、[管理端 5 页面方案 + §9 客户管理 + §14 阶段 5 DLQ](./design/falconx-console-pages-V1.md)、[管理端测试骨架（前端）](./test/falconx-console-frontend-test-skeleton.md) |
| 业务功能设计稿 | [KYC 客户端设计](./design/STAGE-6-KYC-client-design.md)、[出金客户端设计](./design/STAGE-7-WITHDRAW-client-design.md)、[出金管理端设计](./design/STAGE-7-WITHDRAW-console-design.md) |
| 活跃测试用例 | [docs/test/](./test/) 根目录下所有 `STAGE-*-test-cases.md`；已完成阶段 R7 验证报告见 [docs/test/archive/](./test/archive/README.md) |
| 架构决策 | [ADR索引](./adr/README.md) |

## 活动专项与 Prompt

| 类型 | 文档 | 使用边界 |
| --- | --- | --- |
| 活动专项方案 | [逐仓模式改造方案](./process/逐仓模式改造方案.md) | 当前 `Stage 7A` 后续逐仓范围与冻结决策 |
| 活动任务卡 | [Symbol 三表管理端完整化](./process/task-cards/STAGE-2-SYMBOL-THREE-TABLE-ADMIN.md) | 阶段 2.3 行情品种管理补充收口，覆盖 `t_symbol`、`t_symbol_group_visibility`、`t_symbol_quote_mapping` |
| Codex 场景 Prompt | [Codex Agentic 协作 Prompt](./process/codex-prompt.md) | 新会话启动提示，不是正式规范 |
| 前端设计 Prompt | [前端产品设计场景 Prompt](./design/codex-prompt.md) | 仅用于前端产品设计 / 原型 / 交互评审 |
| 三端 Figma 流程 | [全栈与 Figma 协作流程](./process/全栈Figma协作流程.md) | 后端契约、客户端 / 管理端 Figma、React 实现、接口对接和浏览器 QA 的默认流程 |

## 归档

| 归档目录 | 内容 | 索引 |
| --- | --- | --- |
| [docs/process/archive/](./process/archive/README.md) | 6 个：V1 执行路径、Jackson 3 迁移、Stage 7 验收归档、ProdReady 准备包、已归档问题清单、项目需求快照 | 见目录 README |
| [docs/test/archive/](./test/archive/README.md) | 13 个：STAGE-2/5/6/7 已收口阶段的 R7 验证报告 + 一次性 LIVE-REGRESSION 报告 | 见目录 README |

默认任务不读归档；只有追溯历史结论、重开问题或核对已完成专项时再读。归档文件正文已凝固，**不再更新**；阶段当前状态请查 [当前开发计划 §1](./setup/当前开发计划.md)。

## 维护规则

1. 新的正式规范必须登记到本索引和对应目录索引。
2. 单次分析、审计、调研材料不得单独长期保留为活动文档；有保留价值的结论必须收敛进 `统一问题清单`、`统一问题清单-已归档` 或明确命名的专项归档。
3. 已完成的一次性实施计划不继续留在活动目录；若需要保留证据，移入归档并从当前计划解除引用。
4. 临时构建、解包、试验文件不得作为文档提交。
