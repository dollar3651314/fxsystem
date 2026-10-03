package com.falconx.trading.entity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 品种净敞口实体。
 *
 * <p>该对象对应 `falconx_trading.t_risk_exposure`，
 * 用于承载 B-book 模式下平台对每个品种的实时净多/净空暴露。
 */
public record TradingRiskExposure(
        String symbol,
        BigDecimal totalLongQty,
        BigDecimal totalShortQty,
        BigDecimal netExposure,
        BigDecimal netExposureUsd,
        OffsetDateTime updatedAt
) {
}
