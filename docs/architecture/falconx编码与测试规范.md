# FalconX v1 编码与测试规范

## 1. 适用范围

本规范适用于 FalconX v1 后续创建的全部 Java 服务：

- `falconx-gateway`
- `falconx-identity-service`
- `falconx-market-service`
- `falconx-trading-core-service`
- `falconx-wallet-service`

本规范是实施阶段的强制规则，不是建议项。

## 1.1 与模块分层的关系

后续写代码时，必须同时遵守主架构文档中的模块分层原则。

特别是下面这条不能违反：

- 不允许把业务 DTO、Entity、Mapper、Repository 集中塞进 `common` 或 `infrastructure`

默认归属规则：

- 通用工具进 `common`
- 技术底座进 `infrastructure`
- 对外契约进 `contract`
- DTO、Entity、Mapper、Repository 进服务 owner

日志规范同样属于强制规则，必须参考：

- [FalconX v1 日志打印规范](./日志打印规范.md)

## 1.2 数据访问与复用规则

后续所有数据库实现必须遵守下面 3 条硬规则：

1. 所有运行时业务数据库访问一律使用 `MyBatis + XML Mapper` 实现 SQL。
2. 禁止在 Java 代码中写运行时业务 SQL 字符串。
3. 禁止再用 `In-Memory Repository` 代替 owner 服务的真实数据库查询实现。

这里的“禁止在 Java 代码中写运行时业务 SQL 字符串”包括但不限于：

- `JdbcClient.sql(\"...\")`
- `JdbcTemplate`
- `EntityManager.createNativeQuery(\"...\")`
- Repository / Service / Application 层中直接拼接 SQL 字符串

不在此限制内的内容：

- `Flyway migration`
- `docs/sql` 与 `resources/db/*` 中的 SQL 脚本
- 启动阶段执行的 DDL / schema 初始化脚本文件

统一做法：

- SQL 只允许写在 `src/main/resources/mapper/*.xml`
- `Mapper` 接口只声明方法签名
- `Repository` 负责组织查询条件和调用 `Mapper`
- owner 服务的数据库读写都必须落到真实 `Mapper + XML` 查询

补充约束：

- 若后续需要本地假数据或骨架演示，必须明确标注为 `stub`，且不得替代正式 owner 查询链路
- 一旦进入真实实现阶段，默认先写 `Mapper`、`XML`、`Repository`，再写上层业务逻辑
- 若任务来源于问题审计或补充事项文档，必须先核对“问题是否仍存在”，再决定是否改代码；已解决的问题只允许修正文档，不允许重复发明实现

关于复用，额外补充一条强制规则：

- 可以抽象复用的代码尽量复用，但复用不能破坏服务 owner 边界
- 产品元数据、symbol 白名单、市场分类、精度、杠杆与交易状态这类 owner 数据，不得在运行时代码里写死返回；必须来自 owner 服务的正式数据源、owner 写入的共享快照，或冻结后的 contract/seed 数据
- 若运行时需要消费另一服务产出的产品配置结果，必须依赖 owner 产出的正式共享数据，不得在本地配置类、常量类或 `List.of(...)` 中再维护一份静态产品清单
- `market-service` 的 LP 行情订阅白名单必须只来源于 `t_symbol.status=1` 的 owner 数据；数据库启停状态变化应通过热刷新生效，不得再要求通过重启服务加载新的 symbol 白名单
- 当外部源本身承载多资产类别时，运行时放行范围仍然只能由 owner 启用品种决定，不得再额外假定“只接某一种 market_code”
- 若需要验证“产品启停、白名单变化、市场配置变更”，测试必须通过真实 owner 数据库更新驱动，再经 `Mapper + XML + Repository` 链路读取；禁止用内存集合替代数据库状态变更

优先复用的内容包括：

- DTO / Entity 转换器
- 公共校验逻辑
- 统一分页与查询条件对象
- Mapper XML 中的 `sql fragment`
- 公共异常转换
- 重复的日志字段组装

禁止为了省代码而做的错误复用包括：

- 跨服务共用业务 Mapper
- 跨服务共用业务 Repository
- 把某个 owner 服务的查询逻辑上收到 `common` 或 `infrastructure`
- 用一个内存实现同时冒充正式实现和测试替身

