# FalconX v1 CFD 全面测试用例规范

> 版本：v1.0  
> 创建日期：2026-04-18  
> 文档状态：正式规范  
> 适用阶段：Stage 5 已完成基础，Stage 6A/6B/7 验收基准  
> 规范来源：架构方案、数据库设计、状态机规范、事务与幂等规范、安全规范、Kafka 事件规范、接口文档

---

## 1. 概览

### 1.1 文档目的

本文件是 FalconX v1 的统一测试用例规范，作为：

- 开发阶段的测试编写标准
- 阶段验收的审计凭证
- 后续回归测试的检查清单

所有标记为「验收必须」的用例，在对应阶段交付时必须全部通过，不允许以"后续补齐"为由跳过。

### 1.2 CFD 业务范围

FalconX v1 以差价合约（CFD）为核心产品。CFD 涉及：

- **杠杆交易**：以保证金控制更大名义价值，放大盈亏
- **双向持仓**：支持做多（BUY）和做空（SELL）
- **保证金管理**：开仓冻结保证金，强平线保护平台
- **浮盈浮亏（unrealizedPnl）**：随行情实时变化，不持久化
- **止盈止损（TP/SL）**：持仓级自动触发平仓
- **强制平仓（Liquidation）**：亏损达到强平线时系统强制平仓
- **隔夜利息（Swap）**：持仓跨过 rollover 时间点收取或支付费用
- **净敞口（Risk Exposure）**：平台 B-book 对冲视图

### 1.3 测试分层

| 层级 | 简称 | 说明 |
|------|------|------|
| 单元测试 | UT | 纯内存，不依赖外部服务 |
| 集成测试 | IT | 依赖真实 MySQL/Redis/Kafka，使用 Testcontainers |
| 端到端测试 | E2E | 全服务链路，通过 gateway 入口触发 |
| 安全专项 | SEC | 认证、鉴权、边界攻击 |
| 幂等/事务专项 | TXN | 重复请求、并发竞争、事务回滚 |
| 性能边界 | PERF | 限流阈值、降级阈值 |

### 1.4 通用约定

- 所有测试必须使用隔离测试库，不允许污染共享数据
- 金额字段精度统一为 `DECIMAL(24,8)`，测试数据必须符合此约束
- 所有错误码必须对照规范，不允许自行扩展
- 测试结论中写的"通过"必须对应真实验证，不允许只写命令名

---

## 2. 身份认证服务测试（identity-service）

### 2.1 用户注册

#### TC-AUTH-001 正常注册

- **类型**：IT
- **所属模块**：`falconx-identity-service`
- **验收阶段**：Stage 3A / Stage 7
- **前置条件**：数据库已初始化，邮箱不存在

**输入**：
```json
POST /api/v1/auth/register
{
  "email": "test-001@example.com",
  "password": "Passw0rd!"
}
```

**预期结果**：
- HTTP 200，业务码 `0`
- 返回 `userId`、`uid`（格式 `U\d{8}`）、`email`、`status=ACTIVE`、`emailVerified=false`
- 响应头包含 `X-Trace-Id`
- `t_user` 新增一条记录，密码字段为 bcrypt 哈希值（不含明文）
- `t_user.status = ACTIVE`
- `t_user.email_verified = 0`
- `t_user.activated_at != null`

**验证点**：
- [ ] HTTP 状态码 200
- [ ] `code = "0"`
- [ ] `data.status = "ACTIVE"`
- [ ] `data.emailVerified = false`
- [ ] `data.uid` 符合 `U\d{8}` 格式
- [ ] 响应头有 `X-Trace-Id`
- [ ] 数据库 `t_user.password` 不等于明文密码
- [ ] 数据库 `t_user.password` 可通过 bcrypt 校验
- [ ] 数据库 `t_user.email_verified = 0`
- [ ] 数据库 `t_user.activated_at != null`

---

#### TC-AUTH-002 重复邮箱注册

- **类型**：IT
- **验收阶段**：Stage 3A / Stage 7

**输入**：同一邮箱发送两次注册请求

**预期结果**：
- 第一次：业务码 `0`，成功注册
- 第二次：业务码 `10008`，`message = "User Already Exists"`
- `t_user` 只有一条对应记录

**验证点**：
- [ ] 第二次返回 `10008`
- [ ] 数据库中邮箱记录唯一

---

#### TC-AUTH-003 密码不符合长度规范

- **类型**：UT
- **验收阶段**：Stage 3A

**输入**：
- `password = "abc"` （不足 8 字符）
- `password = 'a' * 65` （超过 64 字符）

**预期结果**：两种情况均返回参数校验失败，业务码 `90004`

**验证点**：
- [ ] 短密码返回 `90004`
- [ ] 长密码返回 `90004`
- [ ] 数据库无新增记录

---

#### TC-AUTH-004 邮箱格式非法

- **类型**：UT
- **验收阶段**：Stage 3A

**输入**：`email = "not-an-email"`

**预期结果**：业务码 `90004`

---

### 2.2 用户登录

#### TC-AUTH-010 ACTIVE 用户正常登录

- **类型**：IT
- **验收阶段**：Stage 3A / Stage 7

**前置条件**：存在一个 `ACTIVE` 状态的用户

**输入**：
```json
POST /api/v1/auth/login
{
  "email": "alice@example.com",
  "password": "Passw0rd!"
}
```

**预期结果**：
- 业务码 `0`
- 返回 `accessToken`（RSA JWT，算法 RS256）
- 返回 `refreshToken`（UUID）
- `accessTokenExpiresIn = 900`
- `refreshTokenExpiresIn = 259200`
- `userStatus = "ACTIVE"`
- `emailVerified = false`
- `t_refresh_token_session` 有新记录

**验证点**：
- [ ] `code = "0"`
- [ ] `accessToken` 可用公钥验证签名
- [ ] JWT payload 包含 `sub / uid / email / status / iat / exp / jti`
- [ ] JWT payload 不包含 `emailVerified`
- [ ] `data.emailVerified = false`
- [ ] `exp - iat = 900`
- [ ] Refresh Token 落库
- [ ] Refresh Token 不在响应日志中明文输出

---

#### TC-AUTH-011 legacy PENDING_DEPOSIT 用户登录归一化

- **类型**：IT
- **验收阶段**：Stage 3A

**前置条件**：历史用户状态为 `PENDING_DEPOSIT`

**预期结果**：业务码 `0`，签发 Token，用户状态归一化为 `ACTIVE`

**验证点**：
- [ ] 返回 `code = "0"`
- [ ] `data.userStatus = "ACTIVE"`
- [ ] Access Token 可用公钥验证签名
- [ ] `t_user.status = ACTIVE`
- [ ] `t_user.activated_at != null`

---

#### TC-AUTH-012 FROZEN 用户登录被拒

- **类型**：IT
- **验收阶段**：Stage 3A

**预期结果**：业务码 `10007`，`message = "User Frozen"`

---

#### TC-AUTH-013 BANNED 用户登录被拒

- **类型**：IT
- **验收阶段**：Stage 3A

**预期结果**：业务码 `10002`，`message = "User Banned"`

---

#### TC-AUTH-014 密码错误登录

- **类型**：IT
- **验收阶段**：Stage 3A

**预期结果**：业务码 `10005`，无 Token 签发

---

#### TC-AUTH-015 连续 5 次密码错误触发锁定

- **类型**：IT
- **验收阶段**：Stage 6B

**操作**：同一 IP 连续发送 5 次密码错误请求，再发第 6 次

**预期结果**：
- 前 5 次返回密码错误
- 第 6 次返回 `10003`，触发锁定（15 分钟）

**验证点**：
- [ ] Redis 中存在 `falconx:auth:login:fail:{ip}` 计数键
- [ ] 第 6 次调用返回 `10003`

---

#### TC-AUTH-016 邮箱可信度不进入账户可用状态机

- **类型**：IT
- **验收阶段**：Stage 7A

**前置条件**：新注册用户 `email_verified=0`

**预期结果**：
- 注册响应 `status=ACTIVE` 且 `emailVerified=false`
- 登录响应 `userStatus=ACTIVE` 且 `emailVerified=false`
- Refresh 响应 `emailVerified=false`
- Access Token payload 不包含 `emailVerified`
- 一期不发送验证邮件，不生成验证 token，不提供邮箱验证端点
- `email_verified=0` 不限制登录、Refresh 或交易链路

**验证点**：
- [ ] `t_user.status = ACTIVE`
- [ ] `t_user.email_verified = 0`
- [ ] `data.emailVerified = false`
- [ ] JWT payload 不包含 `emailVerified`

---

### 2.3 Token 刷新

#### TC-AUTH-020 正常刷新

- **类型**：IT
- **验收阶段**：Stage 3A

**前置条件**：已登录，持有有效 `refreshToken`

**预期结果**：
- 业务码 `0`
- 返回新的 `accessToken` 和新的 `refreshToken`
- 返回 `emailVerified=false`
- 旧 `refreshToken` 从 `t_refresh_token_session` 中标记失效
- `t_refresh_token_session` 新增一条新会话记录

**验证点**：
- [ ] 新旧 `refreshToken` 不同
- [ ] `data.emailVerified = false`
- [ ] 旧 `refreshToken` 再次刷新返回 `10006`
- [ ] 新 `accessToken` 有效期重置为 900s

---

#### TC-AUTH-021 旧 Refresh Token 二次使用

- **类型**：IT
- **验收阶段**：Stage 3A

**操作**：使用已消费的 `refreshToken` 再次刷新

**预期结果**：业务码 `10006`，`message = "Refresh Token Invalid"`

---

#### TC-AUTH-022 已过期 Refresh Token

- **类型**：IT
- **验收阶段**：Stage 3A

**前置条件**：`refreshToken` 过期时间早于当前时间

**预期结果**：业务码 `10006`

---

### 2.4 用户状态机

#### TC-AUTH-030 ACTIVE 用户入金事件幂等留痕

- **类型**：IT（含 Kafka 消费）
- **验收阶段**：Stage 5 / Stage 7

**操作**：`identity-service` 消费 `falconx.trading.deposit.credited` 事件

**前置条件**：
- 用户状态为 `ACTIVE`
- 事件包含有效 `userId` 和 `eventId`

**预期结果**：
- `t_user.status` 保持 `ACTIVE`
- `t_user.activated_at` 已存在且不被清空
- `t_inbox` 写入 `eventId` 去重记录

**验证点**：
- [ ] `t_user.status = "ACTIVE"`
- [ ] `t_user.activated_at != null`
- [ ] `t_inbox` 有对应 `eventId` 记录

---

#### TC-AUTH-031 同一事件重复消费（幂等）

- **类型**：IT
- **验收阶段**：Stage 5

**操作**：相同 `eventId` 的 `deposit.credited` 事件发送两次

**预期结果**：
- 第一次：正常消费，`t_user.status = ACTIVE`
- 第二次：幂等跳过，`t_user.status` 不变，不报错
- `t_inbox` 只有一条记录

**验证点**：
- [ ] 无重复消费副作用
- [ ] 无异常抛出
- [ ] `t_inbox` 记录唯一

---

#### TC-AUTH-032 FROZEN/BANNED 用户收到入金事件不迁移

- **类型**：UT
- **验收阶段**：Stage 3A

**前置条件**：用户状态分别为 `FROZEN` / `BANNED`

**预期结果**：状态不变，不报错

---

---

## 3. 市场数据服务测试（market-service）

### 3.1 行情接入与标准化

#### TC-MKT-001 LP 报价正常接入并标准化

- **类型**：UT / IT（LP Provider / Stub Provider）
- **验收阶段**：Stage 2A / Stage 6A

**操作**：向 market-service 注入一条模拟 LP 解压后报价报文

**输入数据**（模拟 LP `price-compression` Snappy 解压后的 JSON）：
```json
{
  "data": [
    {
      "symbol": "EURUSD",
      "bid": "1.08000",
      "ask": "1.08010",
      "ts": "2026-04-18T10:00:00Z"
    }
  ]
}
```

**预期结果**：
- 标准化 symbol 为 `EURUSD`（大写）
- `bid = 1.08000`，`ask = 1.08010`
- `mid = (bid + ask) / 2 = 1.08005`
- `mark` 当前仍作为市场层兼容字段与 `mid` 对齐；交易侧有效标记价必须按 `BUY -> bid / SELL -> ask`
- Redis key `falconx:market:price:EURUSD` 被写入
- ClickHouse `quote_tick` 有新记录
- `falconx.market.price.tick` Kafka 事件被发布

**验证点**：
- [ ] Redis 中 `bid / ask / mid / mark / ts / source / stale / quoteStatus / qualityReason` 字段齐全
- [ ] Redis key TTL ≤ 10s
- [ ] ClickHouse 有对应记录
- [ ] Kafka 事件 `payload.symbol = "EURUSD"`
- [ ] Kafka / Redis / ClickHouse 中 `source = "TM_QUOTE"`

---

#### TC-MKT-002 无效报文不进入标准报价链路

- **类型**：UT
- **验收阶段**：Stage 2A

**输入**：
- 空 JSON、非数组 `data`
- 缺少 `symbol` 字段的报价对象
- 缺少 `bid / ask` 字段的报价对象
- Snappy 解压失败或无法解析成 JSON 的 `price-compression` payload

**预期结果**：
- 上述所有情况均不写 Redis，不写 ClickHouse，不发 Kafka
- 不抛异常，只输出对应调试日志

**验证点**：
- [ ] Redis 无写入
- [ ] 无 Kafka 消息投递
- [ ] 无未捕获异常

---

#### TC-MKT-003 品种不在 t_symbol 白名单内时过滤

- **类型**：IT
- **验收阶段**：Stage 6A

**前置条件**：`t_symbol` 表中该 symbol 状态为 `status=0`（不存在）或 `status=2`（suspended）

**输入**：注入该品种的 LP 解压后报价报文

**预期结果**：
- 不写 Redis
- 不写 ClickHouse
- 不发 Kafka

**验证点**：
- [ ] Redis 无该品种 key
- [ ] ClickHouse 无该品种记录

---

#### TC-MKT-004 白名单支持运行时热刷新

- **类型**：IT
- **验收阶段**：Stage 6A

