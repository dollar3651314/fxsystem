# FalconX 统一接口文档

## 1. 目的

本文件是 FalconX v1 全部已开发接口的统一交付文档。

规则：

- 每次接口开发并测试通过后，必须更新本文件
- 未更新本文件，不视为接口任务完成
- 本文件只记录“已开发且已验证”的接口

适用范围：

- REST 接口
- WebSocket 接口
- 后续如启用的内部接口

## 2. 统一模板

后续新增接口时，必须按下面模板追加。

### 2.1 接口基础信息

- 所属服务：
- 接口名称：
- 接口说明：
- 接口类型：`REST / WebSocket / Internal`
- 请求路径或主题：
- 请求方法：
- 认证要求：
- 幂等要求：

### 2.2 请求信息

- 请求头：
- Path 参数：
- Query 参数：
- 请求体：

请求示例：

```json
{}
```

### 2.3 响应信息

- 成功业务码：
- 失败业务码：
- 响应说明：

成功响应示例：

```json
{}
```

失败响应示例：

```json
{}
```

### 2.4 日志与链路要求

- 关键日志点：
- 是否要求写审计日志：
- 是否要求透传 `traceId`：

### 2.5 测试结论

- 开发人员：
- 测试日期：
- 测试环境：
- 测试结果：
- 备注：

---

## 3. 当前接口清单

说明：

- 自 Stage 4 起，外部 `REST` 接口统一经 `falconx-gateway` 进入
- 文档中的“所属服务”统一按 `gateway -> owner-service` 记录北向调用关系
- 网关生成新的 `X-Trace-Id` 并向下游服务透传，前端不允许自定义传入
- 所有 `/api/v1/**` 请求都受 gateway 全局 IP 每分钟 200 次兜底限流约束，超限返回 HTTP `429` + `10013 / Global IP Rate Limited`
- 所有 `/api/v1/trading/**` 请求在鉴权通过后都受 gateway 每用户每秒 10 次限流约束，超限返回 HTTP `429` + `10012 / Trading Rate Limited`
- 当前正式执行计划以 `docs/setup/当前开发计划.md` 为准：`Stage 7` 已完成并归档；`Stage 7A` 当前冻结范围、风控上限缺口、身份可信度补强和入金页幂等地址申请已完成。若 `main` 上存在更多超前接口或代码事实，不等于对应阶段已验收完成。
- 当前北向 WebSocket 已冻结 `ws://{host}/ws/v1/market` 行情订阅和 `ws://{host}/ws/v1/trading` 用户交易实时推送；用户交易实时推送是 best-effort 通知，断线后必须通过 REST 查询补偿。

### 3.1 identity-service - 用户注册

#### 3.1.1 接口基础信息

- 所属服务：`falconx-gateway -> falconx-identity-service`
- 接口名称：用户注册
- 接口说明：创建最小身份用户，并返回对外 `UID`、当前用户状态与邮箱可信度事实
- 接口类型：`REST`
- 请求路径或主题：`/api/v1/auth/register`
- 请求方法：`POST`
- 认证要求：无需认证
- 幂等要求：无；重复邮箱注册会返回业务失败码

#### 3.1.2 请求信息

- 请求头：
  - `Content-Type: application/json`
- Path 参数：无
- Query 参数：无
- 请求体：
  - `email`：注册邮箱
  - `password`：明文密码

请求示例：

```json
{
  "email": "alice@example.com",
  "password": "Passw0rd!"
}
```

#### 3.1.3 响应信息

- 成功业务码：`0`
- 失败业务码：`10004`、`10008`、`99004`
- 响应说明：
  - 成功时返回 `userId`、`uid`、`email`、`status`、`emailVerified`
  - 当前新用户状态固定为 `ACTIVE`
  - 一期不发送验证邮件，`emailVerified` 默认返回 `false`
  - 同一 IP 在 1 小时内第 6 次注册会返回 `10004`
  - 响应头必须包含服务端自动生成的 `X-Trace-Id`

成功响应示例：

```json
{
  "code": "0",
  "message": "success",
  "data": {
    "userId": 1,
    "uid": "U00000001",
    "email": "alice@example.com",
    "status": "ACTIVE",
    "emailVerified": false
  },
  "timestamp": "2026-04-17T10:12:06.055+08:00",
  "traceId": "4f3c2a7e9b6d41f8a1c0e5b2d7f9a3c1"
}
```

失败响应示例：

```json
{
  "code": "10008",
  "message": "User Already Exists",
  "data": null,
  "timestamp": "2026-04-17T10:12:06.406+08:00",
  "traceId": "a8b6c0a31f7d44a9b1dce03f7c2c2b51"
}
```

#### 3.1.4 日志与链路要求

- 关键日志点：
  - `identity.http.register.received`
  - `identity.register.received`
  - `identity.register.completed`
- 是否要求写审计日志：否
- 是否要求透传 `traceId`：是，由服务入口自动生成并写入响应头与日志

#### 3.1.5 测试结论

- 开发人员：Codex
- 测试日期：`2026-04-29`
- 测试环境：本地 `SpringBootTest + MockMvc`
- 测试结果：通过
- 备注：已验证注册成功响应 `status=ACTIVE`，且 `t_user.activated_at` 在注册时写入
- 备注：已验证注册成功响应 `emailVerified=false`，且 `t_user.email_verified=0`
- 备注：已验证注册后无需入金状态迁移即可登录
- 备注：已验证重复邮箱注册返回 `10008`
- 备注：已验证同一 IP 连续 6 次注册时，第 6 次返回 `10004`

### 3.2 identity-service - 用户登录

#### 3.2.1 接口基础信息

- 所属服务：`falconx-gateway -> falconx-identity-service`
- 接口名称：用户登录
- 接口说明：基于邮箱和密码完成认证，并返回 Access Token 与 Refresh Token
- 接口类型：`REST`
- 请求路径或主题：`/api/v1/auth/login`
- 请求方法：`POST`
- 认证要求：无需认证
- 幂等要求：无

#### 3.2.2 请求信息

- 请求头：
  - `Content-Type: application/json`
- Path 参数：无
- Query 参数：无
- 请求体：
  - `email`：登录邮箱
  - `password`：明文密码

请求示例：

```json
{
  "email": "alice@example.com",
  "password": "Passw0rd!"
}
```

#### 3.2.3 响应信息

- 成功业务码：`0`
- 失败业务码：`10002`、`10003`、`10005`、`10007`、`99004`
- 响应说明：
  - 成功时返回 Access Token、Refresh Token、两个过期秒数、当前用户状态和邮箱可信度事实
  - 历史 `PENDING_DEPOSIT` 用户登录时会归一化为 `ACTIVE` 并签发 Token
  - 一期不因 `emailVerified=false` 限制登录或签发 Token
  - `emailVerified` 仅在接口响应中返回，不写入 JWT payload
  - 同一 IP 连续 5 次密码错误后会进入 15 分钟锁定，第 6 次返回 `10003`
  - 响应头必须包含服务端自动生成的 `X-Trace-Id`

成功响应示例：

```json
{
  "code": "0",
  "message": "success",
  "data": {
    "accessToken": "eyJraWQiOiJmYWxjb254LWlkZW50aXR5LXJzYSIsImFsZyI6IlJTMjU2In0...",
    "refreshToken": "c16b8be8-ec0c-4f07-8306-a18efcad89d8",
    "accessTokenExpiresIn": 900,
    "refreshTokenExpiresIn": 259200,
    "userStatus": "ACTIVE",
    "emailVerified": false
  },
  "timestamp": "2026-04-17T10:12:06.187+08:00",
  "traceId": "c1f35755d4b94e3c9df4cfc1774b58d0"
}
```

失败响应示例：

```json
{
  "code": "10005",
  "message": "Invalid Credentials",
  "data": null,
  "timestamp": "2026-04-17T10:12:06.200+08:00",
  "traceId": "bc73bf3b6c4e4d44b265c9f3a7da0e90"
}
```

#### 3.2.4 日志与链路要求

- 关键日志点：
  - `identity.http.login.received`
  - `identity.login.received`
  - `identity.login.completed`
- 是否要求写审计日志：否
- 是否要求透传 `traceId`：是，由服务入口自动生成并写入响应头与日志

#### 3.2.5 测试结论

- 开发人员：Codex
- 测试日期：`2026-04-29`
- 测试环境：本地 `SpringBootTest + MockMvc`
- 测试结果：通过
- 备注：已验证注册响应 `status=ACTIVE`，注册后可立即登录并签发 Token
- 备注：已验证历史 `PENDING_DEPOSIT` 用户登录时会归一化为 `ACTIVE`，不再返回 `10011`
- 备注：已验证登录成功响应 `emailVerified=false`，且 JWT payload 不包含 `emailVerified`
- 备注：已验证旧 Refresh Token 第二次使用返回 `10006`
- 备注：登录成功后已验证可继续走 Refresh Token 轮换链路
- 备注：已验证同一 IP 连续 5 次密码错误后，第 6 次返回 `10003`

### 3.3 identity-service - Refresh Token 刷新

#### 3.3.1 接口基础信息

- 所属服务：`falconx-gateway -> falconx-identity-service`
- 接口名称：刷新认证令牌
- 接口说明：消费旧 Refresh Token 并返回一组新的认证令牌
- 接口类型：`REST`
- 请求路径或主题：`/api/v1/auth/refresh`
- 请求方法：`POST`
- 认证要求：无需认证
- 幂等要求：无；旧 Refresh Token 一次性使用，重复调用会失败

#### 3.3.2 请求信息

- 请求头：
  - `Content-Type: application/json`
- Path 参数：无
- Query 参数：无
- 请求体：
  - `refreshToken`：上一次登录或刷新得到的 Refresh Token

请求示例：

```json
{
  "refreshToken": "c16b8be8-ec0c-4f07-8306-a18efcad89d8"
}
```

#### 3.3.3 响应信息

- 成功业务码：`0`
- 失败业务码：`10006`、`99004`
- 响应说明：
  - 成功时返回新的 Access Token、Refresh Token、当前用户状态和邮箱可信度事实
  - 旧 Refresh Token 被消费后再次调用会返回 `10006`
  - `emailVerified` 仅在接口响应中返回，不写入 JWT payload
  - 响应头必须包含服务端自动生成的 `X-Trace-Id`

成功响应示例：

```json
{
  "code": "0",
  "message": "success",
  "data": {
    "accessToken": "eyJraWQiOiJmYWxjb254LWlkZW50aXR5LXJzYSIsImFsZyI6IlJTMjU2In0...",
    "refreshToken": "f8983e13-8445-42be-b7b5-2422f2570b19",
    "accessTokenExpiresIn": 900,
    "refreshTokenExpiresIn": 259200,
    "userStatus": "ACTIVE",
    "emailVerified": false
  },
  "timestamp": "2026-04-17T10:12:06.298+08:00",
  "traceId": "5e5442da5b7a495cb0ae426ae5f9f204"
}
```

失败响应示例：

```json
{
  "code": "10006",
  "message": "Refresh Token Invalid",
  "data": null,
  "timestamp": "2026-04-17T10:12:06.309+08:00",
  "traceId": "0b8f5367d8854f37a53787869d311f84"
}
```

#### 3.3.4 日志与链路要求

- 关键日志点：
  - `identity.http.refresh.received`
  - `identity.refresh.request`
  - `identity.refresh.completed`
- 是否要求写审计日志：否
- 是否要求透传 `traceId`：是，由服务入口自动生成并写入响应头与日志

#### 3.3.5 测试结论

- 开发人员：Codex
- 测试日期：`2026-04-29`
- 测试环境：本地 `SpringBootTest + MockMvc`
- 测试结果：通过
- 备注：已验证旧 Refresh Token 二次使用返回 `10006`
- 备注：已验证 Refresh 成功响应 `emailVerified=false`

### 3.4 market-service - 查询最新报价

#### 3.4.1 接口基础信息

- 所属服务：`falconx-gateway -> falconx-market-service`
- 接口名称：查询最新报价
- 接口说明：查询指定交易品种当前最新标准报价快照
- 接口类型：`REST`
- 请求路径或主题：`/api/v1/market/quotes/{symbol}`
- 请求方法：`GET`
- 认证要求：需要 `Bearer Access Token`
- 幂等要求：天然幂等

#### 3.4.2 请求信息

- 请求头：
  - `Authorization: Bearer <accessToken>`
- Path 参数：
  - `symbol`：品种代码，例如 `EURUSD`
- Query 参数：无
- 请求体：无

请求示例：

```http
GET /api/v1/market/quotes/EURUSD
Authorization: Bearer eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...
```

#### 3.4.3 响应信息

- 成功业务码：`0`
- 失败业务码：`10001`、`30003`
- 响应说明：
  - 成功时返回品种最新 `bid / ask / mid / mark`
  - LP Socket.IO 实时链路下，`ts` 表示 market-service 接入主链路处理该 tick 的 UTC 时间；LP payload 源时间只做解析校验，不作为交易终端时间轴
  - `mark` 当前仍是 `market-service` 的兼容报价字段；`trading-core-service` 做逐仓估值、TP/SL、强平和账户浮盈亏时，不直接使用该单值字段，而是按方向从 `bid / ask` 解析有效标记价
  - `quoteStatus=FRESH` 是唯一可成交行情状态；`STALE / NO_QUOTE / MARKET_CLOSED / ABNORMAL` 只能作为展示或审计参考
  - `30003` 表示当前品种无可用报价
  - 响应头必须包含 gateway 生成的 `X-Trace-Id`

成功响应示例：

```json
{
  "code": "0",
  "message": "success",
  "data": {
    "symbol": "EURUSD",
    "bid": 1.08000000,
    "ask": 1.08010000,
    "mid": 1.08005000,
    "mark": 1.08005000,
    "ts": "2026-04-17T11:23:23.275519+08:00",
    "source": "TM_QUOTE",
    "stale": false,
    "quoteStatus": "FRESH",
    "qualityReason": null
  },
  "timestamp": "2026-04-17T11:23:25.726+08:00",
  "traceId": "d2a50968a90542ec95cb7f3075b7b9d6"
}
```

失败响应示例：

```json
{
  "code": "30003",
  "message": "Quote Not Available",
  "data": null,
  "timestamp": "2026-04-17T11:23:25.949+08:00",
  "traceId": "2b8bb063f89b4cb5a741ec0caefad1f8"
}
```

#### 3.4.4 日志与链路要求

- 关键日志点：
  - `gateway.request.received`
  - `gateway.auth.accepted`
  - `market.http.quote.received`
- 是否要求写审计日志：否
- 是否要求透传 `traceId`：是，由 gateway 生成并向 market-service 透传

#### 3.4.5 测试结论

- 开发人员：Codex
- 测试日期：`2026-04-17`
- 测试环境：本地 `SpringBootTest + MockMvc`、`SpringBootTest + WebTestClient`
- 测试结果：通过
- 备注：已验证存在报价与无报价两条分支；网关受保护路由已验证鉴权拦截

### 3.4.6 market-service - 查询历史报价 Tick

#### 3.4.6.1 接口基础信息

- 所属服务：`falconx-gateway -> falconx-market-service`
- 接口名称：查询历史报价 Tick
- 接口说明：从 market owner ClickHouse `quote_tick` 读取指定品种最近历史报价，用于交易终端 Tick 分时图首屏初始化；后续实时跳动由 WebSocket `price.tick` 推进
- 接口类型：`REST`
- 请求路径或主题：`/api/v1/market/quotes/{symbol}/history`
- 请求方法：`GET`
- 认证要求：需要 `Bearer Access Token`
- 幂等要求：天然幂等

#### 3.4.6.2 请求信息

- 请求头：
  - `Authorization: Bearer <accessToken>`
  - `X-User-Group-Code`：由 gateway 注入，market-service 用于校验 symbol 是否可见
- Path 参数：
  - `symbol`：品种代码，例如 `AUDCAD`
- Query 参数：
  - `limit`：返回数量，默认 `600`；服务端限制在 `1..1000`
- 请求体：无

请求示例：

```http
GET /api/v1/market/quotes/AUDCAD/history?limit=600
Authorization: Bearer eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...
```

#### 3.4.6.3 响应信息

- 成功业务码：`0`
- 失败业务码：`10001`、`30001`
- 响应说明：
  - 成功时返回 `quotes` 数组，按 `ts` 升序排列
  - `bid / ask / mid / mark` 直接来自 ClickHouse `quote_tick`，用于 Tick 分时图绘制正确 bid / ask 区间
  - 该接口只用于历史图表展示，不作为交易成交价依据
  - `30001` 表示 `symbol` 不存在或不属于当前用户组可见范围

成功响应示例：

```json
{
  "code": "0",
  "message": "success",
  "data": {
    "quotes": [
      {
        "symbol": "AUDCAD",
        "bid": 0.99010000,
        "ask": 0.99018000,
        "mid": 0.99014000,
        "mark": 0.99014000,
        "ts": "2026-05-11T05:41:00Z",
        "source": "TM_QUOTE",
        "stale": false,
        "quoteStatus": "FRESH",
        "qualityReason": null
      }
    ]
  },
  "timestamp": "2026-05-11T05:41:01Z",
  "traceId": "c7dc651c9ad3481dbb989d4dbf5b2f14"
}
```

#### 3.4.6.4 日志与链路要求

- 关键日志点：
  - `gateway.request.received`
  - `gateway.auth.accepted`
  - `market.http.quote_history.received`
- 是否要求写审计日志：否
- 是否要求透传 `traceId`：是，由 gateway 生成并向 market-service 透传

#### 3.4.6.5 测试结论

- 开发人员：Codex
- 测试日期：`2026-05-11`
- 测试环境：本地 `JUnit + Mockito`、`Vitest`
- 测试结果：通过
- 备注：`DefaultMarketQuoteQueryServiceTests.shouldReturnRecentQuoteTicksFromVisibleCanonicalSymbol` 验证可见 symbol 通过 ClickHouse 历史仓储返回 bid / ask；`marketApi.test.ts` 验证前端调用 `/quotes/{symbol}/history`；`marketChartData.test.ts` 验证 Tick 图不再用 1m K 线补历史，而是使用 quote tick history

### 3.4A market-service - 查询首页品种列表

#### 3.4A.1 接口基础信息

- 所属服务：`falconx-gateway -> falconx-market-service`
- 接口名称：查询首页品种列表
- 接口说明：返回当前用户组可见的 platform symbol 列表，并携带展示用价格快照；用户组来自 gateway 透传的 `X-User-Group-Code`，默认 `default`；平台 symbol 可通过 `t_symbol_quote_mapping` 映射到 LP 源 `source_lp_code + source_symbol`。后端订阅上游 LP 时使用 `source_lp_code + source_symbol` 选定源，再映射回 `platform_symbol` 推送到页面；接口不按 `.p / .c / .f` 或其他后缀做特殊限制，是否展示、订阅和报价只由源 `t_symbol.lp_code + symbol + status`、`t_symbol_quote_mapping` 启停与 `t_symbol_group_visibility` 可见性决定；实时价存在且未 stale 时返回 `LIVE`，实时价缺失但存在最后有效参考价时返回 `REFERENCE`
- 接口类型：`REST`
- 请求路径或主题：`/api/v1/market/symbols`
- 请求方法：`GET`
- 认证要求：需要 `Bearer Access Token`
- 幂等要求：天然幂等

#### 3.4A.2 请求信息

- 请求头：
  - `Authorization: Bearer <accessToken>`
  - `X-User-Group-Code`：由 gateway 从 Access Token `groupCode` 注入，客户端不直接传；缺省为 `default`
- Path 参数：无
- Query 参数：无
- 请求体：无

请求示例：

```http
GET /api/v1/market/symbols
Authorization: Bearer eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...
```

#### 3.4A.3 响应信息

- 成功业务码：`0`
- 失败业务码：`10001`
- 响应说明：
  - `priceStatus=LIVE`：来自短 TTL Redis 最新价，`tradable=true` 表示该价格可作为交易链路候选价；最终是否允许开仓仍由 trading-core-service 的交易时间与风控判断决定
  - `priceStatus=REFERENCE`：来自最后有效参考价，只允许首页、列表、休盘展示使用，`tradable=false`
  - `priceStatus=MISSING`：没有可展示价格，价格字段返回 `null`，`tradable=false`
  - `symbol` 是平台展示和交易 symbol；默认 LP 快照导入品种保留 LP MT5 原始代码与普通市场后缀，例如 `AAPL.NAS`；自定义平台 symbol 如 `AAAUSD / XAU100` 可映射到 LP 源 symbol；接口不按 `.p / .c / .f` 或其他后缀做特殊限制
  - 参考价 Redis key 为 `falconx:market:last-valid-price:{symbol}`，TTL 由 `falconx.market.redis.reference-quote-ttl` 控制，默认 `30d`
  - 参考价 Redis miss 时，market-service 可从 ClickHouse `quote_tick` 最新 tick 回填；回填结果仍按 `REFERENCE` 返回
  - 休盘、非交易时段或参考价状态不得成交；交易接口仍必须读取短 TTL 实时价并执行交易时间校验

成功响应示例：

```json
{
  "code": "0",
  "message": "success",
  "data": {
    "symbols": [
      {
        "symbol": "EURUSD",
        "category": 2,
        "marketCode": "FX",
        "baseCurrency": "EUR",
        "quoteCurrency": "USD",
        "pricePrecision": 5,
        "qtyPrecision": 2,
        "minQty": 100.00000000,
        "maxQty": 800000.00000000,
        "minNotional": 100.00000000,
        "maxLeverage": 500,
        "takerFeeRate": 0.000100,
        "spread": 0.00000000,
        "bid": 1.08100000,
        "ask": 1.08120000,
        "mid": 1.08110000,
        "mark": 1.08110000,
        "quoteTs": "2026-04-30T10:33:24.611293+08:00",
        "quoteSource": "TM_QUOTE",
        "priceStatus": "REFERENCE",
        "tradable": false
      }
    ]
  },
  "timestamp": "2026-04-30T10:33:25.000+08:00",
  "traceId": "b91eac9f32e142578f62efe6654d0058"
}
```

#### 3.4A.4 日志与链路要求

- 关键日志点：
  - `gateway.request.received`
  - `gateway.auth.accepted`
  - `market.http.symbols.received`
  - `market.symbols.list.completed`
- 是否要求写审计日志：否
- 是否要求透传 `traceId`：是，由 gateway 生成并向 market-service 透传

#### 3.4A.5 测试结论

- 开发人员：Codex
- 测试日期：`2026-04-30`
- 测试环境：本地 `SpringBootTest + MockMvc + MySQL + Redis + ClickHouse + Kafka`
- 测试结果：通过
- 备注：`MarketQueryControllerIntegrationTests` 已验证 `LIVE + tradable=true` 与实时价过期后 `REFERENCE + tradable=false`；`MarketLatestQuoteStaleIntegrationTests` 已验证参考价 Redis key TTL 与读取时 `stale=true`；`MarketInfrastructureIntegrationTests` 已验证参考价 Redis miss 时从 ClickHouse 最新 tick 回填，并验证 LP MT5 symbol 快照保留 `1571` 条活跃白名单、`default` 用户组可见性与 1:1 报价映射

