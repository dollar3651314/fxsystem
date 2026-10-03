package com.falconx.market.repository.mapper.record;

import java.time.OffsetDateTime;

/**
 * `t_featured_symbol` 持久化记录。
 */
public record MarketFeaturedSymbolRecord(
        String platformSymbol,
        Integer sortOrder,
        Integer enabled,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
