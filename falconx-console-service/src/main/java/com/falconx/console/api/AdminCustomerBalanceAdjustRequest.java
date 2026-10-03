package com.falconx.console.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * STAGE-2-CUSTOMER：调余额请求体。
 *
 * @param deltaUSD 调整金额（可正可负，2 位小数）
 * @param reason 操作原因（≥ 10 字符）
 */
public record AdminCustomerBalanceAdjustRequest(
        @NotNull BigDecimal deltaUSD,
        @NotBlank @Size(min = 10) String reason
) {
}