### 3.4A-FT market-service - 查询跑马灯热门产品（FEATURED-TICKER）

#### 基础信息

- 所属服务：`falconx-gateway -> falconx-market-service`
- 接口名称：查询跑马灯热门产品列表
- 接口说明：客户端顶栏跑马灯读取管理端配置的热门产品有序列表；返回 `enabled=1` 按 `sort_order` 升序的 symbol 列表。空列表时客户端回退内置默认偏好（前端 `resolveTickerSymbols`）。owner=market-service `t_featured_symbol`。
- 请求路径：`GET /api/v1/market/symbols/featured`
- 请求方法：`GET`
- 认证要求：需登录（gateway JWT）
- 请求参数：无
- 成功响应示例：

```json
{
  "code": "0",
  "message": "success",
  "data": { "symbols": ["BTCUSD", "ETHUSD", "XAUUSD"] },
  "timestamp": "2026-06-04T10:00:00.000+08:00",
  "traceId": "..."
}
```

- 关键日志点：无（轻量读，复用 gateway/market 通用日志）
- 测试结论：`MarketFeaturedSymbolServiceTests`（service 层 replaceAll 排序/过滤/listEnabled 3 项）通过；端到端经 demo 全链验收（管理端配置 → 客户端渲染）

> 配套 internal RPC（console 透传写，filter 校验 `X-Internal-Token`）：`GET /internal/v1/market/symbols/featured`（全部含禁用）、`PUT /internal/v1/market/symbols/featured`（全量替换，body `{items:[{symbol,enabled}]}`，顺序即 sort_order）。
>
> 管理端（`falconx-console-service`，透传上述 internal RPC，RBAC+审计自动）：`GET /admin/symbols/featured`（权限 `symbol:view`，返回含禁用项 `{items:[{symbol,sortOrder,enabled}]}`）、`PUT /admin/symbols/featured`（权限 `symbol:featured:update`，全量替换）。管理端配置页 `/admin/symbols/featured`（菜单「行情品种 › 跑马灯热门产品」，console V15 seed 权限点+菜单）。

### 3.4B market-service - 查询历史 K 线

#### 3.4B.1 接口基础信息

- 所属服务：`falconx-gateway -> falconx-market-service`
- 接口名称：查询历史 K 线
- 接口说明：从 market owner ClickHouse K 线表读取指定品种的历史 K 线，用于交易终端图表初始化；后续当前 K 线只由 WebSocket `kline.{interval}` 推送覆盖，前端不得用 `price.tick` 合成 K 线
- 接口类型：`REST`
- 请求路径或主题：`/api/v1/market/klines/{symbol}`
- 请求方法：`GET`
- 认证要求：需要 `Bearer Access Token`
- 幂等要求：天然幂等

#### 3.4B.2 请求信息

- 请求头：
  - `Authorization: Bearer <accessToken>`
  - `X-User-Group-Code`：由 gateway 注入，market-service 用于校验 symbol 是否可见
- Path 参数：
  - `symbol`：品种代码，例如 `EURUSD`
- Query 参数：
  - `interval`：K 线周期，默认 `1m`；必须属于 `falconx.market.kline.intervals`
  - `limit`：返回数量，默认 `200`；服务端限制在 `1..500`
- 请求体：无

请求示例：

```http
GET /api/v1/market/klines/EURUSD?interval=1m&limit=200
Authorization: Bearer eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...
```

#### 3.4B.3 响应信息

- 成功业务码：`0`
- 失败业务码：`10001`、`30001`、`99004`
- 响应说明：
  - 成功时返回 `klines` 数组，按 `openTime` 升序排列
  - `open / high / low / close` 由 market-service 使用同周期内标准报价 `mid` 聚合生成，保持后端 decimal 精度输出
  - 历史项 `isFinal=true`；当前正在形成中的 K 线由 WebSocket `kline.{interval}` 推送覆盖
  - `30001` 表示 `symbol` 不存在或不属于当前用户组可见范围；`99004` 表示 `interval` 不合法

成功响应示例：

```json
{
  "code": "0",
  "message": "success",
  "data": {
    "klines": [
      {
        "symbol": "EURUSD",
        "interval": "1m",
        "open": 1.08000000,
        "high": 1.08200000,
        "low": 1.07900000,
        "close": 1.08100000,
        "volume": 1.00000000,
        "openTime": "2026-04-30T10:00:00Z",
        "closeTime": "2026-04-30T10:00:59Z",
        "isFinal": true
      }
    ]
  },
  "timestamp": "2026-04-30T10:02:00Z",
  "traceId": "9fbcf7c6f0d1467b"
}
```

#### 3.4B.4 日志与链路要求

- 关键日志点：
  - `gateway.request.received`
  - `gateway.auth.accepted`
  - `market.http.klines.received`
- 是否要求写审计日志：否
- 是否要求透传 `traceId`：是，由 gateway 生成并向 market-service 透传

#### 3.4B.5 测试结论

- 开发人员：Codex
- 测试日期：`2026-04-30`
- 测试环境：本地 `SpringBootTest + MockMvc + MySQL + Redis + ClickHouse`
- 测试结果：通过
- 备注：`MarketQueryControllerIntegrationTests.shouldReturnRecentKlinesForSymbol` 覆盖两条 ClickHouse K 线插入与 REST 查询返回

#### 3.4.6 关联内部事件与主链路说明

- 接口名称：市场价格 Tick 事件
- 所属模块：`falconx-market-service`
- 接口用途：把标准化后的实时价格同步给 `trading-core-service` 的高频报价驱动链路
- 接口类型：`Internal`
- 请求路径或主题：`falconx.market.price.tick`
- 请求方法：`publish`
- 认证要求：无，仅限服务间 Kafka
- 幂等要求：高频事件当前不写 `t_inbox`；消费侧按业务逻辑与短窗口机制保证幂等
- 请求头：
  - Kafka key：`symbol`
  - Kafka headers：
    - `X-Event-Id`
    - `X-Event-Type=market.price.tick`
    - `X-Event-Source=falconx-market-service`
    - `X-Trace-Id`
- 请求体：
  - `symbol`：平台内部标准品种
  - `bid / ask / mid / mark`：标准化后的价格字段
  - `ts`：当前运行时按 Jackson 数值时间输出，语义为 Unix epoch seconds，可带小数秒
  - `source`：当前固定为 `TM_QUOTE`
  - `stale`：是否超时
  - `quoteStatus`：报价质量状态，当前取值为 `FRESH / STALE / NO_QUOTE / MARKET_CLOSED / ABNORMAL`
  - `qualityReason`：不可成交原因，当前包括 `QUOTE_TIME_DRIFT_EXCEEDED / UNCHANGED_TOO_LONG / MARKET_CLOSED / NON_POSITIVE_PRICE / BID_ASK_CROSSED`；可为空
- 请求示例：

```json
{
  "symbol": "EURUSD",
  "bid": 1.08100000,
  "ask": 1.08120000,
  "mid": 1.08110000,
  "mark": 1.08110000,
  "ts": 1776676530.123,
  "source": "TM_QUOTE",
  "stale": false,
  "quoteStatus": "FRESH",
  "qualityReason": null
}
```

- 返回参数：无同步业务响应；发送成功仅代表 Kafka producer 已确认写入 broker
- 数据来源：`LP Socket.IO price-compression -> LpWebSocketProtocolSupport -> QuoteStandardizationService`；实时帧的 `ts` 为 market-service 接入主链路处理时间，用于 Redis、WebSocket 和 K 线聚合时间轴
- 数据流向：`market-service -> Redis 最新价 -> ClickHouse quote_tick -> Kafka falconx.market.price.tick -> trading-core-service`；`STALE / NO_QUOTE / ABNORMAL` 只发布 Kafka 不刷新可成交 Redis/ClickHouse/WebSocket/Kline；`MARKET_CLOSED` 直接跳过所有 sink
- 上下游依赖：
  - 上游：LP 自建行情源、market owner 启用品种白名单、`t_symbol_quote_mapping.lp_subscribe_enabled` 订阅开关；运行时不按 `.p / .c / .f` 或其他后缀做特殊过滤
  - 下游：`trading-core-service` 的 `TradingKafkaEventListener / MarketPriceTickEventConsumer / QuoteDrivenEngine`
- 与其他接口如何配合：
  - 北向 `GET /api/v1/market/quotes/{symbol}` 读取同一条主链路写入 Redis 的最新价
  - `market.kline.update` 负责低频收盘 K 线；`market.price.tick` 只负责高频价格推进
- 关联业务：报价驱动估值、TP/SL、强平、账户浮盈亏
- 业务逻辑说明：
  - `mark` 当前仍是兼容字段；交易侧不得把它当作唯一有效标记价，而应按 `BUY -> bid`、`SELL -> ask` 解析
  - `stale` 由市场侧标准化并写入事件；交易侧仍需结合自身读模型语义做最终判断
  - 只有 `quoteStatus=FRESH` 且交易时段打开的 tick 可以进入成交、TP/SL 或强平判断；其他状态只能更新参考快照或日志
- 异常处理说明：
  - 当前为高频直发 Kafka，发送失败会抛出异常并记录失败日志
  - 该事件不走 `t_outbox`，因此发送失败不会补做低频事件式的 Outbox 重试
- 兼容性与影响范围说明：
  - 当前运行时采用“Kafka headers 承载事件元数据、body 只放 payload JSON”的口径
  - `ts` 当前是数值时间，不得在消费端强绑成字符串格式
  - `market-service` 通过 `falconx.market.lp.*` 或根目录 `.env` 读取 LP 参数；密钥不得写入仓库、日志或接口响应
  - `quoteStatus / qualityReason` 为向后兼容新增字段；历史消息缺少字段时，trading-core-service 按 `stale` 推断为 `FRESH / STALE`

- 测试结论：
  - 开发人员：Codex
  - 测试日期：`2026-04-30`
  - 测试环境：本地 `SpringBootTest + MySQL + Redis + ClickHouse + Kafka`
  - 测试结果：通过目标主链路一致性验证
  - 备注：`SocketIoLpMarketQuoteProviderTests`、`LpWebSocketProtocolSupportTests`、`DefaultQuoteStandardizationServiceTests` 与 `MarketPriceTickMainlineIntegrationTests` 已验证 LP Socket.IO demo 握手、字符串订阅、Snappy 解压、报价解析、`TM_QUOTE` source 与 Redis / ClickHouse / Kafka 主链路一致性；`MarketDataIngestionApplicationServiceTests` 已验证 `STALE / NO_QUOTE / ABNORMAL` 不刷新可成交市场存储、`MARKET_CLOSED` 跳过所有 sink；当前配置文件中的 LP 地址与 `APP-ID` 仍需补齐持续收流证据

#### 3.4.7 关联内部事件与主链路说明

- 接口名称：市场收盘 K 线事件
- 所属模块：`falconx-market-service -> falconx-trading-core-service`
- 接口用途：把 `market-service` 已收盘的低频 K 线事实同步给 `trading-core-service`；当前阶段只要求形成正式 Kafka 消费链路与 owner `t_inbox` 审计事实，不额外派生交易域状态
- 接口类型：`Internal`
- 请求路径或主题：`falconx.market.kline.update`
- 请求方法：`publish`
- 认证要求：无，仅限服务间 Kafka
- 幂等要求：按 `X-Event-Id` 在 trading owner `t_inbox` 做幂等；重复事件只记录重复日志，不重复写入业务事实
- 当前阶段定位：`Stage 6A` 必须收口项；只证明低频正式消费链路成立，不等于 `Stage 7` 基于 K 线扩展新交易规则
- 请求头：
  - Kafka key：`symbol:interval`
  - Kafka headers：
    - `X-Event-Id`
    - `X-Event-Type=market.kline.update`
    - `X-Event-Source=falconx-market-service`
    - `X-Trace-Id`
- 请求体：
  - `symbol`：平台内部标准品种
  - `interval`：K 线周期，例如 `1m`
  - `open / high / low / close`：收盘 K 线 OHLC，由同周期内标准报价 `mid` 聚合生成
  - `volume`：该周期成交量或平台定义量
  - `openTime / closeTime`：K 线起止时间
  - `isFinal`：当前固定应为 `true`
- 请求示例：

```json
{
  "symbol": "EURUSD",
  "interval": "1m",
  "open": 1.08000000,
  "high": 1.08200000,
  "low": 1.07950000,
  "close": 1.08150000,
  "volume": 12.34000000,
  "openTime": "2026-04-20T12:00:00Z",
  "closeTime": "2026-04-20T12:00:59Z",
  "isFinal": true
}
```

- 返回参数：无同步业务响应；发送成功仅代表 Kafka producer 已确认写入 broker
- 数据来源：`market-service` 的真实 K 线聚合与收盘发布链路
- 数据流向：`market-service -> Kafka falconx.market.kline.update -> trading-core-service -> TradingKafkaEventListener -> MarketKlineUpdateEventConsumer -> TradingMarketKlineUpdateApplicationService -> t_inbox`
- 上下游依赖：
  - 上游：`market-service` K 线 owner 聚合与 Outbox 发布
  - 下游：`TradingInboxRepository`、事件审计查询与联调验证
- 与其他接口如何配合：
  - `market.price.tick` 继续承担高频估值、TP/SL、强平和账户浮盈亏驱动
  - `market.kline.update` 当前只补充低频收盘事实，不替代最新价驱动链路
- 关联业务：低频事件审计、跨服务事件留痕、代表性 E2E 证据
- 业务逻辑说明：
  - `trading-core-service` 当前只记录“已正式消费”事实，不基于该事件修改订单、持仓、风险暴露或账户余额
  - 重复事件按 `eventId` 幂等忽略，仍保留重复日志
- 异常处理说明：
  - Kafka 入口异常会抛出异常，由消费者重试策略接管
  - 业务侧落库失败时不写 `t_inbox` 成功事实，避免伪消费
- 兼容性与影响范围说明：
  - 当前运行时采用“Kafka headers 承载事件元数据、body 只放 payload JSON”的口径
  - 该链路只证明 `trading-core-service` 的低频正式消费成立，不默认引入新的 Stage 7 产品规则

- 测试结论：
  - 开发人员：Codex
  - 测试日期：`2026-04-20`
  - 测试环境：本地 `SpringBootTest + MySQL + Redis + Kafka`
  - 测试结果：通过目标低频消费链路验证
  - 备注：`TradingKafkaMarketEventIntegrationTests.shouldConsumeMarketKlineUpdateViaKafkaAndRecordInboxFact` 已验证 `falconx.market.kline.update` 通过真实 Kafka 入口进入 `trading-core-service`，并在 `t_inbox` 写入 `eventId` 与 `eventType=market.kline.update`

### 3.5 trading-core-service - 查询当前交易账户

#### 3.5.1 接口基础信息

- 所属服务：`falconx-gateway -> falconx-trading-core-service`
- 接口名称：查询当前交易账户
- 接口说明：查询当前登录用户在默认结算币种下的交易账户快照
- 接口类型：`REST`
- 请求路径或主题：`/api/v1/trading/accounts/me`
- 请求方法：`GET`
- 认证要求：需要 `Bearer Access Token`
- 幂等要求：天然幂等

#### 3.5.2 请求信息

- 请求头：
  - `Authorization: Bearer <accessToken>`
- Path 参数：无
- Query 参数：无
- 请求体：无

请求示例：

```http
GET /api/v1/trading/accounts/me
Authorization: Bearer eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...
```

#### 3.5.3 响应信息

- 成功业务码：`0`
- 失败业务码：`10001`、`10012`、`10013`
- 响应说明：
  - 返回当前用户账户的 `balance / frozen / marginUsed / available / marginMode`
  - `marginMode`（V11 起返回，`CROSS / ISOLATED` 字符串）：账户级默认保证金模式偏好；下单 `marginMode` 不传时由该字段 inherit；一期硬约束最终 marginMode 必须为 `ISOLATED` 否则下单返回 `MARGIN_MODE_NOT_SUPPORTED`
  - `openPositions` 返回当前 `OPEN` 持仓视图
  - `markPrice` 不直接回显市场事件里的单值 `mark`，而是按持仓方向解析有效标记价：`BUY -> bid`，`SELL -> ask`
  - **STAGE-14E1（2026-06-02，硬 break 无 legacy）**：响应在 `marginMode` 基础上新增账户级 `equity` / `marginLevel` / `marginLevelStatus`（实时算，口径见下）；`openPositions[]` 持仓项浮盈亏由单币 `unrealizedPnl` **硬切**为双币 + 元数据（删 `unrealizedPnl`，加 `quoteCurrency / fxRate / unrealizedPnlInQuote / unrealizedPnlInAccount / isolatedMargin`），与 WebSocket `position.update` / `account.update` 同口径（详见 [WebSocket 接口规范 §5.4](WebSocket接口规范.md)）。**部署须 trading-core + 前端同窗口**。
    - `equity`：账户净值（AC 账户币）= `balance + frozen + Σ uPnL_i(AC)`（复用 `AccountEquityCalculator`）；FX 不可用降级为 null
    - `marginLevel`：账户级保证金率（百分比，已 ×100，2 位 HALF_UP）；无持仓 / 除零 / FX 降级为 null
    - `marginLevelStatus`：`HEALTHY` / `MARGIN_CALL` / `STOP_OUT`（`MarginLevelMonitor` 按 `marginLevel` + `t_risk_config` 阈值判定；marginLevel 为 null 时 `HEALTHY`）
    - 持仓项 `unrealizedPnlInQuote` 不持久化，查询时基于 Redis 最新 `bid / ask` 动态算有效标记价后实时计算；`unrealizedPnlInAccount = unrealizedPnlInQuote × fxRate`（FX 不可用降级开仓冻结 `entryFxRate`，不抛）；`isolatedMargin` ISOLATED 仓有值、CROSS 仓为 null
  - 若账户不存在，服务会按默认结算币种自动初始化一个空账户
  - 若同一用户 1 秒内第 11 次访问 `/api/v1/trading/**`，gateway 返回 HTTP `429` + `10012 / Trading Rate Limited`
  - 若同一 IP 1 分钟内第 201 次访问任意 `/api/v1/**`，gateway 返回 HTTP `429` + `10013 / Global IP Rate Limited`

成功响应示例：

```json
{
  "code": "0",
  "message": "success",
  "data": {
    "accountId": 1,
    "userId": 31001,
    "currency": "USDT",
    "balance": 0.00000000,
    "frozen": 0.00000000,
    "marginUsed": 0.00000000,
    "available": 0.00000000,
    "marginMode": "ISOLATED",
    "equity": -10.00000000,
    "marginLevel": null,
    "marginLevelStatus": "HEALTHY",
    "openPositions": [
      {
        "positionId": 10,
        "symbol": "BTCUSDT",
        "side": "BUY",
        "quantity": 1.00000000,
        "entryPrice": 10000.00000000,
        "markPrice": 9990.00000000,
        "quoteCurrency": "USDT",
        "fxRate": 1.00000000,
        "unrealizedPnlInQuote": -10.00000000,
        "unrealizedPnlInAccount": -10.00000000,
        "isolatedMargin": 1000.00000000,
        "marginMode": "ISOLATED",
        "liquidationPrice": 9000.00000000,
        "takeProfitPrice": 10200.00000000,
        "stopLossPrice": 9800.00000000,
        "quoteStale": false,
        "quoteTs": "2026-04-17T11:22:39.000Z",
        "priceSource": "integration-test"
      }
    ]
  },
  "timestamp": "2026-04-17T11:22:40.253+08:00",
  "traceId": "1e5cbb3a4f674c7d8dca5e343b2fe6bb"
}
```

失败响应示例：

```json
{
  "code": "10001",
  "message": "Unauthorized",
  "data": null,
  "timestamp": "2026-04-17T11:27:07.843+08:00",
  "traceId": "fa0f6d966c69453183bbfbd95647f396"
}
```

#### 3.5.4 日志与链路要求

- 关键日志点：
  - `gateway.request.received`
  - `gateway.auth.accepted`
  - `trading.http.account.received`
- 是否要求写审计日志：否
- 是否要求透传 `traceId`：是，由 gateway 生成并透传

#### 3.5.5 测试结论

- 开发人员：Codex
- 测试日期：`2026-04-17`
- 测试环境：本地 `SpringBootTest + MockMvc`、`SpringBootTest + WebTestClient`
- 测试结果：通过
- 备注：已验证网关透传 `X-User-*` 头到 trading-core-service

### 3.6 trading-core-service - 提交市价单

#### 3.6.1 接口基础信息

- 所属服务：`falconx-gateway -> falconx-trading-core-service`
- 接口名称：提交市价单
- 接口说明：提交一笔最小骨架市价单，由 trading-core-service 完成同步风控、保证金和订单落地
- 接口类型：`REST`
- 请求路径或主题：`/api/v1/trading/orders/market`
- 请求方法：`POST`
- 认证要求：需要 `Bearer Access Token`
- 幂等要求：使用 `clientOrderId` 做幂等；重复请求返回同一订单结果并标记 `duplicate=true`
- 规则冻结说明：正式产品规则已冻结为“单用户单 `symbol` 单净持仓 + 稳定 `positionId`”
- 当前实现说明：当前仓库仍按独立 `OPEN` 持仓事实核对；下述请求/响应示例代表当前实现，不代表净持仓模型已落地
- 后续实现影响：同向下单为加仓，反向下单为减仓或穿零翻仓，`positionId` 语义将收敛为稳定净持仓 ID

#### 3.6.2 请求信息

- 请求头：
  - `Authorization: Bearer <accessToken>`
  - `Content-Type: application/json`
- Path 参数：无
- Query 参数：无
- 请求体：
  - `symbol`：交易品种
  - `side`：`BUY / SELL`
  - `quantity`：下单数量
  - `leverage`：杠杆倍数
  - `marginMode`：可选；未传时默认 `ISOLATED`
  - `takeProfitPrice`：可选，持仓级止盈触发价
  - `stopLossPrice`：可选，持仓级止损触发价
  - `clientOrderId`：客户端幂等键

请求示例：

```json
{
  "symbol": "BTCUSDT",
  "side": "BUY",
  "quantity": 1.0,
  "leverage": 10,
  "marginMode": "ISOLATED",
  "takeProfitPrice": 10100.0,
  "stopLossPrice": 9800.0,
  "clientOrderId": "integration-order-31002"
}
```

#### 3.6.3 响应信息

