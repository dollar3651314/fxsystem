# FalconX — Agent 工作指南

## ⚡ 强制会话启动协议（最优先）

**任何 AI 助手（Claude Code / Codex 等）进入 FalconX 仓库会话的第一步，必须执行：**

```
read .agents/session-bootstrap.md
```

启动协议包含：识别工具 → 读核心规则 → 定位当前阶段 → 检查工作区 → 等待任务。

Claude Code 已通过 `.claude/settings.json` SessionStart hook 自动加载启动协议；Codex 用户必须手动 `read .agents/session-bootstrap.md`。

**未完成启动协议前，不得开始任何编码、文档、提交动作。**

---

## ⚡ 强制前置步骤

**开始任何编码任务前，必须先按 [`docs/process/AI工作模式.md`](docs/process/AI工作模式.md) 拆分角色路由，再执行：**

```
read SKILLS.md
```

根据任务类型找到对应 Skill 编号，**逐步骤执行，不得跳过**。

| 任务类型 | 读取 |
|----------|------|
| 新增 REST 接口 | `SKILLS.md § Skill 1` |
| 新增低频 Kafka 事件（Outbox/Inbox） | `SKILLS.md § Skill 2` |
| 新增高频行情事件（直接 Kafka） | `SKILLS.md § Skill 3` |
| 新增数据库表/字段 | `SKILLS.md § Skill 4` |
| 新增 Redis 缓存 | `SKILLS.md § Skill 5` |
| 新增 ClickHouse 分析查询 | `SKILLS.md § Skill 6` |
| 新增 Wallet 链 | `SKILLS.md § Skill 7` |
| 新增风险控制规则 | `SKILLS.md § Skill 8` |
| 新增状态机迁移 | `SKILLS.md § Skill 9` |
| 编写集成测试 / E2E 测试 | `SKILLS.md § Skill 10` |
| 同步接口文档 | `SKILLS.md § Skill 11` |
| 跨服务 contract 变更 | `SKILLS.md § Skill 12` |
| 前端产品 / 页面设计 | `SKILLS.md § Skill 13` |
| Figma 设计落地到 React | `SKILLS.md § Skill 14` |
| 前端接口 / WebSocket 对接 | `SKILLS.md § Skill 15` |
| 三端业务功能开发（客户端 + 后端 + 管理端） | `SKILLS.md § Skill 16` |
| 前端 QA / 设计复核 | `SKILLS.md § Skill 17` |

---

## 📋 规则分级说明

本仓库的规则按下面 3 层理解：

- **正式约束**：`AGENTS.md` 与 §2 引用的正式规范，必须遵守
- **默认执行策略**：[docs/process/AI协作与提示规范.md](docs/process/AI协作与提示规范.md)，用于最小阅读集、默认确认边界和验证深度
- **协作模式策略**：[docs/process/AI工作模式.md](docs/process/AI工作模式.md)，定义 10 角色（R1-R10）三端协作（客户端 + 后端服务 + 管理端）、派发、边界、完成判定与工具适配（Claude Code / Codex）
- **行为准则**：[docs/process/Karpathy式AI编码行为准则.md](docs/process/Karpathy式AI编码行为准则.md)，用于编码前澄清、简洁优先、精准修改和目标驱动验证
- **辅助导航**：[docs/README.md](docs/README.md) 与 [docs/process/README.md](docs/process/README.md)，仅用于定位入口，不覆盖正式规则

## 🤝 Agentic 协作模式（10 角色 + Superpowers + gstack）

FalconX 采用 **10 角色三端协作模式**（V2，2026-05-08 升级）。角色定义（R1 Commander / R2 Contract Designer / R3 UI/UX / R4 业务后端 / R5 客户端 / R6 Test / R7 QA / R8 Doc / R9 管理端后端 / R10 管理端前端）、职责边界、必读清单与输出物的**唯一真源**：[`docs/process/AI工作模式.md §2`](docs/process/AI工作模式.md)。三端 = 客户端（`falconx-frontend`）+ 后端服务（identity/market/trading-core/wallet）+ 管理端（`falconx-console-service` + `falconx-console-frontend`）。旧二端口径均已废弃。

强制规则：

