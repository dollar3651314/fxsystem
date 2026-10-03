package com.falconx.trading.service.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 隔夜利息共享快照。
 *
 * <p>该对象承接 `market-service` 写入 Redis 的 `Swap rate` 历史规则，
 * 供 `trading-core-service` 在不跨服务查库的前提下按结算日选取有效费率。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TradingSwapRateSnapshot(
        String symbol,
        List<TradingSwapRateRule> rates,
        OffsetDateTime refreshedAt
) {

    /**
     * 按结算日解析生效费率。
     *
     * @param settlementDate 结算业务日
     * @return `effective_from <= settlementDate` 的最新规则
     */
    public Optional<TradingSwapRateRule> resolveEffectiveRule(LocalDate settlementDate) {
        if (settlementDate == null || rates == null || rates.isEmpty()) {
            return Optional.empty();
        }
        return rates.stream()
                .filter(rule -> rule.effectiveFrom() != null && !rule.effectiveFrom().isAfter(settlementDate))
                .max(Comparator.comparing(TradingSwapRateRule::effectiveFrom));
    }

    /**
     * @return 快照中最早生效日期
     */
    public Optional<LocalDate> earliestEffectiveFrom() {
        if (rates == null || rates.isEmpty()) {
            return Optional.empty();
        }
        return rates.stream()
                .map(TradingSwapRateRule::effectiveFrom)
                .filter(java.util.Objects::nonNull)
                .min(LocalDate::compareTo);
    }
}
