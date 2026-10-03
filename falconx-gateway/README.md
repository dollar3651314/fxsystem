# falconx-gateway

## 文档边界

本文件只记录 gateway 的模块职责、当前已落地能力与当前边界。

- 项目阶段状态与验收结论以 `docs/setup/当前开发计划.md` 为准。
- 契约、路由、安全、限流、熔断、WebSocket 行为以专题规范为准。
- 若与 `AGENTS.md`、专题规范或当前开发计划冲突，优先修正本 README，不反向覆盖正式文档。

## 模块职责

`falconx-gateway` 是 FalconX 的统一北向入口服务，负责：

- 统一 JWT 鉴权与用户透传头组装
- `traceId` 生成与透传
- 下游服务路由转发
- 认证入口与交易路径限流
- 下游超时 / 熔断 / fallback 收敛
- `market` 行情 WebSocket 握手鉴权与代理透传

## 当前已落地能力

### HTTP 路由与鉴权

- 已建立 `/api/v1/auth/**`、`/api/v1/market/**`、`/api/v1/trading/**`、`/api/v1/wallet/**` 路由。
- 已建立 JWT 鉴权过滤器、`traceId` 透传过滤器与统一错误响应。
- gateway 会在鉴权阶段检查 Access Token `jti` 是否命中 Redis 黑名单 key。
- `POST /api/v1/auth/logout` 已可经 gateway 路由到 `identity-service`；成功后同一 Access Token 再访问受保护接口会返回 `10001`。

### 限流、超时与 fallback

- `/api/v1/auth/login` 与 `/api/v1/auth/register` 已落地每 IP 每分钟 20 次限流。
- `/api/v1/trading/**` 已落地每用户每秒 10 次限流，超限返回 `429 / 10012 / Trading Rate Limited`。
- 所有 `/api/v1/**` 已落地每 IP 每分钟 200 次全局兜底限流，超限返回 `429 / 10013 / Global IP Rate Limited`。
- `identity / market / trading / wallet` 四条路由已补 `CircuitBreaker`、`connect-timeout`、`response-timeout` 与统一 fallback。
- 下游服务超时或不可用时，会通过 `GatewayFallbackController` 统一返回 `90002`，不会把原始异常直接泄漏给客户端。

### 市场 WebSocket

- 已落地 `ws://{host}/ws/v1/market?token=<accessToken>`。
- 握手阶段校验 Access Token。
- `BANNED` 用户返回 `HTTP 403`。
- 同一用户最多 5 个并发 market WebSocket 连接，第 6 个返回 `HTTP 429`。
- 成功握手后向 `market-service` 透传 `X-User-* / X-Trace-Id`。
- 代理链路记录 `gateway.websocket.*` 结构化日志。

## 当前验证事实

- `GatewayRoutingIntegrationTests` 已验证认证入口限流、trading 用户级限流、全局 IP 兜底限流，以及 `logout -> blacklist -> gateway reject` 闭环。
- `GatewayMarketWebSocketIntegrationTests` 已验证 market WebSocket 的 `401 / 403 / 429` 握手结果、头透传与连接限制。
- 代表性 E2E 已覆盖 `注册 -> 入金 -> 登录 -> 账户查询 -> 开仓`，以及 TP / 强平链路。

## 当前边界与不应误写的内容

- gateway 只代理并约束已冻结的北向契约，不在本模块 README 中宣告项目阶段“已验收”。
- 当前只冻结并实现 `market` 行情 WebSocket；账户 / 订单 / 持仓 / 费用等用户侧实时推送端点尚未冻结正式契约。
- 当前 `logout` 只吊销当前 Access Token，不引入新的 Refresh Token 主动撤销语义。
- 当前不应把 gateway 表述为“全平台生产可用”。

## 相关文档

- `docs/security/安全规范.md`
- `docs/api/REST接口规范.md`
- `docs/api/WebSocket接口规范.md`
- `docs/api/FalconX统一接口文档.md`
- `docs/setup/当前开发计划.md`
