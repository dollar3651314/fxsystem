package com.falconx.trading.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * BBOOK-RISK-CONTROL-01：用户级风控阈值列表项。
 */
public record AdminUserRiskThresholdItem(
        Long userId,
        BigDecimal netExposureThresholdUsd,
        BigDecimal profitableNetExposureThresholdUsd,
        boolean profitableUser,
        String updatedBy,
        String updatedReason,
        LocalDateTime updatedAt
) {
}