- 成功业务码：`0`
- 失败业务码：`10001`、`10007`、`30002`、`30003`、`30005`、`30006`、`30070`、`30072`、`30073`、`40001`、`40002`、`40008`、`40010`、`99004`
- 响应说明：
  - 下单成功时返回订单、持仓、成交和账户快照
  - `requestPrice` 记录的是下单时的可成交参考价：`BUY -> ask`，`SELL -> bid`
  - **（STAGE-14B 货币转换）** 当品种 quote currency ≠ 账户币且 FX rate 不可用（trading 侧 `FxRateService` 查不到换算路径）时返回业务码 `30073`，拒单原因固定为 `FX_RATE_UNAVAILABLE`；开仓阶段对 FX 不可用为**硬拒单**，不落账。平仓 / 强平 / Swap 等被动结算阶段 FX 不可用则不阻断（`fx` 退化为 `1`、`original_currency` 记真实 quoteCurrency、打 WARN），口径见 [事务与幂等规范](../architecture/事务与幂等规范.md) §6.10
  - 行情缺失或 `NO_QUOTE` 返回业务码 `30003`，拒单原因固定为 `MARKET_QUOTE_NOT_FOUND`
  - 行情 `STALE / ABNORMAL` 返回业务码 `30002`，拒单原因固定为 `MARKET_QUOTE_STALE`
  - 保证金不足等通用风控拒绝返回业务码 `40002`，同时保留拒单原因和订单骨架
  - 单用户同 `symbol` 的 `OPEN` 持仓数达到 `t_risk_config.max_position_per_user` 时返回业务码 `30005`，拒单原因固定为 `POSITION_LIMIT_REACHED`
  - 全平台同 `symbol` 的 `OPEN` 持仓数达到 `t_risk_config.max_position_total` 时返回业务码 `30006`，拒单原因固定为 `PLATFORM_POSITION_LIMIT_REACHED`
  - 非交易时段或节假日休市时返回业务码 `40008`，拒单原因固定为 `SYMBOL_TRADING_SUSPENDED`
  - 显式传入 `marginMode=CROSS` 时返回业务码 `40010`，拒单原因固定为 `MARGIN_MODE_NOT_SUPPORTED`
  - **（STAGE-14C1 杠杆/MM 分级）** 下单杠杆超出按 notional（账户币）落档的 tier 档位上限（`leverage > tier.maxLeverage`）时返回业务码 `30070`，拒单原因固定为 `LEVERAGE_EXCEEDS_TIER`；档位解析按 `notional_lower` 含、`notional_upper` 不含（`[lower, upper)`），来源 `t_symbol_leverage_tier`（30s 缓存）。强平价 / MM 的 `mmRate` 取该档 `mm_rate`（替换原硬码 properties 0.005）并冻结到 `t_position.mm_rate_at_open`。表结构见 [数据库设计](../database/falconx一期数据库设计.md) §4.3（V30/V32），档位口径见 [状态机规范](../domain/状态机规范.md) §6.2
  - **（STAGE-14C1 杠杆/MM 分级）** 该 `symbol + group_code` 无杠杆/MM 档位配置或落不进任何档时返回业务码 `30072`，拒单原因固定为 `TIER_CONFIG_NOT_FOUND`
  - **（STAGE-14C2 FX_PAUSED 按类目）** 活跃 `GLOBAL_PAUSE`（含 FX_PAUSED 升级）下，下单品种按 `SymbolSpec.category()` 查 `t_fx_pause_behavior`，`allow_open=false`（如 forex / metal 默认）时返回业务码 `30087`，拒单原因固定为 `GLOBAL_PAUSE_ACTIVE`；`allow_open=true`（如 crypto）不因 pause 拒、继续走品种级别风控动作。降级：`category` 缺失（过渡期旧 Redis 快照）或 behavior 缺失时**开仓保守全拒**（回退 `BBOOK_RISK_GLOBAL_PAUSE`），口径见 [状态机规范](../domain/状态机规范.md) §6.5
  - **（现状核对）** `30071 INSUFFICIENT_MARGIN_FOR_OPEN`（master §7.3 已规划）当前尚未在 controller reason→码映射中接入：开仓保证金不足现走通用风控码 `40002` / 可用余额不足 `40001`
  - **（显示精度统一 切片3）** 市价开仓 `quantity` 小数位超过该 symbol `qtyPrecision` 时按通用风控拒单：业务码 `40002` + 拒单原因 `QTY_PRECISION_EXCEEDED`（同 `NOTIONAL_BELOW_MIN` 模式，保留 REJECTED 订单骨架）。挂单（LIMIT/STOP）走 trading 业务异常：`quantity` 超 `qtyPrecision` 返回 `30017 QTY_PRECISION_EXCEEDED`、`limitPrice`/`triggerPrice` 超 `pricePrecision` 返回 `30018 PRICE_PRECISION_EXCEEDED`。精度真源 `SymbolSpec.qtyPrecision/pricePrecision`；SymbolSpec 或精度字段缺失（过渡期旧 Redis 快照）时跳过该校验。
  - 拒单场景仍会持久化一条 `REJECTED` 订单骨架，便于审计
  - 写操作场景下，若用户状态为 `FROZEN`，gateway 会直接返回 `10007`
  - 开仓成功后若 `net_exposure_usd` 首次超过 `hedge_threshold_usd`，或方向切换后仍处于超阈值状态，会在事务提交后发布服务内 `TradingHedgeAlertEvent` stub，并同步写入 `t_hedge_log`
  - **（STAGE-14B 内部口径说明，非对外 REST 字段）** trading-core 算法层用两个内部 record 承载原币 / 账户币双口径，仅用于服务内换算与落账留痕，**不进入本接口的请求/响应契约**：
    - `MarginResult{inQuote, inAccount, fxRate}`：`inQuote` = 原币（quote currency）保证金；`inAccount = inQuote × fxRate` = 账户币保证金（驱动余额校验）；`fxRate = fx(quoteCurrency→accountCurrency)`，同币种为 `1`，FX 不可用时 `inAccount` / `fxRate` 为 `null` 并触发上面的 `30073` 拒单
    - `PnlResult{inQuote, inAccount, fxRate, quoteCurrency}`：`inQuote` = 原币 PnL；`inAccount = inQuote × fxRate` = 账户币 PnL（写入 balance / equity）；`quoteCurrency` 为原币代码，用于落账 `t_ledger.original_currency` 留痕
    - 两个 record 落账时对应 `t_ledger` 三列（`amount`=`inAccount`、`original_amount`=`inQuote`、`fx_rate_at_settlement`=`fxRate`、`original_currency`=`quoteCurrency`），自洽性与例外见 [事务与幂等规范](../architecture/事务与幂等规范.md) §6.10

成功响应示例：

```json
{
  "code": "0",
  "message": "success",
  "data": {
    "orderNo": "O00000001",
    "orderStatus": "FILLED",
    "rejectionReason": null,
    "duplicate": false,
    "symbol": "BTCUSDT",
    "side": "BUY",
    "quantity": 1.00000000,
    "requestPrice": 10000.00000000,
    "filledPrice": 10000.00000000,
    "leverage": 10,
    "marginMode": "ISOLATED",
    "margin": 1000.00000000,
    "fee": 5.00000000,
    "positionId": 1,
    "positionStatus": "OPEN",
    "takeProfitPrice": 10100.00000000,
    "stopLossPrice": 9800.00000000,
    "tradeId": 1,
    "account": {
      "accountId": 2,
      "userId": 31002,
      "currency": "USDT",
      "balance": 1995.00000000,
      "frozen": 0.00000000,
      "marginUsed": 1000.00000000,
      "available": 995.00000000,
      "openPositions": []
    }
  },
  "timestamp": "2026-04-17T11:22:40.172+08:00",
  "traceId": "b8c3f6696af34c13be20f2ff65c6b36d"
}
```

失败响应示例：

```json
{
  "code": "30002",
  "message": "Price Source Stale Or Disconnected",
  "data": {
    "orderNo": "O00000002",
    "orderStatus": "REJECTED",
    "rejectionReason": "MARKET_QUOTE_STALE",
    "duplicate": false,
    "symbol": "ETHUSDT",
    "side": "SELL",
    "quantity": 1.00000000,
    "requestPrice": 1990.00000000,
    "filledPrice": null,
    "leverage": 10,
    "marginMode": null,
    "margin": 0.00000000,
    "fee": 0.00000000,
    "positionId": null,
    "positionStatus": null,
    "takeProfitPrice": null,
    "stopLossPrice": null,
    "tradeId": null,
    "account": {
      "accountId": 3,
      "userId": 31003,
      "currency": "USDT",
      "balance": 2000.00000000,
      "frozen": 0.00000000,
      "marginUsed": 0.00000000,
      "available": 2000.00000000,
      "openPositions": []
    }
  },
  "timestamp": "2026-04-17T11:22:40.280+08:00",
  "traceId": "9de49fbb96dc430699ac0d0df589ab20"
}
```

持仓数量上限失败响应示例：

```json
{
  "code": "30005",
  "message": "Position Limit Reached",
  "data": {
    "orderNo": "O00000003",
    "orderStatus": "REJECTED",
    "rejectionReason": "POSITION_LIMIT_REACHED",
    "duplicate": false,
    "symbol": "BTCUSDT",
    "side": "BUY",
    "quantity": 1.00000000,
    "requestPrice": null,
    "filledPrice": null,
    "leverage": 10,
    "marginMode": null,
    "margin": 0.00000000,
    "fee": 0.00000000,
    "positionId": null,
    "positionStatus": null,
    "takeProfitPrice": null,
    "stopLossPrice": null,
    "tradeId": null,
    "account": {
      "accountId": 5,
      "userId": 31040,
      "currency": "USDT",
      "balance": 3995.00000000,
      "frozen": 0.00000000,
      "marginUsed": 1000.00000000,
      "available": 2995.00000000,
      "openPositions": []
    }
  },
  "timestamp": "2026-04-29T11:30:12.100+08:00",
  "traceId": "c2a8a7f6df924d2f9d93ec8c17f7c5ad"
}
```

说明：平台同 `symbol` 持仓总数达到上限时响应结构一致，业务码为 `30006`，`message` 为 `Platform Position Limit Reached`，`rejectionReason` 为 `PLATFORM_POSITION_LIMIT_REACHED`。

节假日休市失败响应示例：

```json
{
  "code": "40008",
  "message": "Symbol Trading Suspended",
  "data": {
    "orderNo": null,
    "orderStatus": "REJECTED",
    "rejectionReason": "SYMBOL_TRADING_SUSPENDED",
    "duplicate": false,
    "symbol": "BTCUSDT",
    "side": "BUY",
    "quantity": 1.00000000,
    "requestPrice": null,
    "filledPrice": null,
    "leverage": 10,
    "marginMode": null,
    "margin": 0.00000000,
    "fee": 0.00000000,
    "positionId": null,
    "positionStatus": null,
    "takeProfitPrice": null,
    "stopLossPrice": null,
    "tradeId": null,
    "account": {
      "accountId": 4,
      "userId": 31004,
      "currency": "USDT",
      "balance": 2000.00000000,
      "frozen": 0.00000000,
      "marginUsed": 0.00000000,
      "available": 2000.00000000,
      "openPositions": []
    }
  },
  "timestamp": "2026-04-17T17:17:37.427+08:00",
  "traceId": "f31c7acbcb5f4b9cb3d05d3f9aef4ee4"
}
```

#### 3.6.4 日志与链路要求

- 关键日志点：
  - `gateway.request.received`
  - `gateway.auth.accepted`
  - `trading.http.order.received`
  - `trading.order.received`
  - `trading.order.filled / rejected`
  - `trading.risk.hedge.alert / recovered`（仅在 FX-026 阈值状态变化时出现）
- 是否要求写审计日志：否
- 是否要求透传 `traceId`：是，由 gateway 生成并透传

#### 3.6.5 测试结论

- 开发人员：Codex
- 测试日期：`2026-04-29`
- 测试环境：本地 `SpringBootTest + MockMvc`
- 测试结果：通过
- 备注：已验证成功成交、持仓级 TP/SL 字段落库回显、`marginMode=ISOLATED` 显式下单、显式 `marginMode=CROSS` 返回 `40010 / MARGIN_MODE_NOT_SUPPORTED` 且保留 `REJECTED` 订单骨架、`MARKET_QUOTE_STALE` 返回 `30002` 拒单、`MARKET_QUOTE_NOT_FOUND` 返回 `30003` 拒单、节假日休市返回 `40008 / SYMBOL_TRADING_SUSPENDED`，以及 `TradingControllerIntegrationTests.shouldRejectMarketOrderWhenUserOpenPositionLimitReached`、`TradingControllerIntegrationTests.shouldRejectMarketOrderWhenPlatformOpenPositionLimitReached` 覆盖的 `30005 / 30006` 持仓数量上限拒单

### 3.7 trading-core-service - 手动平仓

#### 3.7.1 接口基础信息

- 所属服务：`falconx-gateway -> falconx-trading-core-service`
- 接口名称：手动平仓
- 接口说明：手动关闭当前用户自己的 `OPEN` 持仓；手动平仓与 TP/SL 自动触发、强平共用正式结算写路径，但本接口只执行手动平仓
- 接口类型：`REST`
- 请求路径或主题：`/api/v1/trading/positions/{positionId}/close`
- 请求方法：`POST`
- 认证要求：需要 `Bearer Access Token`
- 幂等要求：同一终态持仓重复提交返回 `40007`
- 当前实现状态：`已实现`
- 阶段边界：当前只把该接口作为 `Stage 6A` 交易链路核对事实，不把它表述为 `Stage 7 / 7A` 已验收
- 规则冻结说明：净持仓模型下，本接口仍保留 `/positions/{positionId}/close`，但 `positionId` 表示稳定净持仓 ID
- 当前实现说明：当前仓库仍按独立 `positionId` 持仓执行整仓关闭，尚未切换为净持仓加减仓模型

#### 3.7.2 请求信息

- 请求头：
  - `Authorization: Bearer <accessToken>`
  - `Content-Type: application/json`
- Path 参数：
  - `positionId`：持仓 ID
- Query 参数：无
- 请求体：无

#### 3.7.3 响应信息

- 成功业务码：`0`
- 失败业务码：`10001`、`30002`、`30003`、`40004`、`40007`、`40008`
- 响应说明：
  - 平仓价按持仓方向解析有效标记价：`BUY -> bid`，`SELL -> ask`
  - 非交易时段、节假日全休或人工例外停盘阻塞手动平仓，返回 `40008`
  - Redis 无报价时返回 `30003`
  - Redis 报价 stale 或 `quoteStatus=ABNORMAL` 时返回 `30002`
  - Redis 报价 `quoteStatus=NO_QUOTE` 时返回 `30003`
  - 持仓不存在或不属于当前用户时返回 `40004`
  - 持仓已关闭时返回 `40007`
  - 成功平仓时同事务写入 `t_outbox.event_type=trading.position.closed`
  - 成功响应中的 `account.openPositions` 回显当前用户剩余的 `OPEN` 持仓视图，而不是固定返回空数组
  - 手动平仓不会新增 `t_order`
  - 手动平仓会同步刷新 FX-026 风险观测状态；若因此首次进入超阈值或方向切换后仍超阈值，会在事务提交后发布服务内 `TradingHedgeAlertEvent` stub，并同步写入 `t_hedge_log`

成功响应示例：

```json
{
  "code": "0",
  "message": "success",
  "data": {
    "positionId": 39201747692032000,
    "positionStatus": "CLOSED",
    "closePrice": 10150.00000000,
    "closeReason": "MANUAL",
    "realizedPnl": 150.00000000,
    "closedAt": "2026-04-19T12:13:45.000+08:00",
    "tradeId": 39201747700420608,
    "account": {
      "accountId": 39201747620696064,
      "userId": 31005,
      "currency": "USDT",
      "balance": 2145.00000000,
      "frozen": 0.00000000,
      "marginUsed": 0.00000000,
      "available": 2145.00000000,
      "openPositions": []
    }
  },
  "timestamp": "2026-04-19T12:13:45.000+08:00",
  "traceId": "d2a6c8e4f9f5482dbf3a5f9c2fcb2c01"
}
```

#### 3.7.4 关键日志点

- `trading.http.position.close.received`
- `trading.position.exit.completed`
- `trading.risk.hedge.alert / recovered`（仅在 FX-026 阈值状态变化时出现）
- `trading.http.request.failed`

#### 3.7.5 测试结论

- 开发人员：Codex
- 测试日期：`2026-04-19`
- 测试环境：本地 `SpringBootTest + MockMvc + MySQL + Redis`
- 测试结果：通过
- 备注：已验证 `BUY/SELL` 手动平仓成功、节假日全休时新开仓与既有持仓手动平仓均返回 `40008`、报价 stale 返回 `30002`、报价缺失返回 `30003`、持仓不存在或不属于当前用户时返回 `40004`、重复平仓返回 `40007`、平仓不新增 `t_order` 且会写入 `t_outbox.event_type=trading.position.closed`，以及“同一用户仍有另一笔 OPEN 持仓时，平仓成功响应里的 `account.openPositions` 会回显剩余持仓”

### 3.8 trading-core-service - 修改持仓 TP/SL

#### 3.8.1 接口基础信息

- 所属服务：`falconx-gateway -> falconx-trading-core-service`
- 接口名称：修改持仓 TP/SL
- 接口说明：修改当前用户自己 `OPEN` 持仓的止盈/止损触发价；未传字段保持原值，显式传 `null` 表示清空
- 接口类型：`REST`
- 请求路径或主题：`/api/v1/trading/positions/{positionId}`
- 请求方法：`PATCH`
- 认证要求：需要 `Bearer Access Token`
- 幂等要求：同一持仓相同请求体重复提交应返回相同结果
- 当前实现状态：`已实现`
- 阶段边界：当前只用于核对持仓级风险控制与自动触发链路，不等同 `Stage 7 / 7A` 已验收
- 规则冻结说明：净持仓模型落地后，TP/SL 将挂在“稳定净持仓”而非多张独立 `OPEN` 持仓上
- 当前实现说明：当前文档中的字段与示例仍对应现仓库已实现语义

#### 3.8.2 请求信息

- 请求头：
  - `Authorization: Bearer <accessToken>`
  - `Content-Type: application/json`
- Path 参数：
  - `positionId`：持仓 ID
- Query 参数：无
- 请求体：
  - `takeProfitPrice`：可选，持仓级止盈触发价；显式传 `null` 表示清空
  - `stopLossPrice`：可选，持仓级止损触发价；显式传 `null` 表示清空
  - 约束：
    - 请求体必须是 JSON 对象
    - 至少显式提供 `takeProfitPrice` 或 `stopLossPrice` 之一
    - 非数值、`0`、负数统一视为非法请求体
    - 成功更新只修改 `t_position.take_profit_price / stop_loss_price` 并在事务提交后刷新 `OpenPositionSnapshotStore`

请求示例：

```json
{
  "takeProfitPrice": 10250.0,
  "stopLossPrice": 9850.0
}
```

#### 3.8.3 响应信息

- 成功业务码：`0`
- 失败业务码：`99004`、`40004`、`40007`
- 响应说明：
  - 成功时返回更新后的持仓风险控制快照
  - 若 `takeProfitPrice` 或 `stopLossPrice` 未传，则保持原值
  - 若显式传 `null`，则清空对应字段
  - 若持仓不存在或不属于当前用户，返回 HTTP `200` + `40004 / Position Not Found`
  - 若持仓已进入终态，返回 HTTP `200` + `40007 / Position Already Closed`
  - 非对象 JSON、空请求体、空对象、两个字段都未提供、非数值、`0`、负数，统一返回 HTTP `400` + `99004 / invalid request payload`

成功响应示例：

```json
{
  "code": "0",
  "message": "success",
  "data": {
    "positionId": 39201747692032000,
    "symbol": "BTCUSDT",
    "status": "OPEN",
    "takeProfitPrice": 10250.00000000,
    "stopLossPrice": 9850.00000000
  },
  "timestamp": "2026-04-19T17:10:00.000+08:00",
  "traceId": "4c9d90a8c5f64a5890e90e9cccb6cb3d"
}
```

#### 3.8.4 测试结论

- 开发人员：Codex
- 测试日期：`2026-04-19`
- 测试环境：本地 `SpringBootTest + MockMvc + MySQL + Redis`
- 测试结果：通过
- 关键日志点：
  - `trading.http.position.patch.received`
  - `trading.http.request.invalid`
  - `trading.position.risk-controls.updated`
  - `trading.position.risk-controls.noop`
  - `trading.http.request.failed`
- 备注：已验证双字段修改、只改单字段、显式 `null` 清空、非本人持仓返回 `40004`、终态持仓返回 `40007`、非法请求体稳定返回 HTTP `400 + 99004`，以及“PATCH 与自动触发交叉时，正式 owner 写路径会基于最新 DB 持仓重新复核触发条件，不会按旧 TP/SL 误平仓”

#### 3.8.5 代表性 E2E 结论

- 当前已通过代表性 E2E 覆盖 `gateway + identity-service + market-service + trading-core-service + wallet-service + Kafka` 的受控真运行时主链路：
  - `TC-E2E-001`：`注册 -> 入金 -> 登录 -> 开仓`
  - 补充代表性 E2E：`注册 -> 入金 -> 登录 -> 开仓 -> 手动平仓 -> 账户视图收敛`
  - `TC-E2E-010`：`注册 -> 入金 -> 登录 -> 开仓 -> TP 自动平仓 -> 账户视图收敛`
  - 补充代表性 E2E：`注册 -> 入金 -> 登录 -> 开仓 -> SL 自动平仓 -> 账户视图收敛`
  - `TC-E2E-011`：`注册 -> 入金 -> 登录 -> 开仓 -> 强平 -> 账户视图收敛`
