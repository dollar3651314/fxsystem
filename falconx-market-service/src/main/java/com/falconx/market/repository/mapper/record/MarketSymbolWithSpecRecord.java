package com.falconx.market.repository.mapper.record;

import java.math.BigDecimal;

/**
 * 按 platform symbol 视角的市场品种持久化记录。
 *
 * <p>STAGE-2-SYMBOL-PARAMS-DOWNSHIFT 引入：通过 INNER JOIN
 * `t_symbol`（LP 源元数据）与 `t_symbol_quote_mapping`（系统级产品配置）
 * 返回完整可交易品种。
 *
 * <p>{@code symbol} 列由 SQL 写成 {@code m.platform_symbol AS symbol}，
 * 即对外展示 symbol；category / marketCode / precision / 6 个交易字段均取自 mapping。
 */
public record MarketSymbolWithSpecRecord(
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
