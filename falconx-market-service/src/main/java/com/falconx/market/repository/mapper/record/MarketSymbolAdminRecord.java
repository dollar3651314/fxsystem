package com.falconx.market.repository.mapper.record;

import java.time.LocalDateTime;

/**
 * STAGE-2-SYMBOL: t_symbol 完整字段（admin 路径用，含 SUSPENDED）。
 *
 * <p>STAGE-2-SYMBOL-PARAMS-DOWNSHIFT 后：交易字段（max_leverage / taker_fee_rate /
 * spread / min_qty / max_qty / min_notional）已下沉到 `t_symbol_quote_mapping`，
 * admin 主表 record 不再持有；交易参数编辑请使用 mapping 接口。
 */
public record MarketSymbolAdminRecord(
        Long id,
        String lpCode,
        String symbol,
        Integer category,
        String marketCode,
        String baseCurrency,
        String quoteCurrency,
        Integer pricePrecision,
        Integer qtyPrecision,
        Integer status,
        LocalDateTime createdAt
) {
}
