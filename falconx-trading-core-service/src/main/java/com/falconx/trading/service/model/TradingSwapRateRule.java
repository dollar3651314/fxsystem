package com.falconx.trading.service.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 隔夜利息共享快照中的单条费率规则。
 *
 * <p>该对象由 `market-service` 写入 Redis，`trading-core-service` 只读不写，
 * 用于在结算日解析“最新 `effective_from <= settlementDate`”的正式 owner 费率。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TradingSwapRateRule(
        LocalDate effectiveFrom,
        LocalTime rolloverTime,
        BigDecimal longRate,
        BigDecimal shortRate
) {
}
