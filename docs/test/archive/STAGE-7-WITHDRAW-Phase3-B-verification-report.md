# STAGE-7-WITHDRAW Phase 3 B 段 真链 E2E 验证报告

> 验证范围：基于 commit `70f0a53`（Phase 3 A 段）+ 本会话 P0 修复 `df9b0cf`（outbox topicOf 补 3 个 withdraw 事件映射）+ 文档同步 `1a05e1b`，对 TC-E2E-WD-001 在 Sepolia 真链上的端到端验证。
>
> 验证时间：2026-05-14
>
> **本报告是 STAGE-7-WITHDRAW Phase 3 B 段 真链 E2E 收口判定依据**。Phase 3 整体未"完全合上"——B 段 IT 28 条仍待补；本报告仅认定真链链路验证项已通过，IT 补码工作另行启动。

---

## 1. 链上证据

**tx hash**: `0x4c94530c377b200df862c578589b378270a5a5fe36563016e206bf6b147d45b0`

**etherscan**: https://sepolia.etherscan.io/tx/0x4c94530c377b200df862c578589b378270a5a5fe36563016e206bf6b147d45b0

| 字段 | 值 |
| --- | --- |
| chainId | 11155111（Sepolia） |
| from | `0x7a58b441a0bbff8186c2949497303199d1eb151d`（热钱包，本会话生成） |
| to | `0x1c7D4B196Cb0C7B01d743Fbc6116a902379C7238`（Circle 官方 Sepolia USDC 合约） |
| nonce | 3 |
| input | `0xa9059cbb...`（ERC20 `transfer(address,uint256)`） |
| target | `0xB010093f3e20334962023D9cc26D51c1E7F3BB51`（throwaway，仅用于接收测试转账） |
| amount | `10000000`（10 USDC，decimals=6） |
| status | **1（成功）** |
| blockNumber | 10850713 |
| gasUsed | 62159 |
| logs | 1 行 Transfer event emit |

链上余额变化（验证转账实际发生）：

- 热钱包：40 USDC → **30 USDC**
- 目标地址：0 USDC → **10 USDC**

---

## 2. 后端状态变化（DB 终态）

### 2.1 `t_withdraw_order` (trading-core)

| 字段 | 值 | 含义 |
| --- | --- | --- |
| id | 900000001 | seed 数据 ID（独立段位避免与生产数据冲突） |
| user_id | 48275236470263808 | 已 KYC 用户（kyc_level=1） |
| amount | 10.00000000 | 10 USDC |
| network | ERC20 | |
| target_address | `0xB010093f3e20334962023D9cc26D51c1E7F3BB51` | |
| **status** | **5（COMPLETED）** | 由 trading-core 的 `WalletWithdrawConfirmedEventConsumer` 通过 `markCompletedFromProcessingAtomic` CAS 推进 |
| **tx_hash** | `0x4c94530c...147d45b0` | broadcast event consumer 写入 |
| confirmations | 14 | scheduler 检测时已到 14 块（> 12 threshold） |
| processing_started_at | 2026-05-14 18:48:36.501 | |

### 2.2 `t_account` (trading-core)

| 字段 | 初始（seed） | 最终 | 变化 |
| --- | --- | --- | --- |
| balance | 30.00000000 | **20.00000000** | -10（confirmed 扣减提现额） |
| frozen | 10.00000000 | **0.00000000** | -10（confirmed 释放冻结） |
| version | 1 | **3** | CAS 累加 2 次（broadcast + confirmed） |

### 2.3 `t_notification` (trading-core)

| 字段 | 值 |
| --- | --- |
| id | 48361526821785600 |
| user_id | 48275236470263808 |
| type | withdraw.confirmed |
| level | 1（INFO） |
| related_key | withdraw.confirmed |
| related_id | 900000001 |
| created_at | 2026-05-14 18:51:26.508 |

---

## 3. 复现脚本

### 3.1 .env 必备 5 项

```bash
Alchemy_Ethereum_Sepolia_HTTPS=https://eth-sepolia.g.alchemy.com/v2/<your-key>
FALCONX_WALLET_ERC20_PRIVATE_KEY_PEM=<hex private key, 64 字符>
FALCONX_WALLET_ERC20_FROM_ADDRESS=0x<对应地址>
FALCONX_WALLET_ERC20_USDT_CONTRACT=0x1c7D4B196Cb0C7B01d743Fbc6116a902379C7238   # Circle Sepolia USDC
FALCONX_WALLET_ERC20_USDT_DECIMALS=6
```

