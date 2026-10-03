package com.falconx.trading.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * STAGE-7-WITHDRAW：客户端提交出金请求体。
 *
 * <p>详细校验语义见 docs/api/REST接口规范.md §9.2.2。
 */
public record WithdrawSubmitRequest(
        @NotNull BigDecimal amount,
        @Size(max = 16) String currency,
        @NotBlank @Size(max = 16) String network,
        @NotBlank @Size(max = 128) String targetAddress,
        @NotNull Long whitelistId
) {
}
