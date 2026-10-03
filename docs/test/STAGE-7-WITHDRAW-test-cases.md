# 阶段 7 出金链路测试用例清单（V1，2026-05-14）

> 本文件是 BBook 一期 V2 §10 阶段 7 STAGE-7-WITHDRAW 收口的 R6 测试用例骨架。
>
> 覆盖：trading-core 出金提交 / 列表 / 详情 / 取消 / 白名单 + admin internal RPC 审核 + Kafka 事件链（5 事件）+ wallet 链上签名 / nonce / 广播 / 确认 / 失败 + console 转发 + RBAC + 审计 + 客户端 Vitest + 三端 E2E。
>
> Phase 0 仅冻结清单（不落 @Test）；Phase 1-4 实施过程中 R6 把每条 TC 落为 `@Test` / `it()` 真代码。
>
> 关联文档：
> - [R2 客户端 REST §9.2](../api/REST接口规范.md#92-钱包出金-api-冻结契约stage-7-withdraw-phase-0)
> - [R2 管理端 §10](../api/管理端接口规范.md#10-阶段-7-出金审核已冻结2026-05-14-r2-phase-0)
> - [出金状态机 §7A](../domain/状态机规范.md#7a-出金状态机stage-7-withdraw-phase-0-冻结)
> - [KmsSigner §7B](../domain/状态机规范.md#7b-kmssigner-抽象stage-7-withdraw-phase-0-冻结)
> - [Kafka 事件 §12.8-12.12](../event/Kafka事件规范.md)
> - [R3 客户端设计](../design/STAGE-7-WITHDRAW-client-design.md)
> - [R3 管理端设计](../design/STAGE-7-WITHDRAW-console-design.md)
> - [SQL 蓝图](../sql/STAGE-7-WITHDRAW-blueprint.sql)

---

## §1. TC 编号块

| Prefix | 编号区间 | 数量 | 验收阶段 |
| --- | --- | --- | --- |
| `TC-WD-` | 001-099 | ~90 | 阶段 7 |
| `TC-E2E-WD-` | 001-005 | 5 | 阶段 7 |

---

## §2. 文档结构

| 节 | 范围 | TC 数 |
| --- | --- | --- |
| §3 trading-core 客户端 REST | POST/GET/cancel | 18 |
| §4 trading-core 白名单 REST | GET/POST/DELETE + 24h 冷静期 | 10 |
| §5 trading-core admin internal RPC | list / detail / approve / reject / emergency-cancel | 18 |
| §6 trading-core 调度器 | cooling / delayed / processing timeout | 6 |
| §7 trading Kafka 事件发布 | requested / reviewed | 6 |
| §8 trading Kafka 事件消费 | broadcast / confirmed / failed | 9 |
| §9 wallet 链上签名 / nonce / 广播 | KmsSigner + EthNonceManager + TronNonceManager | 10 |
| §10 wallet 链上失败 + DLQ | revert / timeout / nonce 冲突 | 6 |
| §11 console 转发 + 错误码翻译 | /admin/withdraw/* | 5 |
| §12 console RBAC + 审计 | 3 权限码 + OperationAuditAspect | 4 |
| §13 客户端 Vitest | KycSubmitDrawer 类似的 Drawer 4 态 + 白名单 + 历史 + 详情 | 12 |
| §14 三端 E2E | 5 个完整场景 | 5 |

合计 95 个 TC（90 IT/Unit + 5 E2E）。

---

## §3. trading-core 客户端 REST（POST /api/v1/me/withdraw / GET / cancel）

### TC-WD-001 正常提交 → COOLING
- 前置：user kyc_level=1，余额 1000 USDT，白名单 1 个 ACTIVE 地址
- 输入：amount=100.50，network=ERC20，targetAddress 匹配白名单
- 预期：code=0，status=COOLING，coolingUntil = now + 2h；`t_account.frozen` += 100.50；`t_ledger` 1 行 biz_type=12；`t_withdraw_order` 1 行 status=0

### TC-WD-002 KYC 未通过 → 30043
- 前置：kyc_level=0
- 预期：code=30043 WITHDRAW_KYC_REQUIRED，无落库

### TC-WD-003 余额不足 → 30054
- 前置：available=50，amount=100
- 预期：code=30054 WITHDRAW_BALANCE_INSUFFICIENT

### TC-WD-004 单笔超限 → 30041
- amount=10001
- 预期：code=30041 WITHDRAW_AMOUNT_EXCEEDS_SINGLE_LIMIT

### TC-WD-005 单日累计超限 → 30042
- 当日已 PENDING/APPROVED 累计 $29900；新提交 amount=$200
- 预期：code=30042

### TC-WD-006 大额自动延迟标记 → COOLING + requireDelayed=true
- amount=3000
- 预期：status=COOLING，但 admin approve 后会进入 APPROVED_DELAYED

### TC-WD-007 网络非法 → 30044
- network="BTC"
- 预期：code=30044

### TC-WD-008 地址格式非法 → 30045
- ERC20 + targetAddress="0xinvalid"
- 预期：code=30045

### TC-WD-009 地址不在白名单 → 30046
- 预期：code=30046

### TC-WD-010 白名单冷静期未过 → 30055
- 白名单 added_at = now - 1h
- 预期：code=30055

### TC-WD-011 提交重复幂等键 → 24h 内返回原 withdrawId
- 两次 POST 相同 X-Idempotency-Key
- 预期：第二次响应 withdrawId 与第一次相同，frozen 仅 +1 次

### TC-WD-012 GET 列表分页
- 创建 25 条
- 预期：page=2, size=10 返回 11-20

### TC-WD-013 GET 列表 status 过滤
- 预期：仅返回指定状态

### TC-WD-014 GET 详情 NOT_FOUND
- id 不存在 → 30047

### TC-WD-015 GET 详情非己 → 30047
- 用 userA 看 userB 的 withdrawId

### TC-WD-016 COOLING 用户取消成功
- 预期：status=CANCELED，frozen -= amount，biz_type=13

### TC-WD-017 PENDING 用户取消 → 30048
- 状态机不允许 PENDING 自助取消

### TC-WD-018 X-User-Id header 缺失 → 401/400/500
- 同 KYC TC-008 模式

---

## §4. trading-core 白名单 REST

### TC-WD-020 添加白名单 → PENDING 24h 冷静期
- 预期：status=PENDING，activated_at=null

### TC-WD-021 列表显示 ACTIVE 与 PENDING（带倒计时字段）

### TC-WD-022 超 10 条 ACTIVE → 30051

### TC-WD-023 重复添加（同 user+network+address+ACTIVE）→ 30052

### TC-WD-024 删除已 REMOVED 不存在 → 30053

### TC-WD-025 删除后重新添加同地址 → 允许（status=REMOVED 进入唯一键）

### TC-WD-026 ERC20 地址 EIP-55 校验

### TC-WD-027 TRC20 地址 Base58 校验

### TC-WD-028 白名单冷静期调度器（24h 后自动切到 ACTIVE）

### TC-WD-029 label 长度超 64 截断或拒绝

---

## §5. trading-core admin internal RPC

### TC-WD-040 列表无过滤
### TC-WD-041 列表 PENDING 优先 + APPROVED_DELAYED 次优先排序
### TC-WD-042 status / userId / network / amount 过滤组合
### TC-WD-043 详情含 admin 视图扩展字段（kycLevel + dailyAccumulatedUsd + 用户画像）
### TC-WD-044 详情 NOT_FOUND → 30047
### TC-WD-045 PENDING approve amount<$3000 → APPROVED
- 预期：发布 Kafka withdraw.reviewed (result=APPROVED)
### TC-WD-046 PENDING approve amount≥$3000 → APPROVED_DELAYED
- 预期：delayed_until=now+6h，发布 reviewed (result=APPROVED_DELAYED, delayedUntil 非空)
### TC-WD-047 PENDING reject 含 reason → REJECTED
- 预期：frozen -=, biz_type=14
### TC-WD-048 非 PENDING approve → 30049
### TC-WD-049 reject 空 reason → 30049（trading 层校验）或 console 90503
### TC-WD-050 APPROVED_DELAYED emergency-cancel → CANCELED
- 预期：frozen -=, biz_type=15
### TC-WD-051 非 APPROVED_DELAYED emergency-cancel → 30050
### TC-WD-052 并发 approve 同一 withdrawId（CAS）：一个成功一个 30049
### TC-WD-053 X-Admin-User-Id 缺失 → 拒绝
### TC-WD-054 reviewerId 写入 t_withdraw_order.reviewer_id
### TC-WD-055 reviewNote 写入字段
### TC-WD-056 emergency-cancel reason 写入 reject_reason
### TC-WD-057 approve 后查询 GET /api/v1/me/withdraw/{id} status 反映新值

---

## §6. trading-core 调度器

### TC-WD-060 WithdrawCoolingScheduler 自动 COOLING → PENDING
### TC-WD-061 cooling_until 未到 不切
### TC-WD-062 WithdrawDelayedScheduler APPROVED_DELAYED → APPROVED
### TC-WD-063 delayed_until 未到 不切
### TC-WD-064 WithdrawProcessingTimeoutScheduler 30min 超时 → 发起 wallet 失败回滚
### TC-WD-065 调度器并发安全：同一记录 CAS 仅一个调度成功

---

## §7. trading Kafka 事件发布

### TC-WD-070 提交后发布 trading.withdraw.requested（含 headers + payload）
### TC-WD-071 partition key = userId
### TC-WD-072 afterCommit 发布（事务回滚不发）
### TC-WD-073 approve 后发布 trading.withdraw.reviewed (result=APPROVED)
### TC-WD-074 reject 后发布 reviewed (result=REJECTED, rejectReason 非空)
### TC-WD-075 approve_delayed 后 reviewed payload delayedUntil 非空

---

## §8. trading Kafka 事件消费

### TC-WD-080 消费 wallet.withdraw.broadcast → status=PROCESSING + tx_hash 落库
### TC-WD-081 重复 broadcast 幂等（按 withdrawId）
### TC-WD-082 消费 wallet.withdraw.confirmed → status=COMPLETED + balance/frozen 扣减 + ledger biz_type=16
### TC-WD-083 重复 confirmed 幂等
### TC-WD-084 confirmed 时写 t_notification (kyc 通知机制类似) level=INFO
### TC-WD-085 消费 wallet.withdraw.failed → status=FAILED + frozen 回滚 + ledger biz_type=17
### TC-WD-086 failed 时写 t_notification level=WARN 含 failureReason
### TC-WD-087 broadcast/confirmed/failed payload 缺字段 → 拒绝 + DLQ
### TC-WD-088 confirmed 在 PROCESSING 之前到达（race）→ 重排或日志告警

---

## §9. wallet 链上签名 / nonce / 广播

### TC-WD-100 LocalKmsSigner ERC20 EIP-1559 签名后字节有效
### TC-WD-101 LocalKmsSigner TRC20 签名后字节有效
### TC-WD-102 KmsSignerStub 调用直接抛 UnsupportedOperationException
### TC-WD-103 EthNonceManager 启动时拉取链上 nonce
### TC-WD-104 EthNonceManager 并发广播 nonce 递增不重复
### TC-WD-105 TronNonceManager 同上
### TC-WD-106 多副本 Redis 串行化（一期 single instance 跳过；预留）
### TC-WD-107 广播成功发布 wallet.withdraw.broadcast
### TC-WD-108 t_withdraw_tx 写入 status=BROADCAST + tx_hash + nonce + gas_price
### TC-WD-109 监听确认数推进 → confirmation 字段更新

---

## §10. wallet 链上失败 + DLQ

### TC-WD-120 nonce 冲突（链上已存在）→ 失败 20010
### TC-WD-121 RPC 广播 5xx → 失败 20011 + 重试 3 次
### TC-WD-122 30 min 等待 receipt 超时 → 20012
### TC-WD-123 receipt status=0 (revert) → 20013
### TC-WD-124 KMS 不可达 → 20014 + 不发布 broadcast，发布 failed
### TC-WD-125 失败事件进入 DLQ topic

---

## §11. console 转发 + 错误码翻译

### TC-WD-140 /admin/withdraws 转发 status/userId/network/amount/page/size
### TC-WD-141 详情透传
### TC-WD-142 approve trading 10049 → console 90501
### TC-WD-143 reject reason 空 → 本地 90503 不调 trading
### TC-WD-144 emergency-cancel trading 30050 → console 90502

---

## §12. console RBAC + 审计

### TC-WD-150 无 withdraw:view 列表 → 90004
### TC-WD-151 有 withdraw:view 但无 withdraw:review approve → 90004
### TC-WD-152 有 withdraw:review 但无 withdraw:emergency-cancel → 90004
### TC-WD-153 approve / reject / emergency-cancel 写 t_admin_operation_log 含 risk_level=HIGH/CRITICAL

---

## §13. 客户端 Vitest

### TC-WD-160 WithdrawDrawer kyc_level=0 → 红 banner + 跳 KYC 按钮
### TC-WD-161 WithdrawDrawer 无白名单 → 跳白名单管理按钮
### TC-WD-162 WithdrawDrawer 表单渲染 + 金额单位 + USD 等值
### TC-WD-163 amount > $10K → submit disabled + 红字提示
### TC-WD-164 amount >= $3000 → 黄色"延迟"提示横幅
### TC-WD-165 提交成功后切到只读视图 + 立即取消按钮
### TC-WD-166 WhitelistDrawer 列表 + ACTIVE/PENDING 徽章
### TC-WD-167 删除白名单二次确认 modal
### TC-WD-168 历史 tab 状态徽章颜色映射
### TC-WD-169 详情 modal COMPLETED 显示 tx_hash + 区块浏览器链接
### TC-WD-170 FAILED 显示 failureReason
### TC-WD-171 WebSocket falconx:notification:created 触发 invalidate

---

## §14. 三端 E2E

### TC-E2E-WD-001 小额完整链路 COOLING → PENDING → APPROVED → PROCESSING → COMPLETED
- 用户提交 $100 → 等冷静期 → admin approve → wallet 模拟签名+广播+确认 → 用户查询状态=COMPLETED + 站内信 toast

### TC-E2E-WD-002 大额延迟 APPROVED_DELAYED → admin emergency-cancel
- 用户提交 $5000 → 冷静期到 PENDING → admin approve → 进入 APPROVED_DELAYED → admin 紧急取消 → 余额回滚

### TC-E2E-WD-003 admin reject 流程
- 用户提交 → 冷静期到 PENDING → admin reject with reason → 用户看到 REJECTED + reason + 余额回滚

### TC-E2E-WD-004 用户冷静期取消
- 用户提交 → 立即取消 → CANCELED + 余额回滚

### TC-E2E-WD-005 链上失败回滚
- 用户提交 → admin approve → wallet 模拟链上 timeout → FAILED + 余额回滚 + 站内信 WARN

---

## §15. 实施落地表（Phase 0 → Phase N 推进时维护）

| TC 范围 | 实施类:方法 | 状态 |
| --- | --- | --- |
| TC-WD-001~018 | `TradingWithdrawApplicationServiceIntegrationTests` + `UserWithdrawControllerIntegrationTests` | 待落（Phase 1） |
| TC-WD-020~029 | `TradingWithdrawWhitelistIntegrationTests` | 待落（Phase 1） |
| TC-WD-040~057 | `TradingAdminInternalWithdrawControllerIntegrationTests` | 待落（Phase 1） |
| TC-WD-060~065 | `WithdrawCoolingSchedulerTests` + `WithdrawDelayedSchedulerTests` + `WithdrawProcessingTimeoutSchedulerTests` | 待落（Phase 2） |
| TC-WD-070~075 | `TradingWithdrawEventPublisherIntegrationTests` | 待落（Phase 2） |
| TC-WD-080~088 | `TradingWithdrawKafkaConsumerIntegrationTests`（真 docker Kafka + 真 DB） | 待落（Phase 3） |
| TC-WD-100~109 | `LocalKmsSignerTests` + `EthNonceManagerTests` + `TronNonceManagerTests` + `WalletWithdrawBroadcastIntegrationTests` | 待落（Phase 3） |
| TC-WD-120~125 | `WalletWithdrawFailureIntegrationTests` + `WalletWithdrawDlqTests` | 待落（Phase 3） |
| TC-WD-140~144 | `AdminWithdrawEndpointIntegrationTests` (console) | 待落（Phase 4） |
| TC-WD-150~153 | 同上 + `OperationAuditAspectTests`（baseline 已覆盖 framework） | 待落（Phase 4） |
| TC-WD-160~171 | `WithdrawDrawer.test.tsx` + `WhitelistDrawer.test.tsx` + `WithdrawHistory.test.tsx` | 待落（Phase 4） |
| TC-E2E-WD-001~005 | 真服务 curl + Playwright 浏览器整链；归档到 STAGE-7-WITHDRAW R7 验证报告 | 待落（Phase 4） |
