package com.falconx.trading.entity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * BBOOK-RISK-CONTROL-01：用户级风控阈值实体。
 *
 * <p>对应 {@code t_user_risk_threshold}。当用户的全部 OPEN 持仓 USD 累计敞口
 * 超过 {@code netExposureThresholdUsd}（或盈利用户的 profitable 阈值），下单
 * 评估时返回 {@code BBOOK_RISK_USER_EXPOSURE_LIMIT}。
 */
public record TradingUserRiskThreshold(
        Long userId,
        BigDecimal netExposureThresholdUsd,
        BigDecimal profitableNetExposureThresholdUsd,
        boolean profitableUser,
        String updatedBy,
        String updatedReason,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