## 1.3 跨服务缓存与契约兼容规则

凡是写入 Redis、Kafka 或其他被下游服务直接消费的 JSON 结构，都必须满足：

1. 必须明确字段 owner 和来源模块。
2. 必须明确 TTL、刷新策略和 cache miss 策略。
3. 新增字段不得导致现有消费者反序列化失败。
4. 若不是直接使用 `contract` 模块 DTO，则消费者模型必须具备前向兼容能力。

推荐做法：

- HTTP / Kafka 正式契约优先放进 `contract` 模块
- Redis 快照若由两个服务共享，消费者模型默认应允许忽略未知字段
- 基于全量外部订阅的本地过滤型实现，必须补充“热刷新策略 + 刷新失败保留上一轮状态”的设计，避免数据库瞬时故障把运行中白名单清空
- 基于外部 SDK、WebSocket 或客户端库的回调型接入，必须先切到应用自管线程或执行器，再进入 Kafka、Redis、DB、ClickHouse 或应用服务链路；不得把完整业务处理直接挂在第三方回调线程上

禁止：

- 生产者随意扩字段而不验证消费者
- 用“当前测试没报错”代替兼容性设计

## 1.4 JDK 25 与 Spring Boot 4 编码基线

FalconX 当前技术基线固定为 `JDK 25 + Spring Boot 4`。后续新增代码应在满足稳定性、可读性和仓库边界约束的前提下，优先采用这一代正式能力，而不是无故退回旧写法。

强制规则：

1. 优先使用 `JDK 25` 已正式可用、能明显提升可读性或性能的语言与并发能力。
2. 优先使用 `Spring Boot 4 / Spring Framework 7` 官方推荐的 API 与集成方式。
3. 不得在无明确理由的情况下继续引入已被新基线替代的旧风格实现。
4. 若因为兼容性、稳定性、可维护性或与现有架构冲突而不采用新特性，必须在代码注释或实现说明中写明原因。

JSON 技术栈补充约束：

1. 目标版本统一为 `Jackson 3.x`。
2. 当前仓库尚未完成 `Jackson 3` 专项迁移，实际仍运行在 `Jackson 2.21.2` 兼容路径。
3. 在专项迁移未启动前，不得新增 Jackson 2 的自定义版本覆盖，也不得在结论中把当前状态表述为“已统一到 Jackson 3+”。
4. 若后续启动 Jackson 3 迁移，必须把以下内容作为同一批工作处理：
   - Maven 依赖与 BOM 策略
   - `com.fasterxml.jackson.*` 到新坐标体系的影响评估
   - ObjectMapper / JsonMapper 配置兼容
   - Kafka / Redis / HTTP 序列化链路回归测试

推荐优先使用的能力包括：

- `record`、增强 `switch`、模式匹配、不可变数据表达
- 更合理的并发模型与 JDK 新版集合/时间 API
- `Spring Boot 4` 推荐的配置绑定、HTTP 客户端、序列化与错误处理方式
- 在适合的 I/O 场景下评估更高吞吐或更低阻塞的官方性能方案

明确限制：

- 不为了“追新”而牺牲可读性
- 不为了使用新特性而破坏服务 owner 边界
- 不得绕过仓库既定规范（如 `MyBatis + XML`、统一错误码、统一日志与接口文档规则）

## 2. 注释规则

### 2.1 总体要求

后续新增的所有 Java 文件都必须带有清晰注释，用来说明：

- 这段代码解决什么问题
- 为什么这样实现
- 输入输出是什么
- 状态变化和副作用是什么
- 与上下游的调用关系是什么

详细业务注释的重点对象是：

- 公开类
- 公开方法
- 复杂业务逻辑
- 状态迁移逻辑
- 外部依赖调用点
- 事务、幂等和补偿逻辑
- 复杂规则判断与高风险账务逻辑

简单 DTO、record、配置类、测试支撑类不要求机械重复写“事务边界/副作用”等长说明，但仍必须至少有类注释，并在字段或方法上保留必要语义说明。

禁止出现下面两种情况：

- 只有代码，没有任何实现说明
- 只有表面注释，没有解释业务意图和边界

### 2.2 类注释要求

每个公开类都必须有类级注释，至少说明：

- 模块归属
- 角色职责
- 是否属于入口层、应用层、领域层或基础设施层
- 是否会访问数据库、Redis、Kafka 或外部服务

