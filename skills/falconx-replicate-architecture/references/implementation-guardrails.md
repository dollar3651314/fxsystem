# FalconX 复刻实施硬约束

## 1. owner 与边界

- 严格遵守 `Database per Service`
- 不允许跨服务访问他方 owner 表
- 不允许跨服务共用业务 Mapper、业务 Repository 或查询实现
- `gateway` 只依赖 contract，不依赖下游 service 实现

## 2. 数据访问

- 业务查询通过 `MyBatis + XML Mapper`
- 不在 `Controller / Application / Service` 中拼 SQL
- 不用运行时硬编码白名单替代 owner 数据源

## 3. 事件实现

### 低频关键业务事件

- 走 `Outbox / Inbox`
- payload 放入对应 `contract` 模块
- listener 只做接收、反序列化和委托
- 业务处理切到应用自管线程
- 显式恢复 `traceId`、MDC、TCCL

### 高频行情事件

- 允许直接 Kafka，不走 Outbox / Inbox
- 当前代表路径是 `falconx.market.price.tick`
- 消费端仍然不能在回调线程执行业务链路

## 4. 回调线程规则

下列场景都不能直接执行业务链路：

- `@KafkaListener`
- WebSocket 回调
- 外部 SDK listener
- 链节点监听线程

必须先切到应用自管线程或执行器，再进入：

- DB
- Redis
- ClickHouse
- 复杂 ApplicationService
- 事务链路

## 5. 当前实现事实必须保留

- `wallet` 地址分配当前只是应用层 stub 持久化
- 当前没有正式 `POST /api/v1/wallet/addresses`
- 当前正式北向 WebSocket 只有 `ws://{host}/ws/v1/market`
- 当前系统不能写成“生产可用”
- 当前是 `B-book` 口径，不默认补 A-book

## 6. 统一技术口径

- Java `25`
- Spring Boot `4.0.5`
- Jackson `3.1.0`
- `tools.jackson.*` 作为主路径
- `jackson-annotations` 仍为 `com.fasterxml.jackson.annotation.*`

## 7. 文档与验证

如果你不只是输出架构说明，而是实际落代码，必须同步：

- 接口文档
- 事件文档
- 当前计划文档
- README 或对应模块 README

验证时遵守：

- `mvn test`、`mvn clean compile` 等会影响同一 `target/` 的命令串行执行
- 关键业务逻辑要有测试
- 每轮修改形成 Git 可回滚点

## 8. Prompt 生成要求

如果用户要“给另一个 AI 的 Prompt”，Prompt 里必须写清楚：

- 固定 12 模块
- 5 个服务端口
- 共享模块职责
- owner 数据与 topic 归属
- 不能误补的当前边界
- 输出物、验证命令和禁止事项
