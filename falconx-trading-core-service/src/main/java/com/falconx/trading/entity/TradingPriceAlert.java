package com.falconx.trading.entity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * STAGE-4-PRICE-ALERT：用户价格告警实体。
 *
 * <p>触发节流：同一告警最多触发 3 次，每次间隔 ≥ 5 分钟；
 * trigger_count == 3 后 status 自动切 EXHAUSTED 终态。
 */
public record TradingPriceAlert(
        Long id,
        Long userId,
        String symbol,
        TradingPriceAlertDirection direction,
        BigDecimal targetPrice,
        TradingPriceAlertStatus status,
        String note,
        BigDecimal basePrice,
        int triggerCount,
        OffsetDateTime lastTriggeredAt,
        BigDecimal lastTriggeredPrice,
        OffsetDateTime cancelledAt,
        String cancelSource,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