### 3.2 wallet 服务启动行（临时绕过 [FX-072] 和 [FX-073]）

```bash
nohup bash -c 'set -a && source .env && set +a && \
  exec env FALCONX_WALLET_DB_USERNAME=root FALCONX_WALLET_DB_PASSWORD=root \
    FALCONX_WALLET_ETH_ACCOUNT_XPUB="..." FALCONX_WALLET_TRON_ACCOUNT_XPUB="..." \
    java -Djavax.net.ssl.trustStore=tools/wallet-truststore.p12 \
         -Djavax.net.ssl.trustStorePassword=changeit \
         -jar falconx-wallet-service/target/falconx-wallet-service-1.0.0-SNAPSHOT.jar \
         --falconx.wallet.chains.eth.scan-interval=1h \
         --falconx.wallet.chains.bsc.scan-interval=1h \
         --falconx.wallet.chains.tron.scan-interval=1h \
         --falconx.wallet.chains.sol.scan-interval=1h' \
  >> logs/local/wallet-service.log 2>&1 </dev/null &
```

### 3.3 种子数据 SQL

```sql
-- 1) wallet 白名单 ACTIVE
INSERT INTO falconx_wallet.t_withdraw_whitelist
    (id, user_id, network, address, label, status, activated_at, created_at, updated_at)
VALUES (900000001, 48275236470263808, 'ERC20',
        '0xB010093f3e20334962023D9cc26D51c1E7F3BB51',
        'e2e-target', 1, NOW(3), NOW(3), NOW(3))
ON DUPLICATE KEY UPDATE status=1, activated_at=NOW(3);

-- 2) trading account：balance=30 + frozen=10（= 40 USDC，匹配链上）
UPDATE falconx_trading.t_account
SET balance=30, frozen=10, version=version+1, updated_at=NOW(3)
WHERE user_id=48275236470263808 AND currency='USDT';

-- 3) trading withdraw_order：status=APPROVED 直接进入广播链路
INSERT INTO falconx_trading.t_withdraw_order
    (id, user_id, amount, currency, network, target_address, whitelist_id, status,
     cooling_until, reviewer_id, review_at, idempotency_key, daily_amount_usd_snapshot,
     confirmations, created_at, updated_at)
VALUES (900000001, 48275236470263808, 10, 'USDT', 'ERC20',
        '0xB010093f3e20334962023D9cc26D51c1E7F3BB51', 900000001,
        2, DATE_SUB(NOW(3), INTERVAL 5 MINUTE), 1, NOW(3),
        'e2e-2026-05-14-001', 10, 0, NOW(3), NOW(3));
```

### 3.4 触发 Kafka reviewed 事件

```bash
echo '{"withdrawId":900000001,"userId":48275236470263808,"result":"APPROVED",
       "reviewerId":1,"reviewAt":"2026-05-14T18:35:00+08:00",
       "rejectReason":null,"delayedUntil":null,
       "amount":10.00000000,"currency":"USDT","network":"ERC20",
       "targetAddress":"0xB010093f3e20334962023D9cc26D51c1E7F3BB51"}' | \
  docker exec -i falconx-kafka /opt/kafka/bin/kafka-console-producer.sh \
    --bootstrap-server localhost:9092 \
    --topic falconx.trading.withdraw.reviewed
```

### 3.5 时间线

| 时刻 | 事件 |
| --- | --- |
| 18:48:35 | wallet `TradingWithdrawReviewedEventConsumer` 收到 APPROVED |
| 18:48:36 | `LocalKmsSigner.sign` + web3j `eth_sendRawTransaction` → tx hash 返回 |
| 18:48:36 | outbox 写入 `wallet.withdraw.broadcast` + `KafkaWalletEventPublisher` 发送 |
| 18:48:36 | trading-core `WalletWithdrawBroadcastEventConsumer` CAS APPROVED→PROCESSING |
| 18:51:26 | wallet `WalletWithdrawConfirmationScheduler` 检测 14 conf → `markConfirmedFromBroadcastAtomic` → publish `wallet.withdraw.confirmed` |
| 18:51:26 | trading-core `WalletWithdrawConfirmedEventConsumer` CAS PROCESSING→COMPLETED + `settleConfirmedWithdraw`（balance -= 10，frozen -= 10）+ `t_notification` 写入 + WS push |

整链耗时：**约 2 分 50 秒**（受 Sepolia 12 块确认 + ConfirmationScheduler 30s tick 主导）。