**操作**：
1. 确认某 symbol 当前 `status=2`，注入报文 → 无 Redis 写入
2. 将该 symbol 的 `t_symbol.status` 改为 `1`，等待热刷新周期
3. 再次注入报文

**预期结果**：步骤 3 后 Redis 有写入，无需重启服务

**验证点**：
- [ ] 数据库更新后无需重启
- [ ] 刷新周期内生效

---

#### TC-MKT-005 LP Socket.IO 协议与本地链路验证

- **类型**：UT / IT
- **验收阶段**：Stage 6A

**前置条件**：
- 本地 `mysql / redis / kafka / clickhouse` 已启动
- 本地 `.env` 或运行时环境已提供 `domain / APP-ID / serverId`
- `token / secretKey` 当前不参与 Socket.IO 行情握手，仅保留给 LP HTTP/K 线接口或历史加密工具

**操作**：执行 `SocketIoLpMarketQuoteProviderTests`、`LpWebSocketProtocolSupportTests`、`DefaultQuoteStandardizationServiceTests` 与 market ingestion 主链路测试

**预期结果**：
- Socket.IO 握手 URL 按 `socket-client-demo` 包含 `socketVersion=1.1 / APP-ID / EIO=4 / socketSource=1 / Authorization=`
- Socket.IO 行情握手不发送 `signature / encrypt / nonce / timestamp`
- Java `socket.io-client` 使用默认 `/socket.io/` Engine.IO path，传给 `IO.socket(...)` 的 URI 只保留根路径，query 通过 `IO.Options.query` 传入
- 订阅事件固定为 `external-sub-symbol`，payload 包含 `serverId` 与 `symbolList`
- `symbolList` 只包含 `t_symbol_quote_mapping.enabled=1 AND lp_subscribe_enabled=1` 且 `source_symbol` 对应 `t_symbol.status=1` 的配置结果；不按 `.p / .c / .f` 或其他后缀做特殊过滤
- `price-compression` 二进制 payload 支持去掉前导 `0x04` 后 Snappy 解压
- 解压后的报价进入 `ExternalRawQuote -> StandardQuote` 链路，source 保持为 `TM_QUOTE`
- 至少一条本地模拟 LP 报价进入 Redis、ClickHouse 与 `falconx.market.price.tick`
- 把某个已收流 symbol 从 `status=1` 切到 `status=2` 并触发白名单刷新后，该 symbol 在读取时最终变为 `stale=true`

**验证点**：
- [ ] Socket.IO demo 握手、订阅 payload、Snappy 解压和解析用例通过
- [ ] Kafka 中收到 `TM_QUOTE` 来源的 `market.price.tick`
- [ ] Redis / ClickHouse 中可查到对应 LP 报价
- [ ] `stale` 按读取时动态转为 `true`

**当前生产化准备证据（2026-04-29）**：
- `SocketIoLpMarketQuoteProviderTests / LpWebSocketProtocolSupportTests` 已通过，覆盖 `socket-client-demo` query、`IO.Options.query`、订阅字符串 payload、Snappy 解压和 LP `datetimeUtc / datetime` 时间字段。
- `socket-client-demo` 使用示例地址与 `APP-ID` 已取得 `price-compression decoded`。
- `market-service` 使用同一示例地址与 `APP-ID` 覆盖启动后已取得 `market.lp.provider.price-compression.parsed quotes=1 accepted=1 filtered=0`。
- `market-service` 使用当前配置文件中的 LP 地址与 `APP-ID` 启动后仍未在 10 秒窗口内收到 `price-compression`；当前剩余问题指向运行配置或 LP 侧账号映射。

---

#### TC-MKT-006 LP 外部真源认证失败路径

- **类型**：IT（External Real Source）
- **验收阶段**：Stage 6A

**前置条件**：显式设置 LP 外部真源门禁开关，并提供一组错误的 `APP-ID`

**操作**：执行 LP 外部真源失败路径门禁测试

**预期结果**：
- 使用错误认证连接真实 LP 端点后，不进入正常报价链路
- 日志中出现服务端拒绝、连接关闭或本地错误证据；连接错误日志必须脱敏，不得输出 `APP-ID / Authorization` 明文 query 参数
- 日志中出现 `market.lp.provider.reconnect.scheduled`

**验证点**：
- [ ] 无真源报价进入消费回调
- [ ] 失败日志存在
- [ ] 已观察到重连调度日志

---

### 3.2 行情时效（Stale）判定

#### TC-MKT-010 新鲜行情（stale=false / quoteStatus=FRESH）

- **类型**：UT
- **验收阶段**：Stage 2A

**输入**：`quote.ts` 距当前时间不超过 5 秒

**预期结果**：`stale = false`，`quoteStatus = FRESH`，可进入 Redis 最新价、ClickHouse、Kafka、WebSocket 和 K 线聚合。

---

#### TC-MKT-011 过时行情（stale=true / quoteStatus=STALE）

- **类型**：UT
- **验收阶段**：Stage 2A

**输入**：`abs(now - quote.ts)` 超过 `falconx.market.stale.max-age`，默认 5 秒

**预期结果**：`stale = true`，`quoteStatus = STALE`，`qualityReason = QUOTE_TIME_DRIFT_EXCEEDED`；只发布不可成交 Kafka 快照，不刷新可成交 Redis 最新价、ClickHouse、WebSocket 或 K 线

**关键约束**：`stale` 必须按读取时动态计算，不能依赖写入时缓存的布尔值

---

#### TC-MKT-012 Redis key 过期后视为不可用

- **类型**：IT
- **验收阶段**：Stage 2A

**操作**：写入 Redis 后等待 10 秒（TTL 到期），再查询

**预期结果**：Redis key 不存在，读取方应按 `stale=true` 处理

---

#### TC-MKT-013 长时间无价格变化标记 NO_QUOTE

- **类型**：UT
- **验收阶段**：BBook P0

**前置条件**：`falconx.market.quote-quality.unchanged-max-age` 已配置，默认 `1m`

**操作**：对同一 `symbol` 连续注入 `bid / ask` 完全相同且间隔超过阈值的 tick

**预期结果**：
- 第二条及后续 tick 标记为 `quoteStatus = NO_QUOTE`
- `qualityReason = UNCHANGED_TOO_LONG`
- 只发布 `falconx.market.price.tick` 不可成交快照，不刷新 Redis 最新价、ClickHouse、WebSocket 或 K 线

---

#### TC-MKT-014 休盘报价不进入任何 sink

- **类型**：UT / IT
- **验收阶段**：BBook P0

**前置条件**：market owner 交易日历快照判定该 `symbol` 当前休盘

**操作**：注入该 `symbol` 的 LP tick

**预期结果**：
- 返回内部质量状态 `MARKET_CLOSED`
- 不写 Redis 最新价
- 不写 ClickHouse
- 不发 Kafka
- 不推 WebSocket
- 不推进 K 线

---

#### TC-MKT-015 异常价格标记 ABNORMAL

- **类型**：UT
- **验收阶段**：BBook P0

**输入**：
- `bid <= 0`
- `ask <= 0`
- `bid >= ask`

**预期结果**：
- tick 标记为 `quoteStatus = ABNORMAL`
- `qualityReason` 为 `NON_POSITIVE_PRICE` 或 `BID_ASK_CROSSED`
- 只发布不可成交 Kafka 快照，不刷新可成交 Redis 最新价、ClickHouse、WebSocket 或 K 线

---

#### TC-MKT-013 首页品种列表返回实时价

- **类型**：IT
- **验收阶段**：Stage 7A

**操作**：通过 owner ingestion 写入一条 fresh `EURUSD` 报价后，请求 `GET /api/v1/market/symbols`

**预期结果**：
- 响应包含 `EURUSD`
- `bid / ask / mid / mark / quoteTs / quoteSource` 字段存在
- `priceStatus = LIVE`
- `tradable = true`

---

#### TC-MKT-014 首页品种列表返回最后有效参考价

- **类型**：IT
- **验收阶段**：Stage 7A

**操作**：通过 owner ingestion 写入一条 fresh `EURUSD` 报价后，删除短 TTL 实时价 key `falconx:market:price:EURUSD`，再请求 `GET /api/v1/market/symbols`

**预期结果**：
- 响应包含 `EURUSD`
- 价格字段来自 `falconx:market:last-valid-price:EURUSD`
- `priceStatus = REFERENCE`
- `tradable = false`
- 参考价只允许首页和列表展示，休盘或非交易时段不得因为存在参考价而成交

---

#### TC-MKT-015 展示参考价 Redis TTL

- **类型**：IT
- **验收阶段**：Stage 7A

**操作**：保存 `falconx:market:last-valid-price:{symbol}` 参考价后读取 Redis TTL

**预期结果**：
- TTL 来自 `falconx.market.redis.reference-quote-ttl`
- 默认值为 `30d`
- 读取出的参考价必须以 `stale=true` 语义返回给内部服务层，避免被误用作实时成交价

---

#### TC-MKT-016 展示参考价冷启动回填

- **类型**：IT
- **验收阶段**：Stage 7A

**操作**：写入一条 fresh 报价并确认进入 ClickHouse `quote_tick` 后，删除 `falconx:market:last-valid-price:{symbol}`，再查询展示参考价

**预期结果**：
- Redis 参考价 miss 时从 ClickHouse 最新 tick 回填
- 回填后 Redis 重新设置 `falconx.market.redis.reference-quote-ttl`
- 返回语义仍为参考价，不能参与成交

---

#### TC-MKT-017 LP MT5 symbol 快照初始化

- **类型**：IT
- **验收阶段**：Stage 7A

**操作**：执行 market-service Flyway migration 后，通过 `MarketSymbolRepository` 查询 owner 数据。

**预期结果**：
- `t_symbol` 由 LP MT5 快照初始化，`V5` 导入 `1839` 条，`V10` 按当时快照口径删除 `.p / .c / .f` 后缀变体后保留 `1581` 条；后续接口不按后缀做特殊限制
- 删除后 `1571` 条进入 `status=1`，作为 market owner 活跃 LP 源品种元数据
- 删除后 `10` 条保留为 `status=2 suspended`
- `BTCUSD` 存在且为 `CRYPTO / status=1`
- `AAPL.NAS` 存在且为 `US_STOCK / category=6 / status=1`
- `EOSUSD` 存在且为 `status=2`
- `BTCUSDT` 不进入当前活跃白名单

**验证点**：
- [ ] `findAllTradingSymbols().size() == 1571`
- [ ] `findAllLpSubscribedMappings().size() == 1571`
- [ ] 普通市场后缀 symbol 保留原始 `.`，例如 `AAPL.NAS`
- [ ] provider 订阅和报价过滤不删除普通市场后缀
- [ ] provider 订阅和报价过滤只依赖 mapping 启停、订阅开关和 `t_symbol.status`，不按 `.p / .c / .f` 或其他后缀做特殊拒绝

---

### 3.3 K 线聚合

#### TC-MKT-020 K 线收盘写入 ClickHouse

- **类型**：IT
- **验收阶段**：Stage 6A

**操作**：在 1 分钟内连续注入多条报价，等待 1m K 线窗口关闭

**预期结果**：
- ClickHouse `kline` 表新增一条 `interval=1m` 的收盘 K 线
- K 线字段：`symbol / interval / open / high / low / close / volume / open_time / close_time`
- Kafka 发布 `falconx.market.kline.update` 事件

**验证点**：
- [ ] `open = 窗口首条 bid`
- [ ] `high = 窗口内最高 mark`
- [ ] `low = 窗口内最低 mark`
- [ ] `close = 窗口末条 mark`
- [ ] ClickHouse 有对应记录
- [ ] Kafka 事件已发布（通过 Outbox）

---

#### TC-MKT-021 多周期 K 线同步聚合

- **类型**：IT
- **验收阶段**：Stage 6A

**配置**：默认 `1m / 5m / 15m / 1h / 4h / 1d`

**操作**：注入跨多个周期的报价数据

**预期结果**：各周期收盘时分别写入 ClickHouse，互不干扰

---

### 3.4 交易时间管理

#### TC-MKT-030 交易时段内查询快照返回 OPEN

- **类型**：IT
- **验收阶段**：Stage 5

**前置条件**：`t_trading_hours` 配置周一至周五 00:00-24:00 UTC

**操作**：在配置时段内查询 Redis 交易时间快照

**预期结果**：当前时刻处于交易时段，`isOpen = true`

---

#### TC-MKT-031 节假日规则优先于周规则

- **类型**：IT
- **验收阶段**：Stage 5

**前置条件**：
- `t_trading_hours` 配置周五可交易
- `t_trading_holiday` 配置该日期全天休市

**预期结果**：节假日规则优先，`isOpen = false`

**验证点**：
- [ ] Redis 快照反映节假日状态
- [ ] `trading-core-service` 下单返回 `40008`

---

#### TC-MKT-032 例外规则优先于节假日规则

- **类型**：IT
- **验收阶段**：Stage 5

**前置条件**：
- `t_trading_holiday` 某日全天休市
- `t_trading_hours_exception` 对同一日期同一品种配置 10:00-12:00 可交易

**预期结果**：例外规则优先，10:00-12:00 内 `isOpen = true`

---

#### TC-MKT-033 跨午夜 session 判定

- **类型**：UT
- **验收阶段**：Stage 5

**输入**：session 为当天 22:00 到次日 06:00 UTC

**测试时间点**：
- `23:00` → `isOpen = true`
- `03:00 UTC 次日` → `isOpen = true`
- `07:00 UTC 次日` → `isOpen = false`

---

#### TC-MKT-034 交易时间快照 Redis TTL 为 25h

- **类型**：IT
- **验收阶段**：Stage 5

**验证点**：
- [ ] Redis 中交易时间快照 key 的 TTL 在 [88200, 90000] 秒范围内（25h ± 30min）
- [ ] 每日 UTC 00:00 触发全量刷新后 TTL 重置

---

### 3.5 报价查询接口

#### TC-MKT-040 查询已有报价

- **类型**：IT / E2E
- **验收阶段**：Stage 4

**输入**：
```http
GET /api/v1/market/quotes/EURUSD
Authorization: Bearer <validToken>
```

**预期结果**：
- 业务码 `0`
- 返回 `symbol / bid / ask / mid / mark / ts / source / stale`

---

#### TC-MKT-041 查询无报价品种

