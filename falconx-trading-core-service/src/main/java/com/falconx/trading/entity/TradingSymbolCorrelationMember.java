package com.falconx.trading.entity;

import java.math.BigDecimal;

/**
 * STAGE-9-RISK-OPS-COMPLETE §12.1：相关性组成员。
 *
 * @param symbol 品种代码（属于该组）
 * @param weight 权重（0.0000-9.9999，取绝对值相加做敞口聚合）
 */
public record TradingSymbolCorrelationMember(
        String symbol,
        BigDecimal weight
) {
}
