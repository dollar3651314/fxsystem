package com.falconx.console.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Admin 创建 / 编辑 platform symbol 特殊交易日请求。
 */
public record AdminTradingExceptionUpsertRequest(
        @NotNull LocalDate tradeDate,
        @NotNull Integer exceptionType,
        Integer sessionNo,
        LocalTime openTime,
        LocalTime closeTime,
        @NotBlank @Size(max = 32) String timezone,
        @Size(max = 128) String ruleReason,
        @NotBlank @Size(min = 10, max = 500) String reason
) {
}