- **类型**：IT
- **验收阶段**：Stage 4

**前置条件**：Redis 中无该 symbol 的报价 key

**预期结果**：业务码 `30003`，`message = "Quote Not Available"`

---

#### TC-MKT-042 无 Token 查询报价被拒

- **类型**：E2E
- **验收阶段**：Stage 4

**预期结果**：业务码 `10001`，HTTP 401

---

### 3.5 北向行情 WebSocket

#### TC-MKT-043 订阅 `price.tick` 与 `kline` 后收到行情推送

- **类型**：IT
- **验收阶段**：Stage 6B

**前置条件**：
- 通过 `ws://{host}/ws/v1/market?token=<accessToken>` 完成握手
- 已订阅 `channels=["price.tick","kline.1m"]`、`symbols=["EURUSD"]`

**操作**：向 owner ingestion 路径连续写入同一 `symbol` 的 3 条标准报价，跨过同一个 `1m` K 线的收盘边界

**预期结果**：
- 先收到 `type=subscribed`
- 至少收到 1 条 `price.tick`
- 至少收到 1 条 `kline.1m isFinal=false`
- 收盘时额外收到 1 条 `kline.1m isFinal=true`

---

#### TC-MKT-044 WebSocket 订阅不存在的 symbol 返回错误帧

- **类型**：IT
- **验收阶段**：Stage 6B

**操作**：发送 `{"type":"subscribe","channels":["price.tick"],"symbols":["INVALID"]}`

**预期结果**：
- 返回 `type=error`
- `code = "30001"`
- `message` 包含 `symbol not found: INVALID`

---

#### TC-MKT-045 行情过期后只推送一次 stale 通知

- **类型**：IT
- **验收阶段**：Stage 6B

**前置条件**：
- 已订阅 `channels=["price.tick"]`、`symbols=["EURUSD"]`
- `falconx.market.stale.max-age` 和 `falconx.market.web-socket.stale-scan-interval` 已配置

**操作**：写入一条新鲜报价并等待其过期

**预期结果**：
- 收到一条 `type=price.tick` 且 `stale=true` 的通知
- stale 帧只针对同一条过期报价推送一次
- stale 帧不携带 `bid / ask / mid / mark`

---

#### TC-MKT-046 取消订阅后停止推送对应行情

- **类型**：IT
- **验收阶段**：Stage 6B

**前置条件**：
- 已完成 `channels=["price.tick"]`、`symbols=["EURUSD"]` 的订阅

**操作**：
1. 发送 `unsubscribe` 请求，取消 `EURUSD` 的 `price.tick`
2. 等待 `type=unsubscribed`
3. 再向 owner ingestion 路径写入一条 `EURUSD` 新鲜报价

**预期结果**：
- 服务端返回 `type=unsubscribed`
- 取消订阅后的观察窗口内，不再收到该 `symbol` 的 `price.tick` 推送

---

#### TC-MKT-047 WebSocket 应用层 ping/pong 与协议层心跳

- **类型**：IT
- **验收阶段**：Stage 6B

**前置条件**：
- 已建立 `ws://{host}/ws/v1/market?token=<accessToken>` 连接

**操作**：
1. 发送应用层 JSON 心跳 `{"type":"ping","ts":"..."}`
2. 等待服务端返回 `type=pong`
3. 在测试窗口内等待服务端协议层 Ping 帧

**预期结果**：
- 收到 `type=pong`，且 `ts` 与请求一致
- 收到至少 1 次服务端协议层 Ping 帧

---

#### TC-MKT-048 重连成功后必须重新订阅

- **类型**：IT
- **验收阶段**：Stage 6B

**前置条件**：
- 首次连接已完成 `channels=["price.tick"]`、`symbols=["EURUSD"]` 订阅

**操作**：
1. 关闭当前连接并重新建立新连接
2. 不发送 `subscribe`，先写入一条 `EURUSD` 报价
3. 验证未收到推送后，再重新发送 `subscribe`
4. 再写入一条 `EURUSD` 报价

**预期结果**：
- 新连接未重新订阅前，不会收到旧连接残留的 `price.tick`
- 新连接重新订阅后，可再次收到 `price.tick`

---

---

## 4. 交易核心服务测试（trading-core-service）

### 4.1 账户管理

#### TC-TRD-001 查询账户（自动初始化空账户）

- **类型**：IT / E2E
- **验收阶段**：Stage 3B

**前置条件**：用户已激活，无历史账户

**操作**：
```http
GET /api/v1/trading/accounts/me
Authorization: Bearer <validToken>
```

**预期结果**：
- 业务码 `0`
- `balance = 0`，`frozen = 0`，`marginUsed = 0`，`available = 0`
- `openPositions = []`

**验证点**：
- [ ] `available = balance - frozen - marginUsed`（精度约束）
- [ ] `t_account` 有该用户记录

---

#### TC-TRD-002 账户语义一致性验证

- **类型**：UT
- **验收阶段**：Stage 3B

**规则验证**：

| 动作 | 预期效果 |
|------|---------|
| 入金 1000 | `balance += 1000` |
| 开仓，保证金 100 | `frozen += 100`（下单时预留），成交后 `frozen -= 100`，`margin_used += 100` |
| 扣手续费 5 | `balance -= 5` |
| 平仓，盈利 50 | `margin_used -= 100`，`balance += 50` |
| 取消订单 | `frozen -= 100` |

**验证点**：
- [ ] 不允许双扣（同时 `balance` 减少又 `frozen` 增加）
- [ ] `available = balance - frozen - marginUsed` 始终成立

---

### 4.2 入金链路

#### TC-TRD-010 消费 wallet.deposit.confirmed 完成入账

- **类型**：IT
- **验收阶段**：Stage 5

**操作**：向 `falconx.wallet.deposit.confirmed` 发送入金确认事件

**事件 Payload**：
```json
{
  "userId": 10001,
  "chain": "ETH",
  "token": "USDT",
  "txHash": "0xabc123",
  "fromAddress": "0xsource",
  "toAddress": "0xplatform",
  "amount": "1500.00000000",
  "confirmations": 12,
  "requiredConfirmations": 12,
  "confirmedAt": "2026-04-18T10:00:00Z"
}
```

**预期结果**：
- `t_account.balance += 1500`
- `t_deposit` 新增一条 `CREDITED` 记录
- `t_ledger` 新增一条 `biz_type=1` 的账本记录，快照字段齐全
- `t_outbox` 写入 `falconx.trading.deposit.credited` 事件

**验证点**：
- [ ] `t_account.balance = 1500`
- [ ] `t_deposit.status = "CREDITED"`
- [ ] `t_ledger.balance_before` 和 `t_ledger.balance_after` 差值等于 1500
- [ ] `t_outbox` 有对应事件记录
- [ ] `t_inbox` 写入 `eventId` 去重记录

---

#### TC-TRD-011 重复消费 confirmed 事件（幂等）

- **类型**：IT
- **验收阶段**：Stage 5

**操作**：相同 `eventId` 的入金确认事件发送两次

**预期结果**：
- 第一次：正常入账
- 第二次：幂等跳过，`balance` 不变，不重复入账

**验证点**：
- [ ] `t_deposit` 只有一条记录
- [ ] `t_account.balance` 不翻倍

---

#### TC-TRD-012 消费 wallet.deposit.reversed 触发入金撤回

- **类型**：IT
- **验收阶段**：Stage 5 / Stage 7

**前置条件**：已存在对应 `txHash` 的 `CREDITED` 入金记录

**预期结果**：
- `t_deposit.status` 变更为 `REVERSED`
- 若未开仓，`t_account.balance` 回滚入金金额
- `t_ledger` 新增撤回账本记录

**验证点**：
- [ ] `t_deposit.status = "REVERSED"`
- [ ] `balance` 回滚正确

---

### 4.3 市价单下单（CFD 核心）

#### TC-TRD-020 正常市价开仓（多头）

- **类型**：IT / E2E
- **验收阶段**：Stage 4 / Stage 7
- **前置条件**：用户已激活，`balance = 2000 USDT`，BTCUSDT 行情新鲜，当前价 10000

**输入**：
```json
POST /api/v1/trading/orders/market
{
  "symbol": "BTCUSDT",
  "side": "BUY",
  "quantity": 1.0,
  "leverage": 10,
  "takeProfitPrice": 10500.0,
  "stopLossPrice": 9500.0,
  "clientOrderId": "test-order-001"
}
```

**预期结果**（假设成交价 = mark_price = 10000）：
- `orderStatus = "FILLED"`
- `margin = quantity * filledPrice / leverage = 1 * 10000 / 10 = 1000`
- `fee = quantity * filledPrice * feeRate`（取规范费率）
- `positionStatus = "OPEN"`
- `takeProfitPrice = 10500`，`stopLossPrice = 9500` 落库
- `t_account.margin_used = 1000`
- `t_account.balance = 2000 - fee`
- `t_ledger` 包含保证金确认和手续费扣除两条记录
- `t_risk_exposure.total_long_qty += 1`（同一事务内）

**验证点**：
- [ ] `orderStatus = "FILLED"`
- [ ] `positionStatus = "OPEN"`
- [ ] `margin = 1000`（10x 杠杆）
- [ ] `t_position.take_profit_price = 10500`
- [ ] `t_position.stop_loss_price = 9500`
- [ ] `t_account.margin_used = 1000`
- [ ] `t_risk_exposure` 已更新（与持仓同事务）
- [ ] `t_ledger` 有保证金快照（before / after）

---

#### TC-TRD-021 正常市价开仓（空头）

- **类型**：IT
- **验收阶段**：Stage 4

**输入**：`side = "SELL"`，其余相同

**预期结果**：
- `positionSide = "SELL"`
- `t_risk_exposure.total_short_qty += 1`

---

#### TC-TRD-022 余额不足被风控拒绝

- **类型**：IT
- **验收阶段**：Stage 3B

**前置条件**：`balance = 100 USDT`，下单需保证金 1000

**预期结果**：
- `orderStatus = "REJECTED"`
- `rejectionReason = "INSUFFICIENT_MARGIN"`（或等价错误码）
- 业务码 `40002`
- `t_account.balance` 不变

**验证点**：
- [ ] 账户无变化
- [ ] `t_order.status = "REJECTED"`
- [ ] `t_ledger` 无新增记录

---

#### TC-TRD-023 行情过时（STALE）被拒绝开仓

- **类型**：IT
- **验收阶段**：Stage 3B

**前置条件**：Redis 中该 symbol 报价时间戳早于当前 5 秒以上

**预期结果**：
- `orderStatus = "REJECTED"`
- `rejectionReason = "MARKET_QUOTE_STALE"`
- 业务码 `40002`

---

#### TC-TRD-024 非交易时段被拒绝开仓

- **类型**：IT
- **验收阶段**：Stage 5

**前置条件**：当前时间处于该 symbol 的休市时段（节假日或非交易时段）

**预期结果**：
- 业务码 `40008`
- `rejectionReason = "SYMBOL_TRADING_SUSPENDED"`
- `orderStatus = "REJECTED"`

---

#### TC-TRD-025 clientOrderId 幂等（重复下单）

- **类型**：IT
- **验收阶段**：Stage 4

**操作**：相同 `clientOrderId` 连续提交两次

**预期结果**：
- 第一次：正常成交
- 第二次：返回相同订单结果，`duplicate = true`
- 持仓只开一次

**验证点**：
- [ ] 第二次 `duplicate = true`
- [ ] `t_order` 只有一条记录
- [ ] `t_position` 只有一条 OPEN 记录

---

#### TC-TRD-026 杠杆倍数边界验证

- **类型**：UT
- **验收阶段**：Stage 3B

**输入**：`leverage = 0`，`leverage = 1001`（超出规范上限）

**预期结果**：参数校验失败，业务码 `90004` 或 `40001`

---

#### TC-TRD-027 FROZEN 用户下单被网关拒绝

- **类型**：E2E
- **验收阶段**：Stage 4

**前置条件**：用户状态为 `FROZEN`

**预期结果**：Gateway 拦截，业务码 `10007`，trading-core-service 不收到请求

---

#### TC-TRD-028 单用户 OPEN 持仓数达到 max_position_per_user 时拒单

- **类型**：IT
- **验收阶段**：RISK-LIMIT-01

**前置条件**：`t_risk_config.symbol = BTCUSDT`，`max_position_per_user = 1`，用户在同一 `symbol` 下已有 1 条 `OPEN` 持仓，账户可用保证金足够。

**操作**：同一用户再次提交 `BTCUSDT` 市价开仓请求。

**预期结果**：
- 业务码 `30005`
- `orderStatus = "REJECTED"`
- `rejectionReason = "POSITION_LIMIT_REACHED"`
- 持久化 `REJECTED` 订单骨架
- 不新增持仓和成交

**验证点**：
- [ ] 当前用户同 `symbol` 的 `OPEN` 持仓数不增加
- [ ] `t_trade` 不新增成交
- [ ] `t_order.rejection_reason = "POSITION_LIMIT_REACHED"`

---

#### TC-TRD-029 平台 OPEN 持仓总数达到 max_position_total 时拒单

- **类型**：IT
- **验收阶段**：RISK-LIMIT-01

**前置条件**：`t_risk_config.symbol = BTCUSDT`，`max_position_total = 1`，全平台在同一 `symbol` 下已有 1 条 `OPEN` 持仓，第二个用户账户可用保证金足够。

**操作**：第二个用户提交 `BTCUSDT` 市价开仓请求。

**预期结果**：
- 业务码 `30006`
- `orderStatus = "REJECTED"`
- `rejectionReason = "PLATFORM_POSITION_LIMIT_REACHED"`
- 持久化 `REJECTED` 订单骨架
- 不新增第二个用户的持仓和成交

**验证点**：
- [ ] 全平台同 `symbol` 的 `OPEN` 持仓总数不增加
- [ ] 第二个用户 `t_trade` 不新增成交
- [ ] `t_order.rejection_reason = "PLATFORM_POSITION_LIMIT_REACHED"`

---

### 4.4 保证金计算（CFD 核心）

#### TC-TRD-030 保证金计算公式验证

- **类型**：UT
- **验收阶段**：Stage 3B

**公式**：`margin = quantity × filledPrice / leverage`

**测试数据组**：

