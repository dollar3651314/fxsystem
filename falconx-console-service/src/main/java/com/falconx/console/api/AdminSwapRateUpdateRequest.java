package com.falconx.console.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * STAGE-2-SYMBOL: PUT swap-rate 请求（高风险）。
 */
public record AdminSwapRateUpdateRequest(
        @NotNull BigDecimal longRate,
        @NotNull BigDecimal shortRate,
        LocalTime rolloverTime,
        @NotNull LocalDate effectiveFrom,
        @NotBlank @Size(min = 10, max = 500) String reason
) {
}
