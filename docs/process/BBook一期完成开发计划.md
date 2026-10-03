# BBook 一期完成开发计划

> 本文件是 `docs/setup/当前开发计划.md` 的专项执行拆分，用于把 BBook 一期剩余阻断项拆成可派发的多智能体任务。当前正式阶段状态、下一步顺序和完成结论仍以 `docs/setup/当前开发计划.md` 为准。

## 1. 冻结口径

- FalconX 一期是 BBook 平台，平台自身作为用户对手盘。
- 一期不做 A-book 对冲执行出口，不接外部对冲执行链路。
- 当前系统已具备 `ISOLATED` 模式下注册、入金、开仓、手动平仓、TP/SL、强平等核心链路，但仍不能写成“生产可用”或“一期完成”。
- 一期完成还必须补齐用户侧实时推送、CROSS 保证金真实执行、BBook 自营风控、组合 / 跨品种 / 异常行情保护和生产级运营证据。

## 2. 多智能体执行模型

Commander 是唯一调度者，负责冻结契约、派发 subagent、回收结果、统一验证和形成 Git 回滚点。subagent 只处理固定读写范围内的任务，不得自行修改 API、DB、Kafka、状态机、错误码或 owner 边界。

| 执行线 | 任务显示名称 | 任务编号 | 建议 agent 类型 | 写范围 |
| --- | --- | --- | --- | --- |
| 口径修正 | 修正一期 BBook 产品口径 | `BBOOK-V1-SCOPE-01` | 文档 worker | `docs/setup/当前开发计划.md`、`docs/process/统一问题清单.md`、相关摘要文档 |
| 用户实时 | 用户侧账户订单持仓保证金实时推送 | `USER-WS-REALTIME-01` | worker + reviewer | gateway、trading-core、WebSocket 规范、统一接口文档、E2E 测试 |
| CROSS | CROSS 保证金真实执行 | `CROSS-MARGIN-EXEC-01` | worker + reviewer | trading-core、数据库 migration、状态机、接口文档、交易测试 |
| BBook 风控 | BBook 自营风控闭环 | `BBOOK-RISK-CONTROL-01` | worker + reviewer | trading-core 风控配置、敞口、审计、告警、测试 |
| 行情保护 | 组合跨品种与异常行情保护 | `BBOOK-RISK-GUARD-01` | worker + reviewer | market-service、trading-core、错误码、测试、文档 |
| 生产运营 | 生产级监控告警与部署回滚证据 | `PROD-OPS-EVIDENCE-01` | ops/review worker | docs/setup、docs/process/archive、脚本、日志规范 |

## 3. 执行顺序

1. 完成 `BBOOK-V1-SCOPE-01`，把 A-book 对冲出口从一期目标中删除，明确 BBook 自营风险建设方向。
2. 启动 `USER-WS-REALTIME-01` 的契约冻结，先确定路径、消息体、权限隔离、事件源和补偿语义。
3. 并行冻结 `BBOOK-RISK-GUARD-01` 与 `BBOOK-RISK-CONTROL-01` 的规则和 owner 分工。
4. 启动 `CROSS-MARGIN-EXEC-01` 的保证金模型冻结；公式确认后再进入代码实现。
5. `PROD-OPS-EVIDENCE-01` 贯穿执行，但不能替代业务能力验收。
6. 上述 P0 阻断项闭合后，再回到 `WALLET-REGISTER-ADDRESS-01` 和其他 P1/P2 能力。

## 4. 任务拆分

### 修正一期 BBook 产品口径

任务编号：`BBOOK-V1-SCOPE-01`

当前状态：已完成（2026-04-30）。

必须修改：

- `docs/setup/当前开发计划.md`
- `docs/process/统一问题清单.md`
- `docs/api/FalconX统一接口文档.md`
- `docs/database/falconx一期数据库设计.md`
- `falconx-trading-core-service/README.md`
- `falconx-trading-core-service/src/main/java/com/falconx/trading/event/TradingHedgeAlertEvent.java`

必须达到：

- 一期目标明确为 BBook，不再把 A-book 对冲出口写成后续能力。
- `TradingHedgeAlertEvent` 被描述为 BBook 风险观测告警桩，不代表完整自营风控闭环。
- 当前仍不能写成“生产可用”。

验证命令：

```bash
git diff --check
rg "后续.*A-book|作为后续.*对冲|真实 A-book 对冲出口已接入|后续接入.*对冲出口" docs/setup/当前开发计划.md docs/api/FalconX统一接口文档.md docs/database/falconx一期数据库设计.md falconx-trading-core-service/README.md falconx-trading-core-service/src/main/java/com/falconx/trading/event/TradingHedgeAlertEvent.java
```

