package com.falconx.console.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * STAGE-2-SYMBOL: 编辑 source 元数据请求（高风险，reason ≥10 字符）。
 *
 * <p>STAGE-2-SYMBOL-PARAMS-DOWNSHIFT R9：字段集裁剪。原 maxLeverage/takerFeeRate/spread/min_qty/max_qty/min_notional
 * 已下沉到 mapping，改走 {@link AdminSymbolQuoteMappingUpdateRequest}。
 */
public record AdminSymbolUpdateRequest(
        Integer category,
        @Size(max = 32) String marketCode,
        Integer pricePrecision,
        Integer qtyPrecision,
        @NotBlank @Size(min = 10, max = 500) String reason
) {
}
