package com.falconx.market.entity;

/**
 * 市场品种元数据（LP 源 symbol）。
 *
 * <p>该对象对应 `falconx_market.t_symbol`，
 * 只承载基础元数据（产品分类、市场代码、币种、精度、状态）。
 *
 * <p>STAGE-2-SYMBOL-PARAMS-DOWNSHIFT 后：交易参数（杠杆、费率、点差、qty 限制）
 * 已下沉到 `t_symbol_quote_mapping`，本 entity 不再持有这些字段；
 * 涉及交易参数的查询请使用 {@link MarketSymbolWithSpec}。
 */
public record MarketSymbol(
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
