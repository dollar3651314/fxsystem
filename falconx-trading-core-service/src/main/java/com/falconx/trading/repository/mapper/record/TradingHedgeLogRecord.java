package com.falconx.trading.repository.mapper.record;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 对冲观测日志 MyBatis 记录对象。
 */
public record TradingHedgeLogRecord(
        Long id,
        String symbol,
        Long positionId,
        Integer triggerSourceCode,
        Integer actionStatusCode,
        BigDecimal netExposure,
        BigDecimal netExposureUsd,
        BigDecimal hedgeThresholdUsd,
        BigDecimal markPrice,
        LocalDateTime priceTs,
        String priceSource,
        LocalDateTime createdAt
) {
}
