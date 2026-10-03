package com.falconx.console.api;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * STAGE-2-SYMBOL：用户组可见性聚合视图响应（每行一个 groupCode + 该组所有可见 symbol 列表）。
 * 用于管理端「组可见性」一对多展示，避免 5000+ 条平铺。
 */
public record AdminSymbolGroupVisibilityGroupedListResponse(
        List<Item> items,
        long total,
        int page,
        int size
) {
    public record Item(
            String groupCode,
            int visibleCount,
            List<String> visibleSymbols,
            OffsetDateTime lastModifiedAt
    ) {
    }
}
