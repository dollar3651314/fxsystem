package com.falconx.trading.service.model;

import com.falconx.trading.entity.TradingLedgerBizType;
import com.falconx.trading.entity.TradingOrderSide;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * `Swap` 明细查询视图。
 */
public record TradingSwapSettlementView(
        Long ledgerId,
        Long positionId,
        String symbol,
        TradingOrderSide side,
        TradingLedgerBizType settlementType,
        BigDecimal amount,
        BigDecimal balanceAfter,
        OffsetDateTime rolloverAt,
        OffsetDateTime settledAt,
        String referenceNo
) {
}
