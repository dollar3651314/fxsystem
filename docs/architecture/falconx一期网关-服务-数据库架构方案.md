# FalconX v1 GODSA 最终架构方案

## 1. 架构名称

FalconX 一期采用：

`GODSA (Gateway-Orchestrated Domain Core Services Architecture)`

中文名称：

`网关编排的领域核心服务架构`

这套架构是下面几种成熟模式的组合：

- `API Gateway`
- `Database per Service`
- `Event-Driven Integration`
- `API Composition`

本文档只负责：

- 服务拓扑
- 模块分层
- owner 边界
- 数据流
- 高层实施阶段

具体开发顺序和阶段验收，见：

- [开发启动手册](../setup/开发启动手册.md)

## 2. 设计目标

一期目标不是做“纯单体”，也不是把每个业务点都提前拆成独立微服务。

一期追求的是：

- 服务边界清晰
- 同步交易链路稳定
- 跨域协作尽量少
- 后续可继续拆分

一期明确不采用：

- `trade / fund / risk` 三服务强行拆开
- 高频业务场景依赖大量服务间同步互调
- 网关承担核心业务编排

## 3. 最终服务拓扑

一期服务固定为 5 个可独立启动程序：

- `falconx-gateway`
- `falconx-identity-service`
- `falconx-market-service`
- `falconx-trading-core-service`
- `falconx-wallet-service`

共享库只作为代码复用，不单独部署：

- `falconx-common`
- `falconx-domain`
- `falconx-infrastructure`

如需共享服务契约，允许按服务增加独立 `contract` 模块，例如：

- `falconx-identity-contract`
- `falconx-market-contract`
- `falconx-trading-contract`
- `falconx-wallet-contract`

这些 `contract` 模块只承载对外契约，不承载数据库和内部实现。

## 4. 模块分层原则

为了减少代码冗余，同时不破坏微服务 owner 边界，一期固定采用下面的模块分层。

### 4.1 `falconx-common`

定位：

- 跨服务通用工具模块

允许放入：

- 统一响应体
- 错误码
- 基础异常
- ID 生成器
- 时间工具
- `BigDecimal` 精度工具
- 通用常量
- 通用校验工具
- 公共测试工具

禁止放入：

- 业务 DTO
- 业务 Entity
- Mapper
- Repository
- 某个服务 owner 的业务逻辑

### 4.2 `falconx-domain`

定位：

- 领域原语与跨服务稳定领域对象共享模块

允许放入：

- `DomainEvent` 接口或抽象基类（用于 Outbox 泛型支持）
- 跨服务公用枚举，例如：`OrderSide`、`PositionSide`、`ChainType`、`Currency`
- 领域原语值对象，例如：`Money`、`Quantity`、`Symbol`
- 不含任何数据库 annotation、Spring Bean、业务 DTO

禁止放入：

- 任何 Entity（带 `@Entity` 或 `@TableName` 的类）
- 任何 Repository、Mapper
- 任何业务 DTO
- 服务级别的业务逻辑

说明：

- `falconx-domain` 不单独部署，作为共享库被各服务依赖
- 只有真正跨服务稳定复用的领域原语才放入此模块
- 不得把某个服务的"领域模型"集中塞进 domain 模块（仍然遵守 owner 规则）

### 4.3 `falconx-infrastructure`

定位：

- 技术底座复用模块

允许放入：

- MyBatis 公共配置
- Jackson 配置
- Redis 配置
- Kafka 公共消息信封
- Outbox/Inbox 公共支持组件
- RestClient/WebClient 公共配置
- 安全基础配置片段
- 第三方技术适配基础类

禁止放入：

- `t_user / t_account / t_order` 这类业务表的 Mapper
- 业务 DTO
- 业务 Entity
- 具体服务的 Repository

### 4.4 `contract` 模块

定位：

- 服务对外契约共享模块

只在确实需要减少跨服务接口重复定义时引入。

允许放入：

- Request DTO
- Response DTO
- Query DTO
- 事件 payload DTO（跨服务消费所需的事件契约类）
- Client Interface
- 对外稳定枚举

禁止放入：

- Mapper
- Entity
- Repository
- Service 实现
- 数据库配置

### 4.5 服务内部模块

每个服务内部自己维护：

