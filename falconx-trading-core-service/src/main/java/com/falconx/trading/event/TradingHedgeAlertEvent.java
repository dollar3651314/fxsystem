package com.falconx.trading.event;

import com.falconx.trading.entity.TradingHedgeTriggerSource;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * BBook 风险观测告警桩事件。
 *
 * <p>当前阶段只在服务内发布 Spring Event，作为 BBook 自营风险告警链路的占位桩。
 * 它不是 Kafka 契约，也不代表当前已经实现完整 BBook 风控动作。
 */
public record TradingHedgeAlertEvent(
        OffsetDateTime occurredAt,
        String symbol,
        BigDecimal netExposureUsd,
        BigDecimal hedgeThresholdUsd,
        Long positionId,
        TradingHedgeTriggerSource triggerSource,
        BigDecimal markPrice,
        OffsetDateTime quoteTs,
        String priceSource,
        Long hedgeLogId
) {
}
