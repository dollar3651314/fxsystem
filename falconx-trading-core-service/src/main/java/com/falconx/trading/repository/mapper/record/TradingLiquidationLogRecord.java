package com.falconx.trading.repository.mapper.record;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 强平日志 MyBatis 记录对象。
 */
public record TradingLiquidationLogRecord(
        Long id,
        Long userId,
        Long positionId,
        String symbol,
        Integer sideCode,
        Integer marginModeCode,
        BigDecimal quantity,
        BigDecimal entryPrice,
        BigDecimal liquidationPrice,
        BigDecimal markPrice,
        LocalDateTime priceTs,
        String priceSource,
        BigDecimal loss,
        BigDecimal fee,
        BigDecimal marginReleased,
        BigDecimal platformCoveredLoss,
        LocalDateTime createdAt
) {
}
