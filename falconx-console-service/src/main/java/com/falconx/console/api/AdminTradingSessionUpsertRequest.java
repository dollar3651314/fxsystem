package com.falconx.console.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Admin 创建 / 编辑 platform symbol 周交易时段请求。
 */
public record AdminTradingSessionUpsertRequest(
        @NotNull Integer dayOfWeek,
        @NotNull Integer sessionNo,
        @NotNull LocalTime openTime,
        @NotNull LocalTime closeTime,
        @NotBlank @Size(max = 32) String timezone,
        @NotNull Integer enabled,
        @NotNull LocalDate effectiveFrom,
        LocalDate effectiveTo,
        @NotBlank @Size(min = 10, max = 500) String reason
) {
}
