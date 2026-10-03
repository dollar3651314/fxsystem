package com.falconx.trading.dto;

import java.math.BigDecimal;

/**
 * 修改持仓 TP/SL 响应 DTO。
 */
public record UpdateTradingPositionRiskControlsResponse(
        Long positionId,
        String symbol,
        String status,
        BigDecimal takeProfitPrice,
        BigDecimal stopLossPrice
) {
}
