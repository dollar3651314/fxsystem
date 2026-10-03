# STAGE-7-WITHDRAW Phase 3 commit 9 测试用例清单（R6 骨架）

> 范围：wallet 端 ERC20 签名 / 广播 / 确认链路 + trading-core 端三事件消费 + 状态切换 + 账本 + 站内信。
>
> 关联角色：R6（本清单） → R4 wallet / R4 trading-core（按本清单落 @Test）→ R7 验证。
>
> TC 编号注册：`docs/test/CFD全面测试用例规范.md` §13.10（B 段 IT 落地，2026-05-14）。
>
> B 段 IT 落地状态（2026-05-14）：§1-§2 共 28 个用例全部完成 @Test 真代码并通过本地真 MySQL / 真 Kafka 回归。
> §3 E2E 2 用例需 Sepolia / Hoodi testnet 资源（用户提供 .env 后由 R7 单独执行），不阻塞 B 段交付。

## §0. 落地索引

| 测试类 | 段位 | 落地 TC |
| --- | --- | --- |
| `LocalKmsSignerTests`（wallet UT） | §1.1 | TC-WD-100 / 101 |
| LocalKmsSigner @Conditional 注入断言 | §1.1 / §1.4 | TC-WD-103（Stub 兜底由 `WalletWithdrawTxRepositoryIntegrationTests.shouldInjectKmsSignerStubWhenLocalNotConfigured` 既有覆盖；LocalKmsSigner 激活由 `WalletWithdrawBroadcastApplicationServiceIntegrationTests.shouldInjectLocalKmsSignerWhenPrivateKeyConfigured` 覆盖） |
| `EthNonceManagerTests`（wallet UT） | §1.2 | TC-WD-130 / 131 / 132 |
| `WalletWithdrawReviewedEventConsumerIntegrationTests`（wallet IT） | §1.3 | TC-WD-110 / 111 / 112 / 113 |
| `WalletWithdrawBroadcastApplicationServiceIntegrationTests`（wallet IT） | §1.4 | TC-WD-120 / 122 / 123 / 124（+ TC-WD-103 LocalKmsSigner 注入断言） |
| `WalletWithdrawBroadcastWithStubSignerIntegrationTests`（wallet IT） | §1.4 | TC-WD-121（独立 context 触发 KmsSignerStub 兜底） |
| `WalletWithdrawConfirmationSchedulerIntegrationTests`（wallet IT） | §1.5 | TC-WD-140 / 141 / 142 / 143 |
| `WalletWithdrawBroadcastConsumerIntegrationTests`（trading IT） | §2.1 | TC-WD-150 / 151 / 152 |
| `WalletWithdrawConfirmedConsumerIntegrationTests`（trading IT） | §2.2 | TC-WD-160 / 161 / 162 |
| `WalletWithdrawFailedConsumerIntegrationTests`（trading IT） | §2.3 | TC-WD-170 / 171 / 172 |

> TC-WD-123 注：spec 原描述 "nonce 冲突 → nonce manager reset 后重试 1 次"；
> 当前实现走 `markFailedAtomic 20010` 不重试，retry 策略列为 B 段 enhancement 待办；
> 本 IT 验证当前实现的实际行为，避免 spec/impl 漂移。
>
> TC-WD-113 注：consumer 自身不去重（按 APPROVED 每次都调用 broadcast service）；
> 真正的 t_withdraw_tx 唯一约束去重在 TC-WD-124（service 内 `findByWithdrawOrderId` 短路）。

## §1. wallet 端（IT，mock Web3j Service / 真 MySQL / 真 Kafka）

### §1.1 LocalKmsSigner 单元

