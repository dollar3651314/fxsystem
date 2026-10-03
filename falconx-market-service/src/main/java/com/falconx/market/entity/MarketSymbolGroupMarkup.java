package com.falconx.market.entity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 用户组对单个平台 symbol 的双向加点配置。
 *
 * <p>该对象对应 `falconx_market.t_symbol_group_markup`，
 * 用于在 Layer 1（{@code t_symbol_quote_mapping}）之上叠加 Layer 2 组级加点。
 * 仅影响推送 / REST / 撮合 / 持仓 / 强平 / 挂单触发；
 * 不影响 K 线 / ClickHouse {@code quote_tick} 落盘 / Kafka tick。
 */
public record MarketSymbolGroupMarkup(
        String groupCode,
        String platformSymbol,
        BigDecimal bidExtra,
        BigDecimal askExtra,
        int enabled,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