- 已通过事实：
  - gateway 北向注册、登录、下单、账户查询链路可稳定复现
  - `T6A-08` 接口回归已复验：`/api/v1/auth/register`、`/api/v1/auth/login`、`/api/v1/auth/refresh`、`/api/v1/auth/logout`、`/api/v1/trading/accounts/me`、`/api/v1/trading/orders/market` 在当前 `gateway -> identity / trading` 组合下仍保持既有响应口径
  - `T6A-08` 鉴权回归已复验：缺失或非法 `Authorization` 仍返回 `401 + 10001`；登出后当前 Access Token 会按剩余 TTL 写入 Redis 黑名单，并被 gateway 拒绝继续访问受保护接口
  - `T6A-08` 状态回归已复验：注册后用户状态直接为 `ACTIVE`；历史 `PENDING_DEPOSIT` 登录会归一化为 `ACTIVE`，不再作为登录拦截状态
  - `T6A-08` 联动回归已复验：`wallet.deposit.confirmed -> trading.deposit.credited -> identity 入金事件幂等留痕 -> gateway 受保护接口放行` 的链路结果保持一致
  - `market-service` 真运行时已参与 owner ingestion、Redis 最新价与北向报价查询链路
  - `market-service` 当前主行情源已切换为自建 LP Socket.IO：本轮已按 `socket-client-demo` 补齐 `socketVersion=1.1 / APP-ID / EIO=4 / socketSource=1 / Authorization=` 握手、`external-sub-symbol` 字符串订阅、`price-compression` Snappy 解压与标准报价转换证据；当前配置文件中的 LP 地址与 `APP-ID` 仍需补齐持续收流证据
  - `market-service` 已补同一条标准报价在 Redis、ClickHouse 与 Kafka `falconx.market.price.tick` 的一致性证据；当前运行时以 Kafka headers 承载事件元数据、body 承载 payload
  - `wallet-service` 真运行时已参与真实 xpub 派生入金地址分配、原始入金事实与 outbox 投递链路；当前已开放入金页幂等地址申请北向接口
  - Kafka 事件 `falconx.wallet.deposit.confirmed`、`falconx.market.price.tick` 可驱动 `trading-core-service` 与 `identity-service` 完成 owner 状态推进
  - `falconx.market.kline.update` 已由 `trading-core-service` 正式消费，并在 `t_inbox` 形成低频事件留痕
  - `falconx.market.price.tick` 已补 Kafka 入口失败重试；当前仍保持高频直连消费，不写 `t_inbox`
  - `TC-E2E-010` 的 TP 自动平仓样例已按交易侧有效价校准：多头看 `bid`、空头看 `ask`，不能只依据兼容字段 `mark`
  - `QuoteDrivenEngineTriggerRuleTests` 已显式验证空头持仓 `TP/SL` 方向：`SELL` 在 `effectiveMarkPrice <= takeProfitPrice` 时止盈、在 `effectiveMarkPrice >= stopLossPrice` 时止损
  - 手动平仓代表性 E2E 已补 `GatewayManualCloseE2ETests`：平仓后 gateway 账户视图会收敛到 `openPositions=[]`、`marginUsed=0`，且 owner 终态已验证 `t_position(status=2, close_reason=1)`、`t_trade(trade_type=2)`、`t_ledger(biz_type=8)`、`t_outbox(event_type=trading.position.closed)`、`t_risk_exposure.net_exposure=0`
  - `TP` 自动平仓后，gateway 账户视图会收敛到 `openPositions=[]`、`marginUsed=0`，且 `balance` 高于开仓后基线
  - `TP` 场景 owner 终态已验证：`t_position(status=2, close_reason=2)`、`t_trade(trade_type=2)`、`t_ledger(biz_type=8)`、`t_outbox(event_type=trading.position.closed)`、`t_risk_exposure.net_exposure=0`
  - `SL` 自动平仓代表性 E2E 已补 `GatewayStopLossE2ETests`：平仓后 gateway 账户视图会收敛到 `openPositions=[]`、`marginUsed=0`，且 `balance` 低于开仓后基线
  - `SL` 场景 owner 终态已验证：`t_position(status=2, close_reason=3)`、`t_trade(trade_type=2)`、`t_ledger(biz_type=8)`、`t_outbox(event_type=trading.position.closed)`、`t_risk_exposure.net_exposure=0`
  - 强平后，gateway 账户视图会收敛到 `openPositions=[]`、`marginUsed=0`、`balance>=0`
  - 强平场景 owner 终态已验证：`t_position(status=3, close_reason=4)`、`t_trade(trade_type=3)`、`t_ledger(biz_type=9)`、`t_liquidation_log`、`t_outbox(event_type=trading.liquidation.executed)`、`t_risk_exposure.net_exposure=0`
  - `TradingLiquidationIntegrationTests` 已验证负净值保护不会把 `balance` 打成负数，且 `t_liquidation_log.platform_covered_loss` 会记录平台兜底金额；`TradingPersistenceIntegrationTests.shouldRollbackManualCloseWhenRiskExposureUpdateFails` 已验证 `t_risk_exposure` 写入失败时整笔平仓事务回滚
- 边界说明：
  - 以上 E2E 仍不等于 LP 外部真源与外部链节点真扫块已经进入同一自动化用例
  - 当前已证明 `market.kline.update -> trading-core-service -> t_inbox` 的正式低频消费链路成立
  - 当前已证明 `market.price.tick` 的 Kafka 入口失败重试专项成立，但不改变其“高频事件不落 `t_inbox`”的设计边界
  - 当前阶段正式结论已收敛为：`Stage 6A` 主链路已收口；`Stage 6B` 当前冻结范围已完成并收口，已包含 `Swap` owner 共享、本地结算、`accounts/me`、`swap-settlements`、`orders / trades / positions / ledger / liquidations` 用户视角查询、`swap.settled` 业务事件、`ws://{host}/ws/v1/market` 北向行情 WebSocket 与结构化运营观测
- 以上结论不等于系统已达到“生产可用”

### 3.8A trading-core-service - 追加逐仓保证金

#### 3.8A.1 接口基础信息

- 所属服务：`falconx-gateway -> falconx-trading-core-service`
- 接口名称：追加逐仓保证金
- 接口说明：为当前用户自己的 `OPEN` 持仓追加逐仓保证金，并同步重算最新 `liquidationPrice`
- 接口类型：`REST`
- 请求路径或主题：`/api/v1/trading/positions/{positionId}/margin`
- 请求方法：`POST`
- 认证要求：需要 `Bearer Access Token`
- 幂等要求：不提供跨请求幂等键；每次成功调用都会新增一条 `t_ledger.biz_type=10`
- 当前实现状态：`已实现`
- 阶段边界：该接口属于 `Stage 7A` 当前冻结范围，`Stage 7A` 已完成不代表 `CROSS` 或生产化准备已完成

#### 3.8A.2 请求信息

- 请求头：
  - `Authorization: Bearer <accessToken>`
  - `Content-Type: application/json`
- Path 参数：
  - `positionId`：持仓主键
- Query 参数：无
- 请求体：
  - `amount`：本次追加的逐仓保证金金额，必须为正数

请求示例：

```json
{
  "amount": 200.0
}
```

#### 3.8A.3 响应信息

- 成功业务码：`0`
- 失败业务码：`40001`、`40004`、`40007`、`99004`
- 响应说明：
  - 成功后账户 `balance / frozen` 不变，`marginUsed += amount`
  - 成功后持仓保持 `OPEN`
  - 成功后会回显最新 `marginMode / margin / liquidationPrice` 和当前账户快照
  - 当前实现不新增 Kafka topic / payload，也不写 Outbox 业务事件

成功响应示例：

```json
{
  "code": "0",
  "message": "success",
  "data": {
    "positionId": 40246108080967680,
    "symbol": "BTCUSDT",
    "status": "OPEN",
    "marginMode": "ISOLATED",
    "margin": 1200.00000000,
    "liquidationPrice": 8850.00000000,
    "account": {
      "accountId": 40246107988680704,
      "userId": 31036,
      "currency": "USDT",
      "balance": 1995.00000000,
      "frozen": 0.00000000,
      "marginUsed": 1200.00000000,
      "available": 795.00000000,
      "marginMode": "ISOLATED",
      "equity": 1985.00000000,
      "marginLevel": 16541.67,
      "marginLevelStatus": "HEALTHY",
      "openPositions": [
        {
          "positionId": 40246108080967680,
          "symbol": "BTCUSDT",
          "side": "BUY",
          "quantity": 1.00000000,
          "entryPrice": 10000.00000000,
          "markPrice": 9990.00000000,
          "quoteCurrency": "USDT",
          "fxRate": 1.00000000,
          "unrealizedPnlInQuote": -10.00000000,
          "unrealizedPnlInAccount": -10.00000000,
          "isolatedMargin": 1200.00000000,
          "marginMode": "ISOLATED",
          "liquidationPrice": 8850.00000000,
          "takeProfitPrice": 10100.00000000,
          "stopLossPrice": 9800.00000000,
          "quoteStale": false,
          "quoteTs": "2026-04-22T01:24:17Z",
          "quoteSource": "integration-test"
        }
      ]
    }
  },
  "timestamp": "2026-04-22T09:24:17.000+08:00",
  "traceId": "28b7417e78884e30a55577933e8e585c"
}
```

失败响应示例：

```json
{
  "code": "40001",
  "message": "Insufficient Margin",
  "data": null,
  "timestamp": "2026-04-22T09:24:17.000+08:00",
  "traceId": "28b7417e78884e30a55577933e8e585c"
}
```

#### 3.8A.4 日志与链路要求

- 关键日志点：
  - `trading.http.position.margin.received`
  - `trading.position.margin.request`
  - `trading.position.margin.supplemented`
  - `trading.http.request.failed`
- 是否要求写审计日志：否
- 是否要求透传 `traceId`：是，由 gateway 生成并透传

#### 3.8A.5 测试结论

- 开发人员：Codex
- 测试日期：`2026-04-22`
- 测试环境：本地 `SpringBootTest + MockMvc + MySQL + Redis + Kafka`
- 测试结果：通过
- 备注：已验证追加保证金成功、可用余额不足返回 `40001`、持仓不存在返回 `40004`、终态持仓返回 `40007`、`t_ledger.biz_type=10` 落账、`liquidationPrice` 从 `9050.00000000` 重算到 `8850.00000000`、`OpenPositionSnapshotStore` 会在事务提交后刷新，且追加保证金后旧强平条件会在执行前被二次校验跳过

### 3.8B wallet-service - 幂等确保我的入金地址

#### 3.8B.1 接口基础信息

- 所属服务：`falconx-gateway -> falconx-wallet-service`
- 接口名称：幂等确保我的入金地址
- 接口说明：用户首次进入入金页时，由 wallet-service 基于 account-level xpub 幂等分配并返回 `USDT-TRON / TRC20` 与 `USDT-ETH / ERC20` 入金地址；若地址已存在则返回既有地址
- 接口类型：`REST`
- 请求路径或主题：`/api/v1/wallet/deposit-addresses/ensure`
- 请求方法：`POST`
- 认证要求：`Bearer JWT`，经 gateway 鉴权后向下游注入 `X-User-Id`
- 幂等要求：通过 `(user_id, chain)` 唯一约束与事务内 `SELECT ... FOR UPDATE` 保证幂等；客户端不需要传 `X-Idempotency-Key`

#### 3.8B.2 请求信息

- 请求头：
  - `Authorization: Bearer <accessToken>`
  - `Content-Type: application/json`
  - `X-User-Id`：由 gateway 注入，客户端不直接传
- Path 参数：无
- Query 参数：无
- 请求体：空对象

请求示例：

```json
{}
```

#### 3.8B.3 响应信息

- 成功业务码：`0`
- 失败业务码：`20004`、`20006`、`99004`
- 响应说明：
  - 成功时固定返回 `addresses` 数组，当前顺序为 `TRC20 / TRON / USDT`、`ERC20 / ETH / USDT`
  - 地址由 `FALCONX_WALLET_TRON_ACCOUNT_XPUB` 与 `FALCONX_WALLET_ETH_ACCOUNT_XPUB` 的 account-level xpub 派生
  - wallet-service 不保存 mnemonic、私钥或可花费密钥
  - xpub 缺失、非法或派生失败时返回 `20006`，不退回 stub 地址

成功响应示例：

```json
{
  "code": "0",
  "message": "success",
  "data": {
    "addresses": [
      {
        "network": "TRC20",
        "chain": "TRON",
        "token": "USDT",
        "address": "Txxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
        "addressIndex": 1,
        "derivationPath": "m/44'/195'/0'/0/1"
      },
      {
        "network": "ERC20",
        "chain": "ETH",
        "token": "USDT",
        "address": "0x80c7Ee517f8EC258B6f19295D7A95B98D2a7BE06",
        "addressIndex": 1,
        "derivationPath": "m/44'/60'/0'/0/1"
      }
    ]
  },
  "timestamp": "2026-04-29T10:43:19.707+08:00",
  "traceId": "9265667686424d57aca71ca2296ff3f5"
}
```

失败响应示例：

```json
{
  "code": "20006",
  "message": "Wallet Address Allocation Failed",
  "data": null,
  "timestamp": "2026-04-29T10:43:50.114+08:00",
  "traceId": "84ccbcd2f2ca417492677801968b4179"
}
```

#### 3.8B.4 日志与链路要求

- 关键日志点：
  - `wallet.http.deposit-address.ensure.received`
  - `wallet.deposit-address.ensure.request`
  - `wallet.deposit-address.ensure.completed`
  - `wallet.request.failed`
- 是否要求写审计日志：否
- 是否要求透传 `traceId`：是，由 gateway 生成并在 wallet-service 入口写入 MDC 与响应头

#### 3.8B.5 测试结论

- 开发人员：Codex
- 测试日期：`2026-04-29`
- 测试环境：本地 `SpringBootTest + MockMvc + MySQL`
- 测试结果：通过
- 备注：`WalletDepositAddressControllerIntegrationTests.shouldEnsureUsdtTronAndEthDepositAddressesIdempotently` 已验证首次申请返回 TRC20/ERC20 两条真实派生地址、重复请求返回相同地址、DB 写入 `token / network / derivation_path`
- 备注：`WalletDepositAddressFailClosedIntegrationTests.shouldFailClosedWithoutAccountXpub` 已验证 xpub 缺失时返回 `20006` 且不写入地址
- 备注：`XpubWalletAddressDerivationServiceTests` 已验证 ETH/TRON 地址与私钥派生期望值一致，并验证 BSC 等未冻结链返回 `20004`

### 3.9 trading-core-service - 查询 Swap 结算明细

#### 3.9.1 接口基础信息

- 所属服务：`falconx-gateway -> falconx-trading-core-service`
- 接口名称：查询 Swap 结算明细
- 接口说明：分页查询当前登录用户已落账的 `Swap` 结算明细
- 接口类型：`REST`
- 请求路径或主题：`/api/v1/trading/swap-settlements`
- 请求方法：`GET`
- 认证要求：需要 `Bearer Access Token`
- 幂等要求：天然幂等

#### 3.9.2 请求信息

- 请求头：
  - `Authorization: Bearer <accessToken>`
- Path 参数：无
- Query 参数：
  - `page`：页码，默认 `1`
  - `pageSize`：每页条数，默认 `20`，允许范围 `1-100`
- 请求体：无

请求示例：

```http
GET /api/v1/trading/swap-settlements?page=1&pageSize=20
Authorization: Bearer eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...
```

#### 3.9.3 响应信息

- 成功业务码：`0`
- 失败业务码：`10001`、`10012`、`10013`、`99004`
- 响应说明：
  - 返回当前用户自己的 `Swap` 明细分页
  - `settlementType` 固定为 `SWAP_CHARGE / SWAP_INCOME`
  - `amount` 始终返回正数，方向由 `settlementType` 表达
  - `referenceNo` 当前固定为 `swap:{positionId}:{rolloverAt}`
  - `rolloverAt` 表示所属结算时点，`settledAt` 表示账本记录时间；当前首版两者口径一致
  - 若 `page < 1` 或 `pageSize` 超出 `1-100`，返回 HTTP `400` + `99004 / invalid request payload`

成功响应示例：

```json
{
  "code": "0",
  "message": "success",
  "data": {
    "page": 1,
    "pageSize": 20,
    "total": 1,
    "items": [
      {
        "ledgerId": 39925241899782144,
        "positionId": 39925241887199232,
        "symbol": "BTCUSDT",
        "side": "BUY",
        "settlementType": "SWAP_CHARGE",
        "amount": 1.0,
        "balanceAfter": 1994.0,
        "rolloverAt": "2026-04-21T04:08:37Z",
        "settledAt": "2026-04-21T04:08:37Z",
        "referenceNo": "swap:39925241887199232:2026-04-21T04:08:37Z"
      }
    ]
  },
  "timestamp": "2026-04-21T12:09:14.000+08:00",
  "traceId": "50c80174a9854754bd4661a050809261"
}
```

失败响应示例：

```json
{
  "code": "99004",
  "message": "invalid request payload",
  "data": null,
  "timestamp": "2026-04-21T12:09:14.000+08:00",
  "traceId": "1cb3daec5f1c4b7ea43aa0c2d8fc24d4"
}
```

#### 3.9.4 日志与链路要求

- 关键日志点：
  - `gateway.request.received`
  - `gateway.auth.accepted`
  - `trading.http.swap.settlements.received`
- 是否要求写审计日志：否
- 是否要求透传 `traceId`：是，由 gateway 生成并透传

#### 3.9.5 测试结论

- 开发人员：Codex
- 测试日期：`2026-04-21`
- 测试环境：本地 `SpringBootTest + MockMvc + MySQL + Redis + Kafka`
- 测试结果：通过
- 备注：`TradingControllerIntegrationTests.shouldListSwapSettlementsWithPagination` 已验证明细查询分页与字段回显；`TradingControllerIntegrationTests.shouldRejectInvalidSwapSettlementPagination` 已验证非法分页参数返回 HTTP `400 + 99004`

### 3.10 trading-core-service - BBook 风险观测告警桩事件

#### 3.10.1 接口基础信息

- 所属服务：`falconx-trading-core-service`
- 接口名称：BBook 风险观测告警桩事件
- 接口说明：当 `net_exposure_usd` 首次超过 `hedge_threshold_usd`，或方向切换后仍保持超阈值状态时，服务在事务提交后发布内部 Spring Event `TradingHedgeAlertEvent`；该事件只作为 BBook 自营风险观测告警 stub，不是北向 REST，也不是 Kafka topic
- 接口类型：`Internal`
- 请求路径或主题：`Spring Event: com.falconx.trading.event.TradingHedgeAlertEvent`
- 请求方法：`publish`
- 认证要求：无，仅限服务内监听
- 幂等要求：不保证 exactly-once；当前只在 `ALERT_ONLY` 分支发布，同方向持续超阈值不会重复发送
- 当前阶段定位：超前内部 stub，不计入 `Stage 6A` 验收，也不代表 BBook 自营风控闭环、生产告警系统或 A-book 对冲能力已完成；FalconX 一期不做 A-book 对冲执行出口

#### 3.10.2 请求信息

- 请求头：无
- Path 参数：无
- Query 参数：无
- 请求体：
  - `occurredAt`：触发观测的业务时间
  - `symbol`：交易品种
  - `netExposureUsd`：当前净美元敞口
  - `hedgeThresholdUsd`：当前阈值
  - `positionId`：触发本次变化的持仓 ID；纯行情刷新时允许为空
  - `triggerSource`：`OPEN_POSITION / MANUAL_CLOSE / TAKE_PROFIT / STOP_LOSS / LIQUIDATION / PRICE_TICK`
  - `markPrice`：本次估值使用的有效标记价；净多头使用 `bid`，净空头使用 `ask`
  - `quoteTs`：本次估值使用的行情时间
  - `priceSource`：行情来源
  - `hedgeLogId`：已落库的 `t_hedge_log.id`

请求示例：

```json
{
  "occurredAt": "2026-04-19T18:43:52.487+08:00",
  "symbol": "BTCUSDT",
  "netExposureUsd": 19980.00000000,
  "hedgeThresholdUsd": 15000.00000000,
  "positionId": 39299925120520192,
  "triggerSource": "OPEN_POSITION",
  "markPrice": 9990.00000000,
  "quoteTs": "2026-04-19T18:43:52.487+08:00",
  "priceSource": "risk-observability-unit-test",
  "hedgeLogId": 99
}
```

#### 3.10.3 响应信息

- 成功业务码：无同步响应
- 失败业务码：无同步响应
- 响应说明：
  - 该接口是服务内 Spring Event，无 HTTP / Kafka 同步响应
  - 监听器异常会被发布端捕获并记录错误日志，不回滚已经提交的交易主事务
  - 恢复到阈值内只写 `t_hedge_log(action_status=RECOVERED)` 与 `trading.risk.hedge.recovered` 日志，不发布本事件

成功响应示例：

```json
{}
```

失败响应示例：

```json
{}
```

#### 3.10.4 日志与链路要求

- 关键日志点：
  - `trading.risk.hedge.alert`
  - `trading.risk.hedge.recovered`
  - `trading.risk.hedge.event.publish.failed`
- 是否要求写审计日志：是；必须先写 `t_hedge_log`
- 是否要求透传 `traceId`：是；若来源调用链已有 `traceId`，事件监听器日志沿当前 MDC 透传

#### 3.10.5 测试结论

- 开发人员：Codex
- 测试日期：`2026-04-19`
- 测试环境：本地 `JUnit 5 + Mockito`，并结合 `SpringBootTest + MySQL + Redis`
- 测试结果：通过
- 备注：`SpringTradingHedgeAlertEventPublisherTests` 已验证 `afterCommit` 发布时间点与监听器异常隔离；`DefaultTradingRiskObservabilityServiceTests` 已验证首次超阈值发布事件、恢复时不发布事件；`TradingRiskObservabilityIntegrationTests` 已验证 `t_hedge_log` 与告警 / 恢复日志仍保持原有闭环

### 3.11 identity-service - 吊销当前 Access Token

#### 3.11.1 接口基础信息

- 所属服务：`falconx-gateway -> falconx-identity-service`
- 接口名称：吊销当前 Access Token
- 接口说明：对当前 Bearer Access Token 执行登出，只把当前 Access Token 的 `jti` 写入黑名单；不引入新的 Refresh Token 主动撤销语义
- 接口类型：`REST`
- 请求路径或主题：`/api/v1/auth/logout`
- 请求方法：`POST`
- 认证要求：需要 `Bearer Access Token`
- 幂等要求：对同一仍有效 Access Token 的重复请求应返回相同成功结果；Access Token 一旦进入黑名单，后续受保护请求会被 gateway 拒绝

#### 3.11.2 请求信息

- 请求头：
  - `Authorization: Bearer <accessToken>`
- Path 参数：无
- Query 参数：无
- 请求体：无

请求示例：

```http
POST /api/v1/auth/logout
Authorization: Bearer eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...
```

#### 3.11.3 响应信息

- 成功业务码：`0`
- 失败业务码：`10001`、`10013`
- 响应说明：
  - 成功时返回统一 `ApiResponse` 成功体，`data=null`
  - 缺失 `Authorization`、Bearer Token 非法、签名不通过、已过期或类型不正确时，返回 HTTP `401` + `10001 / Unauthorized`
  - 同一 IP 1 分钟内第 201 次访问任意 `/api/v1/**` 时，gateway 会先返回 HTTP `429` + `10013 / Global IP Rate Limited`
  - 成功后当前 Access Token 的 `jti` 会按剩余 TTL 写入 Redis 黑名单；同一 Access Token 再访问任意受保护接口会被 gateway 拒绝并返回 `10001`

成功响应示例：

```json
{
  "code": "0",
  "message": "success",
  "data": null,
  "timestamp": "2026-04-19T20:20:00.000+08:00",
  "traceId": "9df0df7db0d94291b5aa1f084c618638"
}
```

失败响应示例：

```json
{
  "code": "10001",
  "message": "Unauthorized",
  "data": null,
  "timestamp": "2026-04-19T20:20:03.000+08:00",
  "traceId": "08b460f5d3bf4ac1b3d1ab9b92c4c0fe"
}
```

#### 3.11.4 日志与链路要求

- 关键日志点：
  - `gateway.request.received`
  - `gateway.auth.accepted`
  - `identity.http.logout.received`
  - `identity.logout.request`
  - `identity.logout.completed`
- 是否要求写审计日志：否
- 是否要求透传 `traceId`：是，由 gateway 生成并向 identity-service 透传

#### 3.11.5 测试结论

- 开发人员：Codex
- 测试日期：`2026-04-19`
- 测试环境：本地 `SpringBootTest + MockMvc + WebTestClient + Redis`
- 测试结果：通过
- 备注：
  - `AuthControllerIntegrationTests.shouldBlacklistCurrentAccessTokenWhenLogoutSucceeds` 已验证黑名单 key 与剩余 TTL 写入语义
  - `AuthControllerIntegrationTests.shouldRejectLogoutWhenAuthorizationHeaderMissingOrInvalid` 已验证缺失或非法 `Authorization` 返回 `10001`
  - `GatewayRoutingIntegrationTests.shouldRejectSameAccessTokenAfterLogoutViaGateway` 已验证 `logout -> blacklist -> gateway reject` 闭环