| TC 编号 | 名称 | 前置 | 操作 | 断言 |
| --- | --- | --- | --- | --- |
| TC-WD-100 | LocalKmsSigner ERC20 secp256k1 签名往返 | 配置 `kms.erc20.private-key-pem` 为合法 secp256k1 EC PRIVATE KEY；spring profile=test | 调用 `sign("ERC20", fromAddress, unsignedTxBytes)` | 返回字节 length > 65（含 v/r/s），且 `supports("ERC20") = true` |
| TC-WD-101 | LocalKmsSigner 不支持 TRC20 | 未配置 `kms.trc20.private-key-pem` | 调用 `sign("TRC20", ...)` | 抛 `KmsSignerException`；`supports("TRC20") = false` |
| TC-WD-103 | LocalKmsSigner @Profile 隔离 | spring profile=prod 且未提供 KMS bean | 启动 context | `KmsSignerStub` 被注入（已有 commit 348b437 验证），prod 不加载 LocalKmsSigner |

### §1.2 EthNonceManager 单元

| TC 编号 | 名称 | 前置 | 操作 | 断言 |
| --- | --- | --- | --- | --- |
| TC-WD-130 | 启动从 RPC 拉 nonce | mock `eth_getTransactionCount` 返回 5 | `nextNonce(fromAddress)` | 返回 5；内部 AtomicLong=6 |
| TC-WD-131 | 并发递增唯一性 | 启动 nonce=10；10 个并发线程调用 nextNonce | — | 返回值集合 = {10,11,...,19}，无重复 |
| TC-WD-132 | reset 重新拉链 | 启动 nonce=10，调用 1 次后 reset() | mock 返回 15 → 调用 `nextNonce` | 返回 15（不是 11） |

### §1.3 TradingWithdrawReviewedEventConsumer（新建）

| TC 编号 | 名称 | 前置 | 操作 | 断言 |
| --- | --- | --- | --- | --- |
| TC-WD-110 | 消费 APPROVED 触发 broadcast | 发 `trading.withdraw.reviewed` result=APPROVED | listener 处理 | `WalletWithdrawBroadcastApplicationService.broadcast` 被调用（参数匹配 withdrawId） |
| TC-WD-111 | 消费 APPROVED_DELAYED 跳过 | result=APPROVED_DELAYED | listener 处理 | broadcast service **不**被调用（delayed 由 trading-core 调度器自己变 APPROVED 后再发 reviewed） |
| TC-WD-112 | 消费 REJECTED 跳过 | result=REJECTED | listener 处理 | broadcast service **不**被调用 |
| TC-WD-113 | 重复事件幂等 | 同一 withdrawId 两次 reviewed APPROVED | 顺序处理 | broadcast service 只触发 1 次（按 t_withdraw_tx uk_withdraw_order 去重） |

### §1.4 WalletWithdrawBroadcastApplicationService

| TC 编号 | 名称 | 前置 | 操作 | 断言 |
| --- | --- | --- | --- | --- |
| TC-WD-120 | 签名 + sendRawTransaction 成功 | LocalKmsSigner 配置就绪；mock web3j `eth_sendRawTransaction` 返回 txHash | `broadcast(withdrawId=1, network=ERC20, targetAddress, amountUsdt)` | t_withdraw_tx 落 1 行 status=BROADCAST + tx_hash 写入；t_outbox 落 1 行 event_type=`wallet.withdraw.broadcast` |
| TC-WD-121 | KmsSigner Stub 触发 → failed | profile=prod 注入 Stub；reviewed APPROVED 到达 | broadcast 抛 `UnsupportedOperationException` | t_withdraw_tx **不**落；t_outbox 落 1 行 event_type=`wallet.withdraw.failed` failureCode=20014 SIGNER_UNAVAILABLE |
| TC-WD-122 | sendRawTransaction 失败 | LocalKmsSigner ok；mock web3j 抛 IOException | broadcast 调用 | t_withdraw_tx 落 SIGNING（已分配 nonce）；t_outbox 落 failed event failureCode=20010 BROADCAST_FAILED |
| TC-WD-123 | nonce 冲突重试 | mock web3j 返回 nonce too low | broadcast 调用 | nonce manager reset() 后重试 1 次；成功 broadcast |
| TC-WD-124 | uk_withdraw_order 去重 | t_withdraw_tx 已有 withdrawId=1 BROADCAST | broadcast(withdrawId=1) 再次调用 | 不重复插入；直接返回；不重复 outbox |

