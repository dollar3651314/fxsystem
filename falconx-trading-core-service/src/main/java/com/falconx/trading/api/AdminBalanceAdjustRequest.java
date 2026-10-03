package com.falconx.trading.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * STAGE-2-CUSTOMER：管理员调整客户余额请求体。
 *
 * @param deltaUSD 调整金额（可正可负，2 位小数；console 端已做 abs ≤ 5000 与单日累计 ≤ 20000 校验）
 * @param reason 操作原因（≥ 10 字符）
 */
public record AdminBalanceAdjustRequest(
        @NotNull BigDecimal deltaUSD,
        @NotBlank @Size(min = 10) String reason
) {
}