预期结果：

- `git diff --check` 通过。
- `rg` 不再命中把 A-book 对冲出口作为一期目标或后续目标的表述；“一期不做 A-book 对冲执行出口”的禁止性表述允许存在。

### 用户侧账户订单持仓保证金实时推送

任务编号：`USER-WS-REALTIME-01`

当前状态：已完成首版（2026-04-30）。

已冻结契约：

- 路径：`ws://{host}/ws/v1/trading`
- 认证：沿用 gateway JWT WebSocket 握手，透传 `X-User-Id / X-User-Uid / X-Trace-Id`
- 私有数据隔离：服务端只允许当前 token 对应用户订阅自己的账户、订单、持仓、保证金和账本消息
- 消息类型：
  - `account.snapshot`
  - `account.update`
  - `order.update`
  - `trade.created`
  - `position.update`
  - `margin.update`
  - `ledger.created`
  - `liquidation.update`
  - `risk-controls.update`
  - `error`
  - `pong`

首版限制：

- 当前用户交易 WebSocket 是服务内 best-effort 通知，断线后必须用 REST 查询补偿。
- 当前连接数限制仍是 gateway 单实例内存计数，多实例全局连接上限留给生产运营增强。
- 当前不推送 `risk.warning`；BBook 自营风控闭环落地后，必须在 `BBOOK-RISK-CONTROL-01` 单独冻结风险提醒消息体。

实施步骤：

1. 已更新 `docs/api/WebSocket接口规范.md`，冻结用户侧 WebSocket 路径、握手、订阅、消息体、错误帧、断线补偿和连接限制。
2. 已更新 `docs/api/FalconX统一接口文档.md`，新增用户侧实时推送接口条目。
3. 已在 gateway 新增用户侧 WebSocket 代理和握手鉴权测试。
4. 已在 trading-core-service 新增 WebSocket handler、session registry、user channel fan-out、推送 DTO 和事务提交后事件源。
5. 已在交易写路径中发布订单、成交、持仓、保证金和账本变更通知；通知在事务提交后发送。
6. 已补齐 gateway + trading-core 定向集成测试，覆盖订阅、用户身份透传、订单状态变化、持仓变化、保证金变化和 REST 补偿口径。

验证结果：

```bash
mvn -pl falconx-gateway -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=GatewayMarketWebSocketIntegrationTests test
mvn -pl falconx-trading-core-service -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=TradingUserWebSocketIntegrationTests test
mvn -pl falconx-trading-core-service -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=TradingControllerIntegrationTests,TradingUserQueryControllerIntegrationTests test
```

结果：2026-04-30 串行执行通过（6 tests、1 test、49 tests）。

禁止事项：

- 不复用 `/ws/v1/market` 混发用户私有数据。
- 不允许客户端传入任意 `userId` 订阅他人数据。
- 不在交易事务提交前推送最终状态。

### CROSS 保证金真实执行

任务编号：`CROSS-MARGIN-EXEC-01`

当前状态：待冻结模型。

必须冻结公式：

- `equity = balance + realizedPnl + unrealizedPnl - fees`
- `usedMargin = sum(openPositionInitialMargin)`
- `maintenanceMargin = sum(openPositionNotional * maintenanceMarginRate)`
- `availableMargin = equity - usedMargin`
- `marginLevel = equity / maintenanceMargin`
- 强平触发：`equity <= maintenanceMargin` 或冻结后的等效公式

实施步骤：

1. 更新状态机、数据库设计、REST 接口文档和测试规范，冻结 CROSS 公式和字段回显。
2. 修改 `DefaultTradingRiskService`，让 `marginMode=CROSS` 进入 CROSS 风控分支。
3. 修改账户服务和下单事务，按账户级保证金冻结与释放。
4. 修改 `QuoteDrivenEngine` / 强平路径，按账户级权益和维持保证金触发 CROSS 强平。
5. 修改用户查询与实时推送字段，准确回显 `CROSS / ISOLATED` 风险状态。
6. 补齐交易核心集成测试和 gateway E2E。

禁止事项：

- 不把 ISOLATED 的单仓保证金逻辑改名为 CROSS。
- 不在公式和账本字段未冻结时修改生产代码。

### BBook 自营风控闭环

任务编号：`BBOOK-RISK-CONTROL-01`

当前状态：三切片已收口（2026-05-13），详见 [`task-cards/BBOOK-RISK-CONTROL-01.md`](task-cards/BBOOK-RISK-CONTROL-01.md)。剩余非阻断项：点差过大 / 单 tick 跳变 / 短时剧烈波动阈值（行情侧规则，归 `BBOOK-RISK-GUARD-01`）。