示例要求：

- `Controller` 要说明对外暴露什么接口
- `Service` 要说明承担哪一段业务流程
- `Consumer` 要说明消费哪个 Topic 和处理语义
- `Repository/Mapper` 要说明操作哪张表

### 2.3 方法注释要求

下面的方法必须有方法级注释：

- 公开方法
- 涉及状态迁移的方法
- 涉及账务、订单、持仓、强平、入金的方法
- 涉及外部调用的方法
- 涉及缓存读写和消息发布的方法

方法注释至少要说明：

- 入参业务含义
- 核心处理步骤
- 事务范围
- 幂等点
- 失败时的处理策略

### 2.4 关键代码块注释要求

除了类和方法注释，复杂代码块前也必须有简短说明。

典型必须加块注释的场景：

- 保证金计算
- 盈亏计算
- 强平条件判断
- 行情 stale 判定
- Web3 确认数推进
- Kafka 事件去重
- 多层业务条件组合判断
- 账户余额、保证金、费用、盈亏等账务更新

块注释最低要求：

- 为什么这样分支
- 这段逻辑影响哪些状态或金额
- 为什么不能用更简单的实现替代

### 2.5 DTO 与枚举注释要求

- 请求 DTO 要注明字段业务语义和单位
- 响应 DTO 要注明字段来源和展示语义
- 枚举要注明每个状态值的业务含义

补充规则：

- `contract` 模块中的 DTO 也必须有同样详细的注释
- 服务内部 DTO 不因为“只在内部使用”而省略注释

## 2.6 日志要求

所有关键业务节点都必须输出可检索日志。

至少包括：

- 请求入口
- 关键状态迁移
- 外部依赖调用
- 事务成功
- 事务失败
- 事件发布和消费失败

具体规则以 [FalconX v1 日志打印规范](./日志打印规范.md) 为准。

补充规则：

- `traceId` 必须通过日志上下文自动输出
- 不允许在业务代码中反复手工打印 `traceId`

## 2.7 Lombok 使用规则

允许使用 `Lombok` 减少样板代码，但必须受控使用。

允许范围：

- DTO
- Command / Query
- 简单值对象
- 配置属性类

限制：

- 涉及复杂业务语义的实体类，不允许只依赖 `@Data` 掩盖字段含义
- 关键实体若使用 Lombok，仍必须保留类注释和字段业务注释
- 禁止为了省事让 Lombok 替代业务语义说明

建议：

- DTO 优先使用 `@Getter` / `@Setter`
- 构造类优先使用 `@RequiredArgsConstructor`
- 谨慎使用 `@Builder`，避免隐藏必填字段语义

## 2.8 Git 提交与回滚点规则

所有涉及仓库内容修改的交付，都必须形成可回滚的 Git 变更点。

强制规则：

1. 每次完成一轮代码、脚本、配置或规范文档修改并准备交付时，必须至少生成一个语义清晰的 Git commit。
2. 若用户明确要求同步 `GitHub` 或远程仓库，该 commit 必须 push 到默认远程，作为可直接回退的 GitHub 回滚点。
3. 若工作区同时存在与本轮无关的未提交修改，必须先隔离（如 `stash`、独立分支或独立提交），禁止把无关改动混入当前回滚点。
4. 未形成回滚点前，不得把“本轮已完成”写入交付结论。
5. 默认开发分支固定为 `main`；除非用户明确要求处理 `main` 或执行合并发布，常规开发 commit / push 必须落在 `main`。

补充说明：

- 纯分析、只读排查或未改动仓库文件的会话，不强制要求提交 commit。
- commit message 应明确本轮变更范围，避免生成无法定位用途的模糊提交。
- 若当前会话起点不在 `main`，且任务不要求处理其他分支，应先切换到 `main` 再开始实现。

## 3. 测试规则

### 3.1 总体要求

后续每个服务都必须同时具备：

- 单元测试
- 集成测试
- 关键链路测试

只写主代码、不写测试，不视为完成。

### 3.2 单元测试要求

单元测试重点覆盖：

- 纯业务计算
- 状态机迁移
- 幂等逻辑
- 参数校验
- 异常分支

必须重点测试的内容：