- **每个任务由 R1 派发，按角色路由（[`AI工作模式`](docs/process/AI工作模式.md) §4.1）执行**。
- **角色边界不可越界**：每个角色执行前必须通过边界自检卡（[`AI工作模式`](docs/process/AI工作模式.md) §3.1），违反即视为越界。
- **契约先于实现**：R2 必须在 R3/R4/R5/R6/R9/R10 之前完成。
- **三端一体化**：涉及客户端可见行为的业务功能必须客户端 + 后端服务 + 管理端三端同时设计、实现、验证、交付，**任意一项未完成均不得标记完成**（详见 [`AI工作模式`](docs/process/AI工作模式.md) §5.1 与 [`完成定义`](docs/process/完成定义.md) §4.A 三端硬约束）。基础设施任务（阶段 0）与管理端骨架任务（阶段 1）按 [`AI工作模式`](docs/process/AI工作模式.md) §4.1 例外条款豁免。
- **4 条 Karpathy 编码准则永久生效**（思考前先 / 简洁优先 / 精准修改 / 目标驱动），见 [`AI工作模式`](docs/process/AI工作模式.md) §1.2 与 [`Karpathy 式 AI 编码行为准则`](docs/process/Karpathy式AI编码行为准则.md)。
- 任务涉及新能力、行为变化或多步骤实施时，必须按 [`AI工作模式`](docs/process/AI工作模式.md) §6 选择 Superpowers 流程门禁；完成前必须有新鲜验证证据。
- 任务涉及前端页面、Figma、浏览器交互或全栈对接时，必须同时按 [全栈与 Figma 协作流程](docs/process/全栈Figma协作流程.md) 约束客户端、后端服务与管理端边界。
- `gstack` 仅作为计划审查、调查、QA、评审、发布和上下文管理工具层；不得覆盖 FalconX 正式规范、文档同步、测试证据和回滚点要求（详见 [`AI工作模式`](docs/process/AI工作模式.md) §7）。
- `.agents/templates/` 下的 10 个角色模板（含 R9/R10 管理端）+ `fullstack-bundle.md` 三端打包模板是任务派发的默认格式；模板不得替代正式规范。
- 工具适配：Claude Code 用户参考 [`AI工作模式-Claude适配`](docs/process/AI工作模式-Claude适配.md)，Codex 用户参考 [`AI工作模式-Codex适配`](docs/process/AI工作模式-Codex适配.md)。

## 🎯 当前执行口径

FalconX 当前不再按 `Dev / Staging / Production` 放宽下面这些要求：

- owner 数据来源
- 接口 / 数据库 / 事件 / 状态机契约边界
- 当前任务所需的文档同步
- 当前任务所需的测试、日志与回滚点要求

"开发阶段"只影响实施节奏和验证范围，不影响正式边界。

## ⚡ 快速通道

快速通道仅表示"按最小阅读集推进，不做无关通读"，不表示可以跳过正式规则。

✅ **允许快速通道：**
- 纯文本级局部修订：`typo / 格式化 / 注释补充 / 日志文案收敛`，且不改变业务语义
- 同一会话中已经读取过相关文档的后续修改

❌ **不允许快速通道：**
- 任何触及 `API / DB / 事件 / 状态机 / 安全 / 事务 / 缓存语义 / owner 数据 / 跨服务边界` 的修改
- 任何需要用户确认的契约变更

> 💡 **详细规则**：优先看 [AI协作与提示规范](docs/process/AI协作与提示规范.md) 的"任务类型与最小阅读集"，再用 [docs/README.md](docs/README.md) 做入口导航

---

## 0. 项目快照（快速参考）

> 以下为结构快照，供 Agent 快速定位代码位置。规范约束以 §1–§9 为准。

### 技术栈

| 维度 | 值 |
|------|-----|
| Java | 25 |
| Spring Boot | 4.0.5 |
| Spring Cloud | 2025.1.0 |
| ORM | MyBatis Plus 3.5.15 + XML Mapper |
| 缓存 | Redisson 4.3.0（Redis 8.2） |
| 消息 | Kafka 4.2.0 |
| OLTP | MySQL 8.4 |
| OLAP | ClickHouse 25.8 |
| 迁移 | Flyway（各服务独立 schema） |
| JSON | Jackson 3.1.0（`tools.jackson.*` 主路径；`jackson-annotations` 仍为 `com.fasterxml.jackson.annotation.*`） |
| 前端 | Vite + React + TypeScript + React Query + Zustand + Framer Motion |
| 设计交付 | Figma MCP + `figma-implement-design` + gstack `design-review` / `browse` |

### 服务与端口

| 服务 | 端口 | MySQL DB | 职责摘要 |
|------|------|----------|----------|
| `falconx-gateway` | 18080 | — | WebFlux 网关；JWT 验证；限流；Resilience4j 熔断 |
| `falconx-identity-service` | 18081 | `falconx_identity` | 注册/登录；RSA JWT；BCrypt；Redis 黑名单 |
| `falconx-market-service` | 18082 | `falconx_market` + ClickHouse | LP 自建行情；K 线聚合；Redis 缓存；交易日历 |
| `falconx-trading-core-service` | 18083 | `falconx_trading` | 账户/账本；订单/持仓；存款；风控；强平 |
| `falconx-wallet-service` | 18084 | `falconx_wallet` | 链上地址分配；多链监听；存款确认 |
| `falconx-frontend` | 5173 | — | React 交易终端；登录注册；行情 REST / WebSocket；前端交互与设计实现 |