| quantity | filledPrice | leverage | 预期 margin |
|----------|-------------|----------|------------|
| 1.0 | 10000 | 10 | 1000.00000000 |
| 0.5 | 50000 | 100 | 250.00000000 |
| 2.0 | 1.08 | 20 | 0.10800000 |
| 0.001 | 10000 | 5 | 2.00000000 |

**验证点**：
- [ ] 所有结果精度为 8 位小数
- [ ] 使用 `BigDecimal` 计算，不允许浮点运算

---

#### TC-TRD-031 手续费计算

- **类型**：UT
- **验收阶段**：Stage 3B

**公式**：`fee = quantity × filledPrice × feeRate`

**验证点**：
- [ ] 费率从 `t_risk_config` 读取，不硬编码
- [ ] `fee` 扣减体现在 `t_ledger` 中

---

#### TC-TRD-032 强平价格计算（多头）

- **类型**：UT
- **验收阶段**：Stage 3B

**多头强平价公式**：
```
liquidationPrice = entryPrice × (1 - 1/leverage + maintenanceMarginRate)
```

**空头强平价公式**：
```
liquidationPrice = entryPrice × (1 + 1/leverage - maintenanceMarginRate)
```

**测试数据**：

| side | entryPrice | leverage | maintenanceRate | 预期强平价 |
|------|-----------|----------|----------------|-----------|
| BUY | 10000 | 10 | 0.005 | 9050.00 |
| SELL | 10000 | 10 | 0.005 | 10950.00 |

**验证点**：
- [ ] 结果精度 8 位小数
- [ ] 多头强平价 < 入场价
- [ ] 空头强平价 > 入场价

---

### 4.5 浮盈浮亏（unrealizedPnl）

#### TC-TRD-040 多头浮盈计算

- **类型**：UT
- **验收阶段**：Stage 3B / Stage 7

**公式**：`unrealizedPnl = (effectiveMarkPrice - entryPrice) × quantity`（多头）

说明：`effectiveMarkPrice` 为交易侧有效标记价，`BUY -> bid`，`SELL -> ask`

**测试数据**：

| side | entryPrice | markPrice | quantity | 预期 PnL |
|------|-----------|-----------|----------|---------|
| BUY | 10000 | 10500 | 1.0 | 500.00 |
| BUY | 10000 | 9500 | 1.0 | -500.00 |
| SELL | 10000 | 9500 | 1.0 | 500.00 |
| SELL | 10000 | 10500 | 1.0 | -500.00 |

**验证点**：
- [ ] `unrealizedPnl` 不写 `t_position`（数据库无该字段）
- [ ] 查询接口动态计算返回
- [ ] 使用 Redis `bid / ask` 解析有效标记价，不查 MySQL

---

#### TC-TRD-041 unrealizedPnl 不持久化验证

- **类型**：IT
- **验收阶段**：Stage 5

**操作**：开仓后查询 `t_position` 表

**预期结果**：`t_position` 无 `unrealized_pnl` 字段（已从 schema 移除）

---

#### TC-TRD-042 行情 stale 时 unrealizedPnl 的处理

- **类型**：IT
- **验收阶段**：Stage 7

**前置条件**：Redis 行情已过期（stale = true）

**操作**：查询持仓账户

**预期结果**：
- 响应中 `quoteStale = true`
- `unrealizedPnl` 使用最后一次有效价格计算（或标记为 null）
- 不返回 500 错误

---

### 4.5A 手动平仓（Manual Close）

#### TC-TRD-043 BUY 手动平仓成功

- **类型**：IT
- **验收阶段**：Stage 7

**前置条件**：
- 已存在 `OPEN` 多头持仓
- Redis 中存在新鲜 `bid / ask` 报价

**操作**：调用 `POST /api/v1/trading/positions/{positionId}/close`

**预期结果**：
- HTTP 200，业务码 `0`
- `t_position.status = CLOSED`
- `t_position.close_reason = 1`（manual）
- `t_trade` 新增一条 `trade_type = CLOSE`
- `t_account.margin_used -= margin`
- `t_account.balance += realizedPnl`
- `t_risk_exposure` 同事务回补
- `t_outbox.event_type = "trading.position.closed"`
- **不新增**新的 `t_order`

**验证点**：
- [ ] `t_position.status = "CLOSED"`
- [ ] `t_position.close_reason = 1`
- [ ] `t_trade.trade_type = CLOSE`
- [ ] `t_outbox.event_type = "trading.position.closed"`
- [ ] `t_order` 条数不因平仓增加

---

#### TC-TRD-044 SELL 手动平仓成功，realizedPnl 为负时账户语义正确

- **类型**：IT
- **验收阶段**：Stage 7

**前置条件**：
- 已存在 `OPEN` 空头持仓
- `markPrice > entryPrice`，使 `realizedPnl < 0`

**预期结果**：
- HTTP 200，业务码 `0`
- `t_position.realized_pnl < 0`
- `t_account.balance` 仅按 `realizedPnl` 变化
- `t_account.margin_used -= margin`
- `t_account.frozen` 不变
- `available = balance - frozen - margin_used`

**验证点**：
- [ ] `realizedPnl` 为负
- [ ] `balance / frozen / margin_used` 语义符合账户冻结规则
- [ ] `available` 计算恒成立

---

#### TC-TRD-045 节假日全休时开仓与手动平仓均拒绝

- **类型**：IT
- **验收阶段**：Stage 7

**前置条件**：
- 已存在 `OPEN` 持仓
- `t_trading_holiday` 对应品种当日为 `FULL_CLOSE`

**操作**：
1. 尝试新的开仓请求
2. 对既有持仓发起手动平仓

**预期结果**：
- 步骤 1：返回 `40008`
- 步骤 2：返回 `40008`
- 手动平仓不得使用休盘报价生成新的成交事实

---

#### TC-TRD-046 手动平仓遇到 stale 或异常报价返回 30002

- **类型**：IT
- **验收阶段**：Stage 7

**前置条件**：Redis 中报价存在，但 `abs(now - quote.ts) > falconx.trading.stale.max-age`，或 `quoteStatus = STALE / ABNORMAL`

**预期结果**：
- 业务码 `30002`
- 持仓保持 `OPEN`
- 不写 `t_trade / t_ledger / t_outbox`

---

#### TC-TRD-047 手动平仓缺少报价返回 30003

- **类型**：IT
- **验收阶段**：Stage 7

**前置条件**：Redis 中无该 symbol 最新价

**预期结果**：
- 业务码 `30003`
- 持仓保持 `OPEN`
- 不写 `t_trade / t_ledger / t_outbox`

---

#### TC-TRD-048 手动平仓时持仓不存在或不属于当前用户返回 40004

- **类型**：IT
- **验收阶段**：Stage 7

**预期结果**：
- 业务码 `40004`
- 原持仓状态不变
- 不写新的平仓事实

---

#### TC-TRD-049 重复平仓返回 40007

- **类型**：IT
- **验收阶段**：Stage 7

**操作**：对同一 `positionId` 连续调用两次手动平仓

**预期结果**：
- 第一次返回 `0`
- 第二次返回 `40007`
- 不重复写 `t_trade / t_ledger / t_outbox`

---

### 4.6 止盈止损自动触发

#### TC-TRD-050 多头持仓触发止盈

- **类型**：IT（含行情消费）
- **验收阶段**：Stage 7

**前置条件**：
- 持仓 `side = BUY`，`entryPrice = 10000`，`takeProfitPrice = 10500`
- 内存快照已加载该持仓

**操作**：注入使交易侧 `effectiveMarkPrice = 10500` 的价格事件（`≥ takeProfitPrice`，多头取 `bid`）

**预期结果**：
- 持仓自动平仓，`t_position.status = CLOSED`
- `t_position.close_reason = 2`（tp）
- `t_trade` 新增平仓成交记录，`trade_type = CLOSE`
- `t_account.margin_used -= margin`，`balance += pnl`
- `t_ledger` 有平仓账本记录
- 内存快照移除该持仓
- `t_risk_exposure.total_long_qty -= 1`（同事务）

**验证点**：
- [ ] `t_position.status = "CLOSED"`
- [ ] `t_position.close_reason = 2`
- [ ] `t_position.close_price = 10500`
- [ ] `t_risk_exposure` 已更新
- [ ] `t_ledger` 平仓记录完整

---

#### TC-TRD-051 多头持仓触发止损

- **类型**：IT
- **验收阶段**：Stage 7

**前置条件**：`takeProfitPrice = 10500`，`stopLossPrice = 9500`

**操作**：注入 `markPrice = 9500` 的价格事件（`≤ stopLossPrice`）

**预期结果**：
- `t_position.close_reason = 3`（sl）
- 其余同 TC-TRD-050

---

#### TC-TRD-052 空头持仓 TP/SL 触发方向相反

- **类型**：UT
- **验收阶段**：Stage 7

**规则验证**：

| side | 条件 | 触发 |
|------|------|------|
| SELL | `effectiveMarkPrice <= takeProfitPrice` | 止盈 |
| SELL | `markPrice >= stopLossPrice` | 止损 |

---

#### TC-TRD-053 同一价格事件不重复触发 TP/SL

- **类型**：IT
- **验收阶段**：Stage 7

**操作**：同一 `price.tick` 事件触发两次（重复投递模拟）

**预期结果**：仅触发一次平仓，幂等保护

---

#### TC-TRD-054 不可成交 tick 不触发 TP/SL

- **类型**：IT（含行情消费）
- **验收阶段**：BBook P0

**前置条件**：
- 已存在带 `takeProfitPrice / stopLossPrice` 的 `OPEN` 持仓
- 注入价格数值满足 TP 或 SL 条件，但 tick 的 `quoteStatus = NO_QUOTE / STALE / ABNORMAL / MARKET_CLOSED`

**预期结果**：
- 持仓保持 `OPEN`
- 不写 `t_trade`
- 不写 `t_ledger`
- 不写 `t_outbox.event_type=trading.position.closed`
- 该 tick 可被保存为参考快照或日志，但不得触发成交

---

### 4.7 强制平仓（Liquidation）

#### TC-TRD-060 保证金率不足触发强平

- **类型**：IT
- **验收阶段**：Stage 7

**前置条件**：
- 持仓 `side = BUY`，`entryPrice = 10000`，`quantity = 1`，`leverage = 10`
- `margin = 1000`，`maintenanceMarginRate = 0.5%`
- 强平价 = 9050

**操作**：注入 `markPrice = 9050` 的价格事件（`≤ 强平价`）

**预期结果**：
- `t_position.status = LIQUIDATED`
- `t_liquidation_log` 新增一条记录
- `t_account.margin_used -= margin`
- `t_account.balance += 实际清算结果`（可能为 0）
- 内存快照移除该持仓

**验证点**：
- [ ] `t_position.status = "LIQUIDATED"`
- [ ] `t_liquidation_log` 有记录
- [ ] `t_risk_exposure` 同事务更新

---

#### TC-TRD-061 负净值保护（账户余额归零不打负）

- **类型**：IT
- **验收阶段**：Stage 7

**前置条件**：极端行情，强平亏损超过账户余额

**预期结果**：
- `t_account.balance = 0`（不为负数）
- `t_liquidation_log.platform_covered_loss > 0`（记录平台兜底金额）

**验证点**：
- [ ] `balance >= 0` 始终成立
- [ ] `platform_covered_loss = |超出亏损|`

---

#### TC-TRD-062 休盘报价不触发强平

- **类型**：UT
- **验收阶段**：BBook P0

**规则**：休盘时 market-service 不处理该 `symbol` 报价；trading-core-service 即使收到历史或重放 tick，也必须在强平执行前二次校验交易时间并跳过

**验证点**：
- [ ] 代码层面强平路径执行交易时间校验
- [ ] 休盘 tick 不写 `t_liquidation_log`

---

#### TC-TRD-063 t_risk_exposure 在强平时同事务更新

- **类型**：IT
- **验收阶段**：Stage 7

**操作**：触发强平，然后故意模拟 `t_risk_exposure` 写入失败（回滚）

**预期结果**：整个事务回滚，持仓状态未变

---

#### TC-TRD-064 强平日志记录保证金模式

- **类型**：IT
- **验收阶段**：Stage 7A

**操作**：触发一笔 `ISOLATED` 持仓强平

**预期结果**：
- `t_liquidation_log.margin_mode = 2`
- 强平日志中的保证金模式来自同一笔 `t_position.margin_mode`
- `GET /api/v1/trading/liquidations` 对应记录回显 `marginMode = ISOLATED`

---

#### TC-TRD-065 追加保证金后旧强平价失效

- **类型**：IT
- **验收阶段**：Stage 7A

**操作**：
1. 开 `ISOLATED` 持仓并记录 `liquidationPrice0`
2. 调用 `POST /api/v1/trading/positions/{positionId}/margin` 追加保证金
3. 记录重算后的 `liquidationPrice1`
4. 推入触达 `liquidationPrice0` 的报价
5. 推入触达 `liquidationPrice1` 的报价

**预期结果**：
- `liquidationPrice1 != liquidationPrice0`
- 步骤 4 不触发强平
- 步骤 5 触发强平，`t_position.close_reason = 4`

---

#### TC-TRD-066 不可成交 tick 不触发强平

- **类型**：IT
- **验收阶段**：BBook P0

**前置条件**：
- 已存在会在当前价格下满足强平条件的 `OPEN` 持仓
- 注入价格数值满足强平条件，但 tick 的 `quoteStatus = NO_QUOTE / STALE / ABNORMAL / MARKET_CLOSED`

**预期结果**：
- 持仓保持 `OPEN`
- 不写 `t_liquidation_log`
- 不写 `t_trade`
- 不写 `t_ledger`
- 不移除 `OpenPositionSnapshotStore` 中的持仓快照

---

### 4.8 隔夜利息（Swap）

#### TC-TRD-070 隔夜利息收取（多头持仓过 rollover）

- **类型**：IT
- **验收阶段**：Stage 6B

**前置条件**：
- `t_swap_rate` 中 `BTCUSDT` 的 `rate_long = -0.00010000`（多头每天扣）
- 持仓 `side = BUY`，`quantity = 1`，成交价 10000

**操作**：到达 `rollover_time`，Swap 结算定时任务触发