- `identity-service`：注册、登录、密码校验、JWT 生成与解析
- `market-service`：报价标准化、stale 判定、K 线聚合
- `trading-core-service`：保证金计算、手续费计算、盈亏计算、强平判断、订单触发
- `wallet-service`：确认数推进、状态迁移、重复交易去重
- `gateway`：路由规则、鉴权过滤、聚合响应组装

### 3.3 集成测试要求

集成测试要验证：

- Controller 到 Service 到 Repository 的完整调用
- 数据库写入是否正确
- Redis 读写是否符合契约
- Kafka 发布或消费是否符合预期

建议默认技术：

- `JUnit 5`
- `Spring Boot Test`
- `Testcontainers`
- `MockWebServer` 或等价 HTTP mock 工具

### 3.4 规则型逻辑测试要求

涉及下面任一类逻辑时，测试不能只覆盖主路径：

- 交易时间
- 状态机
- 风控规则
- 行情 stale 判定
- 费用 / 结算规则
- 幂等与重试

必须至少覆盖：

- 正常路径
- 文档中显式列出的边界场景
- cache miss / 数据缺失场景
- 跨日 / 跨午夜 / 多时段 / 特殊日期等时间边界

若专题文档中已有检查表或待办场景，测试完成前必须逐项关闭或明确保留到后续阶段。

### 3.4 关键链路测试要求

每个服务至少要有一个“最小可用链路测试”：

- `gateway`：请求进入网关后正确鉴权并路由
- `identity-service`：注册后可登录，登录后可取到合法 token
- `market-service`：收到报价后能写 Redis 并产出标准事件
- `trading-core-service`：下单后能完成账户校验、订单落库、持仓或拒单结果
- `wallet-service`：检测到交易后能推进到确认完成并发出事件

补充要求：

- 关键链路测试应覆盖主要日志节点是否被触发
- 测试中不得输出敏感信息到日志

## 3.5 接口文档交付要求

每次接口开发并测试通过后，必须同步更新统一接口文档：

- [FalconX统一接口文档](../api/FalconX统一接口文档.md)

适用范围：

- REST 接口
- WebSocket 接口
- 后续若启用的内部接口

最低要求：

- 接口基础信息完整
- 请求与响应示例完整
- 错误码完整
- 认证要求完整
- 测试结论完整

未更新统一接口文档，不视为接口任务完成。

## 4. 服务骨架包结构与职责

后续每个服务都要尽量使用统一包结构。

补充规则：

- `mapper` 和 `repository` 只允许出现在 owner 服务内部
- `mapper` 必须配套 XML，不允许只在注解或代码字符串里写 SQL
- 不允许在 `common` 或 `infrastructure` 中新增业务表 Mapper
- 若某个对外接口 DTO 需要被网关和服务同时复用，应优先落到该服务的 `contract` 模块

## 4.1 `falconx-gateway`

推荐包结构：

- `config`
- `security`
- `filter`
- `controller`
- `client`
- `assembler`
- `exception`

每层应该写什么：

- `config`：网关配置、路由配置、HTTP 客户端配置
- `security`：JWT 校验、认证上下文
- `filter`：traceId、鉴权、日志、限流
- `controller`：少量聚合查询入口
- `client`：调用下游服务的 typed client
- `assembler`：聚合多个服务响应
- `exception`：统一异常映射

调用链：

`Client -> Gateway Filter -> Security -> Route/Controller -> Downstream Client -> Response Assembler -> Client`

## 4.2 `falconx-identity-service`

推荐包结构：

- `controller`
- `consumer`
- `application`
- `service`
- `repository`
- `entity`
- `dto`
- `command`
- `query`
- `config`

每层应该写什么：

- `controller`：注册、登录、刷新 token、用户信息接口
- `consumer`：消费 `falconx.trading.deposit.credited`，完成入金事件幂等留痕，并对历史 `PENDING_DEPOSIT` 状态做归一化
- `application`：注册和登录用例编排
- `service`：密码校验、token 生成、状态变更
- `repository`：`t_user` 读写
- `entity`：用户实体和状态枚举
- `dto`：请求响应对象
- `command`：写命令对象
- `query`：读查询对象
- `config`：安全和持久化配置

调用链：

`Controller -> Application Service -> Domain Service -> Repository -> DB`

## 4.3 `falconx-market-service`