| 基础设施 | 端口 |
|----------|------|
| MySQL | 3306 |
| Redis | 6380（host 端口；容器内 6379） |
| Kafka | 9092 |
| ClickHouse HTTP | 8123 |
| ClickHouse Native | 9000 |

### 共享模块

| 模块 | 职责 |
|------|------|
| `falconx-common` | `ApiResponse<T>`、`ErrorCode`、`CommonErrorCode` |
| `falconx-domain` | `ChainType`、`UserStatus` 等领域枚举；`DomainEvent` 基类 |
| `falconx-infrastructure` | `SnowflakeIdGenerator`、`TraceIdSupport`、`KafkaEventMessageSupport`、`OutboxRetryDelayPolicy`、`RsaPemSupport` |

### 契约模块（禁止含业务逻辑）

| 模块 | 冻结的 Payload |
|------|--------------|
| `falconx-identity-contract` | `RegisterRequest/Response`、`LoginRequest`、`AuthTokenResponse`、`RefreshTokenRequest` |
| `falconx-market-contract` | `MarketPriceTickEventPayload`、`MarketKlineUpdateEventPayload` |
| `falconx-trading-contract` | `DepositCreditedEventPayload` |
| `falconx-wallet-contract` | `WalletDepositDetectedEventPayload`、`WalletDepositConfirmedEventPayload`、`WalletDepositReversedEventPayload` |

### Kafka Topics

| Topic | 生产者 | 消费者 |
|-------|--------|--------|
| `falconx.market.price.tick` | market-service | trading-core-service |
| `falconx.market.kline.update` | market-service | trading-core-service |
| `falconx.wallet.deposit.detected` | wallet-service | — |
| `falconx.wallet.deposit.confirmed` | wallet-service | trading-core-service |
| `falconx.wallet.deposit.reversed` | wallet-service | trading-core-service |
| `falconx.trading.deposit.credited` | trading-core-service | identity-service |

### 每服务标准包结构

根包：`com.falconx.{service}`

```
controller/    HTTP 端点，只做参数绑定与委托
command/       命令对象，封装业务操作入参
application/   ApplicationService：编排，不含领域规则
service/       领域业务逻辑（interface + 实现类）
entity/        MyBatis 实体、枚举
repository/    Repository interface + MybatisXxxRepository 实现
  mapper/      MyBatis Mapper interface
config/        @Configuration、@ConfigurationProperties
error/         XxxErrorCode enum、XxxBusinessException
consumer/      Kafka @KafkaListener（禁止在回调线程执行业务，必须切换线程）
producer/      Kafka 事件发布
```

### 前端标准结构

根目录：`falconx-frontend`

```
src/components/     复用 UI / 品牌组件
src/features/       按业务域组织页面、状态、接口适配与测试
src/lib/            API 客户端、环境配置、通用基础设施
src/providers/      React Query 等应用级 Provider
src/styles/         `tokens.css` 与全局样式
```

---

## 1. 目的

本文件用于把当前 FalconX 仓库中的架构、数据库、接口、事件、编码、日志、测试与交付规则固化为默认实施规范。

后续任何 AI 编码代理在本仓库中工作时，都必须先读取并遵守本文件，再进行分析、设计、编码、测试或文档更新。

## 2. 规范来源

下列文档是 FalconX 的正式规范来源，不是参考资料。

### 2.1 架构与实施顺序

- [docs/architecture/falconx一期网关-服务-数据库架构方案.md](docs/architecture/falconx一期网关-服务-数据库架构方案.md)
- [docs/setup/开发启动手册.md](docs/setup/开发启动手册.md)

### 2.2 数据库与存储

- [docs/database/falconx一期数据库设计.md](docs/database/falconx一期数据库设计.md)
- [docs/sql/V1__init_schema.sql](docs/sql/V1__init_schema.sql)
- [docs/sql/V2__seed_symbols.sql](docs/sql/V2__seed_symbols.sql)
- [docs/sql/CH_V1__market_analytics.sql](docs/sql/CH_V1__market_analytics.sql)

### 2.3 接口、安全、事件与状态

