package com.falconx.wallet.repository.mapper.record;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * STAGE-7-WITHDRAW Phase 3：链上出金交易 MyBatis 记录对象。
 */
public record WalletWithdrawTxRecord(
        Long id,
        Long withdrawOrderId,
        Long userId,
        String network,
        String fromAddress,
        String targetAddress,
        BigDecimal amount,
        Long nonce,
        BigDecimal gasPrice,
        BigDecimal gasUsed,
        BigDecimal gasFeeUsd,
        String txHash,
        Long blockNumber,
        Integer confirmations,
        Integer status,
        String failureCode,
        String failureReason,
        LocalDateTime broadcastAt,
        LocalDateTime confirmedAt,
        LocalDateTime failedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
