package com.falconx.market.entity;

import java.math.BigDecimal;

/**
 * 含 mapping 交易参数的市场品种视图。
 *
 * <p>STAGE-2-SYMBOL-PARAMS-DOWNSHIFT 引入：表示按 platform symbol 视角的
 * 完整可交易品种 = `t_symbol`（LP 源元数据）∪ `t_symbol_quote_mapping`（系统级产品配置）。
 *
 * <p>{@code symbol} 字段是 mapping.platform_symbol（对外展示），不是 source_symbol；
 * category / marketCode / precision / 6 个交易字段
 * （{@code maxLeverage / takerFeeRate / spread / minQty / maxQty / minNotional}）均取自 mapping。
 *
 * <p>本视图替代 trading-core 之前对 t_symbol 上的 6 字段消费，
 * 同时作为 C 端 `/api/v1/market/symbols` 返回字段的真源载体。
 */
public record MarketSymbolWithSpec(
        Long id,
        String symbol,
        int category,
        String marketCode,
        String baseCurrency,
        String quoteCurrency,
        int pricePrecision,
        int qtyPrecision,
        BigDecimal minQty,
        BigDecimal maxQty,
        BigDecimal minNotional,
        int maxLeverage,
        BigDecimal takerFeeRate,
        BigDecimal spread,
        int status
) {
}
