package com.falconx.market.repository.mapper.record;

import java.time.LocalDateTime;

/**
 * STAGE-2-SYMBOL 三表管理：t_symbol_group_visibility 管理端记录。
 */
public record MarketSymbolGroupVisibilityAdminRecord(
        String groupCode,
        String symbol,
        Integer visible,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
