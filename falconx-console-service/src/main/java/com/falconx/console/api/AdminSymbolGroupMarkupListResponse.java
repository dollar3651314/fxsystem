package com.falconx.console.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * STAGE-12-GROUP-MARKUP: 用户组加点列表响应。
 *
 * <p>每条 Item 对应 t_symbol_group_markup 一行：
 * 在 t_symbol_quote_mapping 全平台基准价之上的额外双向 bid/ask 加点。
 */
public record AdminSymbolGroupMarkupListResponse(
        List<Item> items,
        long total,
        int page,
        int size
) {
    public record Item(
            String groupCode,
            String platformSymbol,
            BigDecimal bidExtra,
            BigDecimal askExtra,
            Boolean enabled,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
    }
}