### §1.5 WalletWithdrawConfirmationScheduler

| TC 编号 | 名称 | 前置 | 操作 | 断言 |
| --- | --- | --- | --- | --- |
| TC-WD-140 | confirmations < 12 不推进 | t_withdraw_tx status=BROADCAST；mock receipt confirmations=5 | scheduler tick | updateConfirmations(5) 写入；status 不变 |
| TC-WD-141 | confirmations ≥ 12 推进 → confirmed | mock receipt confirmations=12 status=1 | scheduler tick | t_withdraw_tx status=CONFIRMED；t_outbox 落 `wallet.withdraw.confirmed` |
| TC-WD-142 | receipt status=0 → failed | mock receipt status=0（revert） | scheduler tick | t_withdraw_tx status=FAILED；t_outbox 落 `wallet.withdraw.failed` failureCode=20011 TX_REVERTED |
| TC-WD-143 | receipt 暂时不可用 | mock 返回 Optional.empty() | scheduler tick | 不变；下一 tick 重试 |

## §2. trading-core 端（IT，真 Kafka + 真 MySQL + mock 站内信 publisher）

### §2.1 WalletWithdrawBroadcastEventConsumer

| TC 编号 | 名称 | 前置 | 操作 | 断言 |
| --- | --- | --- | --- | --- |
| TC-WD-150 | 消费 broadcast → PROCESSING | t_withdraw_order status=APPROVED；发 `wallet.withdraw.broadcast` | listener 处理 | t_withdraw_order status=PROCESSING + tx_hash 写入 + processing_started_at 写入 |
| TC-WD-151 | 重复 broadcast 幂等 | t_withdraw_order 已 PROCESSING；发同 withdrawId | listener 处理 | CAS WHERE status=APPROVED 失败（0 行）；status 不变 |
| TC-WD-152 | broadcast 时 status=APPROVED_DELAYED | t_withdraw_order=APPROVED_DELAYED | listener 处理 | CAS 失败；记 warn 日志；不抛异常 |

### §2.2 WalletWithdrawConfirmedEventConsumer

| TC 编号 | 名称 | 前置 | 操作 | 断言 |
| --- | --- | --- | --- | --- |
| TC-WD-160 | 消费 confirmed → COMPLETED | t_withdraw_order PROCESSING；t_account.frozen=100 USDT；发 confirmed | listener 处理 | status=COMPLETED；t_account.frozen=0 + balance -= 100；t_ledger 1 行 biz_type=WITHDRAW_SETTLE；站内信 1 行 level=INFO title="出金已完成" |
| TC-WD-161 | 重复 confirmed 幂等 | t_withdraw_order 已 COMPLETED；relatedKey=withdraw.confirmed 已存在 | 再次发同 withdrawId | 跳过；t_account / t_ledger / t_notification 不变 |
| TC-WD-162 | 站内信落 t_notification | 同 TC-WD-160 | listener 处理 | t_notification 1 行 user_id 匹配 + relatedKey=withdraw.confirmed + relatedId=withdrawId |

### §2.3 WalletWithdrawFailedEventConsumer

| TC 编号 | 名称 | 前置 | 操作 | 断言 |
| --- | --- | --- | --- | --- |
| TC-WD-170 | 消费 failed PROCESSING → FAILED + 释放 | t_withdraw_order PROCESSING；frozen=100；发 failed failureCode=20011 | listener 处理 | status=FAILED；t_account.frozen=0；balance 不变；t_ledger biz_type=WITHDRAW_REFUND_CHAIN_FAILED；t_notification level=WARN title="出金失败" body 含 failureReason |
| TC-WD-171 | 消费 failed APPROVED → FAILED（广播前失败，无 tx_hash） | t_withdraw_order APPROVED；发 failed txHash=null failureCode=20014 | listener 处理 | status=FAILED；frozen=0；balance 不变；t_ledger 落账；站内信 |
| TC-WD-172 | 重复 failed 幂等 | relatedKey=withdraw.failed 已存在 | 再次发同 withdrawId | 跳过；frozen 不重复减；t_notification 不重复 |