- `dto`
- `command`
- `query`
- `entity`
- `mapper`
- `repository`
- `service`
- `controller`

固定规则：

- DTO 默认归服务 owner
- Entity 默认归服务 owner
- Mapper 默认归服务 owner
- Repository 默认归服务 owner

原因：

- DTO 属于接口契约，天然有 owner
- Mapper 和 Repository 直接绑定表结构，必须跟随数据库 owner
- 把这些内容集中放到基础设施层，会破坏 `Database per Service`

### 4.6 一期推荐模块清单

一期建议保留下面这些模块：

- `falconx-common`
- `falconx-domain`
- `falconx-infrastructure`
- `falconx-gateway`
- `falconx-identity-contract`
- `falconx-market-contract`
- `falconx-trading-contract`
- `falconx-wallet-contract`
- `falconx-identity-service`
- `falconx-market-service`
- `falconx-trading-core-service`
- `falconx-wallet-service`

### 4.7 模块依赖关系

固定依赖方向如下：

```text
falconx-common
    ^
    |
falconx-domain
    ^
    |
falconx-infrastructure

falconx-identity-contract  -> falconx-common, falconx-domain
falconx-market-contract    -> falconx-common, falconx-domain
falconx-trading-contract   -> falconx-common, falconx-domain
falconx-wallet-contract    -> falconx-common, falconx-domain

falconx-gateway
  -> falconx-common
  -> falconx-domain
  -> falconx-infrastructure
  -> falconx-identity-contract
  -> falconx-market-contract
  -> falconx-trading-contract
  -> falconx-wallet-contract

falconx-identity-service
  -> falconx-common
  -> falconx-domain
  -> falconx-infrastructure
  -> falconx-identity-contract
  -> falconx-trading-contract

falconx-market-service
  -> falconx-common
  -> falconx-domain
  -> falconx-infrastructure
  -> falconx-market-contract

falconx-trading-core-service
  -> falconx-common
  -> falconx-domain
  -> falconx-infrastructure
  -> falconx-trading-contract

falconx-wallet-service
  -> falconx-common
  -> falconx-domain
  -> falconx-infrastructure
  -> falconx-wallet-contract
```

约束：

- 不允许服务模块直接依赖其他服务模块
- `gateway` 只依赖 contract，不依赖下游 service 实现
- `identity-service` 消费 `falconx.trading.deposit.credited` 时，依赖 `falconx-trading-contract` 中的事件契约
- 事件 payload DTO 优先归属对应服务的 `contract` 模块，而不是 `domain`
- `falconx-domain` 只允许依赖 `falconx-common`
- `falconx-infrastructure` 允许依赖 `falconx-common` 和 `falconx-domain`

## 5. 服务职责边界

### `falconx-gateway`

职责：

- 统一入口
- JWT 鉴权
- 请求路由
- 生成并透传内部 `traceId`
- 读接口聚合
- 统一错误响应

禁止事项：

- 不直接写任何业务库
- 不承载订单、账务、强平等核心业务
- 不承担分布式事务编排

### `falconx-identity-service`

职责：

- 用户注册
- 邮箱密码登录
- JWT 签发与刷新
- 用户状态维护

owner 数据：

- `falconx_identity.t_user`

### `falconx-market-service`

职责：

- 外部报价接入
- 报价标准化
- 点差与标记价处理
- 交易时间与节假日规则维护
- Redis 行情缓存
- Redis 交易时间快照缓存
- ClickHouse 报价写入
- K 线生成与 ClickHouse 持久化
- 报价事件发布

owner 数据：

- `falconx_market.t_symbol`
- `falconx_market.t_trading_hours`
- `falconx_market.t_trading_hours_exception`
- `falconx_market.t_trading_holiday`
- `falconx_market.t_swap_rate`
- `falconx_market.t_outbox`

owner 分析库：

- `falconx_market_analytics.quote_tick`
- `falconx_market_analytics.kline`

owner 缓存与事件：

- Redis 行情 key
- Redis 交易时间快照 key
- `falconx.market.price.tick`
- `falconx.market.kline.update`

### `falconx-trading-core-service`

职责：

- 订单
- 持仓
- 成交
- 账户
- 账本
- 保证金
- 风控校验
- 强平执行

说明：

