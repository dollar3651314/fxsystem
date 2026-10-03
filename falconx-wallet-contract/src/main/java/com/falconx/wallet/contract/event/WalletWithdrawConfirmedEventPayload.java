package com.falconx.wallet.contract.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.OffsetDateTime;

/**
 * STAGE-7-WITHDRAW Phase 3：`falconx.wallet.withdraw.confirmed` 事件 payload 契约。
 *
 * <p>wallet-service 在链上确认数达到 {@code min_confirmations}（ERC20 12 / TRC20 19）后发布。
 *
 * <p>下游消费者：trading-core-service 结算余额 — {@code t_account.frozen -= amount} +
 * {@code t_account.balance -= amount} + {@code t_ledger.biz_type=WITHDRAW_SETTLE} +
 * {@code t_withdraw_order.status=COMPLETED}。幂等键 {@code relatedKey='withdraw.confirmed' +
 * relatedId=withdrawId}。
 *
 * @param withdrawId 出金单 ID
 * @param userId 用户 ID
 * @param network 链类型（ERC20 / TRC20）
 * @param txHash 链上交易哈希
 * @param blockNumber 区块高度
 * @param confirmations 当前确认数
 * @param confirmedAt 确认完成时间
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WalletWithdrawConfirmedEventPayload(
        Long withdrawId,
        Long userId,
        String network,
        String txHash,
        Long blockNumber,
        int confirmations,
        OffsetDateTime confirmedAt
) {
}
