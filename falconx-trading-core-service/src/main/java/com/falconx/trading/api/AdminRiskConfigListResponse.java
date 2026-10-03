package com.falconx.trading.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record AdminRiskConfigListResponse(List<Item> items, long total, int page, int size) {

    public record Item(
            Long id,
            String symbol,
            String marketCode,
            BigDecimal maxPositionPerUser,
            BigDecimal maxPositionTotal,
            BigDecimal maintenanceMarginRate,
            Integer maxLeverage,
            BigDecimal hedgeThresholdUsd,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
    }
}
