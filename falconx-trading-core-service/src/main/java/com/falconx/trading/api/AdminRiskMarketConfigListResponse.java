package com.falconx.trading.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public record AdminRiskMarketConfigListResponse(List<Item> items) {

    public record Item(
            String marketCode,
            BigDecimal concentrationThresholdUsd,
            boolean enabled,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
    }
}
