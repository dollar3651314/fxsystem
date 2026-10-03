package com.falconx.trading.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 账本流水查询项。
 */
public record TradingLedgerItemResponse(
        Long ledgerId,
        String bizType,
        BigDecimal amount,
        String idempotencyKey,
        String referenceNo,
        BigDecimal balanceBefore,
        BigDecimal balanceAfter,
        BigDecimal frozenBefore,
        BigDecimal frozenAfter,
        BigDecimal marginUsedBefore,
        BigDecimal marginUsedAfter,
        OffsetDateTime createdAt
) {
}
