# FalconX

## 项目定位

FalconX 是面向 CFD 场景的 `v1` 后端系统，一期采用 `GODSA`（`Gateway-Orchestrated Domain Core Services Architecture`）架构。

本文件只做**项目入口导航**与**当前已落地能力摘要**，不承担阶段验收真源职责。

## 文档使用顺序

发生口径冲突时，按下面顺序处理：

1. `AGENTS.md`
2. `docs/process/AI协作与提示规范.md`
3. 架构 / 数据库 / API / WebSocket / Kafka / 状态机 / 安全 / 事务与幂等等专题规范
4. `docs/setup/开发启动手册.md`
5. `docs/setup/当前开发计划.md`（项目阶段状态与当前正式结论真源）
6. 根 README、各服务 README、场景 Prompt（摘要与导航，不是阶段验收真源）

## 当前正式口径

- `Stage 1-7` 已完成并收口，其中 `Stage 6C` 的 Jackson 3 专项计划已归档，`Stage 7` 验收归档见 `docs/process/archive/Stage7验收归档-2026-04-28.md`。
- 当前新开发计划以 `docs/setup/当前开发计划.md` 为准，执行顺序固定为：先关闭 `STAGE7A-ISOLATED-02`，再处理 `RISK-LIMIT-01`。
- `Stage 7A` 首批逐仓增强子范围已落地，但整阶段仍缺字段迁移、DTO 暴露核对与 `GatewaySupplementMarginE2ETests`；不得写成已完成。
- 当前系统仍不能表述为“生产可用”或“可安全对外公测”。
- 当前交付范围按 **B-book** 口径推进；真实 A-book 对冲出口不在当前完成结论内。

## 已落地能力摘要

### `falconx-gateway`

- 已建立 `/api/v1/auth/**`、`/api/v1/market/**`、`/api/v1/trading/**`、`/api/v1/wallet/**` 路由。
- 已建立 JWT 鉴权、`traceId` 透传、统一 fallback、限流、熔断与超时配置。
- 已落地 `/ws/v1/market` 握手鉴权、连接限制、`BANNED -> 403`、代理透传与结构化日志。

### `falconx-identity-service`

- 已提供 `register / login / refresh / logout`。
- 已消费 `falconx.trading.deposit.credited` 推进用户激活。
- 已具备登录失败锁定、Access Token 黑名单与 `logout -> blacklist -> gateway reject` 基础能力。

### `falconx-market-service`

- 已接入自建 LP Socket.IO 行情源，使用 app token 加密握手、Snappy 压缩报价解析与 owner 白名单热刷新。
- 已落地 owner 白名单热刷新、最新价缓存、`quote_tick / kline` 写入、`market.kline.update` Outbox。
- 已落地 `GET /api/v1/market/quotes/{symbol}` 与 `ws://{host}/ws/v1/market` 行情订阅。

### `falconx-wallet-service`

- 已落地应用层 stub 地址分配持久化、owner 持久化、EVM 原生币与 ERC20 最小扫块识别路径。
- 已支持确认窗口重扫、reversal 观察与 `walletTxId` 稳定主键输出。
- 已形成 `falconx.wallet.deposit.detected / confirmed / reversed` 事件链路。

### `falconx-trading-core-service`

- 已落地 `wallet.deposit.confirmed / reversed` 与 `market.price.tick` 正式消费入口。
- 已具备业务入金入账、账户查询、市价开仓、手动平仓、TP/SL、强平与用户视角查询。
- 已落地 `Swap` 首版 owner 结算链路、`GET /api/v1/trading/swap-settlements` 与 `falconx.trading.swap.settled`。
- 已落地逐仓增强首批子范围：`marginMode` 显式化、追加逐仓保证金、强平价重算与并发保护。

## 当前未完成 / 非当前范围

- `STAGE7A-ISOLATED-02`：逐仓整阶段验收所需的强平日志 `marginMode`、DTO 暴露核对和追加保证金 E2E。
- `RISK-LIMIT-01`：`t_risk_config.max_position_per_user / max_position_total` 风控上限接入。
- 当前只冻结并实现 `market` 行情 WebSocket；账户 / 订单 / 持仓 / 费用等用户侧实时推送端点尚未冻结正式契约。
- LP 外部真源和外部链节点自动化入口虽已存在，但可归档真跑证据仍依赖显式环境变量、实际外部网络与真源持续收流条件。
- `CROSS` 模式不在当前交付范围。
- 真实 A-book 对冲接口不在当前交付范围；`TradingHedgeAlertEvent` 仍只是服务内 Spring Event stub。

## 仓库结构

- `falconx-gateway`
- `falconx-identity-service`
- `falconx-market-service`
- `falconx-wallet-service`
- `falconx-trading-core-service`
- `falconx-common / falconx-domain / falconx-infrastructure`
- `falconx-*-contract`
- `docs/`

## 关键文档入口

- `docs/README.md`
- `AGENTS.md`
- `SKILLS.md`
- `docs/process/AI协作与提示规范.md`
- `docs/setup/开发启动手册.md`
- `docs/setup/当前开发计划.md`
- `docs/api/FalconX统一接口文档.md`
- `docs/api/REST接口规范.md`
- `docs/api/WebSocket接口规范.md`
- `docs/event/Kafka事件规范.md`
- `docs/database/falconx一期数据库设计.md`
- `docs/process/逐仓模式改造方案.md`

## 维护规则

- 根 README 只写项目入口导航、当前已落地能力摘要和未完成边界。
- 阶段完成状态、当前正式结论、阻塞项与下一步，以 `docs/setup/当前开发计划.md` 为准。
- 若根 README 与专题规范或当前开发计划冲突，先改根 README，不反向覆盖正式规范。