**预期结果**：
- `swap_amount = quantity * price * |rate_long| = 1 * 10000 * 0.0001 = 1`
- `t_ledger` 新增一条 `biz_type = 6`（swap_charge）记录
- `t_account.balance -= 1`

**验证点**：
- [ ] `t_ledger.biz_type = 6`
- [ ] 金额精度正确

---

#### TC-TRD-071 隔夜利息收入（空头持仓）

- **类型**：IT
- **验收阶段**：Stage 6B

**前置条件**：`rate_short = 0.00005000`（空头每天获得）

**预期结果**：
- `t_ledger.biz_type = 7`（swap_income）
- `t_account.balance += swap_amount`

---

#### TC-TRD-072 Swap 结算幂等

- **类型**：IT
- **验收阶段**：Stage 6B

**操作**：同一 rollover 时间点的 Swap 任务触发两次

**预期结果**：账本只有一条 Swap 记录，不重复扣款

---

#### TC-TRD-073 Swap 明细查询接口

- **类型**：IT
- **验收阶段**：Stage 6B

**前置条件**：
- 当前用户已存在至少一条 `Swap` 账本记录
- `t_position` 中仍可查询到对应 `positionId / symbol / side`

**操作**：调用 `GET /api/v1/trading/swap-settlements?page=1&pageSize=20`

**预期结果**：
- 返回当前用户自己的 `Swap` 明细分页
- `items[*]` 至少包含 `ledgerId / positionId / symbol / side / settlementType / amount / balanceAfter / rolloverAt / settledAt / referenceNo`
- `referenceNo` 与账本 `swap:{positionId}:{rolloverAt}` 保持一致

---

#### TC-TRD-074 Swap 结算业务事件出站

- **类型**：IT
- **验收阶段**：Stage 6B

**前置条件**：
- 满足 `TC-TRD-070` 或 `TC-TRD-071` 的结算前置条件

**操作**：触发一次成功的 `Swap` 结算

**预期结果**：
- `t_outbox` 新增一条 `event_type = trading.swap.settled`
- Kafka 主题 `falconx.trading.swap.settled` 收到一条事件
- payload 至少包含 `ledgerId / userId / positionId / symbol / side / settlementType / amount / rate / effectivePrice / rolloverAt / quoteTs / settledAt`

---

#### TC-TRD-075 订单列表查询接口

- **类型**：IT
- **验收阶段**：Stage 6B

**前置条件**：
- 当前用户已存在至少两笔订单
- 存在其他用户订单作为隔离对照

**操作**：调用 `GET /api/v1/trading/orders?page=1&pageSize=20`

**预期结果**：
- 只返回当前用户自己的订单分页
- `items[*]` 至少包含 `orderId / orderNo / symbol / side / orderType / quantity / requestedPrice / filledPrice / leverage / margin / fee / clientOrderId / status / createdAt / updatedAt`
- 分页总数不包含其他用户订单

---

#### TC-TRD-076 成交列表查询接口

- **类型**：IT
- **验收阶段**：Stage 6B

**前置条件**：
- 当前用户已存在开仓成交和对应的平仓或强平成交

**操作**：调用 `GET /api/v1/trading/trades?page=1&pageSize=20`

**预期结果**：
- 只返回当前用户自己的成交分页
- `items[*]` 至少包含 `tradeId / orderId / positionId / symbol / side / tradeType / quantity / price / fee / realizedPnl / tradedAt`
- 分页结果按最新成交优先返回

---

#### TC-TRD-077 持仓列表查询接口

- **类型**：IT
- **验收阶段**：Stage 6B

**前置条件**：
- 当前用户同时存在 `OPEN` 持仓和终态持仓

**操作**：调用 `GET /api/v1/trading/positions?page=1&pageSize=20`

**预期结果**：
- 只返回当前用户自己的持仓历史分页
- `items[*]` 至少包含 `positionId / openingOrderId / symbol / side / marginMode / quantity / entryPrice / leverage / margin / liquidationPrice / takeProfitPrice / stopLossPrice / closePrice / closeReason / realizedPnl / status / openedAt / closedAt / updatedAt`
- 对于 `OPEN` 持仓，动态回填 `markPrice / unrealizedPnl / quoteStale / quoteTs / quoteSource`
- 对于终态持仓，不伪造新的 `unrealizedPnl`

---

#### TC-TRD-078 账本流水查询接口

- **类型**：IT
- **验收阶段**：Stage 6B

**前置条件**：
- 当前用户已存在入金、下单、平仓或 `Swap` 等账本流水

**操作**：调用 `GET /api/v1/trading/ledger?page=1&pageSize=20`

**预期结果**：
- 只返回当前用户自己的账本分页
- `items[*]` 至少包含 `ledgerId / bizType / amount / idempotencyKey / referenceNo / balanceBefore / balanceAfter / frozenBefore / frozenAfter / marginUsedBefore / marginUsedAfter / createdAt`
- 首版费用事实可通过 `ORDER_FEE_CHARGED / SWAP_* / LIQUIDATION_PNL / REALIZED_PNL` 等 `bizType` 观察

---

#### TC-TRD-079 强平记录查询接口

- **类型**：IT
- **验收阶段**：Stage 6B

**前置条件**：
- 当前用户已存在至少一条强平记录

**操作**：调用 `GET /api/v1/trading/liquidations?page=1&pageSize=20`

**预期结果**：
- 只返回当前用户自己的强平记录分页
- `items[*]` 至少包含 `liquidationLogId / positionId / symbol / side / marginMode / quantity / entryPrice / liquidationPrice / markPrice / priceTs / priceSource / loss / fee / marginReleased / platformCoveredLoss / createdAt`
- 分页总数不包含其他用户强平记录

---

### 4.9 净敞口（Risk Exposure）

#### TC-TRD-080 开仓后净敞口更新

- **类型**：IT
- **验收阶段**：Stage 5

**操作**：开多头 1 手 BTCUSDT

**预期结果**：`t_risk_exposure` 中 `total_long_qty += 1`

---

#### TC-TRD-081 平仓后净敞口回补

- **类型**：IT
- **验收阶段**：Stage 7

**操作**：平掉多头 1 手 BTCUSDT

**预期结果**：`t_risk_exposure.total_long_qty -= 1`

**关键约束**：与平仓事务同步，不允许异步延迟

---

#### TC-TRD-082 净敞口写入与订单持仓同一事务

- **类型**：IT
- **验收阶段**：Stage 5

**操作**：模拟 `t_risk_exposure` 写入异常

**预期结果**：整个事务回滚，订单/持仓均不落库

---

### 4.10 Outbox 投递

#### TC-TRD-090 Outbox 调度正常投递

- **类型**：IT
- **验收阶段**：Stage 5

**操作**：完成一次入金，等待 Outbox 调度周期（≤ 2s）

**预期结果**：
- `t_outbox.status` 从 `PENDING` 变为 `SENT`
- Kafka 收到对应消息
- `t_outbox.sent_at` 有值

---

#### TC-TRD-091 Outbox 失败重试退避

- **类型**：IT
- **验收阶段**：Stage 5

**操作**：模拟 Kafka 不可用，触发投递失败

**预期结果**：
- `t_outbox.status = FAILED`，`retry_count += 1`
- `next_retry_at` 按退避策略递增（5s → 30s → 120s → 30m）

---

#### TC-TRD-092 Outbox 超过最大重试次数标记 DEAD

- **类型**：IT
- **验收阶段**：Stage 5

**操作**：持续失败达 10 次

**预期结果**：`t_outbox.status = DEAD`

---

---

## 5. 钱包服务测试（wallet-service）

### 5.1 入金地址幂等申请（真实 xpub 派生）

#### TC-WAL-001 为用户幂等分配真实入金地址

- **类型**：IT
- **验收阶段**：Stage 7A

**前置条件**：
- 用户已注册并通过 gateway 鉴权
- wallet-service 已配置 `FALCONX_WALLET_TRON_ACCOUNT_XPUB` 与 `FALCONX_WALLET_ETH_ACCOUNT_XPUB`
- 用户无已有地址

**操作**：调用 `POST /api/v1/wallet/deposit-addresses/ensure`

**预期结果**：
- 响应返回两条地址：`TRC20 / TRON / USDT` 与 `ERC20 / ETH / USDT`
- 地址由 account-level xpub 的 `m/44'/195'/0'/0/{index}` 与 `m/44'/60'/0'/0/{index}` 非硬化路径派生
- `t_wallet_address` 新增两条记录，记录 `token / network / derivation_path`
- 同一用户再次请求返回相同地址（幂等）

---

#### TC-WAL-002 入金地址申请缺失 xpub 时 fail-closed

- **类型**：IT
- **验收阶段**：Stage 7A

**操作**：在未配置 `FALCONX_WALLET_TRON_ACCOUNT_XPUB / FALCONX_WALLET_ETH_ACCOUNT_XPUB` 时调用 `POST /api/v1/wallet/deposit-addresses/ensure`

**预期结果**：
- 返回业务码 `20006 / Wallet Address Allocation Failed`
- 不写入 `t_wallet_address`
- 不退回 stub 地址

---

### 5.2 链上入金检测

#### TC-WAL-010 链上入金检测写入 DETECTED

- **类型**：IT（依赖 Web3 Stub）
- **验收阶段**：Stage 6A

**操作**：模拟链上检测到归属平台地址的 USDT 转账

**预期结果**：
- `t_wallet_deposit_tx.status = DETECTED`
- 发布 `falconx.wallet.deposit.detected` 事件（通过 Outbox）

---

#### TC-TRD-011 确认数推进至 CONFIRMING

- **类型**：IT
- **验收阶段**：Stage 6A

**操作**：确认数从 0 增长，未到阈值

**预期结果**：`t_wallet_deposit_tx.status = CONFIRMING`

---

#### TC-WAL-012 确认数达到阈值时变为 CONFIRMED

- **类型**：IT
- **验收阶段**：Stage 6A

**操作**：确认数到达 `required_confirmations`

**预期结果**：
- `t_wallet_deposit_tx.status = CONFIRMED`
- `confirmed_at` 写入
- 发布 `falconx.wallet.deposit.confirmed` 事件

**验证点**：
- [ ] `confirmed_at` 只写一次，后续确认数增加不覆盖
- [ ] Outbox 中有对应事件

---

#### TC-WAL-013 链回滚导致入金撤回

- **类型**：IT
- **验收阶段**：Stage 6A

**操作**：已 CONFIRMED 的交易被链回滚

**预期结果**：
- `t_wallet_deposit_tx.status = REVERSED`
- 发布 `falconx.wallet.deposit.reversed` 事件

---

#### TC-WAL-014 重复检测同一 txHash（幂等）

- **类型**：IT
- **验收阶段**：Stage 2B

**操作**：相同 `(chain, txHash)` 被检测两次

**预期结果**：`t_wallet_deposit_tx` 只有一条记录

---

#### TC-WAL-015 ETH 外部真节点扫块自动化

- **类型**：IT（依赖外部 ETH 节点）
- **验收阶段**：Stage 6A

**前置条件**：
- 显式提供 `FALCONX_WALLET_EXTERNAL_TEST_ENABLED=true`
- 显式提供 `FALCONX_WALLET_ETH_RPC_URL`
- 本机 JVM trust store 可验证目标节点证书链

**操作**：
- 以配置限制 `ETH` 单链启动外部监听器
- 基于真实最近区块动态发现一笔原生币转账
- 把目标地址登记为平台地址，并从前一块开始扫块

**预期结果**：
- 只初始化 `ETH` 游标，不扩展其他链
- `t_wallet_chain_cursor` 会推进到最新链头
- 原生币转账先进入 `CONFIRMING`，再通过确认窗口重扫推进到 `CONFIRMED`
- `wallet.deposit.detected / confirmed` Outbox payload 中都能看到同一个 `walletTxId`

**当前生产化准备证据（2026-04-29）**：
- 使用 Alchemy Ethereum Mainnet HTTPS 与 `/tmp/falconx-combined-truststore.p12` 临时 trust store 已通过外部真节点入金发现用例。
- 已验证真实 ETH 原生币转账进入 `CONFIRMING`、写出 `wallet.deposit.detected` outbox、推进 `ETH` 游标，且日志中的 RPC URL 已脱敏。
- `FX-057` 完成后，已验证同一笔真实 ETH 原生币转账在确认窗口重扫后进入 `CONFIRMED`，`wallet.deposit.confirmed` outbox payload 与 `wallet.deposit.detected` 使用同一个 `walletTxId`。
- 2026-04-29 外部真节点命令：`WalletExternalChainNodeAutomationIntegrationTests#shouldTrackRealEthDepositAcrossCursorRescanAndPersistWalletTxId` 通过（1 test，约 38.57 秒）；`WalletExternalChainNodeAutomationIntegrationTests#shouldEmitReversalWhenConfirmedTransactionDisappearsFromRealRescanWindow` 通过（1 test，约 8.67 秒）。

---

#### TC-WAL-016 ETH 外部真节点失败重试

- **类型**：IT（依赖外部 ETH 节点错误认证）
- **验收阶段**：Stage 6A

**前置条件**：
- 显式提供 `FALCONX_WALLET_EXTERNAL_TEST_ENABLED=true`
- 显式提供 `FALCONX_WALLET_ETH_RPC_URL` 或独立失败地址

**操作**：以真实节点错误认证地址启动 ETH 监听器

**预期结果**：
- 日志出现 `wallet.listener.chainHead.syncFailed`
- 下一轮轮询会继续重试
- owner 游标不前移
- 不产出新的链上观察记录
- 日志中的外部 RPC URL 必须脱敏，不得输出 Alchemy / Infura 等 path token 或 query token 明文

---

---

## 6. 网关测试（gateway）

### 6.1 路由与鉴权

#### TC-GW-001 未认证请求被拒

- **类型**：E2E
- **验收阶段**：Stage 4

**操作**：调用受保护接口，不携带 Authorization 头

**预期结果**：HTTP 401，业务码 `10001`

---

#### TC-GW-002 签名无效 Token 被拒

- **类型**：E2E
- **验收阶段**：Stage 4

**操作**：携带非法签名的 JWT

**预期结果**：HTTP 401，业务码 `10001`

---

#### TC-GW-003 已过期 Token 被拒