必须覆盖：

- 全平台净敞口阈值 ✅（t_risk_config symbol=NULL 行 + PlatformExposureGuardScheduler 5s 自动激活/恢复 GLOBAL_PAUSE）
- 单 symbol 净敞口阈值 ✅（已在首版完成）
- 单用户敞口阈值 ✅（t_user_risk_threshold + DefaultTradingRiskService.evaluateUserExposureLimit）
- 盈利用户敞口阈值 ✅（is_profitable_user 标记 + profitable_net_exposure_threshold_usd）
- 方向集中度阈值 ✅（DefaultTradingRiskObservabilityService.checkDirectionImbalance，多/空 USD 失衡比）
- 全局 kill switch ✅（auto_liquidate.enabled + GLOBAL_PAUSE）
- symbol 级暂停开仓 ✅（SUSPEND_SYMBOL 已有）
- 只允许减仓 ✅（REDUCE_ONLY 枚举 + 开仓 API 拒绝；close API 不走风控，自然支持）
- 风控动作审计与恢复 ✅（trigger_source / trigger_reason / hedge_log_id 持久化；AUTO_* 自动恢复，MANUAL_ADMIN 手动 deactivate）

实施步骤：

1. 冻结风控配置来源和动作枚举。
2. 新增或扩展风险配置表；若新增字段，使用 trading-core-service 下一版 Flyway migration。
3. 在下单风控、平仓、行情驱动估值和强平前置检查中接入风控动作。
4. 将 `TradingHedgeAlertEvent` 语义收敛为 BBook 风险告警；如改名，必须分阶段兼容测试和文档。
5. 补齐风控拒单、只减仓、暂停 symbol、kill switch、恢复路径和审计测试。

禁止事项：

- 不接入 A-book 对冲执行出口。
- 不把日志告警等同于风控动作完成。

### 组合跨品种与异常行情保护

任务编号：`BBOOK-RISK-GUARD-01`

当前状态：已完成（2026-05-05）。行情侧规则全部落地；跨品种集中度和组合风险并入 `BBOOK-RISK-CONTROL-01`。

必须覆盖：

- 零价、负价、bid/ask 反向
- 点差过大
- 单 tick 跳变过大
- 长时间无报价
- 休盘参考价只展示不成交
- 异常 tick 不触发误强平
- 单品种、品类、相关资产、用户和平台集中度保护

已落地首批规则（2026-04-30）：

- `FRESH / STALE / NO_QUOTE / MARKET_CLOSED / ABNORMAL` 行情质量状态；`FRESH` 是唯一可成交状态。
- `NO_QUOTE` 默认由 `falconx.market.quote-quality.unchanged-max-age=1m` 控制。
- `STALE` 默认由 `falconx.market.stale.max-age=5s` 和 trading 二次 stale 校验控制。
- `STALE / NO_QUOTE / ABNORMAL` 只发布不可成交 Kafka 快照，不刷新可成交 Redis / ClickHouse / WebSocket / K 线。
- `MARKET_CLOSED` 综合交易时间、节假日和人工例外；休盘 tick 不进入 Redis / ClickHouse / Kafka / WebSocket / K 线。
- `ABNORMAL` 覆盖零价、负价和 bid/ask 反向。
- trading-core 下单、手动平仓、TP/SL 和强平均拒绝使用不可成交 tick。

继续实施范围：

- 点差过大、单 tick 跳变过大和短时剧烈波动阈值必须来自后台配置。
- 单品种、品类、相关资产、用户和平台集中度保护必须冻结配置 owner、热刷新、审计和恢复规则。

实施步骤：

1. 在 market-service 冻结报价质量状态和 Redis / Kafka / WebSocket 表达方式。（首批已完成）
2. 在 trading-core-service 冻结交易保护动作：拒绝开仓、暂停手动平仓成交、禁止 TP/SL 和强平误触发。（首批已完成）
3. 补齐异常报价不会成交、不会误强平、参考价展示、恢复后可交易的集成测试。（首批已完成，组合风险和跳变阈值继续补）
4. 更新统一接口文档、WebSocket 规范、Kafka 事件规范、错误码和测试规范。（首批已完成，后续配置模型变更时继续同步）

验证结果（2026-04-30）：

```bash
mvn -pl falconx-market-service -am test
mvn -pl falconx-trading-core-service -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=TradingKafkaMarketEventIntegrationTests,TradingQuoteSnapshotStaleIntegrationTests,TradingAutoCloseIntegrationTests,TradingLiquidationIntegrationTests,TradingControllerIntegrationTests test
```

结果：串行执行通过（market-service 71 tests；trading-core-service 56 tests）。

