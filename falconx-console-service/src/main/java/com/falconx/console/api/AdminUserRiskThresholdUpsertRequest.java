package com.falconx.console.api;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * BBOOK-RISK-CONTROL-01：用户级风控阈值 UPSERT 请求。
 */
public record AdminUserRiskThresholdUpsertRequest(
        @NotNull Long userId,
        BigDecimal netExposureThresholdUsd,
        BigDecimal profitableNetExposureThresholdUsd,
        boolean profitableUser,
        String reason
) {
}
