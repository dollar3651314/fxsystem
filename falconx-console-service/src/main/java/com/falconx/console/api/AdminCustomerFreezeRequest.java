package com.falconx.console.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * STAGE-2-CUSTOMER：冻结 / 解冻客户请求体（共用）。
 */
public record AdminCustomerFreezeRequest(@NotBlank @Size(min = 10) String reason) {
}
