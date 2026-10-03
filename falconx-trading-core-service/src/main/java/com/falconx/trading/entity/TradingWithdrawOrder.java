package com.falconx.trading.entity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * STAGE-7-WITHDRAW：出金主表实体（trading-core owner）。
 *
 * <p>状态机驱动方；余额时点见 {@link TradingLedgerBizType#WITHDRAW_FREEZE}
 * 等枚举注释与 docs/domain/状态机规范.md §7A。
 *
 * <p>提交时立即冻结余额：{@code t_account.frozen += amount}，写
 * {@code t_ledger biz_type=WITHDRAW_FREEZE}；后续状态迁移按状态机退冻或落账。
 */
public record TradingWithdrawOrder(
        Long id,
        Long userId,
        BigDecimal amount,
        String currency,
        TradingWithdrawNetwork network,
        String targetAddress,
        Long whitelistId,
        TradingWithdrawOrderStatus status,
        OffsetDateTime coolingUntil,
        OffsetDateTime delayedUntil,
        OffsetDateTime processingStartedAt,
        Long reviewerId,
        OffsetDateTime reviewAt,
        String reviewNote,
        String rejectReason,
        String txHash,
        int confirmations,
        String failureCode,
        String failureReason,
        String idempotencyKey,
        BigDecimal dailyAmountUsdSnapshot,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