- **类型**：E2E
- **验收阶段**：Stage 4

**操作**：携带 `exp` 早于当前时间的 Token

**预期结果**：HTTP 401，业务码 `10001`

---

#### TC-GW-004 黑名单 Token 被拒

- **类型**：E2E
- **验收阶段**：Stage 6B

**前置条件**：Token 的 `jti` 已加入 Redis 黑名单

**预期结果**：HTTP 401，业务码 `10001`

---

#### TC-GW-005 BANNED 用户所有请求被拒

- **类型**：E2E
- **验收阶段**：Stage 4

**前置条件**：Token `status = BANNED`

**预期结果**：业务码 `10002`

---

#### TC-GW-006 FROZEN 用户写操作被拒，读操作通过

- **类型**：E2E
- **验收阶段**：Stage 4

**操作**：
1. `GET /api/v1/trading/accounts/me` → 应通过（200）
2. `POST /api/v1/trading/orders/market` → 应被拒（`10007`）

---

#### TC-GW-010 traceId 生成与透传

- **类型**：E2E
- **验收阶段**：Stage 4

**验证点**：
- [ ] 每个请求均有唯一 `X-Trace-Id` 响应头
- [ ] 同一请求在 gateway、market-service、trading-core-service 日志中的 `traceId` 完全一致
- [ ] 前端不能自定义 `X-Trace-Id`（即使传入也被覆盖）

---

#### TC-GW-011 X-User-* 头透传到下游

- **类型**：E2E
- **验收阶段**：Stage 4

**验证点**：
- [ ] 下游服务收到 `X-User-Id / X-User-Uid / X-User-Status`
- [ ] 值与 JWT payload 一致

---

### 6.2 公开接口路由

#### TC-GW-020 注册/登录/刷新接口无需 Token

- **类型**：E2E
- **验收阶段**：Stage 4

**操作**：不携带 Authorization 头调用 `/api/v1/auth/**`

**预期结果**：正常进入 identity-service，不被 gateway 拦截

---

### 6.3 北向行情 WebSocket

#### TC-GW-021 Market WebSocket 握手鉴权与头透传

- **类型**：IT
- **验收阶段**：Stage 6B

**操作**：
1. 不带 `token` 握手连接 `ws://{host}/ws/v1/market`
2. 使用 `status=BANNED` 的 Access Token 再次握手
3. 使用有效 Access Token 握手，并向下游 market 代理一个文本帧

**预期结果**：
- 第 1 步返回 `HTTP 401`
- 第 2 步返回 `HTTP 403`
- 第 3 步连接成功，gateway 会向下游透传 `X-User-Id / X-User-Uid / X-User-Status / X-Trace-Id`

---

#### TC-GW-022 同一用户第 6 个 Market WebSocket 连接被拒

- **类型**：IT
- **验收阶段**：Stage 6B

**前置条件**：同一用户已成功建立 5 个并发 `market` WebSocket 连接

**操作**：建立第 6 个连接

**预期结果**：握手阶段返回 `HTTP 429`

---

---

## 7. 跨服务集成测试（端到端链路）

### 7.1 完整用户注册入金链路

#### TC-E2E-001 注册 → 入金 → 登录链路

- **类型**：E2E
- **验收阶段**：Stage 7

**操作序列**：
1. `POST /api/v1/auth/register` → 创建用户（ACTIVE）
2. 模拟 wallet-service 发布 `wallet.deposit.confirmed`（userId 对应）
3. trading-core-service 消费，完成入账，发布 `trading.deposit.credited`
4. identity-service 消费，写入 `t_inbox` 入金事件幂等记录
5. `POST /api/v1/auth/login` → 登录，获取 Token
6. `GET /api/v1/trading/accounts/me` → 查看账户余额

**预期结果**：
- 步骤 1 后 `t_user.status = ACTIVE`
- 步骤 4 后 `identity-service.t_inbox` 存在对应 `deposit.credited` 记录
- 步骤 6 `balance > 0`

**验证点**：
- [ ] 每步日志中 `traceId` 贯穿
- [ ] 不同服务的 `t_inbox / t_outbox` 幂等记录完整
- [ ] 账户余额与入金金额一致

---

### 7.2 完整交易链路

#### TC-E2E-010 下单 → 持仓 → 止盈平仓链路

- **类型**：E2E
- **验收阶段**：Stage 7

**操作序列**：
1. 登录，获取 Token
2. `POST /api/v1/trading/orders/market`，设置 `takeProfitPrice`
3. 注入使交易侧有效价高于 TP 价格的行情事件（多头取 `bid`，空头取 `ask`）
4. `GET /api/v1/trading/accounts/me`

**预期结果**：步骤 4 时持仓已自动平仓，`openPositions = []`，`balance` 含止盈收益

---

#### TC-E2E-011 下单 → 持仓 → 强平链路

- **类型**：E2E
- **验收阶段**：Stage 7

**操作序列**：
1. 下单，持仓杠杆 10x
2. 注入跌破强平价格的行情事件
3. 查询账户

**预期结果**：持仓 `LIQUIDATED`，余额不为负

---

#### TC-E2E-012 追加保证金 → 旧强平价失效 → 新强平价强平链路

- **类型**：E2E
- **验收阶段**：Stage 7A

**操作序列**：
1. 通过 gateway 完成注册、入金、登录
2. 下 `ISOLATED` 市价单开仓，记录初始强平价
3. 通过 gateway 追加逐仓保证金
4. 注入旧强平价报价
5. 注入重算后的新强平价报价

**预期结果**：
- 步骤 3 回显 `marginMode = ISOLATED` 且强平价发生变化
- 步骤 4 不写强平日志，持仓仍为 `OPEN`
- 步骤 5 写强平日志，持仓为 `LIQUIDATED`，账本存在 `LIQUIDATION_PNL`

---

### 7.3 Outbox / Inbox 幂等链路

#### TC-E2E-020 Outbox 事件重复投递，消费端幂等

- **类型**：IT
- **验收阶段**：Stage 5

**操作**：
1. 完成入金，Outbox 投递 `deposit.credited`
2. 模拟重复投递同一事件（相同 `eventId`）

**预期结果**：identity-service 只激活一次，无重复账本记录

---

---

## 8. 安全专项测试

### 8.1 认证安全

#### TC-SEC-001 不允许 HS256 算法 Token

- **类型**：SEC
- **验收阶段**：Stage 4 / Stage 6B

**操作**：构造 HS256 签名的 JWT 提交到 gateway

**预期结果**：gateway 拒绝，HTTP 401

---

#### TC-SEC-002 algorithm confusion（alg=none 攻击）

- **类型**：SEC
- **验收阶段**：Stage 6B

**操作**：构造 `"alg": "none"` 的 JWT

**预期结果**：gateway 拒绝，HTTP 401

---

#### TC-SEC-003 密码明文不出现在日志

- **类型**：SEC
- **验收阶段**：Stage 3A

**操作**：注册/登录请求后检查日志输出

**预期结果**：日志中无 `password` 字段的明文值，无完整 JWT payload

---

#### TC-SEC-004 bcrypt 存储验证

- **类型**：IT
- **验收阶段**：Stage 3A

**验证点**：
- [ ] `t_user.password` 以 `$2a$` 或 `$2b$` 开头
- [ ] cost factor ≥ 12
- [ ] 原文密码无法从哈希值反推

---

### 8.2 输入校验

#### TC-SEC-010 SQL 注入防御

- **类型**：SEC
- **验收阶段**：Stage 5

**操作**：在 `email` 字段注入 `' OR '1'='1`

**预期结果**：业务层正常返回用户不存在，无 SQL 执行错误，不绕过认证

**验证点**：
- [ ] 所有 SQL 通过 MyBatis XML Mapper 执行，无字符串拼接

---

#### TC-SEC-011 XSS 防御

- **类型**：SEC
- **验收阶段**：Stage 7

**操作**：在 `symbol` 等字段注入 `<script>alert(1)</script>`

**预期结果**：参数校验拒绝或转义，不原样返回

---

### 8.3 速率限制

#### TC-SEC-020 注册频率限制

- **类型**：SEC
- **验收阶段**：Stage 6B

**操作**：同一 IP 1 小时内注册超过 5 次

**预期结果**：第 6 次返回 `10004`

---

---

## 9. 事务幂等专项测试

### 9.1 并发场景

#### TC-TXN-001 并发下单，余额足够一笔

- **类型**：TXN（压测级别）
- **验收阶段**：Stage 7

**前置条件**：`balance = 1000`，保证金需求 900，同时发 2 笔下单请求

**预期结果**：只有一笔成交，另一笔余额不足被拒

**验证点**：
- [ ] `t_account.balance ≥ 0` 始终成立
- [ ] `margin_used + frozen ≤ balance`
- [ ] 不出现超卖（两笔都成功）

---

#### TC-TXN-002 FOR UPDATE 锁保证账户余额一致性

- **类型**：IT
- **验收阶段**：Stage 5

**验证点**：
- [ ] 账户读写使用 `SELECT ... FOR UPDATE`（代码层面验证）
- [ ] 并发场景不出现余额不一致

---

### 9.2 事务回滚

#### TC-TXN-010 订单写入成功但持仓写入失败时全部回滚

- **类型**：IT
- **验收阶段**：Stage 3B

**操作**：模拟 `t_position` 写入失败（如唯一约束冲突）

**预期结果**：`t_order` 也回滚，账户余额不变

---

#### TC-TXN-011 Outbox 写入失败时业务事务全部回滚

- **类型**：IT
- **验收阶段**：Stage 5

**操作**：模拟 `t_outbox` 写入异常

**预期结果**：主业务事务也回滚（Outbox 与业务同事务）

---

#### TC-TXN-012 手动平仓时 t_risk_exposure 写入失败整笔事务回滚

- **类型**：IT
- **验收阶段**：Stage 7

**操作**：制造平仓前置完成但 `t_risk_exposure` 更新失败

**预期结果**：
- 平仓事务整体回滚
- `t_position` 保持 `OPEN`
- `t_account` 不结算
- 不写新的 `t_trade / t_ledger / t_outbox`
- 不新增 `t_order`

---

---

## 10. Kafka 事件专项测试

### 10.1 事件格式验证

#### TC-KFK-001 market.price.tick 事件信封完整性

- **类型**：IT
- **验收阶段**：Stage 6A

**验证点**：
- [ ] `eventId` 非空且唯一
- [ ] `eventType = "market.price.tick"`
- [ ] `schemaVersion = 1`
- [ ] `source = "falconx-market-service"`
- [ ] `occurredAt` 为 ISO8601 格式
- [ ] `traceId` 非空
- [ ] `partitionKey = symbol`
- [ ] `payload` 包含 `symbol / bid / ask / mid / mark / ts / source / stale / quoteStatus / qualityReason`

---

#### TC-KFK-002 wallet.deposit.confirmed 事件 payload 完整性

- **类型**：IT
- **验收阶段**：Stage 5

**验证点**：
- [ ] `payload.userId / chain / token / txHash / fromAddress / toAddress / amount / confirmations / requiredConfirmations / confirmedAt` 全部存在
- [ ] `amount` 为字符串类型，精度 8 位

---

#### TC-KFK-003 trading.deposit.credited 事件 payload 完整性

- **类型**：IT
- **验收阶段**：Stage 5

**验证点**：
- [ ] `payload.depositId / userId / accountId / chain / token / txHash / amount / creditedAt` 全部存在

---

### 10.2 消费组命名

#### TC-KFK-010 消费组命名符合规范

- **类型**：代码审查
- **验收阶段**：Stage 5

**规范格式**：`falconx.<service-name>.<context>-consumer-group`

**验证点**：
- [ ] `trading-core-service` 消费 `price.tick` 的消费组名为 `falconx.trading-core-service.price-tick-consumer-group`
- [ ] `identity-service` 消费 `deposit.credited` 的消费组名为 `falconx.identity-service.deposit-credited-consumer-group`

---

---

## 11. 性能边界测试

### 11.1 行情处理吞吐量

#### TC-PERF-001 高频行情处理不阻塞主线程

- **类型**：PERF
- **验收阶段**：Stage 6A

**操作**：以 100 msg/s 速率注入行情数据

**预期结果**：
- Redis 写入延迟 p99 < 20ms
- ClickHouse 批量写入正常
- 无 OOM、无线程堆积

---

#### TC-PERF-002 行情回调不在 WebSocket 回调线程中执行完整消费链路

- **类型**：代码审查 / IT
- **验收阶段**：Stage 6A

**规范约束**：来自 WebSocket SDK 回调的消息必须先切换到应用自管线程再处理

**验证点**：
- [ ] 代码中 WebSocket `onMessage` 只做入队/派发，不直接调用 Redis/Kafka/DB

---

### 11.2 下单延迟

#### TC-PERF-010 市价单端到端延迟

- **类型**：PERF
- **验收阶段**：Stage 7

**操作**：在行情正常情况下提交市价单

**预期结果**：端到端响应时间（包含 gateway → trading-core-service → 数据库写入）p99 < 500ms

---

---

## 12. 日志与链路可观测性测试

### 12.1 日志规范

#### TC-LOG-001 关键业务节点日志存在

- **类型**：IT
- **验收阶段**：各阶段

| 服务 | 关键日志点 |
|------|-----------|
| identity-service | `identity.register.received / completed`，`identity.login.received / completed` |
| market-service | `market.quote.received`，`market.redis.written`，`market.kline.closed`，`market.websocket.subscribe.accepted / price.push / price.stale-push / kline.push` |
| trading-core-service | `trading.order.received`，`trading.order.filled / rejected`，`trading.swap.settlement.completed / duplicate`，`trading.liquidation.triggered / executed` |
| gateway | `gateway.request.received`，`gateway.auth.accepted / rejected`，`gateway.websocket.handshake.accepted / rejected`，`gateway.websocket.proxy.connected / closed` |
| wallet-service | `wallet.listener.chainHead.syncFailed` |

**验证点**：
- [ ] 每个关键操作有 INFO 级日志
- [ ] 日志包含 `traceId / userId / symbol`（如适用）
- [ ] 无 `password / jwt_payload / refreshToken` 明文

---

#### TC-LOG-002 traceId 跨服务一致

- **类型**：E2E
- **验收阶段**：Stage 4

