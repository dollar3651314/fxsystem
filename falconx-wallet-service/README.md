# falconx-wallet-service

## 文档边界

本文件只记录 wallet 模块职责、当前已落地能力与当前边界。

- 项目阶段状态与验收结论以 `docs/setup/当前开发计划.md` 为准。
- 钱包链路、数据库 owner、事件契约、链节点接入规则以专题规范为准。
- 本 README 不再单独声明“`Stage 6A` 部分完成”之类阶段判断。

## 模块职责

`falconx-wallet-service` 负责链上原始事实 owner 能力：

- 入金页幂等地址申请与真实 xpub 派生地址持久化
- 链上原始入金识别与持久化
- 确认推进与回滚观察
- `falconx.wallet.deposit.*` 事件输出
- 链游标与外部节点监听治理

## 当前已落地能力

### owner 原始事实链路

- 已落地 `POST /api/v1/wallet/deposit-addresses/ensure` 入金页幂等地址申请接口。
- 已支持基于 account-level xpub 派生 `TRC20 / TRON / USDT` 与 `ERC20 / ETH / USDT` 地址并写入 owner 持久化。
- wallet-service 只读取 xpub，不保存 mnemonic、私钥或可花费密钥。
- 若 `FALCONX_WALLET_TRON_ACCOUNT_XPUB` 或 `FALCONX_WALLET_ETH_ACCOUNT_XPUB` 缺失，地址申请返回 `20006` 并 fail-closed。
- 已通过 `t_wallet_chain_cursor` 驱动扫块游标。
- 已落地 EVM 原生币、ERC20 与 TRC20 USDT 最小扫块识别路径。
- 已支持确认窗口重扫、reversal 观察与 `walletTxId` 稳定主键输出。
- 启动配置会读取根目录 `.env`；ERC20 合约使用 `FALCONX_WALLET_ERC20_USDC_CONTRACT`
  与 `FALCONX_WALLET_ERC20_USDT_CONTRACT`，TRC20 USDT 合约使用
  `FALCONX_WALLET_TRC20_USDT_CONTRACT`。
- TRC20 测试 RPC 使用 `ALCHEMY_ETHEREUM_NILETEST_HTTPS`，链监听默认每 5 分钟拉取一次。
  该 RPC 必须是 TRON HTTP API 基础地址；监听器读取 `/wallet/getnowblock`，
  并优先通过 `solidity-rpc-url` 读取 `/walletsolidity/gettransactioninfobyblocknum`
  解析 TRC20 `Transfer` 日志。

### 事件链路

- 已形成 `falconx.wallet.deposit.detected`。
- 已形成 `falconx.wallet.deposit.confirmed`。
- 已形成 `falconx.wallet.deposit.reversed`。
- 这些事件继续驱动 `trading-core-service` 的业务入金与回滚链路。

### 外部节点与自动化入口

- 运行时已具备真实节点连接能力。
- 外部 ETH 节点成功 / 失败路径门禁用例已具备。
- TRC20 HTTP 客户端解析与监听路径已具备自动化验证；本 README 不把该验证误写成 Nile 真链入金归档证据。

## 模块联动

- `gateway -> wallet-service`：通过受保护的 `/api/v1/wallet/deposit-addresses/ensure` 提供入金地址幂等申请。
- `wallet-service -> trading-core-service`：通过 `falconx.wallet.deposit.confirmed / reversed` 驱动业务入金与回滚。
- `wallet-service -> MySQL`：owner 持久化地址、游标、链上原始事实。

## 当前边界与不应误写的内容

- 已归档的外部链真入金证据仍以 ETH 为主；TRC20 本轮完成 HTTP API 解析、扫块监听与 wallet 模块回归验证，尚未写入 Nile 真链入金 E2E 证据。
- 当前地址分配只覆盖每用户每链一个幂等入金地址，不得表述为“每次入金轮换新地址”或“多地址生产级隐私治理已完成”。
- 本 README 不承担项目阶段验收结论职责；不得再写“当前处于 `Stage 6A` 部分完成状态”之类过时表述。
- 本 README 不将更完整代币治理、多链全量能力或生产化节点治理写成当前已完成能力。

## 相关文档

- `docs/event/Kafka事件规范.md`
- `docs/database/falconx一期数据库设计.md`
- `docs/api/FalconX统一接口文档.md`
- `docs/setup/当前开发计划.md`
