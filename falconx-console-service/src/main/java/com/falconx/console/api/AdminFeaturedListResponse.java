package com.falconx.console.api;

import java.util.List;

/**
 * 跑马灯热门产品列表响应（管理端，含禁用项，按 sortOrder 升序）。
 */
public record AdminFeaturedListResponse(List<Item> items) {

    public record Item(String symbol, int sortOrder, boolean enabled) {
    }
}
