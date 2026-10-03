package com.falconx.console.api;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * 跑马灯热门产品全量替换请求。items 顺序即展示序（sortOrder）。
 */
public record AdminFeaturedReplaceRequest(
        @NotNull List<Item> items
) {

    public record Item(String symbol, Boolean enabled) {
    }
}
