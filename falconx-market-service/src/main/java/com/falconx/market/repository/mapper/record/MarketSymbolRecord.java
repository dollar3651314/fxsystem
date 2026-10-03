package com.falconx.market.repository.mapper.record;

/**
 * `t_symbol` 持久化记录。
 *
 * <p>STAGE-2-SYMBOL-PARAMS-DOWNSHIFT 后：6 个交易字段（max_leverage / taker_fee_rate /
 * spread / min_qty / max_qty / min_notional）已下沉到 `t_symbol_quote_mapping`，
 * 本 record 不再持有；涉及交易参数的查询请使用 {@link MarketSymbolWithSpecRecord}。
 */
public record MarketSymbolRecord(
        Long id,
        String lpCode,
        String symbol,
        int category,
        String marketCode,
        String baseCurrency,
        String quoteCurrency,
        int pricePrecision,
        int qtyPrecision,
        int status
) {
}
