package com.falconx.trading.service.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 交易时间例外规则。
 *
 * <p>该对象对应单个 `symbol` 的人工覆盖规则，
 * 在交易时间判定中优先级高于周规则和节假日规则。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TradingHoursExceptionRule(
        LocalDate tradeDate,
        int exceptionType,
        Integer sessionNo,
        LocalTime openTime,
        LocalTime closeTime,
        String timezone,
        String reason
) {
}
