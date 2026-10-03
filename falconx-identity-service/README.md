# falconx-identity-service

## 文档边界

本文件只记录 identity 模块职责、当前已落地能力与当前边界。

- 项目阶段状态与验收结论以 `docs/setup/当前开发计划.md` 为准。
- 身份域接口、安全、状态流转与网关协同以专题规范为准。
- 本 README 不再单独声明 `Stage 6B / 7 / 7A` 是否“已验收”。

## 模块职责

`falconx-identity-service` 负责身份域 owner 能力：

- 用户注册、登录、Token 刷新
- 用户状态维护
- 邮箱可信度事实维护
- 基于业务事件的入金幂等留痕与历史状态归一化
- 身份域安全策略与认证会话治理

## 当前已落地能力

### 北向接口

- `POST /api/v1/auth/register`
- `POST /api/v1/auth/login`
- `POST /api/v1/auth/refresh`
- `POST /api/v1/auth/logout`

### 身份域事实

- owner MySQL、Flyway、`MyBatis + XML Mapper` 已接入。
- 已通过 `t_user.email_verified` 记录独立邮箱可信度事实；一期默认 `false`，不发送验证邮件，不提供验证端点，不写入 JWT payload。
- 已消费 `falconx.trading.deposit.credited` 写入 `t_inbox` 幂等记录，并对历史 `PENDING_DEPOSIT` 用户归一化为 `ACTIVE`。
- 已具备登录失败锁定、Access Token 黑名单与 `logout -> blacklist -> gateway reject` 基础能力。
- 已形成最小身份闭环：注册、登录、刷新、登出、入金事件联动。

## 模块联动

- `gateway -> identity-service`：注册、登录、刷新、登出由 gateway 路由进来。
- `trading-core-service -> identity-service`：消费 `falconx.trading.deposit.credited` 完成入金事件幂等留痕。
- `identity-service -> gateway`：通过 Access Token 黑名单影响受保护路由访问结果。

## 当前边界与不应误写的内容

- 本模块 README 不承担项目阶段验收结论职责；不得写“`Stage 7` 当前范围已验收”之类结论。
- 本模块已具备身份域闭环事实，但这不等于全平台 E2E、压测归档或生产可用已经完成。
- 一期 `emailVerified=false` 不限制登录、Refresh 或交易；后续如需邮件发送、验证 token、重发或高风险操作限制，必须另行冻结产品和合规规则。
- 当前不在本模块 README 中宣告 wallet / market / trading 的联合阶段完成状态。

## 相关文档

- `docs/security/安全规范.md`
- `docs/domain/状态机规范.md`
- `docs/api/REST接口规范.md`
- `docs/api/FalconX统一接口文档.md`
- `docs/setup/当前开发计划.md`
