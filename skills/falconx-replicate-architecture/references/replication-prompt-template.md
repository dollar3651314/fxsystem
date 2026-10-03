# FalconX 架构复刻 Prompt 模板

下面这段模板可以直接给另一个 AI 使用。需要时把花括号内容替换掉。

```text
你现在要在一个新的仓库中，按当前 FalconX 仓库事实，复刻一个同构的后端项目架构。

目标不是做“类似交易系统”，而是尽量精确复制 FalconX v1 的架构、模块、边界、技术栈和当前实现口径。

一、固定架构
- 架构模式：GODSA（Gateway-Orchestrated Domain Core Services Architecture）
- 组合模式：API Gateway + Database per Service + Event-Driven Integration + API Composition
- 保持 Maven 多模块工程

二、必须复刻的模块
1. falconx-common
2. falconx-domain
3. falconx-infrastructure
4. falconx-identity-contract
5. falconx-market-contract
6. falconx-trading-contract
7. falconx-wallet-contract
8. falconx-gateway
9. falconx-identity-service
10. falconx-market-service
11. falconx-trading-core-service
12. falconx-wallet-service

三、技术版本
- Java 25
- Spring Boot 4.0.5
- Spring Cloud 2025.1.0
- Spring Cloud Gateway 5.0.1
- MyBatis Plus 3.5.15 + XML Mapper
- Redisson 4.3.0
- Kafka 4.2.0
- MySQL 8.4
- Redis 8.2
- ClickHouse 25.8
- Jackson 3.1.0
- Web3j 5.0.0
- Solanaj 1.20.4
- Trident 0.9.2

四、服务与端口
- falconx-gateway: 18080
- falconx-identity-service: 18081
- falconx-market-service: 18082
- falconx-trading-core-service: 18083
- falconx-wallet-service: 18084

五、固定边界
- 不允许服务模块直接依赖其他服务模块
- gateway 只依赖 contract，不依赖下游 service 实现
- 每个服务只访问自己的 owner 数据库
- 业务查询使用 MyBatis + XML Mapper
- 低频 Kafka / WebSocket / 外部 listener 回调线程不能直接执行业务链路，必须切到应用自管线程

六、固定 owner 与协作
- market-service owner：symbol / trading hours / latest quote / kline / quote_tick / market topics
- trading-core-service owner：account / ledger / deposit / order / position / trade / risk / liquidation
- wallet-service owner：wallet address / deposit tx / chain cursor / wallet topics
- identity-service owner：user
- 默认不走服务间同步 HTTP，跨服务协作优先 Kafka + Redis

七、固定 Kafka topics
- falconx.market.price.tick
- falconx.market.kline.update
- falconx.wallet.deposit.detected
- falconx.wallet.deposit.confirmed
- falconx.wallet.deposit.reversed
- falconx.trading.deposit.credited

八、必须保留的当前实现口径
- wallet 地址分配当前只是应用层 stub 持久化，不要自动补成正式链地址生成
- 当前没有正式北向 wallet 地址申请接口
- 当前正式冻结的北向 WebSocket 只有 ws://{host}/ws/v1/market
- 当前系统不能表述为“生产可用”或“可安全对外公测”
- 当前按 B-book 口径推进，不自动补 A-book 对冲出口

九、你必须输出
1. 根 pom.xml 与模块清单
2. 模块依赖关系
3. 每个服务的职责说明
4. 包结构与目录树
5. 配置骨架（application.yml 关键键）
6. 存储 owner 映射
7. Kafka topic 生产/消费映射
8. 如果要求落代码，则直接创建脚手架文件
9. 验证步骤和限制说明

十、禁止事项
- 不要擅自引入 service-to-service 直接依赖
- 不要把业务实体塞进 common/domain/infrastructure
- 不要把当前未冻结能力写成已实现能力
- 不要用“生产可用”作为默认结论
- 不要把 wallet stub 说成正式地址服务

十一、交付方式
- 先给出架构蓝图
- 再给出模块树
- 再落代码或脚手架
- 最后给出验证命令和使用说明

如果存在不确定项，优先按“与当前 FalconX 仓库保持一致”的原则处理，不要自行发明新的架构分层。
```
