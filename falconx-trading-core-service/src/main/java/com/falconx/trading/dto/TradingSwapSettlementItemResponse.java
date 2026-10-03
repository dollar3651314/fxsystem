package com.falconx.trading.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * `Swap` 明细单项响应。
 */
public record TradingSwapSettlementItemResponse(
        Long ledgerId,
        Long positionId,
        String symbol,
        String side,
        String settlementType,
        BigDecimal amount,
        BigDecimal balanceAfter,
        OffsetDateTime rolloverAt,
        OffsetDateTime settledAt,
        String referenceNo
) {
}
