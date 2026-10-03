package com.falconx.trading.repository.mapper.record;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 跨品种集中度阈值配置 MyBatis 记录对象。
 */
public record TradingRiskMarketConfigRecord(
        String marketCode,
        BigDecimal concentrationThresholdUsd,
        Integer isEnabled,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
