package com.falconx.wallet.contract.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * STAGE-7-WITHDRAW Phase 3：`falconx.wallet.withdraw.broadcast` 事件 payload 契约。
 *
 * <p>wallet-service 在 KmsSigner 签名 + 链上广播成功后立即发布；先于链上确认。
 *
 * <p>下游消费者：trading-core-service 切 {@code t_withdraw_order.status=PROCESSING} +
 * 记录 {@code tx_hash}。按 {@code withdrawId} 幂等。
 *
 * @param withdrawId 出金单 ID
 * @param userId 用户 ID
 * @param network 链类型（ERC20 / TRC20）
 * @param txHash 链上交易哈希
 * @param nonce 链上 nonce
 * @param gasFeeUsd 预估或实际 gas 费用（USD 计价）
 * @param broadcastAt 广播完成时间
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WalletWithdrawBroadcastedEventPayload(
        Long withdrawId,
        Long userId,
        String network,
        String txHash,
        Long nonce,
        BigDecimal gasFeeUsd,
        OffsetDateTime broadcastAt
) {
}