推荐包结构：

- `controller`
- `application`
- `service`
- `provider`
- `cache`
- `analytics`
- `repository`
- `consumer`
- `producer`
- `entity`
- `dto`
- `config`

每层应该写什么：

- `controller`：symbol 查询、最新价查询、K 线查询
- `application`：行情对外用例编排
- `service`：标准化、K 线计算、stale 判定
- `provider`：LP Socket.IO 或其他行情源适配
- `cache`：Redis 最新价和 K 线缓存
- `analytics`：ClickHouse 的 `quote_tick` 和 `kline` 写入、查询
- `repository`：`t_symbol`、`t_outbox` 读写
- `consumer`：预留二期外部事件消费
- `producer`：价格事件和 K 线事件发布
- `entity`：symbol、kline、quote 模型
- `dto`：对外查询 DTO
- `config`：WebSocket、Kafka、Redis 配置

调用链：

`LP Socket.IO -> Provider -> Quote Standardizer -> Redis/ClickHouse -> Kafka -> Query API`

## 4.4 `falconx-trading-core-service`

推荐包结构：

- `controller`
- `application`
- `service`
- `engine`
- `repository`
- `producer`
- `consumer`
- `entity`
- `dto`
- `command`
- `query`
- `calculator`
- `config`

每层应该写什么：

- `controller`：下单、平仓、资产、持仓、订单查询
- `application`：交易用例编排
- `service`：账户、订单、持仓、账本、风险业务服务
- `engine`：`quote-driven-engine`、OPEN 持仓内存快照、TP/SL 触发和强平扫描
- `repository`：`t_account / t_ledger / t_deposit / t_order / t_position / t_trade / t_risk_config / t_liquidation_log`
- `producer`：交易结果、账变结果、入金入账结果、强平结果事件
- `consumer`：消费市场价格事件和钱包入金事件
- `entity`：账户、订单、持仓、成交、风控参数等实体
- `dto`：请求响应对象
- `command`：下单、平仓、确认入账等命令
- `query`：账户、持仓、订单查询对象
- `calculator`：保证金、手续费、盈亏、强平价格计算
- `config`：事务、Kafka、Redis、DB 配置

调用链一：同步下单

`Controller -> Application -> Account/Order/Risk Service -> Repository -> DB -> Producer`

调用链二：实时触发

`Kafka Price Tick -> Consumer -> Quote-Driven Engine -> Calculator/Service -> Repository -> Producer`

补充约束：

- `QuoteDrivenEngine` 运行时只允许读取内存持仓快照和 Redis 最新价
- 不允许在每个 tick 里直接查 MySQL 扫描持仓
- 启动时必须从 DB 装载 `OPEN` 持仓构建快照索引

调用链三：入金入账

`Kafka Deposit Confirmed -> Consumer -> Account/Ledger Service -> Repository -> DB -> Producer`

## 4.5 `falconx-wallet-service`

推荐包结构：

- `controller`
- `application`
- `service`
- `listener`
- `repository`
- `producer`
- `entity`
- `dto`
- `config`

每层应该写什么：

- `controller`：钱包地址申请、入金记录查询
- `application`：钱包用例编排
- `service`：地址分配、确认推进、重复交易处理
- `listener`：EVM、Tron、Solana 链监听器
- `repository`：`t_wallet_address / t_wallet_deposit_tx / t_wallet_chain_cursor`
- `producer`：入金检测、确认、回滚事件发布
- `entity`：地址、链交易、游标实体
- `dto`：地址申请和交易查询 DTO
- `config`：链节点、Kafka、DB 配置

调用链：

`Chain Listener -> Wallet Service -> Repository -> DB -> Kafka Producer`

## 5. 每次开发后的验收要求

每次实现一个功能点后，至少要完成：

1. 主代码注释完整  
2. 单元测试覆盖核心逻辑  
3. 集成测试覆盖主调用链  
4. 当前服务可独立启动  
5. 与上下游契约保持一致  

如果改动涉及跨服务协作，还必须补：

6. 事件契约测试或接口契约测试  

## 6. 当前结论

FalconX v1 后续实施必须同时满足三件事：

- 代码有详细注释
- 测试完善
- 服务骨架和调用链有明确说明

后续任何服务骨架落地时，都必须以本文件作为实施基线。
