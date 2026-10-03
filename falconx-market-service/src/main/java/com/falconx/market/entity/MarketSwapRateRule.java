package com.falconx.market.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 隔夜利息快照中的单条费率规则。
 *
 * <p>该对象只保留 `trading-core-service` 结算所需的最小字段，
 * 由 market owner 从 `t_swap_rate` 聚合后写入 Redis 共享快照。
 */
public record MarketSwapRateRule(
        LocalDate effectiveFrom,
        LocalTime rolloverTime,
        BigDecimal longRate,
        BigDecimal shortRate
) {
}
