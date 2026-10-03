package com.falconx.trading.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 手动平仓响应 DTO。
 */
public record CloseTradingPositionResponse(
        Long positionId,
        String positionStatus,
        BigDecimal closePrice,
        String closeReason,
        BigDecimal realizedPnl,
        OffsetDateTime closedAt,
        Long tradeId,
        TradingAccountResponse account
) {
}
