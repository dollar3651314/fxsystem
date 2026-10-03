package com.falconx.console.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * STAGE-2-SYMBOL 三表管理 + STAGE-2-SYMBOL-PARAMS-DOWNSHIFT R9 字段集扩展：新建报价映射请求。
 *
 * <p>R2 三轮 + V15：mapping 成为系统唯一 Symbol 配置真源，必填 category / marketCode /
 * 6 个交易参数 + 2 个系统级 precision；
 * 90601-90604 校验语义搬迁到此（市场端 service 层校验）。
 */
public record AdminSymbolQuoteMappingCreateRequest(
        @NotBlank @Size(max = 32) String platformSymbol,
        @Size(max = 32) String sourceProvider,
        @Size(max = 32) String sourceLpCode,
        @NotBlank @Size(max = 32) String sourceSymbol,
        @NotNull Integer category,
        @NotBlank @Size(max = 32) String marketCode,
        @NotNull BigDecimal priceMultiplier,
        BigDecimal bidAdjustment,
        BigDecimal askAdjustment,
        @NotNull Integer enabled,
        @NotNull Integer lpSubscribeEnabled,
        @NotNull Integer maxLeverage,
        @NotNull BigDecimal takerFeeRate,
        @NotNull BigDecimal spread,
        @NotNull BigDecimal minQty,
        @NotNull BigDecimal maxQty,
        @NotNull BigDecimal minNotional,
        @NotNull Integer pricePrecision,
        @NotNull Integer qtyPrecision,
        @NotBlank @Size(min = 10, max = 500) String reason
) {
}
