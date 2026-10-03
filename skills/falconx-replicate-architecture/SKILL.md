---
name: falconx-replicate-architecture
description: Use when the user wants to scaffold, clone, port, or describe a project that must match the current FalconX v1 GODSA backend architecture. Covers the exact multi-module topology, service boundaries, version matrix, storage ownership, Kafka topics, package conventions, and reusable prompt generation for AI implementation.
---

# FalconX Architecture Replica

这个 Skill 用来把当前仓库沉淀成一个可复用的“同构复刻蓝图”。

目标不是抽象出一个泛化的交易系统模板，而是尽量按当前 FalconX 仓库事实，复刻出同一套：

- Maven 多模块拓扑
- 服务边界与 owner 规则
- 端口、数据库、缓存、Kafka、ClickHouse 分工
- 包结构、契约模块与实现约束
- 当前已冻结和未冻结的能力边界

## 适用触发词

当用户出现下面意图时使用本 Skill：

- “按当前 FalconX 架构重建一个新项目”
- “生成和这个仓库同构的后端骨架”
- “把 FalconX 当前架构整理成可给 AI 执行的提示词”
- “复制这个项目的模块、服务、事件和存储设计”
- “输出一个能让另一个 AI 复刻当前项目架构的专业 Prompt”

## 必读引用

按任务范围最小化读取，不要一次性把所有引用都塞进上下文。

1. 总是先读 [references/architecture-blueprint.md](references/architecture-blueprint.md)
2. 需要落模块、目录、依赖关系时再读 [references/module-map.md](references/module-map.md)
3. 需要真正编码、补配置、补测试或生成强约束 Prompt 时再读 [references/implementation-guardrails.md](references/implementation-guardrails.md)
4. 需要给别的 AI 生成可执行提示词时再读 [references/replication-prompt-template.md](references/replication-prompt-template.md)

## 工作流程

1. 先锁定复刻目标
   - 默认目标是“按当前 FalconX 仓库事实做同构复刻”
   - 除非用户明确要求，不要擅自升级成“生产化增强版”或“重新设计版”

2. 先复刻固定骨架，再补业务细节
   - 先复刻父工程、共享模块、contract 模块、5 个服务模块
   - 再补端口、配置键、topic、owner 存储与包结构
   - 最后才补具体业务链路

3. 保留当前项目的真实边界
   - `wallet` 地址分配当前只是应用层 stub 持久化，不要自动升级成正式地址申请能力
   - 当前正式冻结的北向 WebSocket 只有 `ws://{host}/ws/v1/market`
   - 当前系统不能表述为“生产可用”
   - 当前实现口径按 `B-book` 推进，不自动补 A-book 对冲出口

4. 输出必须包含可执行信息
   - 模块清单
   - 依赖方向
   - 服务职责
   - 存储 owner
   - Kafka topics 与生产/消费关系
   - 包结构和配置骨架
   - 验证步骤

## 复刻时的硬约束

- 不允许把业务 DTO、Entity、Mapper、Repository 上收进 `common` 或 `infrastructure`
- 不允许服务模块直接依赖其他服务模块，只能依赖共享模块和 `contract`
- 不允许跨服务直连别人的 owner 数据库
- 低频 Kafka / WebSocket / 外部回调线程不得直接承载完整业务链路，必须切到应用自管线程并恢复线程上下文
- 业务数据库访问保持 `MyBatis + XML Mapper`
- 保持 `Java 25 + Spring Boot 4.0.5 + Jackson 3.1.0`

## 默认交付物

如果用户要你“复刻当前项目架构”，默认至少给出下面其中一项或多项：

- 新仓库脚手架
- 架构说明文档
- 模块与目录树
- 配置样板
- 给另一个 AI 的复刻 Prompt
- 验证清单

## 结束前检查

- 是否复刻了 12 个 Maven 模块，而不是只建 5 个服务
- 是否保留了 `gateway -> contract -> service` 的依赖边界
- 是否保留了 `Database per Service`
- 是否保留了 `market / wallet / trading / identity` 的 Kafka 事件方向
- 是否避免把未冻结能力写成已交付能力
- 是否明确标注当前不是生产可用口径
