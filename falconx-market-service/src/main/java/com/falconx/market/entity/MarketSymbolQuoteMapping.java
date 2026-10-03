package com.falconx.market.entity;

import java.math.BigDecimal;

/**
 * 平台 symbol 与外部报价源 symbol 的映射配置。
 *
 * <p>该对象对应 `falconx_market.t_symbol_quote_mapping`，
 * 用于把 LP 原始报价转换为平台可展示和可交易的标准产品报价。
 */
public record MarketSymbolQuoteMapping(
        String platformSymbol,
        String sourceProvider,
        String sourceLpCode,
        String sourceSymbol,
        BigDecimal priceMultiplier,
        BigDecimal bidAdjustment,
        BigDecimal askAdjustment,
        int enabled,
        int lpSubscribeEnabled
) {
}
