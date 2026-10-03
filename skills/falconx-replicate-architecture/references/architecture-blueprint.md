# FalconX 当前架构蓝图

## 1. 复刻目标

目标是复刻当前 FalconX 仓库的 `v1` 后端架构，而不是设计一个泛化交易平台。

固定架构模式：

- `GODSA`
- `Gateway-Orchestrated Domain Core Services Architecture`
- 组合模式：`API Gateway + Database per Service + Event-Driven Integration + API Composition`

## 2. 技术版本矩阵

来自当前父工程与仓库正式口径：

| 维度 | 版本 |
| --- | --- |
| Java | `25` |
| Spring Boot | `4.0.5` |
| Spring Cloud | `2025.1.0` |
| Spring Cloud Gateway | `5.0.1` |
| MyBatis Plus | `3.5.15` |
| Redisson | `4.3.0` |
| Kafka | `4.2.0` |
| MySQL | `8.4` |
| Redis | `8.2` |
| ClickHouse | `25.8` |
| ClickHouse JDBC | `0.9.7` |
| Jackson | `3.1.0` |
| Web3j | `5.0.0` |
| Solanaj | `1.20.4` |
| Trident | `0.9.2` |

## 3. 固定模块清单

父工程固定 12 个模块：

1. `falconx-common`
2. `falconx-domain`
3. `falconx-infrastructure`
4. `falconx-identity-contract`
5. `falconx-market-contract`
6. `falconx-trading-contract`
7. `falconx-wallet-contract`
8. `falconx-gateway`
9. `falconx-identity-service`
10. `falconx-market-service`
11. `falconx-trading-core-service`
12. `falconx-wallet-service`

## 4. 可部署服务

### `falconx-gateway`

- 端口：`18080`
- 职责：统一 HTTP / WebSocket 入口、JWT 鉴权、限流、熔断、超时、路由、`traceId` 透传

### `falconx-identity-service`

- 端口：`18081`
- 数据库：`falconx_identity`
- 职责：注册、登录、刷新、登出、用户状态推进

### `falconx-market-service`

- 端口：`18082`
- 数据库：`falconx_market`
- 分析库：`falconx_market_analytics`
- 职责：Tiingo 行情接入、标准化、Redis 最新价、K 线聚合、ClickHouse 写入、行情事件发布

### `falconx-trading-core-service`

- 端口：`18083`
- 数据库：`falconx_trading`
- 职责：账户、账本、订单、持仓、风控、强平、交易域事件消费

### `falconx-wallet-service`

- 端口：`18084`
- 数据库：`falconx_wallet`
- 职责：应用层 stub 地址分配持久化、多链监听、原始入金事实持久化、确认/回滚事件发布

## 5. 共享模块职责

### `falconx-common`

- 统一响应
- 错误码
- 基础异常
- 通用工具

### `falconx-domain`

- 领域枚举与值对象
- 跨服务稳定领域原语
- `DomainEvent` 抽象

### `falconx-infrastructure`

- TraceId 支撑
- Kafka 公共消息封装
- Outbox / Inbox 公共支持
- 基础技术配置

### `falconx-*-contract`

- 冻结的请求/响应 DTO
- 事件 payload
- 共享 client 契约

## 6. 存储与 owner 边界

### `identity-service`

- owner 表：`t_user`

### `market-service`

- owner 表：`t_symbol`、`t_trading_hours`、`t_trading_hours_exception`、`t_trading_holiday`、`t_swap_rate`、`t_outbox`
- owner ClickHouse：`quote_tick`、`kline`
- owner Redis：最新价与交易时间快照

### `trading-core-service`

- owner 表：`t_account`、`t_ledger`、`t_deposit`、`t_order`、`t_position`、`t_trade`、`t_risk_exposure`、`t_risk_config`、`t_hedge_log`、`t_liquidation_log`

### `wallet-service`

- owner 表：`t_wallet_address`、`t_wallet_deposit_tx`、`t_wallet_chain_cursor`

## 7. 固定 Kafka topics

| Topic | 生产者 | 消费者 |
| --- | --- | --- |
| `falconx.market.price.tick` | `market-service` | `trading-core-service` |
| `falconx.market.kline.update` | `market-service` | `trading-core-service` |
| `falconx.wallet.deposit.detected` | `wallet-service` | 当前无正式消费者 |
| `falconx.wallet.deposit.confirmed` | `wallet-service` | `trading-core-service` |
| `falconx.wallet.deposit.reversed` | `wallet-service` | `trading-core-service` |
| `falconx.trading.deposit.credited` | `trading-core-service` | `identity-service` |

## 8. 核心协作路径

### 北向

- `Client -> gateway -> identity`
- `Client -> gateway -> market`
- `Client -> gateway -> trading-core`
- `Client -> gateway -> wallet`

### 东西向

- `market-service -> Redis + ClickHouse + Kafka -> trading-core-service`
- `wallet-service -> Kafka -> trading-core-service`
- `trading-core-service -> Kafka -> identity-service`

默认不走服务间同步 HTTP 互调。

## 9. 当前实现边界

这些边界要原样保留，不要在复刻时擅自“补完整”：

- 当前系统不能表述为“生产可用”或“可安全对外公测”
- 当前交付范围按 `B-book` 口径推进
- 当前只有 `ws://{host}/ws/v1/market` 被正式冻结并实现
- 账户/订单/持仓/费用等用户侧实时推送端点尚未冻结
- `wallet` 地址分配当前只是应用层 stub 持久化，不是正式链地址生成，也没有正式北向地址申请接口
- 真实 A-book 对冲出口不在当前完成范围

## 10. 复刻优先顺序

1. 父 `pom.xml` 与版本矩阵
2. 共享模块与 contract 模块
3. 5 个服务模块与端口
4. 存储 owner 与配置文件
5. Kafka topics 与 contract payload
6. gateway 路由与认证骨架
7. market / trading / wallet / identity 的主协作链路