- [docs/api/REST接口规范.md](docs/api/REST接口规范.md)
- [docs/api/WebSocket接口规范.md](docs/api/WebSocket接口规范.md)
- [docs/api/FalconX统一接口文档.md](docs/api/FalconX统一接口文档.md)
- [docs/security/安全规范.md](docs/security/安全规范.md)
- [docs/event/Kafka事件规范.md](docs/event/Kafka事件规范.md)
- [docs/domain/状态机规范.md](docs/domain/状态机规范.md)
- [docs/architecture/事务与幂等规范.md](docs/architecture/事务与幂等规范.md)
- [docs/market/LP自建行情源接入契约.md](docs/market/LP自建行情源接入契约.md)

### 2.4 编码、日志、测试与交付

- [docs/architecture/falconx编码与测试规范.md](docs/architecture/falconx编码与测试规范.md)
- [docs/architecture/日志打印规范.md](docs/architecture/日志打印规范.md)
- [docs/process/AI协作与提示规范.md](docs/process/AI协作与提示规范.md)
- [docs/process/AI工作模式.md](docs/process/AI工作模式.md)
- [docs/process/AI工作模式-Claude适配.md](docs/process/AI工作模式-Claude适配.md)
- [docs/process/AI工作模式-Codex适配.md](docs/process/AI工作模式-Codex适配.md)
- [docs/process/Karpathy式AI编码行为准则.md](docs/process/Karpathy式AI编码行为准则.md)
- [docs/process/全栈Figma协作流程.md](docs/process/全栈Figma协作流程.md)
- [docs/process/完成定义.md](docs/process/完成定义.md)
- [docs/process/统一问题清单.md](docs/process/统一问题清单.md)

### 2.5 架构决策

- [docs/adr/README.md](docs/adr/README.md)

### 2.6 当前进度（状态文档，非规范来源）

- [docs/setup/当前开发计划.md](docs/setup/当前开发计划.md)

> 说明：当前开发计划记录阶段完成状态与下一步范围，属于进度跟踪文档。代理应以此了解项目所处阶段，但不得将"已完成"的历史状态当作当前约束执行。规范约束以 §2.1–§2.5 为准。

---

## 3. 核心工作规则

### 3.1 文档读取规则

#### 🔴 强制规则

3.1.1 除纯文本级局部修订外，默认先按 [AI协作与提示规范](docs/process/AI协作与提示规范.md) 的"按任务类型最小阅读集"确定阅读范围。

3.1.1A 非平凡编码、调试、重构和审查任务在进入实现前，必须按 [Karpathy 式 AI 编码行为准则](docs/process/Karpathy式AI编码行为准则.md) 明确关键假设、最小实现边界、精准修改范围和验证目标。

3.1.2 任务只要触及某一专题，就必须回到对应正式规范全文，不得只看摘要、速查表或历史分析记录。

3.1.3 以下场景必须扩展阅读对应正式规范：
- 修改服务边界、数据库 schema、接口契约、事件 payload
- 修改安全、事务、幂等、状态机、缓存语义、交易时间或外部源接入
- 不熟悉的业务领域
- 新功能开发涉及多个服务协作

#### 🟢 可选规则

3.1.4 以下窄范围可直接处理：
- 纯文本级局部修订：`typo / 格式化 / 注释补充 / 日志文案收敛`
- 同一会话中已经完成最小阅读集后的同范围后续调整

> 💡 **读取范围**：先按最小阅读集起步；若任务触达更多专题，再向对应正式规范扩展

### 3.2 架构与边界规则

#### 🔴 强制规则

3.2.1 不允许写出违反服务边界、数据 owner、事件 owner 的实现。

3.2.2 不允许把业务 DTO、Entity、Mapper、Repository 上收进 `common` 或 `infrastructure`，除非架构文档明确允许。

3.2.3 业务代码（Service / Application 层）的数据库查询一律通过 `MyBatis + XML Mapper` 实现；禁止在 Service / Application / Controller 层拼接 SQL 字符串。测试辅助类与工具脚本不受此限制。

3.2.4 一旦进入真实实现阶段，数据库查询必须通过 owner 服务的真实 `Mapper + XML + Repository` 链路完成，禁止用 `In-Memory Repository` 代替正式查询实现。

#### 🟡 推荐规则

3.2.5 可以抽象复用的代码尽量复用，但复用不能破坏服务 owner 边界；禁止跨服务共用业务 Mapper、业务 Repository 或查询实现。

### 3.3 产品数据来源规则

#### 🔴 强制规则（所有阶段）

3.3.1 产品元数据、可交易 symbol 列表、品种分类与市场代码等 owner 数据，必须来自正式 owner 数据源（数据库、owner 写入的 Redis 快照、或冻结后的 contract），禁止在运行时代码中通过 `List.of(...)`、本地静态白名单、配置默认值等方式硬编码返回。

3.3.2 若某服务需要消费另一服务的产品配置结果，应优先使用 owner 产出的正式共享数据（如 market 写入的 Redis 快照），不得在本地再维护一份静态产品白名单。

