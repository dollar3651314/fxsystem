package com.falconx.console.api;

import java.time.LocalDateTime;
import java.util.List;

/**
 * STAGE-2-SYMBOL 三表管理：用户组可见性列表响应。
 */
public record AdminSymbolGroupVisibilityListResponse(
        List<Item> items,
        long total,
        int page,
        int size
) {
    public record Item(
            String groupCode,
            String symbol,
            Integer visible,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
    }
}
