package com.falconx.console.api;

import java.util.List;

/**
 * STAGE-12-GROUP-MARKUP: 按 groupCode 聚合的列表响应（前端列表卡片视图）。
 */
public record AdminSymbolGroupMarkupGroupedListResponse(
        List<Group> groups
) {
    public record Group(
            String groupCode,
            int configuredCount,
            List<AdminSymbolGroupMarkupListResponse.Item> items
    ) {
    }
}