禁止事项：

- 不让异常价格进入成交路径。
- 不用本地静态白名单替代 owner 数据或风控配置。

### 生产级监控告警与部署回滚证据

任务编号：`PROD-OPS-EVIDENCE-01`

当前状态：修复中。2026-05-26 已补 trading tick 热路径指标、market/trading WebSocket 服务内 backlog 指标、canary 默认端口修正和生产观测与回滚手册；正式 / 准生产 trust store、Secret 治理、业务 canary 真代码和演练证据仍待补齐。

必须覆盖：

- gateway、identity、market、trading-core、wallet 健康检查和关键链路 canary
- MySQL、Redis、Kafka、ClickHouse 可用性和积压指标
- LP Socket.IO 收流、断线重连、10 秒无报价告警
- 钱包外部 RPC 延迟、确认推进、reversal 和 cursor 健康
- 正式或准生产 JVM trust store
- Secret 治理、部署步骤、回滚步骤、日志检索语句、告警规则和演练证据

实施步骤：

1. 更新生产化准备包，列出每项证据的采集命令、成功判定和失败判定。
2. 补齐服务健康检查或脚本化 canary。
3. 建立日志检索键表，覆盖主链路、行情、交易、钱包、风控和 WebSocket。
4. 形成部署与回滚演练归档。
5. 接入或归档 `docs/operations/生产观测与回滚手册.md` 中列出的热路径指标、告警阈值、Kafka lag 检查和回滚证据模板。

禁止事项：

- 不把本地 Maven 测试通过写成生产级证据。
- 不把 `/tmp` 临时 trust store 写成正式长期依赖。

## 5. 首批 subagent 派发计划

### 只读核对任务

- 任务显示名称：核对用户实时推送代码落点
- 任务编号：`USER-WS-REALTIME-01-AUDIT`
- 写范围：无
- 返回：gateway 代理、trading WebSocket 缺口、可复用 market WebSocket 组件、必须新增测试清单

### 契约草案任务

- 任务显示名称：起草用户侧实时推送契约
- 任务编号：`USER-WS-REALTIME-01-CONTRACT`
- 写范围：`docs/api/WebSocket接口规范.md`、`docs/api/FalconX统一接口文档.md`
- 禁止：不得修改代码、不得修改 DB / Kafka / 状态机
- 返回：路径、消息体、错误帧、断线补偿、测试清单

### 风控规则草案任务

- 任务显示名称：起草 BBook 风控与异常行情规则
- 任务编号：`BBOOK-RISK-GUARD-01-CONTRACT`
- 写范围：`docs/process/BBook一期完成开发计划.md` 后续规则章节、必要时 `docs/process/统一问题清单.md`
- 禁止：不得修改生产代码、不得新增 DB schema
- 返回：报价质量状态、风控动作、错误码需求、owner 分工、测试清单

## 6. 完成判定

一期完成必须同时满足：

- 用户侧实时推送已实现并通过 gateway + trading E2E。
- CROSS 保证金已真实执行并覆盖账户级风控、强平、查询和推送。
- BBook 自营风控动作可执行、可审计、可恢复。
- 异常行情不会成交，不会误强平，休盘参考价只展示。
- 生产级监控、告警、canary、日志检索、部署和回滚证据闭合。
- 所有相关接口、数据库、事件、状态机、测试和当前开发计划同步完成。
- 形成 Git 回滚点；若用户要求同步 GitHub，再 push 远程回滚点。

## 7. 剩余执行路径

本计划是 BBook 一期范围与多智能体拆分的入口。剩余具体任务的实施步骤、文件改动、数据库变更、测试用例命名、验证命令以及文档同步要求，全部下沉到 [`BBook一期完成执行路径`](./BBook一期完成执行路径.md) 中分阶段维护。后续开发严格按执行路径文档推进，不再在本文件继续展开任务级细节。

执行路径文档对照本计划任务编号的覆盖：

| 本计划任务 | 当前状态 | 执行路径所在阶段 |
| --- | --- | --- |
| `BBOOK-V1-SCOPE-01` | 已完成 | — |
| `USER-WS-REALTIME-01` | 已完成首版 | 阶段 4.1 多实例广播补齐 |
| `CROSS-MARGIN-EXEC-01` | 基础完成 | 阶段 1 全量收尾 |
| `BBOOK-RISK-CONTROL-01` | 引擎完成 | 阶段 3 运营最小集补齐 |
| `BBOOK-RISK-GUARD-01` | 已完成 | 阶段 5 长断线保护延伸 |
| `PROD-OPS-EVIDENCE-01` | 待补 | 阶段 6 代码层（证书/部署演练单独立项） |
