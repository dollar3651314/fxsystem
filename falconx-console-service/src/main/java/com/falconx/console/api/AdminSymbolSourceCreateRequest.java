package com.falconx.console.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * STAGE-2-SYMBOL-PARAMS-DOWNSHIFT R9：新建 LP 源 symbol 请求。
 *
 * <p>权限 symbol:source:create（HIGH_RISK）；仅允许 status=1 创建；如需 suspended 创建后走 suspend 接口。
 */
public record AdminSymbolSourceCreateRequest(
        @Size(max = 32) String lpCode,
        @NotBlank @Size(max = 32) String symbol,
        @NotNull Integer category,
        @NotBlank @Size(max = 32) String marketCode,
        @NotBlank @Size(max = 16) String baseCurrency,
        @NotBlank @Size(max = 16) String quoteCurrency,
        @NotNull Integer pricePrecision,
        @NotNull Integer qtyPrecision,
        @NotBlank @Size(min = 10, max = 500) String reason
) {
}
