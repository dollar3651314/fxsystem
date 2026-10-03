# falconx-market-service

## 文档边界

本文件只记录 market 模块职责、当前已落地能力与当前边界。

- 项目阶段状态与验收结论以 `docs/setup/当前开发计划.md` 为准。
- 行情输入、缓存、ClickHouse、WebSocket、事件契约以专题规范为准。
- 本 README 不单独定义 `Stage 6A / 6B / 7` 的项目结论。

## 模块职责

`falconx-market-service` 负责市场数据 owner 能力：

- 外部报价接入与标准化
- Redis 最新价缓存
- ClickHouse `quote_tick / kline` 写入
- `falconx.market.price.tick` 与 `falconx.market.kline.update` 事件输出
- 北向市场查询与行情 WebSocket
- `Swap rate` owner 快照输出

## 当前已落地能力

### 外部报价与 owner 数据

- 已接入自建 LP Socket.IO 行情源，使用 app token 加密握手、`external-sub-symbol` 订阅和 `price-compression` Snappy 解压。
- 平台标准报价来源当前为 `TM_QUOTE`。
- 本地开发可通过根目录 `.env` 注入 `domain / APP-ID / token / secretKey / serverId / metaTradeVersion / metaTraderId`；生产部署使用 `FALCONX_MARKET_LP_*` 环境变量。
- 平台放行范围来自 owner `t_symbol.status=1`，并支持定时热刷新。

### 缓存、分析存储与事件

- 已落地 Redis 最新价缓存。
- 已落地 `quote_tick` 写入与真实 K 线收盘聚合。
- 已落地 ClickHouse `quote_tick / kline` 写入。
- 已落地 `falconx.market.price.tick` 与 `falconx.market.kline.update` 生产侧链路。
- 已落地 `Swap rate` owner Redis 共享快照，供 `trading-core-service` 本地结算读取。

### 北向能力

- 已提供 `GET /api/v1/market/quotes/{symbol}`。
- 已落地 `ws://{host}/ws/v1/market` 行情 WebSocket。
- 支持 `subscribe / unsubscribe / ping`。
- 支持 `price.tick`、`kline.{interval}` 与 stale 通知。
- 支持服务端 ping/pong 心跳和 `market.websocket.*` 结构化日志。

## 当前验证事实

- `MarketWebSocketIntegrationTests` 已覆盖 `subscribe / unsubscribe / ping-pong / 协议层心跳 / 重连后重新订阅 / stale`、无效 symbol 错误帧与日志证据。
- `market-service` 真运行时已纳入代表性 E2E，并通过 owner ingestion 路径参与主链路验证。
- LP 协议层、压缩解析和主链路本地门禁用例已具备；真源收流证据需显式外部环境补齐。

## 模块联动

- `market-service -> trading-core-service`：输出 `falconx.market.price.tick` 与 `falconx.market.kline.update`，并共享 `Swap rate` owner 快照。
- `gateway -> market-service`：透传 `/api/v1/market/**` 与 `/ws/v1/market`。
- `market-service -> ClickHouse / Redis`：写入分析存储与最新价缓存。

## 当前边界与不应误写的内容

- 当前只冻结并实现 `market` 行情 WebSocket；账户 / 订单 / 持仓 / 费用等用户侧实时推送端点不在本模块当前正式范围。
- LP 外部真源可归档真跑证据仍依赖显式 LP 参数、实际外部网络和持续收流条件。
- 本 README 不再写“`Swap` 查询展示和业务事件尚未完成”之类项目级结论；`Swap` 的项目完成状态由 `docs/setup/当前开发计划.md` 统一定义。
- 本 README 不承担项目阶段验收结论职责。

## 相关文档

- `docs/market/LP自建行情源接入契约.md`
- `docs/api/WebSocket接口规范.md`
- `docs/api/REST接口规范.md`
- `docs/api/FalconX统一接口文档.md`
- `docs/event/Kafka事件规范.md`
- `docs/setup/当前开发计划.md`
