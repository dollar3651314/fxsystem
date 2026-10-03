package com.falconx.trading.entity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 跨品种集中度阈值配置实体，对应 {@code t_risk_market_config}。
 *
 * @param marketCode                 品种分类（FX/CRYPTO/COMMODITY）
 * @param concentrationThresholdUsd  同分类净敞口合计阈值（USD 绝对值）
 * @param enabled                    是否启用
 * @param createdAt                  创建时间
 * @param updatedAt                  更新时间
 */
public record TradingRiskMarketConfig(
        String marketCode,
        BigDecimal concentrationThresholdUsd,
        boolean enabled,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
