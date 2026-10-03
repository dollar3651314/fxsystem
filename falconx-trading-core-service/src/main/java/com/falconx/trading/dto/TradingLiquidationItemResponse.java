package com.falconx.trading.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 强平记录查询项。
 */
public record TradingLiquidationItemResponse(
        Long liquidationLogId,
        Long positionId,
        String symbol,
        String side,
        String marginMode,
        BigDecimal quantity,
        BigDecimal entryPrice,
        BigDecimal liquidationPrice,
        BigDecimal markPrice,
        OffsetDateTime priceTs,
        String priceSource,
        BigDecimal loss,
        BigDecimal fee,
        BigDecimal marginReleased,
        BigDecimal platformCoveredLoss,
        OffsetDateTime createdAt
) {
}