---

## 4. 七项断言验证矩阵

来源：[`STAGE-7-WITHDRAW-Phase3-test-cases.md`](../STAGE-7-WITHDRAW-Phase3-test-cases.md) §3 TC-E2E-WD-001 断言扩展。

| # | 断言 | 验证方式 | 实际值 | ✓ |
| --- | --- | --- | --- | --- |
| 1 | 链上能在 Etherscan 查到 transfer tx | `provider.getTransaction(txHash)` + Etherscan | tx `0x4c94530c...147d45b0` block 10850713 | ✅ |
| 2 | Transfer event 已 emit | `receipt.logs.length` | 1 | ✅ |
| 3 | t_withdraw_order.status=COMPLETED | DB 查询 | status=5（COMPLETED） | ✅ |
| 4 | tx_hash 写入 t_withdraw_order | DB 查询 | `0x4c94530c...147d45b0` | ✅ |
| 5 | t_account.frozen=0 | DB 查询 | 0.00000000 | ✅ |
| 6 | t_account.balance 正确扣减 | DB 查询 | 30 → 20（-10 提现额） | ✅ |
| 7 | t_notification 出金完成 | DB 查询 | id=48361526821785600 type=withdraw.confirmed level=INFO | ✅ |
| 8 | 链上 USDC 转移验证（实际余额变化） | `usdc.balanceOf(from/target)` | from 40→30，target 0→10 | ✅ |

---

## 5. 副产物（B 段挖出的 4 个问题）

| 严重度 | Bug / 议题 | 状态 |
| --- | --- | --- |
| 🚨 **P0** | `MybatisWalletOutboxRepository.topicOf` 漏 3 个 withdraw 事件 topic 映射 → broadcast 链路一启动就抛 `IllegalStateException`，且 publishFailNoTx 吞没原始失败原因 | ✅ **已修复**（commit `df9b0cf`） |
| 🚨 P1 | `scripts/local-start.sh:83` wallet 启动不 source .env → Sepolia env 全部缺失 | ⏳ 已挂 [FX-072] |
| 🚨 P1 | WSL 公司代理 MITM Alchemy TLS（LifeByte root CA 不在 Java cacerts）→ web3j IOError | ⏳ 已挂 [FX-073]，可用 truststore 已生成 `tools/wallet-truststore.p12`（.gitignored） |
| ℹ️ Design | t_withdraw_order 一旦因瞬时错误进 FAILED，后续 broadcast 事件 CAS 会被跳过，没有"重置 + 重试"工具 | 已知，TC-WD-172 文档化为幂等行为；Phase 4 admin 工作台应配套提供 |

---

## 6. 资源消耗

- **Sepolia ETH**: 约 0.0002 ETH gas（含一次因金额超余额导致的链上 revert 试错）
- **Sepolia USDC**: 转出 10 USDC 至 throwaway 地址
- **会话时长**: 约 30 分钟（含 Maven 编译 wallet + trading-core JAR 两次、TLS 排查 + 余额不足 revert 后重跑）

---

## 7. 仍待完成（不阻塞 Phase 4 启动）

1. **B 段 IT 28 条**：覆盖 LocalKmsSigner / EthNonceManager / TradingWithdrawReviewedEventConsumer / WalletWithdrawBroadcastApplicationService / WalletWithdrawConfirmationScheduler / 3 个 trading-core consumer 全部分支。详见 [`Phase3-test-cases`](../STAGE-7-WITHDRAW-Phase3-test-cases.md) §1.1-§2.3。建议独立会话由 R6 → R4 推进，使用 mock Web3j，不依赖真链。
2. **TC-E2E-WD-002**（签名失败回滚整链）：依赖把 KMS 配置切到无效私钥并触发 broadcast 失败的运维工序；A 段已通过 mock signer 覆盖等价分支 TC-WD-121，B 段未额外覆盖。
3. **[FX-072]** wallet 启动行 source .env
4. **[FX-073]** wallet 启动行加 `-Djavax.net.ssl.trustStore`
5. **TRC20 真链 E2E**：需补 .env Tron Nile testnet RPC + TRC20 USDT 合约 + 测试网 TRX gas + 测试 USDT 余额，推迟到 TRC20 接入会话。
6. **Phase 4**：管理端 console `/admin/withdraws/*` 透传 + console-frontend 审核工作台 + 高危二次确认弹窗。R2 R3 R6 R9 R10 协作推进。
