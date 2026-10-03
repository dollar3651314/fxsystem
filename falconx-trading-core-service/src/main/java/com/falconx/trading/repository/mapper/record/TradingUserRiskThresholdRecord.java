package com.falconx.trading.repository.mapper.record;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * BBOOK-RISK-CONTROL-01：用户级风控阈值 MyBatis 记录对象。
 */
public record TradingUserRiskThresholdRecord(
        Long userId,
        BigDecimal netExposureThresholdUsd,
        BigDecimal profitableNetExposureThresholdUsd,
        Integer isProfitableUser,
        String updatedBy,
        String updatedReason,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