### 3.12 market-service - 北向行情 WebSocket 订阅

#### 3.12.1 接口基础信息

- 所属服务：`falconx-gateway -> falconx-market-service`
- 接口名称：北向行情 WebSocket 订阅
- 接口说明：通过 gateway 建立 WebSocket 连接后，按订阅协议接收 `price.tick`、`kline.{interval}` 和 stale 通知
- 接口类型：`WebSocket`
- 请求路径或主题：`ws://{host}/ws/v1/market?token=<accessToken>`
- 请求方法：`WebSocket Upgrade`
- 认证要求：需要有效 Access Token，握手时通过 Query Parameter `token` 传入
- 幂等要求：无；连接断开后需重新握手并重新订阅

#### 3.12.2 请求信息

- 请求头：
  - `Upgrade: websocket`
  - `Connection: Upgrade`
  - `X-User-Group-Code`：gateway 从 Access Token 注入并代理给 market-service，客户端不直接传
- Path 参数：无
- Query 参数：
  - `token`：gateway 校验的 Access Token
- 请求体：
  - 握手后客户端发送 JSON 文本帧，支持 `subscribe`、`unsubscribe`、`ping`

请求示例：

```text
ws://localhost:18080/ws/v1/market?token=eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...
```

```json
{
  "type": "subscribe",
  "requestId": "req-001",
  "channels": ["price.tick", "kline.1m"],
  "symbols": ["EURUSD"]
}
```

#### 3.12.3 响应信息

- 成功业务码：无统一 `ApiResponse`；握手成功后进入 WebSocket 帧交互
- 失败业务码：
  - 握手阶段：`HTTP 401 / 403 / 429`
  - 应用层错误帧：`30001`、`99004`
- 响应说明：
  - `token` 缺失、非法、过期或在黑名单：握手阶段返回 `HTTP 401`
  - `token` 对应用户状态为 `BANNED`：握手阶段返回 `HTTP 403`
  - 同一用户第 6 个并发连接：握手阶段返回 `HTTP 429`
  - `symbols` 包含不存在或不属于当前用户组可见范围的交易品种：返回 `type=error`、`code=30001`
  - `symbols` 使用平台展示和交易 symbol；自定义平台 symbol 的实时价格来自 `t_symbol_quote_mapping` 配置的 LP 源、乘数和加点。后端订阅上游 LP 时使用 `source_lp_code + source_symbol` 选定源，再映射回 `platform_symbol` 推送；只有映射启用、开启 `lp_subscribe_enabled` 且 `(source_lp_code, source_symbol)` 对应源存在于 `t_symbol.status=1` 时会进入 LP 订阅与实时映射链路；运行时不按 `.p / .c / .f` 或其他后缀做特殊过滤
  - 订阅包含 `price.tick` 且当前存在 Redis 最新价或最后有效参考价时，服务端会在 `subscribed` 确认后立即补发一帧当前 `price.tick` 快照；`quoteStatus=FRESH` 表示可成交实时价，其他质量状态只用于展示参考
  - `kline.{interval}` 的 `open / high / low / close` 统一使用同周期内标准报价 `mid` 聚合生成，交易终端 K 线图只使用 REST 历史 K 线与 WebSocket `kline.{interval}` 推送绘制，不得用 `price.tick` 在前端合成或覆盖 K 线
  - `price.tick` 只用于行情列表、Tick 图、下单面板、stale / quoteStatus 状态降级和实时交易状态展示，不作为 K 线数据源
  - stale 通知帧沿用 `type=price.tick`，只用于把前端展示状态降级为参考行情；该帧不包含 `bid / ask / mid / mark`，不得用于分时图、K 线或交易触发
  - 当前只实现行情订阅，不包含账户/订单/持仓/费用等用户侧实时推送

成功响应示例：

```json
{
  "type": "subscribed",
  "requestId": "req-001",
  "channels": ["price.tick", "kline.1m"],
  "symbols": ["EURUSD"]
}
```

```json
{
  "type": "price.tick",
  "symbol": "EURUSD",
  "bid": "1.08200000",
  "ask": "1.08220000",
  "mid": "1.08210000",
  "mark": "1.08210000",
  "ts": "2026-04-21T05:37:50Z",
  "source": "TM_QUOTE",
  "stale": false,
  "quoteStatus": "FRESH",
  "qualityReason": null
}
```

```json
{
  "type": "kline.1m",
  "symbol": "EURUSD",
  "interval": "1m",
  "open": "1.08100000",
  "high": "1.08220000",
  "low": "1.08050000",
  "close": "1.08070000",
  "volume": "0",
  "openTime": "2026-04-21T05:37:00Z",
  "closeTime": "2026-04-21T05:37:59Z",
  "isFinal": true
}
```

```json
{
  "type": "price.tick",
  "symbol": "EURUSD",
  "stale": true,
  "quoteStatus": "STALE",
  "qualityReason": "QUOTE_TIME_DRIFT_EXCEEDED",
  "ts": "2026-04-21T05:37:16.759Z"
}
```

失败响应示例：

```json
{
  "type": "error",
  "requestId": "req-err",
  "code": "30001",
  "message": "symbol not found: INVALID"
}
```

#### 3.12.4 日志与链路要求

- 关键日志点：
  - `gateway.websocket.handshake.received / accepted / rejected`
  - `gateway.websocket.proxy.connected / bridge.terminated / closed`
  - `market.websocket.session.opened / closed`
  - `market.websocket.subscribe.accepted / unsubscribe.accepted`
  - `market.websocket.price.push`
  - `market.websocket.price.snapshot-push`
  - `market.websocket.price.stale-push`
  - `market.websocket.kline.push`
- 是否要求写审计日志：否
- 是否要求透传 `traceId`：是；gateway 在握手阶段生成 `X-Trace-Id` 并向 `market-service` 透传

#### 3.12.5 测试结论

- 开发人员：Codex
- 测试日期：`2026-04-21`
- 测试环境：本地 `SpringBootTest + JDK HttpClient.WebSocket + Redis + Kafka + MySQL + ClickHouse`
- 测试结果：通过
- 备注：
  - `GatewayMarketWebSocketIntegrationTests` 已验证缺失 Token 返回 `401`、`BANNED` 返回 `403`、同用户第 6 个连接返回 `429`，以及 `X-User-* / X-User-Group-Code / X-Trace-Id` 向下游透传
  - `MarketWebSocketIntegrationTests` 已验证 `subscribe -> price.tick -> kline -> stale` 推送链路、订阅后当前参考价快照补发、`unsubscribe` 后停止推送、应用层 `ping -> pong`、协议层 Ping 心跳、重连后重新订阅，以及 `INVALID` symbol 返回 `30001`
  - 当前代表性 E2E `GatewayMinimalMainlineE2ETests`、`GatewayManualCloseE2ETests`、`GatewayTakeProfitE2ETests`、`GatewayStopLossE2ETests`、`GatewayLiquidationE2ETests` 已复跑通过，确认新增行情 WebSocket 与代理链路未破坏既有主调用链

### 3.12A trading-core-service - 用户交易实时 WebSocket 推送

#### 3.12A.1 接口基础信息

- 所属服务：`falconx-gateway -> falconx-trading-core-service`
- 接口名称：用户交易实时 WebSocket 推送
- 接口说明：通过 gateway 建立用户私有 WebSocket 连接，接收当前登录用户自己的账户、订单、成交、持仓、保证金、账本和强平变更通知
- 接口类型：`WebSocket`
- 请求路径或主题：`ws://{host}/ws/v1/trading?token=<accessToken>`
- 请求方法：`WebSocket Upgrade`
- 认证要求：需要有效 Access Token，握手时通过 Query Parameter `token` 传入
- 幂等要求：无；连接断开后需重新握手并重新订阅
- 补偿要求：推送是 best-effort 通知；断线、发送失败或服务重启后，客户端必须用 REST 查询账户、订单、持仓、账本和强平记录补偿缺口

#### 3.12A.2 请求信息

- 请求头：
  - `Upgrade: websocket`
  - `Connection: Upgrade`
- Path 参数：无
- Query 参数：
  - `token`：gateway 校验的 Access Token
- 请求体：
  - 握手后客户端发送 JSON 文本帧，支持 `subscribe`、`unsubscribe`、`ping`
  - 客户端不得传入 `userId`；服务端只使用 gateway 透传的 `X-User-Id`

请求示例：

```text
ws://localhost:18080/ws/v1/trading?token=eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...
```

```json
{
  "type": "subscribe",
  "requestId": "user-sub-001",
  "channels": ["account", "orders", "positions", "trades", "margin", "ledger", "liquidations"]
}
```

#### 3.12A.3 响应信息

- 成功业务码：无统一 `ApiResponse`；握手成功后进入 WebSocket 帧交互
- 失败业务码：
  - 握手阶段：`HTTP 401 / 403 / 429`
  - 应用层错误帧：`99004`
- 响应说明：
  - `token` 缺失、非法、过期或在黑名单：握手阶段返回 `HTTP 401`
  - `token` 对应用户状态为 `BANNED`：握手阶段返回 `HTTP 403`
  - 同一用户第 6 个并发连接：握手阶段返回 `HTTP 429`
  - 连接成功后服务端立即推送 `account.snapshot`
  - 支持频道：`account / orders / positions / trades / margin / ledger / liquidations`

成功响应示例：

```json
{
  "type": "account.snapshot",
  "channel": "account",
  "requestId": null,
  "data": {
    "accountId": 1001,
    "userId": 30001,
    "currency": "USDT",
    "balance": 2000.00000000,
    "frozen": 0.00000000,
    "marginUsed": 1000.00000000,
    "available": 1000.00000000,
    "openPositions": []
  },
  "ts": "2026-04-30T12:00:00Z"
}
```

```json
{
  "type": "order.update",
  "channel": "orders",
  "requestId": null,
  "data": {
    "orderId": 9001,
    "orderNo": "O202604300001",
    "symbol": "BTCUSD",
    "side": "BUY",
    "orderType": "MARKET",
    "quantity": 1.00000000,
    "filledPrice": 10000.00000000,
    "status": "FILLED",
    "rejectReason": null
  },
  "ts": "2026-04-30T12:00:02Z"
}
```

已冻结首版业务帧：

| type | channel | data |
| --- | --- | --- |
| `account.snapshot` | `account` | `TradingAccountResponse` |
| `account.update` | `account` | `TradingAccountResponse` |
| `order.update` | `orders` | `TradingOrderItemResponse` |
| `trade.created` | `trades` | `TradingTradeItemResponse` |
| `position.update` | `positions` | `TradingPositionItemResponse` |
| `position.pnl` | `positions` | `TradingPositionPnlUpdatePayload`（轻量 PnL 增量帧，按 symbol 100ms 节流） |
| `margin.update` | `margin` | `{ position, account }` |
| `ledger.created` | `ledger` | 最新一条 `TradingLedgerItemResponse`；完整账本以 REST 分页查询为准 |
| `liquidation.update` | `liquidations` | `TradingLiquidationItemResponse` |
| `risk-controls.update` | `positions` | `TradingPositionItemResponse` |
| `error` | `null` | `{ code, message }` |
| `pong` | `null` | `{ ts }` |

> **STAGE-14E1 双币 / MarginLevel 字段最终切换（2026-06-02，🔴 硬 break，无 legacy 兼容）**：master §7.5 WebSocket 最终 break 已落地。`position.update` / `position.pnl` 浮盈亏由单币硬切双币（**删 `unrealizedPnl`**，加 `quoteCurrency / fxRate / unrealizedPnlInQuote / unrealizedPnlInAccount / isolatedMargin`）；`account.update` / `account.snapshot` 加 `equity / marginLevel / marginLevelStatus`，`openPositions[]` 同步硬切双币。后端 `trading-core` 与客户端 `falconx-frontend` 同切片同步切换，**部署须同窗口上线**，旧客户端解析新帧会丢失浮盈亏。完整字段表 + 推送语义（marginLevel 在 fill/close 推送非 per-tick）见 [WebSocket 接口规范 §5.4](WebSocket接口规范.md)。
>
> （历史）STAGE-14B 值口径过渡（字段名不变、只换承载值）已被本次 E1 字段切换取代；`realizedPnl` 仍为账户币值。

失败响应示例：

```json
{
  "type": "error",
  "channel": null,
  "requestId": "bad-001",
  "data": {
    "code": "99004",
    "message": "invalid request payload"
  },
  "ts": "2026-04-30T12:00:03Z"
}
```

#### 3.12A.4 日志与链路要求

- 关键日志点：
  - `gateway.websocket.handshake.received / accepted / rejected`
  - `gateway.websocket.proxy.connected / bridge.terminated / closed`
  - `trading.websocket.session.opened / closed`
  - `trading.websocket.subscribe.accepted / unsubscribe.accepted`
  - `trading.websocket.realtime.order.filled / order.rejected`
  - `trading.websocket.realtime.position.closed`
  - `trading.websocket.realtime.margin.updated`
  - `trading.websocket.realtime.risk-controls.updated`
- 是否要求写审计日志：否，业务事实仍以订单、持仓、成交、账本和强平表为准
- 是否要求透传 `traceId`：是；gateway 在握手阶段生成 `X-Trace-Id` 并向 `trading-core-service` 透传

#### 3.12A.5 测试结论

- 开发人员：Codex
- 测试日期：`2026-04-30`
- 测试环境：本地 `SpringBootTest + JDK HttpClient.WebSocket + Redis + MySQL`
- 测试结果：通过
- 备注：
  - `GatewayMarketWebSocketIntegrationTests` 新增 `/ws/v1/trading` 缺失 Token 返回 `401` 和授权连接代理透传 `X-User-* / X-Trace-Id` 覆盖
  - `TradingUserWebSocketIntegrationTests` 覆盖连接后 `account.snapshot`、订阅确认、市价开仓提交后 `order.update / position.update / trade.created / account.update / ledger.created`，以及追加逐仓保证金后的 `margin.update`
  - 2026-04-30 串行执行通过：`mvn -pl falconx-gateway -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=GatewayMarketWebSocketIntegrationTests test`（6 tests）
  - 2026-04-30 串行执行通过：`mvn -pl falconx-trading-core-service -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=TradingUserWebSocketIntegrationTests test`（1 test）
  - 2026-04-30 串行执行通过：`mvn -pl falconx-trading-core-service -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=TradingControllerIntegrationTests,TradingUserQueryControllerIntegrationTests test`（49 tests）
  - 当前首版不推送 `risk.warning`；BBook 自营风控闭环落地后必须单独冻结风险提醒消息体

### 3.13 trading-core-service - 查询订单列表

#### 3.13.1 接口基础信息

- 所属服务：`falconx-gateway -> falconx-trading-core-service`
- 接口名称：查询订单列表
- 接口说明：分页查询当前登录用户自己的订单历史
- 接口类型：`REST`
- 请求路径或主题：`/api/v1/trading/orders`
- 请求方法：`GET`
- 认证要求：需要 `Bearer Access Token`
- 幂等要求：天然幂等

#### 3.13.2 请求信息

- 请求头：
  - `Authorization: Bearer <accessToken>`
- Path 参数：无
- Query 参数：
  - `page`：页码，默认 `1`
  - `pageSize`：每页条数，默认 `20`，允许范围 `1-100`
- 请求体：无

请求示例：

```http
GET /api/v1/trading/orders?page=1&pageSize=20
Authorization: Bearer eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...
```

#### 3.13.3 响应信息

- 成功业务码：`0`
- 失败业务码：`10001`、`10012`、`10013`、`99004`
- 响应说明：
  - 只返回当前用户自己的订单分页
  - 返回结果按 `created_at DESC, id DESC` 排序
  - 首版费用查询通过订单级 `fee` 字段返回，不单独新增 `/fees`
  - 若 `page < 1` 或 `pageSize` 超出 `1-100`，返回 HTTP `400` + `99004 / invalid request payload`

成功响应示例：

```json
{
  "code": "0",
  "message": "success",
  "data": {
    "page": 1,
    "pageSize": 20,
    "total": 2,
    "items": [
      {
        "orderId": 39987503683473408,
        "orderNo": "OAXQDXKXWKCG",
        "symbol": "ETHUSDT",
        "side": "SELL",
        "orderType": "MARKET",
        "quantity": 1.0,
        "requestedPrice": 9990.0,
        "filledPrice": 9990.0,
        "leverage": 10.0,
        "margin": 999.0,
        "fee": 5.0,
        "clientOrderId": "query-order-32001-2",
        "status": "FILLED",
        "rejectReason": null,
        "createdAt": "2026-04-21T08:16:03Z",
        "updatedAt": "2026-04-21T08:16:03Z"
      }
    ]
  },
  "timestamp": "2026-04-21T16:16:03.000+08:00",
  "traceId": "87d625a0e3994281b0b55ec68c7f6fc6"
}
```

#### 3.13.4 日志与链路要求

- 关键日志点：
  - `gateway.request.received`
  - `gateway.auth.accepted`
  - `trading.http.orders.received`
- 是否要求写审计日志：否
- 是否要求透传 `traceId`：是，由 gateway 生成并透传

#### 3.13.5 测试结论

- 开发人员：Codex
- 测试日期：`2026-04-21`
- 测试环境：本地 `SpringBootTest + MockMvc + MySQL + Redis + Kafka`
- 测试结果：通过
- 备注：`TradingUserQueryControllerIntegrationTests.shouldListOrdersWithPagination` 已验证当前用户隔离、分页总数与字段回显；`TradingUserQueryControllerIntegrationTests.shouldRejectInvalidPaginationForUserQueryEndpoints` 已验证非法分页参数返回 HTTP `400 + 99004`

### 3.14 trading-core-service - 查询成交列表

#### 3.14.1 接口基础信息

- 所属服务：`falconx-gateway -> falconx-trading-core-service`
- 接口名称：查询成交列表
- 接口说明：分页查询当前登录用户自己的成交历史
- 接口类型：`REST`
- 请求路径或主题：`/api/v1/trading/trades`
- 请求方法：`GET`
- 认证要求：需要 `Bearer Access Token`
- 幂等要求：天然幂等

#### 3.14.2 请求信息

- 请求头：
  - `Authorization: Bearer <accessToken>`
- Path 参数：无
- Query 参数：
  - `page`：页码，默认 `1`
  - `pageSize`：每页条数，默认 `20`，允许范围 `1-100`
- 请求体：无

请求示例：

```http
GET /api/v1/trading/trades?page=1&pageSize=20
Authorization: Bearer eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...
```

#### 3.14.3 响应信息

- 成功业务码：`0`
- 失败业务码：`10001`、`10012`、`10013`、`99004`
- 响应说明：
  - 只返回当前用户自己的成交分页
  - 返回结果按 `traded_at DESC, id DESC` 排序
  - 成交类型固定为 `OPEN / CLOSE / LIQUIDATION`
  - 成交级费用通过 `fee` 字段返回

成功响应示例：

```json
{
  "code": "0",
  "message": "success",
  "data": {
    "page": 1,
    "pageSize": 20,
    "total": 2,
    "items": [
      {
        "tradeId": 39987325114527744,
        "orderId": 39987325101944832,
        "positionId": 39987325093556224,
        "symbol": "BTCUSDT",
        "side": "BUY",
        "tradeType": "CLOSE",
        "quantity": 1.0,
        "price": 10045.0,
        "fee": 0.0,
        "realizedPnl": 45.0,
        "tradedAt": "2026-04-21T08:15:23Z"
      }
    ]
  },
  "timestamp": "2026-04-21T16:16:03.000+08:00",
  "traceId": "38721266cfbc4c08a7f9d779887d4d22"
}
```

#### 3.14.4 日志与链路要求

- 关键日志点：
  - `gateway.request.received`
  - `gateway.auth.accepted`
  - `trading.http.trades.received`
- 是否要求写审计日志：否
- 是否要求透传 `traceId`：是，由 gateway 生成并透传

#### 3.14.5 测试结论

- 开发人员：Codex
- 测试日期：`2026-04-21`
- 测试环境：本地 `SpringBootTest + MockMvc + MySQL + Redis + Kafka`
- 测试结果：通过
- 备注：`TradingUserQueryControllerIntegrationTests.shouldListTradesWithPagination` 已验证当前用户隔离、开平成交顺序与费用字段回显；`TradingUserQueryControllerIntegrationTests.shouldRejectInvalidPaginationForUserQueryEndpoints` 已验证非法分页参数返回 HTTP `400 + 99004`

### 3.15 trading-core-service - 查询持仓列表

#### 3.15.1 接口基础信息

- 所属服务：`falconx-gateway -> falconx-trading-core-service`
- 接口名称：查询持仓列表
- 接口说明：分页查询当前登录用户自己的持仓历史
- 接口类型：`REST`
- 请求路径或主题：`/api/v1/trading/positions`
- 请求方法：`GET`
- 认证要求：需要 `Bearer Access Token`
- 幂等要求：天然幂等

#### 3.15.2 请求信息

- 请求头：
  - `Authorization: Bearer <accessToken>`
- Path 参数：无
- Query 参数：
  - `page`：页码，默认 `1`
  - `pageSize`：每页条数，默认 `20`，允许范围 `1-100`
- 请求体：无

请求示例：

```http
GET /api/v1/trading/positions?page=1&pageSize=20
Authorization: Bearer eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...
```

#### 3.15.3 响应信息

- 成功业务码：`0`
- 失败业务码：`10001`、`10012`、`10013`、`99004`
- 响应说明：
  - 只返回当前用户自己的持仓分页
  - 返回结果按 `updated_at DESC, id DESC` 排序
  - `accounts/me` 继续只负责账户快照与当前 `OPEN` 持仓，完整历史统一走 `/positions`
  - 对 `OPEN` 持仓，服务会动态补充 `markPrice / quoteStale / quoteTs / quoteSource` 与双币浮盈亏
  - **STAGE-14E1（2026-06-02，硬 break 无 legacy）**：持仓列表项浮盈亏由单币 `unrealizedPnl` **硬切**为双币 + 元数据（删 `unrealizedPnl`，加 `quoteCurrency / fxRate / unrealizedPnlInQuote / unrealizedPnlInAccount / isolatedMargin`），与 `accounts/me.openPositions` / WebSocket `position.update` 同口径（`toPositionResponse` 委托 `TradingUserRealtimePayloadFactory`）。对终态持仓不回填新的 `unrealizedPnlInQuote/InAccount`。

成功响应示例：

```json
{
  "code": "0",
  "message": "success",
  "data": {
    "page": 1,
    "pageSize": 20,
    "total": 2,
    "items": [
      {
        "positionId": 39987476413382656,
        "openingOrderId": 39987476404994048,
        "symbol": "ETHUSDT",
        "side": "SELL",
        "quantity": 1.0,
        "entryPrice": 9990.0,
        "leverage": 10.0,
        "margin": 999.0,
        "marginMode": "ISOLATED",
        "liquidationPrice": 10889.1,
        "takeProfitPrice": 9800.0,
        "stopLossPrice": 10100.0,
        "markPrice": 10005.0,
        "quoteCurrency": "USDT",
        "fxRate": 1.0,
        "unrealizedPnlInQuote": -15.0,
        "unrealizedPnlInAccount": -15.0,
        "isolatedMargin": 999.0,
        "closePrice": null,
        "closeReason": null,
        "realizedPnl": null,
        "status": "OPEN",
        "quoteStale": false,
        "quoteTs": "2026-04-21T08:16:02Z",
        "quoteSource": "integration-test",
        "openedAt": "2026-04-21T08:16:02Z",
        "closedAt": null,
        "updatedAt": "2026-04-21T08:16:02Z"
      }
    ]
  },
  "timestamp": "2026-04-21T16:16:02.000+08:00",
  "traceId": "255b778f99b84c8d8f8d35d7204456a7"
}
```

