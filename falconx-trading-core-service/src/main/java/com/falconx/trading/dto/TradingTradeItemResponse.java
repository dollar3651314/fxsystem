package com.falconx.trading.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 成交查询项。
 */
public record TradingTradeItemResponse(
        Long tradeId,
        Long orderId,
        Long positionId,
        String symbol,
        String side,
        String tradeType,
        BigDecimal quantity,
        BigDecimal price,
        BigDecimal fee,
        BigDecimal realizedPnl,
        OffsetDateTime tradedAt
) {
}