**操作**：触发同一业务链路或同一事件链路，收集链路上涉及服务的日志

**预期结果**：链路上各服务日志中的 `traceId` 完全一致；允许链路形态为 `gateway -> service` 或 `producer -> consumer` 的跨服务事件链路，不强制要求同一个北向请求同步贯穿所有服务

---

#### TC-LOG-003 错误日志携带完整上下文

- **类型**：IT
- **验收阶段**：Stage 3B

**操作**：触发业务异常（如余额不足、行情过时）

**预期结果**：ERROR 日志包含 `traceId / userId / symbol / rejectionReason`，不只有异常 stacktrace

---

#### TC-LOG-004 Stage 6B 运营关键链路日志存在

- **类型**：IT / 审计
- **验收阶段**：Stage 6B

**验证点**：
- [ ] `GatewayMarketWebSocketIntegrationTests` 已验证 `gateway.websocket.handshake.rejected`（`401/403/429`）与 `gateway.websocket.proxy.connected`
- [ ] `MarketWebSocketIntegrationTests` 已验证 `market.websocket.session.opened / closed` 的 `activeSessions`，以及 `market.websocket.subscribe.accepted / unsubscribe.accepted` 的 `channelCount / symbolCount`
- [ ] `TradingSwapSettlementIntegrationTests` 已验证 `trading.swap.settlement.batch.completed / duplicate`
- [ ] `TradingLiquidationIntegrationTests` 已验证 `trading.liquidation.triggered / executed`
- [ ] `SocketIoLpMarketQuoteProviderTests`、`LpWebSocketProtocolSupportTests`、`DefaultQuoteStandardizationServiceTests`、`MarketSingleQuoteSourceArchitectureTests` 已验证 LP Socket.IO demo 握手、订阅 payload、Snappy 解压、报价解析、`TM_QUOTE` 标准化，以及 market-service 只暴露 LP 作为生产行情源
- [ ] `Web3jChainDepositListenerTests`、`WalletExternalChainNodeAutomationIntegrationTests`、`Web3jChainDepositListenerExternalFailureIntegrationTests` 已覆盖 `wallet.listener.chainHead.synced / syncFailed` 的 `scannedBlocks / detectedCount / reversedCount` 统计日志
- [ ] `SpringTradingHedgeAlertEventPublisherTests` 已验证 `trading.risk.hedge.event.published`

---

---

## 13. 验收检查清单

### 13.1 Stage 5 验收必须用例

以下用例必须在 Stage 5 全部通过：

- [ ] TC-AUTH-001、TC-AUTH-002、TC-AUTH-010、TC-AUTH-011、TC-AUTH-020、TC-AUTH-021
- [ ] TC-AUTH-030、TC-AUTH-031（Kafka 消费幂等）
- [ ] TC-MKT-001、TC-MKT-010、TC-MKT-011、TC-MKT-012
- [ ] TC-MKT-030、TC-MKT-031、TC-MKT-032、TC-MKT-033、TC-MKT-034
- [ ] TC-TRD-001、TC-TRD-002、TC-TRD-010、TC-TRD-011（入金幂等）
- [ ] TC-TRD-020、TC-TRD-022、TC-TRD-023、TC-TRD-024、TC-TRD-025
- [ ] TC-TRD-030、TC-TRD-031（保证金计算）
- [ ] TC-TRD-080、TC-TRD-082（净敞口）
- [ ] TC-TRD-090、TC-TRD-091、TC-TRD-092（Outbox）
- [ ] TC-WAL-001、TC-WAL-002、TC-WAL-014
- [ ] TC-GW-001、TC-GW-002、TC-GW-003、TC-GW-005、TC-GW-006、TC-GW-010、TC-GW-011
- [ ] TC-TXN-001、TC-TXN-010、TC-TXN-011
- [ ] TC-SEC-003、TC-SEC-004、TC-SEC-010

### 13.2 Stage 6A 新增验收必须用例

- [ ] TC-MKT-003、TC-MKT-004（白名单热刷新）
- [ ] TC-MKT-005、TC-MKT-006（LP Socket.IO 协议链路与真源失败路径）
- [ ] TC-MKT-020、TC-MKT-021（K 线聚合）
- [ ] TC-WAL-010、TC-WAL-012、TC-WAL-013、TC-WAL-015、TC-WAL-016
- [ ] TC-KFK-001、TC-KFK-002、TC-KFK-003
- [ ] TC-PERF-002（WebSocket 线程隔离）

### 13.3 Stage 6B 新增验收必须用例

- [ ] TC-AUTH-015（登录限流）
- [ ] TC-GW-004（黑名单）
- [ ] TC-GW-021、TC-GW-022（行情 WebSocket 握手鉴权与连接限制）
- [ ] TC-SEC-001、TC-SEC-002（JWT 算法攻击）
- [ ] TC-SEC-020（注册频率限制）
- [ ] TC-MKT-043、TC-MKT-044、TC-MKT-045、TC-MKT-046、TC-MKT-047、TC-MKT-048（北向行情 WebSocket）
- [ ] TC-TRD-070、TC-TRD-071、TC-TRD-072、TC-TRD-073、TC-TRD-074、TC-TRD-075、TC-TRD-076、TC-TRD-077、TC-TRD-078、TC-TRD-079（Swap 与用户视角查询）
- [ ] TC-LOG-004（Stage 6B 运营关键链路日志）
- [ ] TC-E2E-001、TC-E2E-010、TC-E2E-011（既有主链路基线不回退）

### 13.4 Stage 7 验收必须用例（全部）

- [x] TC-E2E-001（完整注册激活链路）
- [x] TC-E2E-010（TP/SL 自动平仓）
- [x] TC-E2E-011（强平链路）
- [x] TC-TRD-043、TC-TRD-044、TC-TRD-045、TC-TRD-046、TC-TRD-047、TC-TRD-048、TC-TRD-049（手动平仓）
- [x] TC-TRD-050、TC-TRD-051、TC-TRD-052、TC-TRD-053（TP/SL）
- [x] TC-TRD-060、TC-TRD-061、TC-TRD-063（强平）
- [x] TC-TRD-041（unrealizedPnl 不持久化）
- [x] TC-TRD-040、TC-TRD-042（浮盈浮亏）
- [x] TC-TRD-012（入金撤回）
- [x] TC-TXN-012（手动平仓事务回滚）
- [x] TC-LOG-001、TC-LOG-002、TC-LOG-003

### 13.4A Stage 7 验收归档映射说明

说明：

- `2026-04-28` 已完成 `STAGE7-CLOSE-01`，归档见 [Stage7验收归档-2026-04-28](../process/archive/Stage7验收归档-2026-04-28.md)。
- 本节只记录 `Stage 7` 验收项与当前测试资产的归档映射，不代表当前系统可写成“生产可用”或“可安全对外公测”。

| 验收项分组 | 当前测试资产 | 当前状态 |
| --- | --- | --- |
| `TC-E2E-001 / 010 / 011` | `GatewayMinimalMainlineE2ETests`、`GatewayManualCloseE2ETests`、`GatewayTakeProfitE2ETests`、`GatewayStopLossE2ETests`、`GatewayLiquidationE2ETests` | 已归档通过（2026-04-28） |
| `TC-TRD-043 ~ 049` | `TradingControllerIntegrationTests` | 已归档通过（2026-04-28） |
| `TC-TRD-050 ~ 053` | `TradingAutoCloseIntegrationTests`、`QuoteDrivenEngineTriggerRuleTests` | 已归档通过（2026-04-28） |
| `TC-TRD-060 / 061 / 063` | `TradingLiquidationIntegrationTests`、`DefaultTradingScheduleServiceTests` | 已归档通过（2026-04-28） |
| `TC-TRD-012` | `TradingKafkaWalletDepositIntegrationTests` | 已归档通过（2026-04-28） |
| `TC-TXN-012` | `TradingPersistenceIntegrationTests.shouldRollbackManualCloseWhenRiskExposureUpdateFails` | 已归档通过（2026-04-28） |
| `TC-TRD-040 / 041 / 042` | `TradingPricingSupportTests`、`TradingQuoteSnapshotStaleIntegrationTests`、`TradingControllerIntegrationTests`、`TradingUserQueryControllerIntegrationTests` | 已归档通过（2026-04-28） |
| `TC-LOG-001 / 002 / 003` | `AuthControllerIntegrationTests`、`GatewayRoutingIntegrationTests`、`MarketInfrastructureIntegrationTests`、`TradingControllerIntegrationTests`、`TradingLiquidationIntegrationTests`、`Web3jChainDepositListenerTests` | 已归档通过（2026-04-28） |
| 核心链路验收回归 | `Stage7验收归档-2026-04-28.md` | 已归档通过（2026-04-28） |

### 13.4B Stage 7A 逐仓验收必须用例

- [x] TC-TRD-064（强平日志记录保证金模式）
- [x] TC-TRD-065（追加保证金后旧强平价失效）
- [x] TC-TRD-077 / TC-TRD-079（用户视角查询回显 `marginMode`）
- [x] TC-E2E-012（gateway 追加保证金到强平完整链路）

| 验收项分组 | 当前测试资产 | 当前状态 |
| --- | --- | --- |
| `TC-TRD-064 / 065` | `TradingLiquidationIntegrationTests`、`TradingControllerIntegrationTests` | 已通过（2026-04-28） |
| `TC-TRD-077 / 079` | `TradingUserQueryControllerIntegrationTests` | 已通过（2026-04-28） |
| `TC-E2E-012` | `GatewaySupplementMarginE2ETests` | 已通过（2026-04-28） |

---

### 13.4C BBook P0 行情质量保护验收用例

- [x] TC-MKT-010、TC-MKT-011、TC-MKT-013、TC-MKT-014、TC-MKT-015（行情质量状态）
- [x] TC-TRD-054（不可成交 tick 不触发 TP/SL）
- [x] TC-TRD-062、TC-TRD-066（休盘或不可成交 tick 不触发强平）
- [x] TC-KFK-001（`market.price.tick` 新增 `quoteStatus / qualityReason`）

| 验收项分组 | 当前测试资产 | 当前状态 |
| --- | --- | --- |
| `TC-MKT-010 / 011 / 013 / 014 / 015` | `DefaultQuoteStandardizationServiceTests`、`DefaultMarketQuoteQualityGuardServiceTests`、`DefaultMarketTradingScheduleGuardServiceTests`、`MarketDataIngestionApplicationServiceTests` | 已通过（2026-04-30；`mvn -pl falconx-market-service -am test`，71 tests） |
| `TC-TRD-054` | `TradingAutoCloseIntegrationTests` | 已通过（2026-04-30；trading-core 定向回归） |
| `TC-TRD-062 / 066` | `TradingLiquidationIntegrationTests`、`DefaultTradingScheduleServiceTests` | 已通过（2026-04-30；trading-core 定向回归） |
| `TC-KFK-001` | `MarketPriceTickMainlineIntegrationTests`、`TradingKafkaMarketEventIntegrationTests` | 已通过（2026-04-30；market-service 全量与 trading-core 定向回归） |

---

### 13.4D RISK-LIMIT-01 风控缺口验收用例

- [x] TC-TRD-028（单用户同 `symbol` 的 `OPEN` 持仓数达到 `max_position_per_user` 时拒单）
- [x] TC-TRD-029（全平台同 `symbol` 的 `OPEN` 持仓总数达到 `max_position_total` 时拒单）

| 验收项分组 | 当前测试资产 | 当前状态 |
| --- | --- | --- |
| `TC-TRD-028 / 029` | `TradingControllerIntegrationTests.shouldRejectMarketOrderWhenUserOpenPositionLimitReached`、`TradingControllerIntegrationTests.shouldRejectMarketOrderWhenPlatformOpenPositionLimitReached` | 已通过（2026-04-29） |

### 13.5 STAGE-1-CONSOLE-FOUNDATION 验收必须用例

> 本节为 BBook 一期 V2 §4 阶段 1「管理后台地基」的验收清单。详细 TC 输入 / 预期 / 验证点见独立文档：
>
> - 后端：[`STAGE-1-CONSOLE-test-cases.md`](./STAGE-1-CONSOLE-test-cases.md)
> - 前端：[`falconx-console-frontend-test-skeleton.md`](./falconx-console-frontend-test-skeleton.md)

新增 TC 编号块：

| Prefix | 编号区间 | 数量 | 说明 |
| --- | --- | --- | --- |
| `TC-CONSOLE-` | 001-099 | 51 | 阶段 1 后端集成测试（auth + me + permissions + menus + RBAC + IP 白名单 + 跨 schema 只读 + 审计） |
| `TC-E2E-CONSOLE-` | 001-009 | 1 | 阶段 1 端到端链路 |
| `FE-CONSOLE-` | 001-099 | 48 | 阶段 1 前端测试（登录 / 改密 / 单飞刷新 / RBAC 渲染 / 高风险二次确认 / Token 安全） |

阶段 1 验收硬约束：

- [ ] TC-CONSOLE-001 ~ 051 全部通过（后端 51 用例）
- [ ] TC-E2E-CONSOLE-001 通过（默认超管首次登录至看到全菜单完整链路）
- [ ] FE-CONSOLE-001 ~ 048 全部通过（前端 48 用例）
- [ ] R7 浏览器 QA 截图覆盖 5 个核心页面 + 控制台 0 错误
- [ ] R8 同步 [`管理端接口规范`](../api/管理端接口规范.md) §3 增量条目 + [`当前开发计划`](../setup/当前开发计划.md) 阶段 1 状态 + 本文件 §13.5 落地映射

