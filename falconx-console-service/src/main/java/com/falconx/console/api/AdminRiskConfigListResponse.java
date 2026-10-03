package com.falconx.console.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

public record AdminRiskConfigListResponse(List<Item> items, long total, int page, int size) {

    public record Item(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
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
