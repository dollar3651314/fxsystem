package com.falconx.market.repository.mapper.record;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * `t_swap_rate` 记录对象。
 */
public record MarketSwapRateRecord(
        Long id,
        String symbol,
        BigDecimal longRate,
        BigDecimal shortRate,
        LocalTime rolloverTime,
        LocalDate effectiveFrom,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
