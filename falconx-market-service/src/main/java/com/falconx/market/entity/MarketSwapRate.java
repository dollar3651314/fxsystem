package com.falconx.market.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;

/**
 * 隔夜利息费率 owner 实体。
 *
 * <p>该对象对应 `falconx_market.t_swap_rate`，
 * 表达 market owner 维护的按 `symbol + effective_from` 生效的费率规则。
 */
public record MarketSwapRate(
        Long id,
        String symbol,
        BigDecimal longRate,
        BigDecimal shortRate,
        LocalTime rolloverTime,
        LocalDate effectiveFrom,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
