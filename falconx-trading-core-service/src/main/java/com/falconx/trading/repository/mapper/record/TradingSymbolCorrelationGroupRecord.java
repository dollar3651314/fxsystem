package com.falconx.trading.repository.mapper.record;

import java.math.BigDecimal;

public record TradingSymbolCorrelationGroupRecord(
        String groupCode,
        String groupName,
        BigDecimal thresholdUsd,
        Integer enabled
) {
}
