package com.falconx.market.repository.mapper.record;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * STAGE-2-SYMBOL 三表管理 + STAGE-2-SYMBOL-PARAMS-DOWNSHIFT 字段扩展：
 * t_symbol_quote_mapping 管理端记录。
 *
 * <p>R4.1.B 起字段集扩展 6 交易参数 + 2 precision；V15 起 category / marketCode
 * 也由 mapping 承接为系统级配置：
 * - 交易参数（maxLeverage/takerFeeRate/spread/minQty/maxQty/minNotional）从 t_symbol 下沉到此
 * - precision（pricePrecision/qtyPrecision）为系统级精度，source 只保留上游元数据
 */
public record MarketSymbolQuoteMappingAdminRecord(
        String platformSymbol,
        String sourceProvider,
        String sourceLpCode,
        String sourceSymbol,
        Integer category,
        String marketCode,
        BigDecimal priceMultiplier,
        BigDecimal bidAdjustment,
        BigDecimal askAdjustment,
        Integer enabled,
        Integer lpSubscribeEnabled,
        Integer maxLeverage,
        BigDecimal takerFeeRate,
        BigDecimal spread,
        BigDecimal minQty,
        BigDecimal maxQty,
        BigDecimal minNotional,
        Integer pricePrecision,
        Integer qtyPrecision,
        Integer sourceStatus,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