#### 3.15.4 日志与链路要求

- 关键日志点：
  - `gateway.request.received`
  - `gateway.auth.accepted`
  - `trading.http.positions.received`
- 是否要求写审计日志：否
- 是否要求透传 `traceId`：是，由 gateway 生成并透传

#### 3.15.5 测试结论

- 开发人员：Codex
- 测试日期：`2026-04-21`
- 测试环境：本地 `SpringBootTest + MockMvc + MySQL + Redis + Kafka`
- 测试结果：通过
- 备注：`TradingUserQueryControllerIntegrationTests.shouldListPositionsWithPagination` 已验证当前用户隔离、`OPEN / CLOSED` 混合回显与动态报价字段；`TradingUserQueryControllerIntegrationTests.shouldRejectInvalidPaginationForUserQueryEndpoints` 已验证非法分页参数返回 HTTP `400 + 99004`

### 3.16 trading-core-service - 查询账本流水

#### 3.16.1 接口基础信息

- 所属服务：`falconx-gateway -> falconx-trading-core-service`
- 接口名称：查询账本流水
- 接口说明：分页查询当前登录用户自己的账本流水
- 接口类型：`REST`
- 请求路径或主题：`/api/v1/trading/ledger`
- 请求方法：`GET`
- 认证要求：需要 `Bearer Access Token`
- 幂等要求：天然幂等

#### 3.16.2 请求信息

- 请求头：
  - `Authorization: Bearer <accessToken>`
- Path 参数：无
- Query 参数：
  - `page`：页码，默认 `1`
  - `pageSize`：每页条数，默认 `20`，允许范围 `1-100`
- 请求体：无

请求示例：

```http
GET /api/v1/trading/ledger?page=1&pageSize=20
Authorization: Bearer eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...
```

#### 3.16.3 响应信息

- 成功业务码：`0`
- 失败业务码：`10001`、`10012`、`10013`、`99004`
- 响应说明：
  - 只返回当前用户自己的账本分页
  - 返回结果按 `created_at DESC, id DESC` 排序
  - 首版费用查询通过 `ORDER_FEE_CHARGED / SWAP_* / LIQUIDATION_PNL / REALIZED_PNL` 等 `bizType` 体现，不单独新增 `/fees`

成功响应示例：

```json
{
  "code": "0",
  "message": "success",
  "data": {
    "page": 1,
    "pageSize": 20,
    "total": 5,
    "items": [
      {
        "ledgerId": 39987505298280449,
        "bizType": "REALIZED_PNL",
        "amount": 45.0,
        "idempotencyKey": "manual-close:39987505294086144",
        "referenceNo": "PAXQDYBUT6GW",
        "balanceBefore": 1995.0,
        "balanceAfter": 2040.0,
        "frozenBefore": 0.0,
        "frozenAfter": 0.0,
        "marginUsedBefore": 1000.0,
        "marginUsedAfter": 0.0,
        "createdAt": "2026-04-21T08:16:04Z"
      }
    ]
  },
  "timestamp": "2026-04-21T16:16:04.000+08:00",
  "traceId": "489dd3818d7e4ec4b25002e9cd37b86d"
}
```

#### 3.16.4 日志与链路要求

- 关键日志点：
  - `gateway.request.received`
  - `gateway.auth.accepted`
  - `trading.http.ledger.received`
- 是否要求写审计日志：否
- 是否要求透传 `traceId`：是，由 gateway 生成并透传

#### 3.16.5 测试结论

- 开发人员：Codex
- 测试日期：`2026-04-21`
- 测试环境：本地 `SpringBootTest + MockMvc + MySQL + Redis + Kafka`
- 测试结果：通过
- 备注：`TradingUserQueryControllerIntegrationTests.shouldListLedgerEntriesWithPagination` 已验证账本分页、用户隔离与 `bizType` 字段回显；`TradingUserQueryControllerIntegrationTests.shouldRejectInvalidPaginationForUserQueryEndpoints` 已验证非法分页参数返回 HTTP `400 + 99004`

### 3.17 trading-core-service - 查询强平记录

#### 3.17.1 接口基础信息

- 所属服务：`falconx-gateway -> falconx-trading-core-service`
- 接口名称：查询强平记录
- 接口说明：分页查询当前登录用户自己的强平记录
- 接口类型：`REST`
- 请求路径或主题：`/api/v1/trading/liquidations`
- 请求方法：`GET`
- 认证要求：需要 `Bearer Access Token`
- 幂等要求：天然幂等

#### 3.17.2 请求信息

- 请求头：
  - `Authorization: Bearer <accessToken>`
- Path 参数：无
- Query 参数：
  - `page`：页码，默认 `1`
  - `pageSize`：每页条数，默认 `20`，允许范围 `1-100`
- 请求体：无

请求示例：

```http
GET /api/v1/trading/liquidations?page=1&pageSize=20
Authorization: Bearer eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...
```

#### 3.17.3 响应信息

- 成功业务码：`0`
- 失败业务码：`10001`、`10012`、`10013`、`99004`
- 响应说明：
  - 只返回当前用户自己的强平记录分页
  - 返回结果按 `created_at DESC, id DESC` 排序
  - 返回字段覆盖保证金模式、强平价、触发价、真实亏损、手续费、释放保证金和平台兜底金额

成功响应示例：

```json
{
  "code": "0",
  "message": "success",
  "data": {
    "page": 1,
    "pageSize": 20,
    "total": 1,
    "items": [
      {
        "liquidationLogId": 39987502173523968,
        "positionId": 39987502160941056,
        "symbol": "BTCUSDT",
        "side": "BUY",
        "marginMode": "ISOLATED",
        "quantity": 1.0,
        "entryPrice": 10000.0,
        "liquidationPrice": 9050.0,
        "markPrice": 9045.0,
        "priceTs": "2026-04-21T08:16:03Z",
        "priceSource": "integration-test",
        "loss": -955.0,
        "fee": 0.0,
        "marginReleased": 1000.0,
        "platformCoveredLoss": 0.0,
        "createdAt": "2026-04-21T08:16:03Z"
      }
    ]
  },
  "timestamp": "2026-04-21T16:16:03.000+08:00",
  "traceId": "00cd4ec7b3d14857a48b529556d87c7d"
}
```

#### 3.17.4 日志与链路要求

- 关键日志点：
  - `gateway.request.received`
  - `gateway.auth.accepted`
  - `trading.http.liquidations.received`
- 是否要求写审计日志：否
- 是否要求透传 `traceId`：是，由 gateway 生成并透传

#### 3.17.5 测试结论

- 开发人员：Codex
- 测试日期：`2026-04-28`
- 测试环境：本地 `SpringBootTest + MockMvc + MySQL + Redis + Kafka`
- 测试结果：通过
- 备注：`TradingUserQueryControllerIntegrationTests.shouldListLiquidationsWithPagination` 已验证当前用户隔离、`marginMode` 强平字段回显与分页总数；`TradingUserQueryControllerIntegrationTests.shouldRejectInvalidPaginationForUserQueryEndpoints` 已验证非法分页参数返回 HTTP `400 + 99004`

### 3.18 identity-service - 提交 KYC 申请

#### 3.18.1 接口基础信息

- 所属服务：`falconx-gateway -> falconx-identity-service`
- 接口名称：提交 KYC 申请
- 接口说明：用户提交 KYC 申请（身份证 / 护照 / 驾照三选一 + 证件正面 / 反面 / 手持自拍三张图片 base64）
- 接口类型：`REST`
- 请求路径或主题：`/api/v1/me/kyc`
- 请求方法：`POST`
- 认证要求：需要 `Bearer Access Token`（gateway 透传 `X-User-Id`）
- 幂等要求：当前用户存在 PENDING 申请时拒绝重复提交（10042）；已 APPROVED 拒绝（10043）；REJECTED 后允许重新提交

#### 3.18.2 请求信息

- 请求头：`Authorization: Bearer <accessToken>`
- 请求体：
  - `idType`：`ID_CARD` / `PASSPORT` / `DRIVER_LICENSE`
  - `idNumber`：证件号字符串，最大 64 字符，非空白
  - `idFrontBase64` / `idBackBase64` / `selfieBase64`：3 张证件图片的 base64 编码（不含 `data:image/...,` 前缀）
  - `idFrontMimeType` / `idBackMimeType` / `selfieMimeType`：可选，默认 `image/jpeg`

请求示例：

```http
POST /api/v1/me/kyc
Authorization: Bearer eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...
Content-Type: application/json

{"idType":"ID_CARD","idNumber":"110101199001011234","idFrontBase64":"<base64>","idBackBase64":"<base64>","selfieBase64":"<base64>"}
```

#### 3.18.3 响应信息

- 成功业务码：`0`
- 失败业务码：`10040`（idNumber 非法）/ `10041`（缺证件）/ `10042`（PENDING 重复提交）/ `10043`（已 APPROVED 重复提交）/ `10001`（未授权）/ `99004`（请求格式错误）
- 响应说明：返回新建 submission 的 `submissionId`（雪花 ID 字符串）+ `status=PENDING` + 提交时间；不回显 base64 数据
- 数据库副作用：在 `t_kyc_submission` 写入一行（status=0）+ `t_kyc_document` 写入 3 行（doc_type=1/2/3，含 sha256 + mimeType）

成功响应示例：

```json
{
  "code": "0",
  "message": "success",
  "data": {
    "submissionId": "48275238449975296",
    "userId": "48275236470263808",
    "level": 1,
    "status": "PENDING",
    "idType": "ID_CARD",
    "idNumber": "110101199001011234",
    "submittedAt": "2026-05-14T05:08:33.758Z",
    "reviewAt": null,
    "rejectReason": null
  },
  "timestamp": "2026-05-14T13:08:33.770+08:00",
  "traceId": "7309a0b02b1c477dbe16840dbdb77fff"
}
```

#### 3.18.4 日志与链路要求

- 关键日志点：
  - `gateway.request.received`
  - `gateway.auth.accepted`
  - `identity.http.kyc.submit.received`
  - `identity.kyc.submitted`
- 是否要求写审计日志：否（C 端用户自身操作）
- 是否要求透传 `traceId`：是

#### 3.18.5 测试结论

- 开发人员：Claude Opus
- 测试日期：`2026-05-14`
- 测试环境：本地 `SpringBootTest + MockMvc + MySQL + Redis + Kafka` (docker-compose)
- 测试结果：通过
- 备注：`IdentityKycApplicationServiceIntegrationTests` (10 应用层 IT) + `UserKycControllerIntegrationTests.shouldReturnPendingWhenSubmitViaHttp` 已覆盖 TC-KYC-001 ~ 008；E2E TC-E2E-KYC-001 整链已通过

### 3.19 identity-service - 查询 KYC 状态

#### 3.19.1 接口基础信息

- 所属服务：`falconx-gateway -> falconx-identity-service`
- 接口名称：查询 KYC 状态
- 接口说明：查询当前用户最新一条 KYC 申请状态（按 submittedAt DESC LIMIT 1）
- 接口类型：`REST`
- 请求路径或主题：`/api/v1/me/kyc`
- 请求方法：`GET`
- 认证要求：需要 `Bearer Access Token`
- 幂等要求：天然幂等

#### 3.19.2 请求信息

- 请求头：`Authorization: Bearer <accessToken>`
- Path/Query/Body 参数：均无

#### 3.19.3 响应信息

- 成功业务码：`0`
- 失败业务码：`10001`（未授权）
- 响应说明：从未提交时 `data=null`；存在记录时返回 status / submittedAt / reviewAt / rejectReason 等
- 缓存策略：客户端 React Query `staleTime=5s`；trading-core WS 推 `notification.created` 后客户端 invalidate 立即刷新

成功响应示例（APPROVED 状态）：

```json
{
  "code": "0",
  "message": "success",
  "data": {
    "submissionId": "48275238449975296",
    "userId": "48275236470263808",
    "level": 1,
    "status": "APPROVED",
    "idType": "ID_CARD",
    "idNumber": "110101199001011234",
    "submittedAt": "2026-05-14T05:08:33.758Z",
    "reviewAt": "2026-05-14T05:08:48.682Z",
    "rejectReason": null
  },
  "timestamp": "2026-05-14T13:08:53.932+08:00",
  "traceId": "f013ea149aaa4779bcf1628fce131386"
}
```

#### 3.19.4 日志与链路要求

- 关键日志点：`gateway.request.received` + `gateway.auth.accepted`（identity 端无专属 INFO 日志，只在异常或 KYC 流转节点打）
- 是否要求写审计日志：否
- 是否要求透传 `traceId`：是

#### 3.19.5 测试结论

- 开发人员：Claude Opus
- 测试日期：`2026-05-14`
- 测试环境：本地 `SpringBootTest + MockMvc`
- 测试结果：通过
- 备注：`IdentityKycApplicationServiceIntegrationTests` (TC-KYC-010~013，含 `shouldReturnNullWhenNeverSubmitted` / `shouldReturnPendingSubmissionAsLatest` / `shouldReflectApprovedReviewAtAndLevel`) + `UserKycControllerIntegrationTests.shouldReturnNullWhenGetLatestNeverSubmitted` 已全覆盖

### 3.20 trading-core-service - 提交出金

#### 3.20.1 接口基础信息

