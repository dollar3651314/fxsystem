package com.falconx.trading.api;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import java.math.BigDecimal;

/**
 * BBOOK-RISK-CONTROL-01：方向集中度阈值更新请求。
 *
 * <p>{@code ratioThreshold} 为 null 表示停用方向集中度检查（清空配置）。
 */
public record AdminDirectionImbalanceUpdateRequest(
        @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal ratioThreshold,
        BigDecimal minTotalUsd,
        String reason
) {
}
