package com.falconx.trading.repository.mapper.record;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * `Swap` 明细查询记录。
 */
public record TradingSwapSettlementRecord(
        Long ledgerId,
        Long positionId,
        String symbol,
        Integer sideCode,
        Integer bizTypeCode,
        BigDecimal amount,
        BigDecimal balanceAfter,
        String rolloverAtText,
        LocalDateTime settledAt,
        String referenceNo
) {
}
