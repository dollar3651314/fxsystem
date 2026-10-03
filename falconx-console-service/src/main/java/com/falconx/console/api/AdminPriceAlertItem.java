package com.falconx.console.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * STAGE-4-PRICE-ALERT：管理端价格告警列表项。
 */
public record AdminPriceAlertItem(
        String id,
        String userId,
        String symbol,
        String direction,
        BigDecimal targetPrice,
        String status,
        String note,
        BigDecimal basePrice,
        int triggerCount,
        int remainingTriggers,
        OffsetDateTime lastTriggeredAt,
        BigDecimal lastTriggeredPrice,
        OffsetDateTime cancelledAt,
        String cancelSource,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        /** symbol 价格显示精度（透传 trading-core SymbolSpec.pricePrecision）；过渡期可能为 null。 */
        Integer pricePrecision,
        /** 跨 schema enrich：用户对外短号（t_user.uid）。null = 未 enrich / 用户不存在。 */
        String userUid,
        /** 跨 schema enrich：用户邮箱。null = 未 enrich。 */
        String userEmail,
        /** 跨 schema enrich：用户姓名（firstName + lastName，profile 未填为 null）。 */
        String userFullName
) {
    /** Builder-style helper：基于已有 item 覆写 3 个用户信息字段，主字段不变。 */
    public AdminPriceAlertItem withUserInfo(String userUid, String userEmail, String userFullName) {
        return new AdminPriceAlertItem(id, userId, symbol, direction, targetPrice, status, note,
                basePrice, triggerCount, remainingTriggers, lastTriggeredAt, lastTriggeredPrice,
                cancelledAt, cancelSource, createdAt, updatedAt, pricePrecision,
                userUid, userEmail, userFullName);
    }
}
