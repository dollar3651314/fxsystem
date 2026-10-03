package com.falconx.trading.entity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 风控参数实体。
 *
 * <p>该对象对应 `t_risk_config`，用于承载 owner 侧按品种维护的交易风控配置。
 * `hedgeThresholdUsd` 是 B-book 净美元敞口告警阈值；
 * `marketCode` 是品种分类，用于跨品种集中度保护。
 */
public record TradingRiskConfig(
        Long riskConfigId,
        String symbol,
        String marketCode,
        BigDecimal maxPositionPerUser,
        BigDecimal maxPositionTotal,
        BigDecimal maintenanceMarginRate,
        Integer maxLeverage,
        BigDecimal hedgeThresholdUsd,
        // BBOOK-RISK-CONTROL-01：方向集中度（多/空 USD 失衡比阈值，0-1）
        BigDecimal directionImbalanceRatioThreshold,
        // BBOOK-RISK-CONTROL-01：方向集中度最小触发总敞口（USD）
        BigDecimal directionImbalanceMinTotalUsd,
        // STAGE-3-PENDING-ORDER：挂单价与 markPrice 最小距离比例（0-1），NULL=不限
        BigDecimal pendingOrderMinDistanceRatio,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
