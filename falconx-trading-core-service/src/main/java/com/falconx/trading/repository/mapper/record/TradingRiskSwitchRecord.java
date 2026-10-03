package com.falconx.trading.repository.mapper.record;

import java.time.LocalDateTime;

/**
 * 交易风控开关 MyBatis 记录对象，对应 `t_trading_risk_switch`。
 */
public record TradingRiskSwitchRecord(
        String switchKey,
        Integer enabled,
        String reason,
        String updatedBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
