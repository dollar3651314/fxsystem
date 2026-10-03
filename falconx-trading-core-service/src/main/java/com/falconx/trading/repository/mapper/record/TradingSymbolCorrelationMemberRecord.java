package com.falconx.trading.repository.mapper.record;

import java.math.BigDecimal;

public record TradingSymbolCorrelationMemberRecord(
        String groupCode,
        String symbol,
        BigDecimal weight
) {
}
