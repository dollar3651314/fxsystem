package com.falconx.trading.api;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * BBOOK-RISK-CONTROL-01：用户级风控阈值 UPSERT 请求。
 *
 * <p>所有阈值字段允许 null，表示该项不限。
 */
public record AdminUserRiskThresholdUpsertRequest(
        @NotNull Long userId,
        BigDecimal netExposureThresholdUsd,
        BigDecimal profitableNetExposureThresholdUsd,
        boolean profitableUser,
        String reason
) {
}
