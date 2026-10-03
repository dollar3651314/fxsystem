package com.falconx.console.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * STAGE-2-SYMBOL 三表管理 + STAGE-2-SYMBOL-PARAMS-DOWNSHIFT R9：报价映射列表响应。
 *
 * <p>R2 三轮 + V15：Item 字段集扩展 category / marketCode / 6 交易参数 + 2 系统级 precision。
 */
public record AdminSymbolQuoteMappingListResponse(
        List<Item> items,
        long total,
        int page,
        int size
) {
    public record Item(
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
}
