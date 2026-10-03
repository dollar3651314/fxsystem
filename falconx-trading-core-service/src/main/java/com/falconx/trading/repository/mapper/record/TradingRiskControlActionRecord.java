package com.falconx.trading.repository.mapper.record;

import java.time.LocalDateTime;

/**
 * 风控执行动作 MyBatis 记录对象。
 */
public record TradingRiskControlActionRecord(
        Long id,
        String symbol,
        Integer actionTypeCode,
        Integer isActive,
        String triggerSource,
        String triggerReason,
        Long hedgeLogId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
