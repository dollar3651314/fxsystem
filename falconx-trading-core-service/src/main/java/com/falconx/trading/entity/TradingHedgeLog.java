package com.falconx.trading.entity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 对冲观测日志实体。
 *
 * <p>该对象对应 `t_hedge_log`，
 * 用于冻结净敞口美元口径、阈值、触发来源和告警/恢复状态，
 * 让 B-book 风险观测具备可追溯的 owner 审计事实。
 */
public record TradingHedgeLog(
        Long hedgeLogId,
        String symbol,
        Long positionId,
        TradingHedgeTriggerSource triggerSource,
        TradingHedgeLogStatus actionStatus,
        BigDecimal netExposure,
        BigDecimal netExposureUsd,
        BigDecimal hedgeThresholdUsd,
        BigDecimal markPrice,
        OffsetDateTime priceTs,
        String priceSource,
        OffsetDateTime createdAt
) {
}