| 验收项分组 | 测试资产（待 R9/R10 实施后落地） | 当前状态 |
| --- | --- | --- |
| `TC-CONSOLE-001 ~ 009` 登录 | _`AdminAuthLoginIntegrationTests`_ | ⏳ 骨架已交付 |
| `TC-CONSOLE-010 ~ 014` Token 刷新 | _`AdminAuthRefreshIntegrationTests`_ | ⏳ |
| `TC-CONSOLE-015 ~ 018` 登出 | _`AdminAuthLogoutIntegrationTests`_ | ⏳ |
| `TC-CONSOLE-019 ~ 025` 改密 | _`AdminAuthChangePasswordIntegrationTests`_ | ⏳ |
| `TC-CONSOLE-026 ~ 037` me/permissions/menus | _`AdminMe*IntegrationTests`_ | ⏳ |
| `TC-CONSOLE-038 ~ 041` RBAC 注解 | _`PermissionGuardIntegrationTests`_ | ⏳ |
| `TC-CONSOLE-042 ~ 044` IP 白名单 | _`AdminIpWhitelistIntegrationTests`_ | ⏳ |
| `TC-CONSOLE-045 ~ 047` 跨 schema 只读 | _`ConsoleCrossSchemaReadOnlyIntegrationTests`_ | ⏳ |
| `TC-CONSOLE-048 ~ 051` 审计日志 | _`AdminOperationLogIntegrationTests`_ | ⏳ |
| `TC-E2E-CONSOLE-001` E2E | _`GatewayAdminFoundationE2ETests`_ | ⏳ |
| `FE-CONSOLE-001 ~ 048` 前端 | _`falconx-console-frontend/src/**/*.test.{ts,tsx}`_（项目待 R10 创建） | ⏳ |

### 13.6 STAGE-1 P2-P5 RBAC 自管验收必须用例（R6 二轮，2026-05-09）

> 详见独立文档 [`STAGE-1-RBAC-CRUD-test-cases.md`](./STAGE-1-RBAC-CRUD-test-cases.md)。

新增 TC 编号块：

| Prefix | 编号区间 | 数量 | 说明 |
| --- | --- | --- | --- |
| `TC-CONSOLE-` | 100-179 | 60 | 阶段 1 P2-P5 后端集成测试（admin-users 22 + admin-roles 18 + admin-menus 12 + admin-permissions 8） |
| `FE-CONSOLE-` | 200-249 | 50 | 阶段 1 P2-P5 前端测试 |

阶段 1 R10 二轮验收硬约束：

- [ ] TC-CONSOLE-100~177 全部通过（后端 60 用例）
- [ ] FE-CONSOLE-200~249 全部通过（前端 50 用例）

### 13.7 STAGE-2.1 客户管理验收必须用例（R6 二轮，2026-05-09）

> 详见独立文档 [`STAGE-2-CUSTOMER-test-cases.md`](./STAGE-2-CUSTOMER-test-cases.md)。

新增 TC 编号块：

| Prefix | 编号区间 | 数量 | 说明 |
| --- | --- | --- | --- |
| `TC-CONSOLE-` | 300-351 | 50 | 阶段 2.1 console 后端（列表 8 + 详情 4 + 冻结 8 + 解冻 5 + 调余额 12 + 限额双写 7 + 跨用例审计 6） |
| `TC-INT-CONSOLE-` | 001-006 | 6 | 阶段 2.1 business-service 接收 internal RPC 侧（identity + trading-core） |
| `TC-E2E-CONSOLE-` | 002 | 1 | 管理员调余额完整链路（gateway + console + identity + trading-core） |
| `FE-CONSOLE-` | 100-129 | 30 | 阶段 2.1 前端（C1 列表 + C2 详情 + C3-C5 三 Modal） |

阶段 2.1 验收硬约束：

- [ ] TC-CONSOLE-300~351 全部通过（后端 50 用例）
- [ ] TC-INT-CONSOLE-001~006 全部通过（跨服务侧 6 用例）
- [ ] TC-E2E-CONSOLE-002 通过（端到端 1 条）
- [ ] FE-CONSOLE-100~129 全部通过（前端 30 用例）
- [ ] R7 浏览器 QA 截图覆盖 5 子页面（C1-C5）+ 3 高风险二次确认 Modal

### 13.8 STAGE-2.3 行情品种管理验收必须用例（R6 三轮，2026-05-09）

> 详见独立文档 [`STAGE-2-SYMBOL-test-cases.md`](./STAGE-2-SYMBOL-test-cases.md)。

新增 TC 编号块：

| Prefix | 编号区间 | 数量 | 说明 |
| --- | --- | --- | --- |
| `TC-CONSOLE-` | 350-379 | 30 | 阶段 2.3 console 服务侧（symbol 列表/详情/编辑/暂停恢复/swap-rate/trading-hours） |
| `TC-MARKET-` | 200-219 | 20 | 阶段 2.3 market internal RPC 侧（X-Internal-Token + owner 查询/更新） |
| `TC-E2E-CONSOLE-` | 003 | 1 | 运营调 swap-rate 后影响 trading-core Swap 结算的端到端链路 |
| `FE-CONSOLE-` | 250-279 | 30 | 阶段 2.3 管理端前端 P6 行情品种页 |

阶段 2.3 验收硬约束：

- [ ] TC-CONSOLE-350~379 全部通过（console 后端 30 用例）
- [ ] TC-MARKET-200~219 全部通过（market internal RPC 20 用例）
- [ ] TC-E2E-CONSOLE-003 通过（swap-rate 调整端到端 1 条）
- [ ] FE-CONSOLE-250~279 全部通过（管理端前端 30 用例）
- [ ] R7 浏览器 QA 截图覆盖列表加载、编辑 Drawer、暂停/恢复、Swap Rate Drawer + history、Trading Hours 弹窗

### 13.9 STAGE-2-SYMBOL-PARAMS-DOWNSHIFT 验收必须用例（R6 三轮，2026-05-12）

任务卡：[`STAGE-2-SYMBOL-PARAMS-DOWNSHIFT`](../process/task-cards/STAGE-2-SYMBOL-PARAMS-DOWNSHIFT.md)

完整测试用例骨架：[`STAGE-2-SYMBOL-PARAMS-DOWNSHIFT-test-cases.md`](./STAGE-2-SYMBOL-PARAMS-DOWNSHIFT-test-cases.md)

新增 TC 编号块：

| Prefix | 编号区间 | 数量 | 说明 |
| --- | --- | --- | --- |
| `TC-PARAMS-SOURCE-` | 001-015 | 15 | market source CRUD（POST /admin/symbols 含 90619/90620） |
| `TC-PARAMS-MAPPING-` | 001-025 | 25 | market mapping CRUD 字段扩展 + 90601-90604 搬迁 + DB CHECK 约束 |
| `TC-PARAMS-SPEC-` | 001-010 | 10 | SymbolSpec Redis Hash warmup + mapping CRUD afterCommit 刷新 |
| `TC-PARAMS-TICK-` | 001-006 | 6 | market `/internal/v1/market/symbols/last-tick` ClickHouse 查询 |
| `TC-PARAMS-VIS-` | 001-008 | 8 | group visibility 聚合视图 + 大组 group_concat truncate 场景 |
| `TC-PARAMS-TRADING-` | 001-020 | 20 | trading-core SymbolSpec 消费 + open_fee_rate 快照 + 历史持仓保护 |
| `TC-PARAMS-CONSOLE-` | 001-015 | 15 | console-service REST + 错误码翻译 + V8 RBAC 迁移 |
| `FE-PARAMS-` | 001-025 | 25 | console-frontend 三 Tab 重设计（主表 lastTickAt + 映射 7 字段 + 可见性 Transfer） |
| `TC-E2E-PARAMS-` | 001-005 | 5 | 端到端：mapping.fee/leverage 改后整链生效 + 历史持仓保护 + 新建 source 自动订阅 |

合计 129 用例。

阶段 5.X 验收硬约束：

- [ ] TC-PARAMS-SOURCE-001~015 全部通过（source CRUD 15）
- [ ] TC-PARAMS-MAPPING-001~025 全部通过（mapping CRUD 字段扩展 25）
- [ ] TC-PARAMS-SPEC-001~010 全部通过（SymbolSpec Redis 10）
- [ ] TC-PARAMS-TICK-001~006 全部通过（last-tick RPC 6）
- [ ] TC-PARAMS-VIS-001~008 全部通过（聚合视图 8）
- [ ] TC-PARAMS-TRADING-001~020 全部通过（trading-core 消费 + 历史保护 20）
- [ ] TC-PARAMS-CONSOLE-001~015 全部通过（console + RBAC 迁移 15）
- [ ] FE-PARAMS-001~025 全部通过（前端 25）
- [ ] TC-E2E-PARAMS-001~005 全部通过（E2E 5）
- [ ] R7 浏览器 QA 三 Tab 重设计桌面 + 移动截图齐全
- [ ] R7 客户端回归：C 端 /api/v1/market/symbols 字段名不变 + 取值来自 mapping
- [ ] V11 (market) + V2 (console) + V13 (trading) Flyway migration 顺序部署 + 不回滚
- [ ] Phase A + Phase B 同一发布窗口（任务卡强制约束）

### 13.10 STAGE-7-WITHDRAW Phase 3 B 段 IT 验收必须用例（R6 二轮，2026-05-14）

骨架真源：[`STAGE-7-WITHDRAW-Phase3-test-cases.md`](./STAGE-7-WITHDRAW-Phase3-test-cases.md)

新增 TC 编号块（28 个 IT/UT；E2E 2 个推迟到 testnet 资源就绪后由 R7 单独执行）：

| Prefix | 编号区间 | 数量 | 说明 |
| --- | --- | --- | --- |
| `TC-WD-` | 100 / 101 | 2 | LocalKmsSigner UT（ERC20 签名往返 / TRC20 私钥缺失抛 KmsSignerException） |
| `TC-WD-` | 103 | 1 | LocalKmsSigner 条件装配（Stub 兜底 / private-key-pem 激活 LocalKmsSigner，两侧均覆盖） |
| `TC-WD-` | 130 / 131 / 132 | 3 | EthNonceManager UT（启动拉链 / 并发递增唯一 / reset 重新拉链） |
| `TC-WD-` | 110 / 111 / 112 / 113 | 4 | TradingWithdrawReviewedEventConsumer IT（APPROVED dispatch / APPROVED_DELAYED skip / REJECTED skip / 重复 dispatch 不去重） |
| `TC-WD-` | 120 / 121 / 122 / 123 / 124 | 5 | WalletWithdrawBroadcastApplicationService IT（签名 + 广播成功 / Stub 兜底 FAILED 20014 / 广播 IO 失败 20010 / nonce-too-low 20010 / uk_withdraw_order 去重） |
| `TC-WD-` | 140 / 141 / 142 / 143 | 4 | WalletWithdrawConfirmationScheduler IT（conf 不足更新 / conf ≥ 12 CONFIRMED / receipt revert FAILED 20011 / receipt 暂不可用 retry） |
| `TC-WD-` | 150 / 151 / 152 | 3 | trading WalletWithdrawBroadcastEventConsumer IT（APPROVED→PROCESSING / 重复 CAS 0 行幂等 / APPROVED_DELAYED 防回退） |
| `TC-WD-` | 160 / 161 / 162 | 3 | trading WalletWithdrawConfirmedEventConsumer IT（PROCESSING→COMPLETED 结算 + 站内信 INFO / 重复跳过 / relatedKey body 校验） |
| `TC-WD-` | 170 / 171 / 172 | 3 | trading WalletWithdrawFailedEventConsumer IT（PROCESSING→FAILED 退冻 + 站内信 WARN / APPROVED→FAILED 广播前失败 / 重复跳过） |
| `TC-E2E-WD-` | 001 / 002 | 2 | Hoodi/Sepolia 真链 E2E（B 段 testnet 资源就绪后单独执行，不在本 commit 验收） |

阶段 7 Phase 3 B 段验收硬约束：

- [x] TC-WD-100 / 101 通过（LocalKmsSignerTests）
- [x] TC-WD-103 双侧覆盖通过（Stub 兜底 + LocalKmsSigner 激活）
- [x] TC-WD-130 / 131 / 132 通过（EthNonceManagerTests）
- [x] TC-WD-110~113 通过（WalletWithdrawReviewedEventConsumerIntegrationTests）
- [x] TC-WD-120 / 122 / 123 / 124 通过（WalletWithdrawBroadcastApplicationServiceIntegrationTests）
- [x] TC-WD-121 通过（WalletWithdrawBroadcastWithStubSignerIntegrationTests）
- [x] TC-WD-140~143 通过（WalletWithdrawConfirmationSchedulerIntegrationTests）
- [x] TC-WD-150~152 通过（WalletWithdrawBroadcastConsumerIntegrationTests）
- [x] TC-WD-160~162 通过（WalletWithdrawConfirmedConsumerIntegrationTests）
- [x] TC-WD-170~172 通过（WalletWithdrawFailedConsumerIntegrationTests）
- [ ] TC-E2E-WD-001 / 002（推迟到 testnet 资源就绪）

合计 28 IT/UT 通过 + 2 E2E 推迟。详见 [`STAGE-7-WITHDRAW-Phase3-test-cases.md`](./STAGE-7-WITHDRAW-Phase3-test-cases.md) §0 落地索引。

---

## 14. 测试环境规范

### 14.1 基础设施要求

- MySQL 8.4（独立测试 schema，不复用生产 schema）
- Redis 8.2.x（独立 DB 编号）
- Kafka（Testcontainers 或独立测试集群）
- ClickHouse 25.x（独立测试库）

### 14.2 数据隔离规则

- 每个集成测试类执行前后必须清理数据（`@Transactional` 回滚或显式 `DELETE`）
- 不允许测试用例间共享用户 ID 或账户状态
- Flyway checksum 冲突时，测试环境必须使用新的隔离库，不允许静默复用已应用的旧 checksum 库

### 14.3 Stub / Mock 使用约定

| 场景 | 允许 |
|------|------|
| 单元测试中 Mock 外部依赖 | 允许 |
| 集成测试中 Mock MySQL / Redis | **禁止** |
| 集成测试中使用 Stub Provider 注入报价 | 允许（仅 dev/test profile） |
| 生产链路中使用 In-Memory Repository | **禁止** |

### 14.4 并行执行约束

- `mvn test` 和 `mvn clean compile` 不允许并行执行（共用同一 target/ 目录）
- 会清理 target/ 的命令必须串行执行

---

## 15. 问题上报规则

- 测试中发现的真实 Bug，必须回写到 [docs/process/统一问题清单.md](../process/统一问题清单.md)
- 测试用例本身若发现文档歧义，需先更正文档，再修改用例
- 不允许因测试用例"暂时通过"就关闭问题，必须验证与文档语义完全一致

---

*本文档随项目阶段演进持续更新，每次新增测试场景须同步维护验收检查清单。*
