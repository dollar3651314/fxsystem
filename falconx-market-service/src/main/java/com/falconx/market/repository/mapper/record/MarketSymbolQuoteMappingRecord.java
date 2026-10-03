package com.falconx.market.repository.mapper.record;

import java.math.BigDecimal;

/**
 * `t_symbol_quote_mapping` 持久化记录。
 */
public record MarketSymbolQuoteMappingRecord(
        String platformSymbol,
        String sourceProvider,
        String sourceLpCode,
        String sourceSymbol,
        BigDecimal priceMultiplier,
        BigDecimal bidAdjustment,
        BigDecimal askAdjustment,
        Integer enabled,
        Integer lpSubscribeEnabled
) {
}
