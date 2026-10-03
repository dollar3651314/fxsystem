package com.falconx.trading.repository.mapper.record;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 品种净敞口 MyBatis 记录对象。
 *
 * @param symbol 交易品种
 * @param totalLongQty 平台总多头
 * @param totalShortQty 平台总空头
 * @param netExposure 净敞口
 * @param netExposureUsd 美元口径净敞口
 * @param updatedAt 更新时间
 */
public record TradingRiskExposureRecord(
        String symbol,
        BigDecimal totalLongQty,
        BigDecimal totalShortQty,
        BigDecimal netExposure,
        BigDecimal netExposureUsd,
        LocalDateTime updatedAt
) {
}
