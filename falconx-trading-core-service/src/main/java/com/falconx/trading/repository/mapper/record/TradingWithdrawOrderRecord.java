package com.falconx.trading.repository.mapper.record;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * STAGE-7-WITHDRAW：出金主表 MyBatis 记录对象。
 */
public record TradingWithdrawOrderRecord(
        Long id,
        Long userId,
        BigDecimal amount,
        String currency,
        String network,
        String targetAddress,
        Long whitelistId,
        Integer status,
        LocalDateTime coolingUntil,
        LocalDateTime delayedUntil,
        LocalDateTime processingStartedAt,
        Long reviewerId,
        LocalDateTime reviewAt,
        String reviewNote,
        String rejectReason,
        String txHash,
        Integer confirmations,
        String failureCode,
        String failureReason,
        String idempotencyKey,
        BigDecimal dailyAmountUsdSnapshot,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
