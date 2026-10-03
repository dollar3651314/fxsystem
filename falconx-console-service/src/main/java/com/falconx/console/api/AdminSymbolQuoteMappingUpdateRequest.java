package com.falconx.console.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * STAGE-2-SYMBOL 三表管理 + STAGE-2-SYMBOL-PARAMS-DOWNSHIFT R9 字段集扩展：更新报价映射请求。
 *
 * <p>R2 三轮 + V15：可空更新（仅传需修改字段）；category / marketCode /
 * 6 交易参数 + 2 系统级 precision。
 */
public record AdminSymbolQuoteMappingUpdateRequest(
        @Size(max = 32) String sourceProvider,
        @Size(max = 32) String sourceLpCode,
        @Size(max = 32) String sourceSymbol,
        Integer category,
        @Size(max = 32) String marketCode,
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
        @NotBlank @Size(min = 10, max = 500) String reason
) {
}
