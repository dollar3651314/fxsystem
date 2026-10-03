package com.falconx.console.api;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import java.math.BigDecimal;

/**
 * BBOOK-RISK-CONTROL-01：方向集中度阈值更新请求。
 */
public record AdminDirectionImbalanceUpdateRequest(
        @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal ratioThreshold,
        BigDecimal minTotalUsd,
        String reason
) {
}