- 一期把 `trade + fund + risk` 合并为一个领域核心服务。
- 这是同步交易事务边界，不再拆成多个独立服务。

owner 数据：

- `falconx_trading.t_account`
- `falconx_trading.t_ledger`
- `falconx_trading.t_deposit`
- `falconx_trading.t_order`
- `falconx_trading.t_position`
- `falconx_trading.t_trade`
- `falconx_trading.t_risk_exposure`
- `falconx_trading.t_risk_config`
- `falconx_trading.t_hedge_log`
- `falconx_trading.t_liquidation_log`

内部引擎：

- `quote-driven-engine`

### `falconx-wallet-service`

职责：

- Web3 地址分配
- 多链入金监听
- 原始链上交易落库
- 确认数推进
- 入金确认事件发布

owner 数据：

- `falconx_wallet.t_wallet_address`
- `falconx_wallet.t_wallet_deposit_tx`
- `falconx_wallet.t_wallet_chain_cursor`

owner 事件：

- `falconx.wallet.deposit.detected`
- `falconx.wallet.deposit.confirmed`
- `falconx.wallet.deposit.reversed`

## 6. 调用与协作规则

### 6.1 北向调用

外部请求统一遵守：

`Client -> Gateway -> Owning Service`

例如：

- 注册登录：`Client -> gateway -> identity-service`
- 查询行情：`Client -> gateway -> market-service`
- 下单平仓：`Client -> gateway -> trading-core-service`
- 钱包地址申请：`Client -> gateway -> wallet-service`

### 6.2 东西向协作

一期默认规则：

- 不设计常规服务间同步互调
- 不要求 `identity / market / trading-core / wallet` 彼此直接发 HTTP 请求
- 跨域协作优先通过 `Kafka + Redis` 完成

允许的主协作方式：

- `market-service -> Redis + Kafka`
- `wallet-service -> Kafka`
- `trading-core-service` 消费市场与钱包事件

### 6.3 同步 HTTP 的保留策略

如果后续确实出现必须同步调用的场景，官方优先方案固定为：

- `RestClient`
- `HTTP Service Interface Clients`

但这不是一期默认路径。

一期默认路径仍然是：

- 外部请求经 `gateway`
- 跨服务业务协作走异步事件

## 7. 报价、订单实时计算与 K 线归属

这是一期必须冻结的边界。

### 放在 `market-service` 的能力

- 接 LP Socket.IO / 外部报价
- 标准化 tick
- 生成内部标准价格对象
- 计算 `bid / ask / mid / mark`
- 写 Redis 最新价格
- 生成并更新 K 线
- 发布价格事件

### 放在 `trading-core-service` 的能力

- 消费价格事件
- 扫描待触发订单
- 计算持仓浮盈浮亏
- 计算保证金率
- 触发止盈止损
- 触发强平

结论：

- `K线` 属于 `market-service`
- `订单实时计算` 属于 `trading-core-service`
- 一期不额外拆独立“实时计算服务”

## 8. 数据流

### 8.1 行情链路

`LP Socket.IO -> market-service -> Redis + ClickHouse + Kafka`

然后：

`trading-core-service <- Kafka(price.tick)`

用途：

- 更新未实现盈亏
- 判定限价/止损触发
- 判定强平

### 8.2 入金链路

`Wallet Listener -> wallet-service -> Kafka(deposit.confirmed) -> trading-core-service`

然后：

- `trading-core-service` 入账
- 写 `t_account`
- 写 `t_ledger`
- 写业务入金记录 `t_deposit`

### 8.3 聚合查询链路

`Client -> gateway -> parallel query -> compose response`

例如首页资产页可由 `gateway` 聚合：

- 用户信息
- 账户资产
- 持仓信息
- 市场行情快照

## 9. 数据库策略

一期采用：

- `1` 个 MySQL 实例
- `1` 个 ClickHouse 实例
- `4` 个 MySQL 逻辑 schema
- `1` 个 ClickHouse 分析库
- 严格 `Database per Service`

推荐 schema：

- `falconx_identity`
- `falconx_market`
- `falconx_trading`
- `falconx_wallet`

推荐 ClickHouse database：

- `falconx_market_analytics`

网关默认不持有业务 schema。

约束：