3.3.3 `market-service` 的外部行情源本地放行白名单必须只依赖 `t_symbol.status=1` 的 owner 数据，并支持运行时热刷新；数据库启停状态变化后，不允许再要求通过重启服务才能生效。

#### 🟢 可选规则（仅限测试和调试）

3.3.4 测试数据只允许存在于测试代码、测试夹具、测试初始化脚本或经用户明确授权的临时调试脚本中，不得进入运行时代码路径。

### 3.4 数据库变更规则

#### 🔴 强制规则

3.4.1 若修改的是原始基线 migration 或 SQL 蓝图（如 `V1 / V2`），必须同时处理 Flyway checksum 影响。测试或调试环境不得静默复用已应用旧 checksum 的库；应切换到新的隔离测试库，或在得到明确授权后执行 repair。

3.4.2 不允许未更新文档就新增主题名、错误码、包结构、状态码、存储模式、接口契约。

### 3.5 任务范围与决策规则

#### 🔴 强制规则

3.5.1 不允许代理自行扩展任务范围、增加用户未明确要求的实现内容或替用户做架构外决策。

3.5.2 若决策会影响以下任一项，必须先向用户确认，再继续实施：
- 接口契约（路径 / 方法 / 字段）
- 数据库 schema（新增表 / 字段 / 索引）
- Kafka 事件 payload
- 状态机迁移规则
- 包结构
- 错误码 / 状态码
- 新增跨服务共享模型、共享快照或共享契约

#### 🟢 可选规则

3.5.3 对不改变接口契约、数据库 schema、Kafka payload、错误码、状态码、包结构与 owner 边界的内部实现、同 owner 受控重构、测试/日志补齐、文档澄清和提示词优化，代理可直接实施，不需要因算法、私有方法拆分、注释风格、验证命令选择等内部细节重复向用户确认。

### 3.6 阶段与文档规则

#### 🔴 强制规则

3.6.1 若任务会改变阶段状态、阶段验收或下一步实施顺序，必须同步更新 [当前开发计划](docs/setup/当前开发计划.md)。

3.6.2 不允许把"骨架完成"表述成"真实基础设施已打通"或"生产可用"。

3.6.3 若当前实现仍基于 `In-Memory Repository`、`stub provider / listener` 或日志型 publisher，必须在计划与结论中显式说明。

3.6.4 阶段完成后不得留下"下一步为空白"的计划真空，必须把下一阶段范围写入计划文档。

3.6.5 每次更新或输出开发计划时，计划项的展示名称必须使用本轮修改或任务内容的概要描述；`P0 / P1`、流水号、任务编号只能作为追踪字段，不得作为展示名称。

### 3.7 问题修复流程

#### 🔴 强制规则

3.7.1 若用户要求"读取某份问题 / 审计 / 补充事项文档并修复"，必须先确认问题当前状态（通过问题清单或代码检查），再区分为：
- `真实存在，需要修复`
- `已修复，仅需同步文档`
- `不成立，不得误修`

3.7.2 后续新增问题、审计项、整改项，默认统一收敛到 [统一问题清单](docs/process/统一问题清单.md)。

3.7.3 若用户未明确指定问题文档，且任务是"统一读取问题并修复"，默认优先读取该文件；若目标问题已关闭或需要追溯历史修复记录，再补读 [已归档问题清单](docs/process/archive/统一问题清单-已归档.md)。

#### 🟡 推荐流程

3.7.4 优先处理 P0/P1 问题。

3.7.5 批量修复前先修复 1-2 个问题验证方案。

3.7.6 对于明显过时的问题（如描述的代码已不存在），可以直接标记为"不成立"。

#### 🟢 效率优化

3.7.7 使用快速查询命令搜索问题编号（见 §11 常用命令）。

3.7.8 同一类型问题可以批量处理。

3.7.9 已经在本次会话中核对过的问题不需要重复核对。

### 3.8 跨服务兼容性规则

#### 🔴 强制规则

3.8.1 对 Redis 快照、Kafka payload、跨服务共享 JSON 结构的修改，必须同时考虑跨服务兼容性：
- 新增字段默认不得导致现有消费方反序列化失败
- 若存在跨服务共享缓存或事件模型，优先保证消费方可忽略未知字段，或通过 `contract` 模块显式冻结 DTO
- 修改后必须验证生产者与消费者的联通性，不能只测单边服务

3.8.2 对来自 WebSocket、SDK、客户端库或外部监听器线程的回调，禁止直接在回调线程里执行完整的业务消费链路（Kafka、Redis、DB、ClickHouse、复杂应用服务）。必须先切换到应用自管线程或执行器，再进入后续处理；若下游组件依赖 TCCL、MDC 或其他线程上下文，也必须显式恢复。