- 所属服务：`falconx-gateway -> falconx-trading-core-service`
- 接口名称：提交出金申请
- 接口说明：用户向白名单地址申请 USDT 出金（一期固定 USDT，支持 ERC20 / TRC20）。提交成功立即冻结余额并进入 2h 冷静期。详细业务规则见 [REST §9.2.2](REST接口规范.md#922-post-apiv1mewithdraw-提交出金)。
- 接口类型：`REST`
- 请求路径或主题：`/api/v1/me/withdraw`
- 请求方法：`POST`
- 认证要求：需要 `Bearer Access Token`
- 幂等要求：客户端必须传 `X-Idempotency-Key`，24h 内重复 key 返回原 withdrawId

#### 3.20.2 请求信息

- 请求头：`Authorization: Bearer <accessToken>` + `X-Idempotency-Key: <≤64 字符>`
- 请求体：`{ amount, currency, network, targetAddress, whitelistId }`

#### 3.20.3 响应信息

- 成功业务码：`0`，data 含 `withdrawId / status=COOLING / coolingUntil` 等
- 失败业务码：`30040` / `30041` / `30042` / `30043` / `30044` / `30045` / `30046` / `30054` / `30055`（详见 REST §9.2.7）
- 关键状态变化：`t_withdraw_order` 新增一行 status=0 COOLING；`t_account.frozen += amount`；`t_ledger` 写 biz_type=13(WITHDRAW_FREEZE)
- 缓存策略：无

#### 3.20.4 日志与链路要求

- 关键日志点：`trading.http.withdraw.submit.received` + `trading.withdraw.submit.completed`（或 `.idempotent`）
- 是否要求写审计日志：否（admin 审核环节才写 `t_admin_operation_log`）
- 是否要求透传 `traceId`：是

#### 3.20.5 测试结论

- 开发人员：Claude Opus 4.7
- 测试日期：`2026-05-14`
- 测试环境：本地 `SpringBootTest + 真 MySQL + 真 Redis`
- 测试结果：通过
- 备注：`WithdrawSubmitIntegrationTests` 9 个 IT 全过（TC-WD-001/002/003/004/005/008/009/010/011），覆盖正常 COOLING + KYC + 余额 + 单笔 + 单日 + 地址格式 + 白名单 cooling + 幂等

### 3.21 trading-core-service - 出金列表查询

#### 3.21.1 接口基础信息

- 所属服务：`falconx-gateway -> falconx-trading-core-service`
- 接口名称：分页查询当前用户出金记录
- 接口说明：返回当前登录用户的出金单列表，按 created_at DESC 排序。支持 status 过滤（COOLING/PENDING/APPROVED/.../REJECTED 9 态名）。
- 接口类型：`REST`
- 请求路径或主题：`/api/v1/me/withdraw`
- 请求方法：`GET`
- 认证要求：需要 `Bearer Access Token`
- 幂等要求：天然幂等

#### 3.21.2 请求信息

- 请求头：`Authorization: Bearer <accessToken>`
- Query：`page`（默认 1）/ `pageSize`（默认 20，最大 100）/ `status`（可选）

#### 3.21.3 响应信息

- 成功业务码：`0`，data 含 `page / pageSize / total / items[]`
- items 每项与 §3.20 响应一致 + `confirmations` 字段
- 缓存策略：客户端 React Query `staleTime=10s`；wallet broadcast/confirmed 事件后 trading-core 推 WS `notification.created`，客户端 invalidate 后刷新

#### 3.21.4 日志与链路要求

- 关键日志点：无专属 INFO（命中 gateway.request.received 即可）
- 是否要求透传 `traceId`：是

#### 3.21.5 测试结论

- 开发人员：Claude Opus 4.7
- 测试日期：`2026-05-14`
- 测试环境：本地 `SpringBootTest + 真 MySQL + 真 Redis`
- 测试结果：通过
- 备注：`WithdrawQueryIntegrationTests.shouldPaginateUserList` / `shouldFilterListByStatus`（TC-WD-012 / 013）通过

### 3.22 trading-core-service - 出金详情查询

#### 3.22.1 接口基础信息

- 所属服务：`falconx-gateway -> falconx-trading-core-service`
- 接口名称：查询单条出金详情
- 接口说明：查询当前用户某条出金单的详情（含链上 tx_hash、confirmations、failureReason）。
- 接口类型：`REST`
- 请求路径或主题：`/api/v1/me/withdraw/{id}`
- 请求方法：`GET`
- 认证要求：需要 `Bearer Access Token`
- 幂等要求：天然幂等

#### 3.22.2 请求信息

- 请求头：`Authorization: Bearer <accessToken>`
- Path：`id`（withdrawId 雪花 ID）

#### 3.22.3 响应信息

- 成功业务码：`0`，data 同 §3.20
- 失败业务码：`30047 WITHDRAW_NOT_FOUND`（不存在或不属于当前用户）

#### 3.22.4 日志与链路要求

- 关键日志点：`trading.http.withdraw.detail.received`（非 owner 时另起 `trading.withdraw.detail.not-found`）
- 是否要求透传 `traceId`：是

#### 3.22.5 测试结论

- 开发人员：Claude Opus 4.7
- 测试日期：`2026-05-14`
- 测试环境：本地 `SpringBootTest + 真 MySQL + 真 Redis`
- 测试结果：通过
- 备注：`WithdrawQueryIntegrationTests.shouldThrow30047WhenWithdrawNotFound` / `shouldThrow30047WhenWithdrawDoesNotBelongToUser` / `shouldReturnOwnedWithdrawDetail`（TC-WD-014 / 015）通过

### 3.23 trading-core-service - 用户冷静期取消出金

#### 3.23.1 接口基础信息

- 所属服务：`falconx-gateway -> falconx-trading-core-service`
- 接口名称：用户冷静期取消出金
- 接口说明：仅允许 `status=COOLING` 的出金单调用。状态机迁移 `COOLING → CANCELED`，冻结余额退回（biz_type=14）。详见 docs/api/REST接口规范.md §9.2.5。
- 接口类型：`REST`
- 请求路径或主题：`/api/v1/me/withdraw/{id}/cancel`
- 请求方法：`POST`
- 认证要求：需要 `Bearer Access Token`
- 幂等要求：CAS 串行化，并发竞态返回 30048

#### 3.23.2 请求信息

- 请求头：`Authorization: Bearer <accessToken>`
- Path：`id`（withdrawId 雪花 ID）
- Body：无

#### 3.23.3 响应信息

- 成功业务码：`0`，data 同 §3.20（status=CANCELED）
- 失败业务码：`30047` NOT_FOUND（不存在或非己）/ `30048` NOT_CANCELABLE（非 COOLING）

#### 3.23.4 日志与链路要求

- 关键日志点：`trading.http.withdraw.cancel.received` + `trading.withdraw.cancel.completed`
- CAS 失败：`trading.withdraw.cancel.cas-conflict`

#### 3.23.5 测试结论

- 开发人员：Claude Opus 4.7
- 测试日期：`2026-05-14`
- 测试环境：本地 `SpringBootTest + 真 MySQL + 真 Redis`
- 测试结果：通过
- 备注：`WithdrawCancelIntegrationTests` 4 IT 通过（TC-WD-016 / 017 + 非己 + 不存在）

### 3.24 trading-core-service - 用户白名单 CRUD

#### 3.24.1 接口基础信息

- 所属服务：`falconx-gateway -> falconx-trading-core-service -> falconx-wallet-service` 透传
- 接口名称：客户端出金白名单 CRUD
- 接口说明：GET / POST / DELETE `/api/v1/me/withdraw/whitelist[/{id}]`。trading-core 入口做地址 / network 本地校验后透传给 wallet。详见 docs/api/REST接口规范.md §9.2.6。
- 接口类型：`REST`
- 认证要求：需要 `Bearer Access Token`

#### 3.24.2 请求信息

- POST body：`{network, address, label?}`
- DELETE：path id 即可

#### 3.24.3 响应信息

- 成功业务码：`0`
- 失败业务码：`30044` network 非 ERC20/TRC20 / `30045` 地址格式非法 / `30051` 超 10 条 / `30052` 重复 / `30053` 不存在或非己

#### 3.24.4 日志与链路要求

- `trading.http.withdraw.whitelist.{list,add,delete}.received` + 跨服务 `trading.external-rpc.{get,post,delete}.received`
- wallet 端：`wallet.internal.withdraw.whitelist.{get,list,add,delete}.received` + `wallet.withdraw.whitelist.{added,removed}` + 24h cooling 调度器 `wallet.withdraw.whitelist.cooling.scheduler.{activated,batch}`

#### 3.24.5 测试结论

- 开发人员：Claude Opus 4.7
- 测试日期：`2026-05-14`
- 测试环境：本地 `SpringBootTest + 真 MySQL`
- 测试结果：通过
- 备注：trading-core `WithdrawWhitelistIntegrationTests` 8 IT（TC-WD-020 / 021 / 022 / 023 / 024 / 026 / 027 + network 非法）+ wallet `WalletWithdrawWhitelistIntegrationTests` 7 IT（TC-WD-020 / 022 / 023 / 024 / 025 / 021 / 029）+ wallet `WalletWithdrawWhitelistCoolingSchedulerIntegrationTests` 2 IT（TC-WD-028 + 未满 24h 不推进）

### 3.25 trading-core-service - admin 审核出金（internal RPC）

#### 3.25.1 接口基础信息

- 所属服务：`falconx-console-service -> falconx-gateway -> falconx-trading-core-service`
- 接口名称：admin 审核 / 紧急取消（internal）
- 接口说明：5 端点 list / detail / approve / reject / emergency-cancel，全部走 `/internal/v1/trading/withdraws*`，依赖 `X-Internal-Token` + `X-Admin-User-Id`。详见 docs/api/管理端接口规范.md §10。
- 接口类型：`REST` (internal)

#### 3.25.2 端点

- GET `/internal/v1/trading/withdraws?status&userId&network&minAmount&page&pageSize`
- GET `/internal/v1/trading/withdraws/{id}`
- POST `/internal/v1/trading/withdraws/{id}/approve` body `{note?}`
- POST `/internal/v1/trading/withdraws/{id}/reject` body `{note}` (必填)
- POST `/internal/v1/trading/withdraws/{id}/emergency-cancel` body `{note?}`

#### 3.25.3 响应信息

- 成功业务码：`0`，data 同 §3.20
- 失败业务码：`30047` NOT_FOUND（detail）/ `30049` NOT_PENDING（approve / reject）/ `30050` EMERGENCY_NOT_ALLOWED（非 APPROVED_DELAYED）
- 列表排序：PENDING 优先 + APPROVED_DELAYED 次优先 + 其余按 created_at DESC

#### 3.25.4 关键状态变化

- approve amount&lt;$3K → APPROVED；amount≥$3K → APPROVED_DELAYED + delayed_until = now + 6h
- reject → REJECTED + frozen 退冻（biz_type=15）
- emergency-cancel → CANCELED + frozen 退冻（biz_type=16）
- approve / reject 发布 outbox `trading.withdraw.reviewed`；emergency-cancel 不发布（撤回审核结论）

#### 3.25.5 测试结论

- 开发人员：Claude Opus 4.7
- 测试日期：`2026-05-14`
- 测试环境：本地 `SpringBootTest + 真 MySQL + 真 Redis`
- 测试结果：通过
- 备注：`WithdrawAdminIntegrationTests` 12 IT 通过（TC-WD-040 / 041 / 042 / 044 / 045 / 046 / 047 / 048 / 049 / 050 / 051 / 052 / 054-056）+ `WithdrawDelayedSchedulerIntegrationTests` 2 IT（TC-WD-062 / 063）

### 3.26 wallet-service - 注册成功 Kafka 事件

#### 3.26.1 接口基础信息

- 所属服务：`falconx-identity-service` 生产 → `falconx-wallet-service` 消费
- 接口名称：`falconx.identity.user.registered`
- 接口说明：identity 在注册事务 afterCommit 发布，wallet 消费后幂等派生 TRC20 / ERC20 入金地址。失败语义分两路：`WALLET_ADDRESS_ALLOCATION_FAILED`（xpub 缺失）落 `t_wallet_address_provision_dlq` 表 + 吞掉；其它异常 rethrow 走 Spring Kafka 默认重试 + DLT。详见 docs/event/Kafka事件规范.md §12.13。
- 接口类型：`Kafka topic`

#### 3.26.2 Payload

```json
{
  "eventId": "user-registered-2000001",
  "eventType": "identity.user.registered",
  "userId": 2000001,
  "uid": "u8a3f7d2c",
  "email": "alice@example.com",
  "registeredAt": "2026-05-15T03:08:00Z"
}
```

#### 3.26.3 关键字段

- `userId`：Kafka 分区键
- `eventId`：固定格式 `user-registered-{userId}`，DLQ 入库去重键
- 当前实现未使用 `KafkaEventMessageSupport` headers（P2 待办，详见 [管理端接口规范 §11.6](./管理端接口规范.md)）

#### 3.26.4 测试结论

- 测试日期：`2026-05-15`
- 测试环境：本地 `SpringBootTest + 真 Kafka + 真 MySQL`
- 测试结果：待落地（commit B R6 集成测试补齐）
- 备注：当前仅有 commit `f91fcd6` 落地代码 + commit `db75a91` 落地 DLQ 兜底，集成测试覆盖率为 0；本轮 commit B 补齐 identity 发布 IT + wallet consumer IT + DLQ 路径 IT

---

### 3.27 console-service - 地址预分配 DLQ admin RPC

#### 3.27.1 接口基础信息

- 所属服务：`falconx-console-frontend -> falconx-console-service -> falconx-gateway -> falconx-wallet-service`
- 接口名称：地址预分配 DLQ 列表 / 重试
- 接口说明：运营在 console 上查看 wallet `t_wallet_address_provision_dlq` 表 + 手动重试。详见 docs/api/管理端接口规范.md §11。
- 接口类型：`REST` (admin + internal)

#### 3.27.2 端点

console 层：

- GET `/admin/wallet/provision-dlq?status&userId&page&size`（权限 `wallet-provision:view`）
- POST `/admin/wallet/provision-dlq/{id}/retry` body `{reason}`（权限 `wallet-provision:retry`，高危）

wallet internal RPC（被 console 调用）：

- GET `/internal/v1/wallet/console/provision-dlq?status&userId&page&size`
- POST `/internal/v1/wallet/console/provision-dlq/{id}/retry` body `{reason}`

#### 3.27.3 响应信息

- 成功业务码：`0`
- 失败业务码：`90860` DLQ_NOT_FOUND（404）/ `90861` ALREADY_RESOLVED（409）/ `90862` REASON_REQUIRED（400，console 前置校验）

#### 3.27.4 关键状态变化

- 重试成功：`status=PENDING → RESOLVED`，`resolvedAt` 填充，调用 `ensureDefaultUsdtDepositAddresses` 补派生地址
- 高危：OperationAuditAspect 自动写 `t_admin_operation_log`

#### 3.27.5 测试结论

- 测试日期：`2026-05-15`
- 测试环境：本地 `SpringBootTest + MockMvc + 真 MySQL`
- 测试结果：待落地（commit B R6 集成测试补齐）
- 备注：当前 commit `db75a91` 已落地代码 + 路由注册，集成测试覆盖率为 0；本轮 commit B 补齐 wallet admin RPC IT + console transport IT + console-frontend Vitest

### 3.28 trading-core-service - send(templateCode, params) 通知 API

#### 3.28.1 接口基础信息

- 所属服务：`falconx-trading-core-service`（内部 API，仅服务内调用）
- 接口名称：`TradingNotificationApplicationService.send`
- 接口说明：STAGE-8-NOTIFICATION Phase 0 引入的模板化通知发送 API。8 触发点（KYC/出金/价格告警/持仓平仓/入金/风控）调用该方法落 t_notification + 经 dispatcher 投递。详见 docs/api/管理端接口规范.md §12 + V22__notification_template.sql。
- 接口类型：`Internal Service Method`

#### 3.28.2 签名

```java
TradingNotification send(
    String templateCode,          // 模板 code（必须 enabled=1）
    long userId,                   // 目标用户
    String type,                   // 业务 type（默认 = templateCode）
    Map<String, String> params,    // 占位符值
    String relatedKey,             // 关联业务（PRICE_ALERT / POSITION / WITHDRAW / DEPOSIT / KYC / RISK）
    Long relatedId,                // 关联实体 ID
    String payloadJson             // 额外结构化数据
);
```

#### 3.28.3 流程

1. `NotificationTemplateService.getRequiredEnabled(code)`：模板不存在或 disabled → 抛 30060
2. 渲染 title + body（`${var}` → params 值；未提供保留原样 + log warn）
3. 构造 TradingNotification 含 templateCode + level（来自模板）
4. 遍历 channels，对每个 channel 找到 `NotificationChannelDispatcher.supports(channel) = true` 的 dispatcher 调 dispatch
5. IN_APP dispatcher 写 t_notification + WS 推送；EMAIL/TELEGRAM stub log only

#### 3.28.4 错误码

- 30060 `NOTIFICATION_TEMPLATE_NOT_FOUND`：模板不存在或 disabled
- 30063 `NOTIFICATION_USER_NOT_FOUND`：userId ≤ 0（admin sendManual 路径）

#### 3.28.5 测试结论

- 测试日期：`2026-05-15`
- 测试环境：本地 `SpringBootTest + 真 MySQL + Kafka`
- 测试结果：代码层 mvn compile + test-compile BUILD SUCCESS；IT 真代码待 R6 二轮落地
- 备注：8 触发点全部 send() 接入；自身 IT（NotificationTemplateServiceTests / send IT）按 R6 二轮补

### 3.29 console-service - 通知 + 模板管理 admin RPC

#### 3.29.1 接口基础信息

- 所属服务：`falconx-console-frontend -> falconx-console-service -> falconx-gateway -> falconx-trading-core-service`
- 接口名称：通知 + 模板管理 8 端点
- 接口说明：运营查看用户通知 / 管理通知模板 CRUD / 手动发送通知。详见 docs/api/管理端接口规范.md §12。
- 接口类型：`REST` (admin + internal)

#### 3.29.2 端点

console 层（8 端点）：

- GET    `/admin/notification-templates`           权限 `notification:template:view`
- GET    `/admin/notification-templates/{code}`    权限同上
- POST   `/admin/notification-templates`           权限 `notification:template:manage` 高危
- PUT    `/admin/notification-templates/{code}`    权限同上，高危
- DELETE `/admin/notification-templates/{code}`    权限同上，高危（软删 enabled=0；内置 6 前缀不可删）
- GET    `/admin/notifications`                    权限 `notification:view`（7 query 参数过滤）
- GET    `/admin/notifications/{id}`               权限同上
- POST   `/admin/notifications/send`                权限 `notification:send` 高危（body 含 userId/templateCode/params/reason）

trading-core internal RPC（被 console 调用）：

- `/internal/v1/trading/console/notification-templates*`（5 端点）
- `/internal/v1/trading/console/notifications*`（3 端点）

#### 3.29.3 响应信息

- 成功业务码：`0`
- 失败业务码：90880-90887（trading 30060-30065 翻译，详见 §12.10）

#### 3.29.4 关键 RBAC + 审计

- 4 RBAC 权限码：notification:view / notification:template:view / notification:template:manage / notification:send
- 2 高危：notification:template:manage + notification:send（HighRiskPermissionRegistry 注册）+ OperationAuditAspect 自动写 t_admin_operation_log

#### 3.29.5 测试结论

- 测试日期：`2026-05-15`
- 测试环境：本地 `SpringBootTest + MockMvc + WireMock for trading-core`
- 测试结果：mvn -pl falconx-console-service test **44/44 全过**（含 baseline）；STAGE-8 自身 IT 真代码待 R6 二轮落地
- 备注：commits `0d9947e` + `8c1a992` 已落地 trading-core admin RPC + console-service 透传 + 8 错误码翻译 + 6 DTO + 2 高危 RBAC

---

### 3.30 console-service - 杠杆/MM 档位（tier）配置 admin RPC（STAGE-14C2）

#### 3.30.1 接口基础信息

- 所属服务：`falconx-console-frontend -> falconx-console-service -> falconx-gateway -> falconx-trading-core-service`
- 接口名称：杠杆/MM 档位（tier）配置 4 端点（列表 + CRUD）
- 接口说明：运营按 `symbol + group_code` 维护多档位杠杆上限与维持保证金率（`t_symbol_leverage_tier`）。console 不直写 trading 业务表，全部透传 trading-core internal RPC；写入成功后 trading 侧 `LeverageTierResolver` 失效本进程缓存（30s 惰性 TTL 兜底，无 Kafka）。详见 docs/api/管理端接口规范.md §18。
- 接口类型：`REST`（admin + internal）

#### 3.30.2 端点

console 层（4 端点，路径 master §7.4）：

- GET    `/admin/trading/tiers`        权限 `tier:view`（query：symbol / groupCode / page / size；分页按扁平档位条数，同 symbol 多档位可能跨页）
- POST   `/admin/trading/tiers`        权限 `tier:edit` 高危（body：symbol/groupCode/tierNo/notionalLower/notionalUpper/maxLeverage/mmRate）
- PUT    `/admin/trading/tiers/{id}`   权限 `tier:edit` 高危（body：tierNo/notionalLower/notionalUpper/maxLeverage/mmRate）
- DELETE `/admin/trading/tiers/{id}`   权限 `tier:edit` 高危（软删 enabled=0）

trading-core internal RPC（被 console 调用，前缀 `/internal/v1/trading/console`，经 `TradingInternalApiTokenFilter` 校验 X-Internal-Token + X-Admin-User-Id）：

- GET    `/internal/v1/trading/console/tier`       分页列表（含软删行供 admin 查看）
- POST   `/internal/v1/trading/console/tier`       新建（区间重叠 90932 / CHECK 90931）
- PUT    `/internal/v1/trading/console/tier/{id}`  编辑（not found 90930）
- DELETE `/internal/v1/trading/console/tier/{id}`  软删 enabled=0（not found 90930）

#### 3.30.3 响应信息

- 成功业务码：`0`
- 失败业务码（console 翻译 trading 90930-90932）：
  - `90930` ADMIN_TIER_NOT_FOUND — 目标档位不存在（PUT/DELETE）
  - `90931` ADMIN_TIER_CHECK_VIOLATION — 违反 DB CHECK（`max_leverage × mm_rate ≤ 1.0`）或区间非法
  - `90932` ADMIN_TIER_OVERLAP — 同 `symbol + group_code` 档位区间 `[notional_lower, notional_upper)` 重叠

#### 3.30.4 关键 RBAC + 审计

- 2 RBAC 权限码：`tier:view`（查看）/ `tier:edit`（新建/编辑/软删，高危）；V12 seed 关联到已持有 `risk-config:view` / `risk-config:update` 的角色 + `SUPER_ADMIN`，并在「交易监控」菜单下新增「杠杆档位配置」菜单（permission_code=`tier:view`）。
- 1 高危：`tier:edit`（`HighRiskPermissionRegistry` 注册），写操作经 `AdminTierApplicationService` 落审计快照，`OperationAuditAspect` 自动写 `t_admin_operation_log`（含 target_id）；前端写操作走二次确认 + reason。
- **DELETE 不带 reason 入参**：前端 reason 仅用于前端二次确认提示，后端软删未接收该参数，审计记录有 target_id 无 reason（如需 reason 落 DELETE 审计须后端加参）。

#### 3.30.5 测试结论

- 测试日期：`2026-05-29`
- 测试环境：console 本地 `SpringBootTest + MockMvc + WireMock for trading-core`；trading-core 同进程真 DB IT；前端 vitest
- 测试结果：console `AdminTierEndpointIntegrationTests` 透传 IT 11 全过；trading-core tier CRUD/RPC 相关 IT 全绿（含 `it017` CRUD→开仓风控生效闭环）；console-frontend vitest 74 全过（tier 页 10）。真三端跨服务 HTTP E2E（console→gateway→trading）为 WSL 受限手动项，证据靠 console 透传 IT + trading 同进程闭环 IT + resolver 30s 语义三段拼接。
- 备注：commits Task 2（`7471cb22` 错误码）+ Task 3/4（`bf81242b`/`f7ed9641` trading CRUD 写 + RPC + invalidate）+ Task 7/8/9（`3baf6ffc`/`a6e97941`/`be284f6a` console V12 + 透传 + 前端页）+ Task 10（`e1bd2541` 闭环 IT）

---

### 3.31 trading-core-service - 用户级 margin mode 切换 + 追加逐仓保证金（STAGE-14D1）

#### 3.31.1 接口基础信息

- 服务：`trading-core-service`，经 gateway 鉴权后注入 `X-User-Id`（无 JWT 自解析，照搬 `UserWithdrawController` 入口模式）。
- 范围：用户级 CROSS/ISOLATED 账户模式查询与切换（切换闸门 + 5min 冷静期 + CROSS gating）+ 追加逐仓保证金 `/me/` 路径（复用既有 `/api/v1/trading/positions/{id}/margin` service，新增越权校验）。
- 关联设计：master §6.1（切换状态机）、§7.4（端点）；commits Task 1（`d7b0527d` V33）+ Task 2（`603eed42` 错误码 + payload）+ Task 3（`afbd8c6a` 切换端点）+ Task 4（`3a156005` Kafka + V34 通知）+ Task 5（`986b2396` supplement /me/ 收口）。

#### 3.31.2 端点

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/me/margin-mode` | 查询当前 margin mode + 切换时间 + 冷静期 + `canSwitch` + `blockers` |
| POST | `/api/v1/me/margin-mode` | 切换 margin mode（body `{ "targetMode": "ISOLATED" \| "CROSS" }`） |
| POST | `/api/v1/me/positions/{positionId}/supplement-margin` | 为本人 OPEN ISOLATED 持仓追加逐仓保证金（body `{ "amount": "100.00" }`） |

> 旧端点 `POST /api/v1/trading/positions/{id}/margin` 保留兼容（与 `/me/` 端点共用同一 `TradingPositionMarginApplicationService`）。

#### 3.31.3 请求 / 响应信息

- `GET /api/v1/me/margin-mode` 响应 `MarginModeQueryResult`：`currentMode`（ISOLATED/CROSS）、`modeChangedAt`、`modeCoolingUntil`（null=不在冷静期）、`canSwitch`（布尔，blockers 为空时 true）、`blockers`（`OPEN_POSITIONS` / `ACTIVE_PENDING` / `COOLING` 子集；CROSS gate 与目标模式相关，不进 blockers，由 POST 判定）、`crossModeEnabled`（布尔，平台 `cross_mode.enabled`；false 时切到 CROSS 会被 30088 拒，**前端据此主动 disable CROSS 选项并提示「全仓暂未开放」**，避免下单默认模式选 CROSS 后被 40010/30088 拒）。
- `POST /api/v1/me/margin-mode` 切换成功响应 `MarginModeSwitchResult`：`oldMode` / `newMode` / `changedAt` / `coolingUntil`（=`now + 5min`）。事务内对账户行 `SELECT FOR UPDATE` 后依序判闸门，全过则落 `t_account.margin_mode + mode_changed_at=now + mode_cooling_until`，并在同事务发 Outbox + ACCOUNT_MODE_CHANGED 站内信。
- `POST .../supplement-margin` 成功响应 `AddIsolatedMarginResponse`：`positionId` / `symbol` / `status` / `marginMode` / `margin`（追加后）/ `liquidationPrice`（重算后）/ `account`（账户快照）。

#### 3.31.4 错误码（闸门判定顺序）

| 错误码 | 含义 | 判定 |
|---|---|---|
| `30083` MODE_NO_CHANGE | 目标模式与当前相同 | 同模式短路（最先判，避免无谓查询） |
| `30088` CROSS_MODE_NOT_ENABLED | 目标为 CROSS 但 `cross_mode.enabled` 关闭 | D1 默认 gate（CROSS 强平/实时 MM 未就位前不放用户进入无强平保护的 CROSS，开关默认 false，留 D2 打开） |
| `30080` MODE_HAS_OPEN_POSITIONS | 存在 OPEN 持仓 | 闸门 1 |
| `30081` MODE_HAS_ACTIVE_PENDING | 存在开仓挂单 | 闸门 2（SL/TP 必伴随 OPEN 持仓，已被 30080 覆盖，此处仅查开仓挂单） |
| `30082` MODE_COOLING_PERIOD_ACTIVE | 处于 5min 冷静期内 | 闸门 3（`mode_cooling_until > now`） |
| `40010` MARGIN_MODE_NOT_SUPPORTED | targetMode 字符串非法（非 ISOLATED/CROSS） | controller 解析层（大小写不敏感） |
| `30085` POSITION_NOT_ISOLATED | 对 CROSS 仓追加保证金无意义 | supplement |
| `30086` SUPPLEMENT_AMOUNT_INVALID | 追加金额非正 | supplement（与 Bean Validation 并存，service 层兜底） |
| `40004` POSITION_NOT_FOUND | 持仓不存在 / 非本人（越权） | supplement（`findByIdAndUserIdForUpdate(positionId, userId)` 按 (positionId, userId) 联合定位，非本人查不到，天然防越权） |
| `40007` / `40001` | 持仓已平 / 保证金不足 | supplement（复用既有路径） |

> master 列举切换闸门为 4 项（OPEN/挂单/冷静期/同模式）；30088 CROSS gate 为 D1 临时保护，放在同模式判定之后、持仓/挂单/冷静期之前，使「目标不可达」类错误先于「账户当前状态」类错误返回。

#### 3.31.5 测试结论

- 测试日期：`2026-06-01`；测试环境：trading-core 本地真 DB / 真 Kafka IT + Mockito UT。
- 测试结果：`MarginModeSwitchApplicationServiceTests` 12 UT（切换闸门 30080-30083 各拒 + CROSS gating 30088 + 冷静期 + queryMode blockers）全过；`MarginModeSwitchKafkaNotificationIntegrationTests` 1 IT（Outbox 计数 + ACCOUNT_MODE_CHANGED 通知渲染）；`TradingAccountModeSwitchRepositoryIntegrationTests` 1 IT + `MybatisTradingAccountRepositorySwitchMarginModeTests` 2 测试（switchMarginMode 落库）；`TradingControllerIntegrationTests` 40 全过（含 supplement `/me/` 成功 + 越权 40004 + 旧端点兼容）；`TradingPositionMarginApplicationServiceTests` 2。汇总 58 测试全绿。
- 已知边界：CROSS gating（`cross_mode.enabled` 默认 false）D2 打开；supplement pause gating（30087）D1 未接；冷静期时长 admin 可配 UI 留 D3。

#### 3.31.6 CROSS 开仓行为（STAGE-14D2）

> STAGE-14D2 放开 CROSS 全链（CROSS 开仓 + 账户级强平 + 实时 MM），commits Task 2（`d7559759` CROSS 开仓放开）+ Task 4（`4e31fe47` 账户级强平）。本节登记开仓侧 API 行为变化，不新增端点（复用 §3.13 下单端点）。

- **`cross_mode.enabled` gate（30088）**：CROSS 账户开仓（用户已切到 CROSS 模式）受 `cross_mode.enabled` 全局开关控制。开关**默认 false**，此时 CROSS 开仓被拒 `30088` CROSS_MODE_NOT_ENABLED（与切换到 CROSS 的 gate 同码同语义）；开关 true（admin 经 risk-switch 接口开启）后 CROSS 开仓放行。
- **CROSS 仓 `liquidationPrice=null`**：CROSS 模式开仓成功后，`t_position.liquidation_price` 落 **NULL**（CROSS 强平按账户级 MarginLevel 判据，不再依赖单仓 liqPrice）；开仓回显与持仓查询的 `liquidationPrice` 字段对 CROSS 仓返回 null。ISOLATED 仓 liqPrice 行为不变。
- **IM 冻结同 ISOLATED**：CROSS 开仓的初始保证金（IM）计算 + `mm_rate_at_open` / `tier_no_at_open` / `entry_fx_rate` 冻结口径与 ISOLATED 一致（tier 解析 + FX 换算），区别仅在强平判据与单仓 liqPrice 是否落值。
- 启用步骤：admin 经 risk-switch 接口开启 `cross_mode.enabled`（运维决策，详见 R7 报告 §9 启用步骤）。

#### 3.31.7 supplement-margin 受 FX_PAUSED 闸门（STAGE-14D3a）

> STAGE-14D3a（commit `7633a361`）给 `POST /api/v1/me/positions/{positionId}/supplement-margin`（及兼容端点 `POST /api/v1/trading/positions/{id}/margin`）接 FX_PAUSED 闸门，不新增端点 / 错误码。

- **新增拒单码 `30087` GLOBAL_PAUSE_ACTIVE**：当存在活跃 `GLOBAL_PAUSE`（含 FX_PAUSED 升级）时，supplement 视同「开仓侧加保证金」，复用开仓侧 `allow_open` 开关——取持仓 `SymbolSpec.category()` 查 `t_fx_pause_behavior`，`allow_open=false`（如 forex/metal 默认）→ 拒 `30087`。
- **判定顺序**：插入点在 `30085` POSITION_NOT_ISOLATED 校验之后、`40001`/`40007` 余额/持仓校验之前（见 §3.31.4 表）。
- **降级（缺信息从严）**：`category==null`（spec 缺失）或 `t_fx_pause_behavior` 命中缺失 → 保守全拒 `30087`（与开仓侧 30009 降级同方向「从严」，supplement 端统一 30087，符合 master §7.4 supplement 错误集含 30087）。
- 测试：`SupplementMarginFxPauseGatingTests` 6 UT/IT（pause+forex 拒 30087 / pause+crypto 放行 / category=null 保守全拒 / 无 pause 放行回归）。

#### 3.31.8 客户端对接登记（STAGE-14E1，无新端点）

> STAGE-14E1（commit `9b31d3b6` Task5）把 §3.31.2 的 `GET/POST /api/v1/me/margin-mode` 接入客户端 `falconx-frontend`，**不新增端点 / 错误码**，仅登记前端对接事实。

- **客户端组件**：`MarginModeToggle`（`src/features/trading/`）对接 `GET /me/margin-mode` 读 `currentMode / canSwitch / blockers`，`POST /me/margin-mode` 提交切换。
- **blockers 中文化**：`OPEN_POSITIONS` / `ACTIVE_PENDING` / `COOLING` 映射中文文案 + 切换前确认 modal；后端拒单码 `30080-30088`（持仓 / 挂单 / 冷静期 / 同模式 / CROSS gate）映射中文 toast。
- **`accountMarginMode` 与本地 `defaultMarginMode` 区分**：客户端展示账户级当前模式（来自 `account.update.marginMode`），与下单面板本地默认 `defaultMarginMode` 分开维护。
- 测试：客户端 vitest（`MarginModeToggle` 用例覆盖 canSwitch=false 禁用 + blockers 文案 + 确认 modal + 拒单码 toast）。
- **（精度统一后续修复）** CROSS 未开放（`crossModeEnabled=false`）时前端三处主动门禁：① 账户级 `MarginModeToggle` CROSS 按钮 disable + 常驻提示「全仓暂未开放」（独立于 canSwitch，覆盖无持仓但全仓关闭场景）；② **设置页 `defaultMarginMode` CROSS 选项 disable + 提示，且历史脏偏好=CROSS 时自动纠正为 ISOLATED**（此前选 CROSS 作下单默认致所有下单被 40010 拒的根因）；③ `OrderTicket` 下单 `effectiveMarginMode`：偏好=CROSS 但未开放时提交前强制回退 ISOLATED（双保险）。后端 `GET /me/margin-mode` 新增 `crossModeEnabled` 字段支撑（`MarginModeSwitchApplicationServiceTests` +1 UT）。

### 3.32 trading-core-service - 平台风控配置 internal RPC（STAGE-14D3a）

#### 3.32.1 接口基础信息

- 服务：`trading-core-service`，路径前缀 `/internal/v1/trading/console/config`（master §7.6），经 `TradingInternalApiTokenFilter` 鉴权（`X-Internal-Token` + `X-Admin-User-Id` + `X-Trace-Id`，缺失/无效 → 90701/90702/90703）。
- 范围：冷静期 + StopOut/MarginCall 阈值 + FX_PAUSED 8 类目行为开关的 admin 运行时可配（写 `t_risk_config` 平台行 `symbol IS NULL` / `t_fx_pause_behavior`）。**D3a 仅落 trading-core internal RPC，由后续 D3b console 透传**（console 三端 UI 未做）。
- 关联设计：master §6.5（FX_PAUSED 类目行为）/ §7.6（可配阈值范围）；commits Task1（`a78bc2ac` V37）+ Task2（`1dbf7045` 冷静期读 DB）+ Task3（`0b3e1d9e` 写方法）+ Task4（`0c3adb2c` 配置 RPC）+ Task6（`73c9be9b` FxPauseBehavior 写）+ Task7（`7249ecfd` FxPauseBehavior RPC）。
- **owner 修正（master §7.4 设计稿偏差）**：master §7.4 把 fx pause behavior admin 路由写成 `/admin/market/fx/pause-behavior`（market）；但 `t_fx_pause_behavior` 表**物理在 trading-core `falconx_trading` 库**（C1 V31 建表 + seed 8 行），按实际表 owner 修正写路径加在 trading-core（market 不得写 trading 库，[AGENTS §3.2](../../AGENTS.md)）。

#### 3.32.2 端点

| 方法 | 路径 | 说明 | 校验范围 |
|---|---|---|---|
| GET | `/internal/v1/trading/console/config/platform-risk` | 读冷静期 + StopOut/MarginCall 阈值（D3b 两页共用，回退默认 300/0.30/1.00） | — |
| PUT | `/internal/v1/trading/console/config/cooling-period` | 写冷静期（body `{ "coolingPeriodSeconds": 600 }`） | `@Min(60) @Max(604800)` |
| PUT | `/internal/v1/trading/console/config/risk-thresholds` | 写 StopOut/MarginCall（body `{ "stopOutLevel": "0.25", "marginCallLevel": "1.20" }`） | stopOut `@DecimalMin("0.05") @DecimalMax("0.95")` / marginCall `@DecimalMin("0.50") @DecimalMax("2.00")` |
| GET | `/internal/v1/trading/console/config/fx-pause-behavior` | 读 FX_PAUSED 类目行为开关全量（8 行） | — |
| PUT | `/internal/v1/trading/console/config/fx-pause-behavior/{category}` | 按类目写开仓/平仓/强平开关（body `{ "allowOpen": true, "allowClose": true, "allowLiquidation": true }`，三开关 `@NotNull`） | path `@Min(1) @Max(8)`；`X-Admin-User-Id` 落 `updated_by_admin_id` 审计列 |

#### 3.32.3 错误码 / 生效语义

- **校验失败统一 `99004` INVALID_REQUEST_PAYLOAD**：所有越界（cooling <60 或 >604800、stopOut/marginCall 越界、category <1 或 >8、缺字段）由 Bean Validation（命令 record `@Min/@Max/@DecimalMin/@DecimalMax/@NotNull` + 类 `@Validated` 使 path `@Min/@Max` 生效）经 `TradingGlobalExceptionHandler`（`MethodArgumentNotValidException` / `ConstraintViolationException` 并入 `handleBadRequest`）→ 400 `99004`。**D3a 不新增 trading 错误码。**
- **生效语义**：冷静期直读 `t_risk_config` 平台行（无缓存）即时运行时生效；StopOut/MarginCall 写后由 `DefaultMarginLevelMonitor` 30s TTL 自然生效（≤30s，同 C2 tier，不强制 invalidate）；FX_PAUSED 写后失效 `AtomicReference` 快照即时生效。`allow_close` 写时恒为 1（master §6.5 手动平仓不限制）。
- 关键日志：`trading.internal.config.cooling-period.update.received` / `trading.internal.config.risk-thresholds.update.received` / `trading.internal.config.fx-pause.update.received`（含 adminUserId）。

#### 3.32.4 测试结论

- 测试日期 `2026-06-01`；环境 trading-core 本地真 DB / 隔离库 IT + Mockito UT。
- `TradingPlatformConfigApplicationServiceTests` 4 UT + 配置 internal RPC IT 9（含 fx-pause：PUT/GET 各端点 + 越界/缺字段 → 99004 + GET fx-pause 8 行）+ 配置写 repo IT 2（隔离库真 migrate V37 写回读一致）+ FxPauseBehavior 写 IT 3（写后即时反映 + adminUserId 落审计）全绿。详见 [R7 报告 §3/§4](../test/STAGE-14D3a-CONFIG-BACKEND-R7-verification-report.md)。
- 已知边界：console 三端 UI（透传 + RBAC + console 错误码 90950/90951/90952）由 D3b 落地（见 §3.33）。

---

### 3.33 console-service - 平台风控配置 admin REST（STAGE-14D3b）

#### 3.33.1 接口基础信息

- 所属服务：`falconx-console-frontend -> falconx-console-service -> falconx-gateway -> falconx-trading-core-service`
- 接口名称：平台风控配置 6 端点（冷静期 GET/PUT + StopOut·MarginCall 阈值 GET/PUT + FX_PAUSED 8 类目行为 GET + PUT/{category}）
- 接口说明：D3b 把 D3a 落地的 trading-core 配置 internal RPC（§3.32）经 console 透传给运营。console 不直写 trading 业务表，全部透传 trading-core internal RPC（`/internal/v1/trading/console/config/*`）；只做 RBAC + 高危审计 + 错误翻译。详见 docs/api/管理端接口规范.md §19。
- 接口类型：`REST`（admin）

#### 3.33.2 端点

console 层（6 端点）：

- GET `/admin/trading/margin-mode-config`            权限 `margin-mode-config:view`（读冷静期 `coolingPeriodSeconds`）→ trading `GET .../config/platform-risk`
- PUT `/admin/trading/margin-mode-config`            权限 `margin-mode-config:edit` 高危（body `coolingPeriodSeconds` 60-604800 `@NotNull` + reason）→ trading `PUT .../config/cooling-period`
- GET `/admin/trading/risk-thresholds`               权限 `risk-threshold:view`（读 StopOut/MarginCall 阈值）→ trading `GET .../config/platform-risk`
- PUT `/admin/trading/risk-thresholds`               权限 `risk-threshold:edit` 高危（body `stopOutLevel` 0.05-0.95 / `marginCallLevel` 0.50-2.00 `@NotNull` + reason）→ trading `PUT .../config/risk-thresholds`
- GET `/admin/trading/fx-pause-behavior`             权限 `fx:pause-behavior:view`（读全量 8 类目）→ trading `GET .../config/fx-pause-behavior`
- PUT `/admin/trading/fx-pause-behavior/{category}`  权限 `fx:pause-behavior:edit` 高危（body `allowOpen`/`allowClose`/`allowLiquidation` 均 `@NotNull` + reason，category 1-8）→ trading `PUT .../config/fx-pause-behavior/{category}`

> **owner 修正**：FX_PAUSED 行为透传目标为 trading-core（非 master §7.4 写的 market），因 `t_fx_pause_behavior` 物理在 `falconx_trading` 库。trading-core internal RPC 见 §3.32。

#### 3.33.3 响应信息

- 成功业务码：`0`
- 失败业务码（console 翻译 trading 99004 → 90950-90952，或 console 侧 `@Valid` 拒 null/越界 400）：
  - `90950` ADMIN_MARGIN_MODE_CONFIG_INVALID — 冷静期写非法
  - `90951` ADMIN_FX_PAUSE_BEHAVIOR_INVALID — FX_PAUSED 行为写非法（category / 三开关字段）
  - `90952` ADMIN_RISK_THRESHOLD_INVALID — StopOut/MarginCall 阈值写非法
- 三个写请求 console 侧加 `@NotNull` + `@Valid` 拒 null 字段返 400（防 `Map.of` 透传 NPE 500）。

#### 3.33.4 关键 RBAC + 审计

- 6 RBAC 权限码：`margin-mode-config:view|edit` / `risk-threshold:view|edit` / `fx:pause-behavior:view|edit`（3 个 edit 高危）；V13 seed 关联到已持有 `risk-config:view` / `risk-config:update` 的角色，并在「交易监控」菜单下新增 3 菜单（「冷静期配置」/「StopOut 阈值配置」/「FX 暂停行为配置」）。
- 3 高危 edit 码（`HighRiskPermissionRegistry` 注册），写操作经 `AdminPlatformConfigApplicationService` / `AdminFxPauseBehaviorApplicationService` 落审计快照，`OperationAuditAspect` 自动写 `t_admin_operation_log`；前端写操作走二次确认 + reason。
- V13 Flyway 实测 `Migrating to v13` 通过（作用于 `falconx_console` 库，干净无 `USE`）。

#### 3.33.5 测试结论

- 测试日期：`2026-06-01`
- 测试环境：console 本地 `SpringBootTest + MockMvc + WireMock/Mockito for trading-core`；前端 vitest（jsdom）
- 测试结果：console 透传 IT 17 全过（`AdminPlatformConfigEndpointIntegrationTests` 10 + `AdminFxPauseBehaviorEndpointIntegrationTests` 7，含 RBAC / 99004→90950/90951/90952 翻译 / reason 必填 / @NotNull 拒 null 400），BUILD SUCCESS；console-frontend vitest 100（97 通过 + 3 既有 skip），D3b 三新页 23（冷静期 7 / 阈值 8 / FX_PAUSED 8）；三件套 build 退出 0、lint 0 改动文件（18 errors 既有 baseline，不在三新页）。真三端跨服务 HTTP E2E（console→gateway→trading）为 WSL 受限手动项，证据靠 console 透传 IT + D3a trading 配置 RPC IT 拼接；浏览器登录态截图同 WSL 受限手动项。
- 备注：commits Task1（`8a65be4b` 错误码 90950/90951/90952 + 高危）+ Task2（`3b599059`/`ef3616eb` 冷静期/阈值透传）+ Task3（`1a4aa3c1`/`5faf89e1` FX_PAUSED 透传 + @Valid）+ Task4（`09c644de` V13 seed）+ Task5/6/7（`529f4df7`/`37c77ea4`/`c6d790b1` 前端三页）

---

### 3.34 console-service - 管理端 FX 实时汇率监控 admin REST（STAGE-14E2）

#### 3.34.1 接口基础信息

- 所属服务：`falconx-console-frontend -> falconx-console-service -> falconx-gateway -> falconx-market-service`
- 接口名称：管理端 FX 实时汇率监控（全量 8 FX 快照只读）
- 接口说明：console 透传 market-service 14A internal RPC `GET /internal/v1/market/fx/rates`（§3 market FX RPC），给 admin FX 监控页提供全量 FX rate 快照。console 不直写 market 业务表，`InternalRpcClient` 按 path 经 gateway 路由到 market（无新增 client）。详见 docs/api/管理端接口规范.md §20。
- 接口类型：`REST`（admin，只读）

#### 3.34.2 端点

- GET `/admin/market/fx/rates`  权限 `fx:view`（只读，非高危，不写审计）→ market `GET /internal/v1/market/fx/rates`

#### 3.34.3 请求 / 响应信息

- 请求头：admin JWT（gateway 校验）；无请求参数 / 请求体
- 成功响应（`data` = `List<FxRateView>`）：`baseCurrency / quoteCurrency / rate / eventTimeMillis / sourceLpCode / sourceSymbol`（字段对齐 market `FxRateSnapshotPayload`，14A 不改）

```json
{
  "code": "0",
  "data": [
    { "baseCurrency": "EUR", "quoteCurrency": "USD", "rate": "1.08326000", "eventTimeMillis": 1748476800000, "sourceLpCode": "GODSA", "sourceSymbol": "EURUSD" }
  ]
}
```

> payload **无独立 `stale` 字段**——前端 `FxRateMonitorPage` REST 5s 轮询并依 `eventTimeMillis` 与当前时间差前端判 stale badge。

#### 3.34.4 错误码

- `90940` ADMIN_FX_RATE_NOT_FOUND — FX 快照透传时 market 下游 not-found / 错误

#### 3.34.5 关键 RBAC + 审计 + 权限/菜单 ID 登记

- 1 RBAC 权限码：`fx:view`（只读，非高危，不入 `HighRiskPermissionRegistry`，不写审计）。
- **V14 seed（`falconx_console` 库，干净无 `USE`）**：权限点 ID **9800001**（`fx:view`，module=fx / action=view），关联到已持有 `risk-config:view` 的角色 + SUPER_ADMIN 通配；菜单 ID **9800010**（「FX 汇率监控」，parent 9500005「交易监控」，path `/admin/market/fx-rates`，icon `LineChartOutlined`，绑定 `fx:view`）。ID 区段 9800001/9800010 避开既有占用段。

#### 3.34.6 admin 实时推送双币 / 多币聚合（同切片配套，无新 REST 端点）

- `admin.position.update` 删 `unrealizedPnl` 加双币 + 元数据；`admin.exposure.update` 补 `quoteCurrency`；`admin.position.summary` `totalUnrealizedPnl` QC→AC 修正（双币算法共用 `TradingRealtimeDualPnlSupport`），见 [WebSocket 接口规范 §5.5](WebSocket接口规范.md)。
- exposure REST（§3 admin exposure / 管理端规范 §7.3）响应补 `quoteCurrency`，供前端 `TradingExposureBoardPage` 「按报价币聚合」tab（按 `quoteCurrency` 分组对 `netExposureUsd` 求 USD 等价汇总）。
- **硬 break：`trading-core-service` 与 `console-frontend` 必须同窗口部署**（无 legacy，旧管理端解析新帧会丢浮盈亏字段）。

#### 3.34.7 测试结论

- 测试日期：`2026-06-02`
- 测试环境：console 本地 `SpringBootTest + MockMvc + WireMock/Mockito for market`；trading-core JUnit；前端 vitest（jsdom）
- 测试结果：console IT 22 全过（`AdminMarketFxEndpointIntegrationTests` 4 + `AdminTradingExposureQuoteCurrencyPassThroughTests` 1 + 回归 `AdminPlatformConfigEndpointIntegrationTests` 10 + `AdminFxPauseBehaviorEndpointIntegrationTests` 7），BUILD SUCCESS；trading-core UT 6（admin payload 3 + exposure quoteCurrency 3）随全量回归 592 tests / 2-fail（2 失败均为既有 `TradingKafkaWalletDepositIntegrationTests` flake——consumer race + 瞬态死锁，零 E2 新增）；console-frontend vitest 全量 115（+3 skip，FX 页 7 + 聚合/WS 11）+ build 退出 0；客户端 `falconx-frontend` build 0（E1 无回归）。真三端 console→gateway→market E2E + 浏览器登录态截图 = WSL 受限手动项（vitest + build + console IT 代证，不伪造）。
- 备注：commits Task1（`4804f043` admin WS 双币 + `TradingRealtimeDualPnlSupport`）+ Task2（`63b48f20` exposure RPC quoteCurrency）+ Task3（`9a2ee0ea` FX 监控透传 + 90940 + V14）+ Task4（`afbc7489`/`8c67f8be` 前端 FX 页）+ Task5（`ff06bc12` 前端聚合 tab + admin WS 消费）

---

### 3.35 trading-core-service - 杠杆/MM 档位查询（B 切片杠杆 tier 护栏，2026-06-03）

#### 3.35.1 接口基础信息

- 所属服务：`falconx-frontend -> falconx-gateway -> falconx-trading-core-service`
- 接口：`GET /api/v1/trading/symbols/{symbol}/leverage-tiers`（走既有 gateway `trading-route` catch-all，无新增路由）
- 鉴权：用户 Bearer Token；档位按 gateway 注入的 `X-User-Group-Code` 解析（组无配置回退 `default`，与开仓风控 `LeverageTierResolver` 同源 `t_symbol_leverage_tier`）。

#### 3.35.2 响应

`TradingLeverageTierListResponse`：`symbol` / `groupCode`（实际命中组）/ `quoteCurrency`（QC，SymbolSpec 来源，过渡期可 null）/ `fxRate`（QC→账户币当前汇率；同币种 1；FX 不可用 null）/ `tiers[]`（tierNo 升序，过滤 disabled）：`tierNo`、`notionalLower`（含，账户币）、`notionalUpper`（不含；null=最高档无上限）、`maxLeverage`、`mmRate`。

#### 3.35.3 用途与客户端对接

- **客户端动态降档**：`OrderTicket` 按 `数量×价格×fxRate ≈ notional(AC)` 落档（`src/features/trading/leverageTiers.ts`），杠杆输入上限/提示随档位收紧（「最大 Nx（当前名义价值档位 N）」），替代仅按 `SymbolSpec.maxLeverage`（=tier1 上限）的静态校验——修「UI 可选但下单 30070」脱节。fxRate 缺失降级按 tier1 上限（后端 30070 兜底）。
- **拒单码补充**：`30071 LIQUIDATION_DISTANCE_TOO_CLOSE`（新）——开仓强平价距离 ≤ 点差×2 拒单（防 tier 误配满杠杆零缓冲瞬时强平的守卫，见 master §5.1 修正记录；V38 已修 seed，本守卫防 admin 自配 tier 复踩）。客户端 `REJECTION_LABEL` 已中文化 30070/30071/30072。
- **seed 对齐**：market `V19__align_mapping_max_leverage_to_tier1.sql`（generator `mapping-align` 模式生成）把 `t_symbol_quote_mapping.max_leverage` 对齐 tier1 上限（只降不升、幂等），防新环境复现 mapping↔tier 错配。
- 测试：trading `TradingUserQueryLeverageTiersTests` 3 UT + `TradingLeverageTierStopOutIntegrationTests` 守卫 IT；客户端 `leverageTiers.test.ts` 7 vitest。


## 4. Stage 7A 后续逐仓范围的接口冻结结论

本章节对应 [逐仓模式改造方案](../process/逐仓模式改造方案.md) §5.2 的 **D1 冻结结论**，用于锁死 Stage 7A 后续范围在本文件中的接口面。

### 4.1 不新增逐仓专用 REST 端点

- 不新增任何以 `/api/v1/trading/positions/.*/margin` 以外的形式出现的逐仓专用端点。
- 不新增以 `marginMode` 为分组 / 过滤条件的独立查询端点。
- 现有用户视角查询（§3.13 订单 / §3.14 成交 / §3.15 持仓 / §3.16 账本 / §3.17 强平）已提供完整访问能力，不再扩展同类端点。

### 4.2 不新增逐仓专用查询参数

- `GET /api/v1/trading/{orders,trades,positions,ledger,liquidations}` 不新增 `marginMode` 查询参数。
- 过滤逻辑保持现状，由客户端对响应字段二次过滤。

### 4.3 响应 DTO 层 `marginMode` 字段暴露

- `STAGE7A-ISOLATED-02` 已完成响应层核对：§3.15 持仓查询响应项和 §3.17 强平查询响应项均暴露 `marginMode`。
- 强平查询的 `marginMode` 来源为 `t_liquidation_log.margin_mode`，由强平执行路径从 `t_position.margin_mode` 同步写入。

- 其它接口响应中已有 `marginMode` 的位置（见 §3.6 提交市价单回显、§3.8A 追加逐仓保证金回显等）保持不变。

### 4.4 禁止项

- 任何偏离本章节的接口调整，必须先回到 [逐仓模式改造方案](../process/逐仓模式改造方案.md) §5.2 修订后再回写本文件，不得在实施阶段直接新增端点或查询参数。
