package com.falconx.trading.repository.mapper.record;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * STAGE-4-PRICE-ALERT：价格告警 MyBatis 记录对象。
 */
public record TradingPriceAlertRecord(
        Long id,
        Long userId,
        String symbol,
        Integer direction,
        BigDecimal targetPrice,
        Integer status,
        String note,
        BigDecimal basePrice,
        Integer triggerCount,
        LocalDateTime lastTriggeredAt,
        BigDecimal lastTriggeredPrice,
        LocalDateTime cancelledAt,
        String cancelSource,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