### 3.9 缓存实现规则

#### 🔴 强制规则

3.9.1 任何缓存型实现都必须明确 3 件事后才算完成：
- TTL（过期时间）
- 刷新策略（定时 / 事件驱动 / 启动预热）
- cache miss 时的业务降级策略

若这三项缺任一项，不得在结论中写"已完成"。

### 3.10 验证命令规则

#### 🔴 强制规则

3.10.1 执行验证命令时，禁止并行运行会相互影响构建产物的命令，尤其是：
- `mvn test`
- `mvn clean compile`
- 其他会清理同一 `target/` 目录的命令

这类命令必须串行执行，避免把环境冲突误判成代码问题。

### 3.11 技术栈使用规则

#### 🔴 强制规则（当前阶段）

3.11.1 必须使用 Spring Boot 4 BOM 管理的 Jackson 3.1.0：
- 禁止新增 Jackson 2 的自定义版本覆盖
- 禁止在子模块私自覆盖 `tools.jackson.*` 版本
- `jackson-annotations` 仍保留 `com.fasterxml.jackson.annotation.*`，不得擅自替换为非 BOM 管理版本

#### 🟡 推荐规则

3.11.2 优先使用 JDK 25 的语言特性（record、pattern matching、enhanced switch 等）。

3.11.3 优先使用 Spring Boot 4 的推荐 API 和配置方式。

#### 🟢 可选规则

3.11.4 可以使用传统写法，如果更清晰易懂。

3.11.5 可以延迟使用新特性，如果团队不熟悉，但必须在代码审查时说明原因。

### 3.12 Git 提交规则

#### 🔴 强制规则

3.12.1 每次完成一轮仓库内容修改并准备交付时，必须形成 GitHub 可回滚点：
- 至少创建一个语义清晰、可定位本轮范围的 Git commit
- 若用户要求提交远程仓库，必须把该 commit push 到默认远程，作为 GitHub 回滚点
- 若工作区存在与本轮无关的未提交改动，必须先隔离后再提交，禁止把无关改动混入当前回滚点

3.12.2 默认开发分支固定为 `main`：
- 后续常规开发、修复、验证与提交一律在 `main` 分支进行
- 除非用户明确要求合并、发布或直接处理 `main`，不得把日常开发提交落到 `main`
- 若开始工作时当前不在 `main`，且任务不要求处理其他分支，必须先切换到 `main` 再继续

### 3.13 前端、Figma 与全栈对接规则

#### 🔴 强制规则

3.13.1 前端设计、Figma 落地、React 实现和接口对接必须执行 [全栈与 Figma 协作流程](docs/process/全栈Figma协作流程.md)，并根据任务类型读取 `SKILLS.md § Skill 13-17`。

3.13.2 Figma 只作为视觉、布局、组件状态和交互动效的设计真源；不得用 Figma 覆盖后端正式契约。任何 `API / DB / Kafka / WebSocket / 状态机 / 错误码 / owner` 变化仍必须先按后端正式规范冻结并取得用户确认。

3.13.3 使用 Figma 实现页面时，必须先获取目标节点的 `get_design_context` 与 `get_screenshot`，再开始编码；实现时必须把 Figma 输出翻译为 `falconx-frontend` 现有 React、CSS token、状态管理和数据请求模式，禁止直接引入一套平行设计系统。

3.13.4 前端不得为了页面完整而伪造后端能力。后端尚未提供的账户、订单、持仓、成交、账本、强平、Swap、钱包实时推送等能力，只能展示禁用、占位或明确“未接入”状态，禁止轮询或本地 mock 成真实生产路径。

3.13.5 前端不得记录完整 JWT、Refresh Token、带 token 的 WebSocket URL、认证响应原文或用户敏感输入；浏览器侧不得主动发送 `X-Trace-Id`。

3.13.6 每个三端业务功能必须同时给出业务后端、客户端前端、管理端后端和管理端前端验证证据。前端最低验证为 `npm run test`、`npm run lint`、`npm run build` 与桌面/移动浏览器截图或 QA 记录；后端按涉及服务执行 Maven 测试或 E2E。

#### 🟡 推荐规则

3.13.7 Figma 到代码优先复用 `src/styles/tokens.css`、`src/components/`、`src/features/{domain}` 结构；新增 token 或通用组件时必须说明复用边界。

3.13.8 设计流程默认按：`design-consultation -> design-shotgun -> Figma 定稿 -> figma-implement-design -> design-review + browse/qa` 执行；用户给定现成 Figma 节点时，可从 `figma-implement-design` 开始。

---

## 4. 当前实施约束

以下约束是当前仓库默认实施口径，不因"开发阶段"而放宽。