- 服务只写自己的 schema
- 不跨服务直连数据库
- 不共享业务表
- K 线和报价历史不写 MySQL，统一写 ClickHouse

## 10. 技术基线

- `JDK 25`
- `Spring Boot 4.0.5`
- `Spring Cloud 2025.1.0`
- `Spring Cloud Gateway 5.0.1`
- `Spring Security 7.x`
- `MyBatis-Plus 3.5.15`
- `MySQL 8.4`
- `Redis 8.2.x`
- `Redisson 4.3.0`
- `Kafka Client 4.1.x`
- `Flyway 11.x`
- `ClickHouse 25.x`
- `clickhouse-jdbc 0.9.7`
- `Web3j 5.0.0`
- `Trident 0.9.2`
- `Solanaj 1.20.4`
- `Lombok`
- `WebSocket`
- `JUnit 5`
- `Testcontainers`

JSON 技术栈补充说明：

- 目标架构要求：`Jackson 3.x`
- 当前仓库实际解析版本：`Jackson 2.21.2`
- 原因：当前服务代码仍使用 `com.fasterxml.jackson.*` 兼容路径，尚未完成 `Jackson 3` 专项迁移
- 因此当前阶段只能冻结为：`禁止继续引入新的 Jackson 2 自定义版本覆盖`，并将 `Jackson 3.x` 作为后续明确迁移目标
- 在未完成代码与依赖双迁移前，不得在文档或结论中声称“仓库已统一到 Jackson 3+”

调用原则：

- 网关使用官方网关能力做北向路由
- 一期默认不建设大量东西向 HTTP 调用
- 若必须同步调用，使用 `RestClient + HTTP Service Clients`

## 11. 高层实施阶段

### Stage 0：文档冻结

- 冻结服务边界
- 冻结数据库 owner
- 冻结市场数据边界
- 冻结事件主题命名

### Stage 1：共享模块与服务骨架

建立共享模块、契约模块和 5 个独立可启动程序：

- `common`
- `domain`
- `infrastructure`
- `identity-contract`
- `market-contract`
- `trading-contract`
- `wallet-contract`
- `gateway`
- `identity-service`
- `market-service`
- `trading-core-service`
- `wallet-service`

### Stage 2：市场与钱包事件底座

- `market-service` 完成报价接入骨架
- `market-service` 完成 ClickHouse 报价与 K 线写入骨架
- `wallet-service` 完成链监听骨架
- `Kafka` 主题与消息信封固定

### Stage 3：身份与交易核心底座

- `identity-service` 完成注册、登录、JWT 与入金事件消费
- `trading-core-service` 完成账户、账本、订单、持仓、风险模块骨架
- 内部 `quote-driven-engine` 落位

### Stage 4：网关与服务框架集成

- `gateway` 完成路由、鉴权、聚合查询骨架
- 最小闭环联调进入可执行状态

说明：

- 该阶段只代表“统一入口与服务骨架可协同工作”
- 不代表真实基础设施、真实外部源或真实数据流已经完成

### Stage 5：数据持久化与基础设施接入

- 4 个 MySQL schema 真实接入
- Flyway migration 正式启用
- Redis / Redisson / ClickHouse JDBC 真正落位
- 关键 owner 数据不再依赖内存仓储

### Stage 6：外部源与安全完善

- `Stage 6A`：外部报价源、链节点 RPC、Kafka 真接入
- `Stage 6B`：密钥外部化、黑名单、限流等安全完整化

### Stage 7：端到端业务闭环

- 注册 / 登录 / 入金 / 行情 / 下单形成最小可用闭环
- 错误码、日志与 `traceId` 支持全链路排查

详细实施顺序和每阶段验收标准，见：

- [开发启动手册](../setup/开发启动手册.md)

## 12. 配套治理文档

后续实施必须同时参考下面这些文档：

- [FalconX ADR 目录](../adr/README.md)
- [日志打印规范](./日志打印规范.md)
- [REST 接口规范](../api/REST接口规范.md)
- [WebSocket 接口规范](../api/WebSocket接口规范.md)
- [Kafka 事件规范](../event/Kafka事件规范.md)
- [状态机规范](../domain/状态机规范.md)
- [事务与幂等规范](./事务与幂等规范.md)
- [安全规范](../security/安全规范.md)