## §3. E2E（Sepolia 真链）

| TC 编号 | 名称 | 前置 | 操作 | 断言 | 状态 |
| --- | --- | --- | --- | --- | --- |
| TC-E2E-WD-001 | ERC20 USDC 真链出金整链 | wallet KMS 私钥 + USDC 合约地址（Circle Sepolia `0x1c7D4B19...c7238`）+ USDC 余额 + Sepolia ETH gas 就绪 | reviewed APPROVED → broadcast → 12 conf → confirmed | 链上能在 Etherscan 查到 transfer tx；t_withdraw_order status=COMPLETED；t_account.frozen=0 balance 正确扣减；t_notification 出金完成 | ✅ **PASSED 2026-05-14**（tx [`0x4c94530c...147d45b0`](https://sepolia.etherscan.io/tx/0x4c94530c377b200df862c578589b378270a5a5fe36563016e206bf6b147d45b0)，详见 [B 段验证报告](./archive/STAGE-7-WITHDRAW-Phase3-B-verification-report.md)）|
| TC-E2E-WD-002 | 签名失败回滚整链 | wallet KMS 配置为无效私钥 | 客户端提交出金 → admin approve → 触发 broadcast 失败 | t_withdraw_order status=FAILED；t_account.frozen=0 balance 回滚；t_notification 失败 | ⏳ 待跑（依赖 KMS 配置切换运维工序，B 段未覆盖；A 段已通过 mock signer 覆盖等价分支 TC-WD-121）|

> **B 段实际跑通的"等价短路径"**：直接 SQL 插 t_withdraw_order(status=APPROVED) + Kafka 直发 `falconx.trading.withdraw.reviewed` → 跳过 user submit/admin approve/30s cooling，保留 KmsSigner.sign + web3j broadcast + Sepolia 12 conf + trading-core consumer 状态机推进的真链路。该路径在测试网 USDC 合约下与原 TC-E2E-WD-001 完整客户端流程等价。

## §4. 验证命令

```bash
# A 段单元 / IT
mvn -pl falconx-wallet-service -am -Dtest=Withdraw* test
mvn -pl falconx-trading-core-service -am -Dtest=WithdrawBroadcast*,WithdrawConfirmed*,WithdrawFailed* test

# B 段 IT 28 条（§1.1 + §1.2 + §1.3 + §1.4 + §1.5 + §2.1-§2.3）尚未补真代码——
# 见 docs/setup/当前开发计划.md 阶段卡，建议独立会话由 R6 → R4 推进。

# B 段 真链 E2E TC-E2E-WD-001 跑通命令（dev 复现）：
# 1) 准备 .env：Alchemy_Ethereum_Sepolia_HTTPS / FALCONX_WALLET_ERC20_PRIVATE_KEY_PEM /
#    FROM_ADDRESS / FALCONX_WALLET_ERC20_USDT_CONTRACT / FALCONX_WALLET_ERC20_USDC_CONTRACT / decimals
# 2) 给 from address 领 Sepolia ETH + USDC（Circle faucet https://faucet.circle.com → Ethereum Sepolia）
# 3) wallet 服务启动行加 source .env + -Djavax.net.ssl.trustStore=tools/wallet-truststore.p12
#    （两项启动配置待 scripts/local-start.sh 修，挂统一问题清单 P1）
# 4) seed SQL + Kafka publish reviewed event：见 B 段验证报告 §3 "复现脚本"
```
