package com.falconx.trading.dto;

import java.math.BigDecimal;

/**
 * 追加逐仓保证金响应。
 */
public record AddIsolatedMarginResponse(
        Long positionId,
        String symbol,
        String status,
        String marginMode,
        BigDecimal margin,
        BigDecimal liquidationPrice,
        TradingAccountResponse account
) {
}