### 🔴 强制约束

4.1 FalconX v1 采用 `GODSA`。

4.2 一期服务固定为：`falconx-gateway`、`falconx-identity-service`、`falconx-market-service`、`falconx-trading-core-service`、`falconx-wallet-service`

4.3 一期共享模块固定为：`falconx-common`、`falconx-domain`、`falconx-infrastructure` 及各服务 `contract` 模块

4.4 服务边界不得破坏。

4.5 owner 数据不得跨服务访问。

4.6 产品数据、可交易 symbol、配置结果等运行时 owner 数据必须来自正式 owner 数据源，不得因调试或开发阶段在运行时代码中硬编码返回。

4.7 当前任务涉及的文档、日志、测试与 Git 回滚点要求，不得以"开发阶段后补"为由延后。

---

## 5. 代码质量规则

### 🔴 强制规则

5.1 所有核心业务逻辑都必须有测试（单元测试或集成测试）。

5.2 所有关键业务节点都必须按日志规范打日志。

5.3 `traceId` 必须由系统自动生成，并通过日志上下文自动输出。

5.4 不允许在业务代码中反复手工拼接 `traceId`。

### 🟡 推荐规则

5.5 所有业务代码以及公开类 / 公开方法都应具备与复杂度相称的清晰注释。

5.6 复杂业务代码必须补充块级注释。

5.7 需要重复出现的转换、校验、查询组装、日志字段组装与 XML SQL 片段，优先做受控复用。

### 🟢 可选规则

5.8 简单 DTO、record、配置类和测试支撑类至少应具备类注释。

5.9 可以使用 Lombok 减少样板代码。

---

## 6. 接口交付规则

### 🔴 强制规则

6.1 每次接口开发并测试通过后，必须按照文档分类同步更新接口文档：[FalconX统一接口文档](docs/api/FalconX统一接口文档.md)

6.2 未更新统一接口文档，不视为接口任务完成。

### 统一接口文档必须包含

- [ ] 所属服务
- [ ] 接口名称
- [ ] 请求路径
- [ ] 请求方法
- [ ] 认证要求
- [ ] 请求头
- [ ] 请求参数或请求体
- [ ] 成功响应示例
- [ ] 失败响应与错误码
- [ ] 关键日志点
- [ ] 测试结论

---

## 7. 冲突处理优先级

若多个文档同时相关，按下面顺序处理：

1. 架构文档
2. 开发启动手册
3. 数据库设计与 SQL 蓝图
4. REST / WebSocket / 安全 / Kafka / 状态机 / 事务规范
5. 编码 / 日志 / 测试 / 完成定义
6. 全栈 / Figma 协作流程
7. ADR

若仍冲突，不允许自行猜测，必须先修正文档。

---

## 8. 交付要求

### 🔴 强制规则

8.1 后续所有说明、交付结论、代码评审意见、提交信息与 Git 提交说明，一律使用中文；仅在代码标识、协议字段、命令、日志键名或用户明确要求保留英文的内容中允许保留原文。

8.1.1 每次修改交付时，必须先按生产标准完成审查、验证与风险判断，不得以"开发阶段""后续再补""先合并再说"等理由降低结论口径。

8.1.2 "生产可用"只能作为基于当前真实证据的结论使用，不得作为固定套话。只有在本轮范围内满足上线前置条件、验证结果闭合、无阻断问题且不存在已知高风险缺口时，才允许在结论中写"生产可用"；否则必须明确写出：
- 当前不满足生产可用的具体原因
- 剩余阻断项
- 已验证通过的范围边界

8.1.3 只要本轮交付新增、修改或验证了任何能力，最终结论必须附具体使用说明。使用说明必须写清楚：
- 使用入口（接口、命令、主题、配置项或启动方式）
- 前置条件（依赖服务、认证、数据准备、环境变量或配置）
- 执行步骤
- 预期结果或成功判定
- 已知限制与禁用场景

### 🔴 强制检查

任务完成前必须做以下检查：

- [ ] 是否破坏了服务 owner 边界？
- [ ] 是否修改了数据库 schema？如果是，是否更新了文档？
- [ ] 是否修改了接口契约？如果是，是否更新了统一接口文档？
- [ ] 是否修改了事件 payload？如果是，是否更新了 Kafka 事件规范？
- [ ] 是否涉及安全、事务、幂等？如果是，是否符合相关规范？
- [ ] 是否涉及前端 / Figma？如果是，是否完成 Figma 截图对比、浏览器桌面与移动端验证？
- [ ] 是否涉及三端对接？如果是，后端接口文档、客户端 / 管理端 API client、错误态、权限态和认证态是否同步？
- [ ] 是否有明显的并发问题、资源泄漏、SQL 注入风险？
- [ ] 是否生成了 Git 回滚点？
- [ ] 是否在 `main` 分支？

