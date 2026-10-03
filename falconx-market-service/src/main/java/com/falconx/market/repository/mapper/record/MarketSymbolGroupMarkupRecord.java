package com.falconx.market.repository.mapper.record;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * `t_symbol_group_markup` 持久化记录。
 */
public record MarketSymbolGroupMarkupRecord(
        String groupCode,
        String platformSymbol,
        BigDecimal bidExtra,
        BigDecimal askExtra,
        Integer enabled,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
