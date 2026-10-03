package com.falconx.market.entity;

import java.time.OffsetDateTime;

/**
 * 顶栏跑马灯「热门产品」有序列表项。
 *
 * <p>对应 `falconx_market.t_featured_symbol`。单一全局有序列表（无 group 维度），
 * 管理端配置展示哪些 symbol、顺序与启停；客户端公开接口按 sortOrder 升序取
 * enabled=1 渲染跑马灯。
 */
public record MarketFeaturedSymbol(
        String platformSymbol,
        int sortOrder,
        int enabled,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
