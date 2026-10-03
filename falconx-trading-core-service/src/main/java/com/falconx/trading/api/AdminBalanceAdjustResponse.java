package com.falconx.trading.api;

import java.math.BigDecimal;

/**
 * STAGE-2-CUSTOMER：调余额成功响应。
 */
public record AdminBalanceAdjustResponse(
        long userId,
        BigDecimal deltaUSD,
        BigDecimal balanceBefore,
        BigDecimal balanceAfter,
        Long ledgerEntryId
) {
}
