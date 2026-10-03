package com.falconx.wallet.entity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * STAGE-7-WITHDRAW Phase 3：链上出金交易实体（wallet-service owner）。
 *
 * <p>一个 {@code withdrawOrderId} 仅对应一条 tx（uk_withdraw_order），同链 + 同 fromAddress 的
 * {@code nonce} 全局唯一（uk_network_nonce）。{@code txHash} 在广播成功后填入，全局唯一。
 */
public record WalletWithdrawTx(
        Long id,
        Long withdrawOrderId,
        Long userId,
        String network,
        String fromAddress,
        String targetAddress,
        BigDecimal amount,

        long nonce,
        BigDecimal gasPrice,
        BigDecimal gasUsed,
        BigDecimal gasFeeUsd,

        String txHash,
        Long blockNumber,
        int confirmations,

        WalletWithdrawTxStatus status,
        String failureCode,
        String failureReason,

        OffsetDateTime broadcastAt,
        OffsetDateTime confirmedAt,
        OffsetDateTime failedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