### 🟡 建议检查

- [ ] 是否有单元测试或集成测试？
- [ ] 是否有必要的注释和文档？
- [ ] 是否遵循了命名规范？
- [ ] 是否有重复代码可以复用？

### 🟢 可选检查

- [ ] 是否使用了 JDK 25 新特性？
- [ ] 是否可以进一步优化性能？
- [ ] 是否可以简化代码逻辑？

---

## 9. 人工协作补充约束

### 🔴 强制规则

9.1 提交代码前必须对照 [完成定义](docs/process/完成定义.md) 自检。

9.2 只要本轮交付包含代码、脚本、配置或规范文档变更，结束前必须生成可回滚的 Git 提交点；若用户要求同步 GitHub，则以已 push 的 commit 作为最终回滚点。

9.3 若主导者会话向执行会话下达实施任务，任务指令中禁止使用"至少、尽量、优先、尽快、如有必要、建议、可以考虑"等模糊或弹性词汇；必须使用确定性表述，直接写清楚必须修改的文件、必须覆盖的场景、必须达到的验证结果、明确禁止事项和必须返回的交付清单。

9.4 默认不允许主导者自行新开、替换、扩增或重建会话。R1 Commander 可以按 [AI工作模式](docs/process/AI工作模式.md) 派发 R2-R10 角色，但每个角色任务都必须具备固定任务编号、任务显示名称、读写范围、禁止事项、必须读取文档、验证命令和回收清单；任务显示名称必须是本轮修改内容的概要描述，不得只写编号；R3-R10 角色不得自行决定契约变更、阶段结论或发布动作。

### 🟡 推荐规则

9.5 若使用 Git 平台提交流程，优先使用仓库中的 PR 模板。

9.6 不允许以"后续再补文档/测试/日志"为理由跳过当前交付要求。

---

## 10. 快速决策树

### 我要修改代码，需要先读文档吗？

```
开始
  ↓
是否只是纯文本级局部修订？（typo / 格式化 / 注释补充 / 日志文案收敛，不改业务语义）
  ↓
 是 → 🟢 可以直接修改
  ↓
 否 → 先按 AI协作与提示规范 的最小阅读集读取相关文档
       ↓
      是否触及以下专题？
      API / DB / 事件 / 状态机 / 安全 / 事务 / 幂等 / 缓存语义 / owner数据 / 跨服务边界
       ↓
      是 → 🔴 补读对应正式专题规范，再实施
       ↓
      否 → 按当前最小阅读集继续推进
```

### 我要修复问题清单中的问题，怎么开始？

```
开始
  ↓
用 rg / grep 搜索问题编号，查询当前状态（见"常用命令"）
  ↓
核对当前代码与文档是否一致
  ↓
问题真实存在？
  ↓
 是 → 按规范修复
  ↓
 否 → 根据当前事实收敛状态为：
      已修复（仅需同步文档）/ 不成立（不得误修）/ 后续阶段处理
```

---

## 11. 常用资源与命令

### 快速查询

- [docs/README.md](docs/README.md) — 按场景查询当前有效文档入口
- [统一问题清单](docs/process/统一问题清单.md) — 查询已知问题
- [当前开发计划](docs/setup/当前开发计划.md) — 查询项目进度

### 核心规范

- [架构方案](docs/architecture/falconx一期网关-服务-数据库架构方案.md)
- [编码规范](docs/architecture/falconx编码与测试规范.md)
- [安全规范](docs/security/安全规范.md)
- [事务与幂等规范](docs/architecture/事务与幂等规范.md)
- [全栈与 Figma 协作流程](docs/process/全栈Figma协作流程.md)

### 常用命令

```bash
# 查询问题状态
rg "FX-XXX" docs/process/统一问题清单.md docs/process/archive/统一问题清单-已归档.md -A 5

# 查看当前分支
git branch --show-current

# 切换到开发分支
git checkout main

# 运行模块测试（串行，不与 clean compile 并行）
mvn -pl falconx-{service} -am test

# 全量编译检查
mvn clean compile

# 前端验证（在 falconx-frontend 下串行执行）
npm run test
npm run lint
npm run build
```

---

## 总结

记住三个原则：

1. 🔴 **安全第一**：涉及安全、事务、幂等的必须严格遵守
2. 🟡 **证据优先**：阶段结论、问题状态和"已完成"表述都要有代码与验证证据
3. 🟢 **效率兼顾**：按最小阅读集推进，不为速度放宽正式边界

遇到不确定的情况，优先查询 [docs/README.md](docs/README.md)，或者询问用户。
