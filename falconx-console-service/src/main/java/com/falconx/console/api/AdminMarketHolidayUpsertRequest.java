package com.falconx.console.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Admin 创建 / 编辑市场节假日请求。
 */
public record AdminMarketHolidayUpsertRequest(
        @NotBlank @Size(max = 32) String marketCode,
        @NotNull LocalDate holidayDate,
        @NotNull Integer holidayType,
        LocalTime openTime,
        LocalTime closeTime,
        @NotBlank @Size(max = 32) String timezone,
        @NotBlank @Size(max = 128) String holidayName,
        @Size(max = 16) String countryCode,
        @NotBlank @Size(min = 10, max = 500) String reason
) {
}
