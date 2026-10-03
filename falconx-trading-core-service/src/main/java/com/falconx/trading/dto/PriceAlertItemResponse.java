package com.falconx.trading.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * STAGE-4-PRICE-ALERT：价格告警返回项。
 */
public record PriceAlertItemResponse(
        String id,  // 雪花 ID 用 String 避免前端 JSON.parse 精度损失
        // 用户路径填 null（用户看自己不需要 userId）；admin 路径必填，console 用它在表格区分用户。
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
        /** symbol 价格显示精度（SymbolSpec.pricePrecision）；用户路径填 null（前端自有精度 hook），admin 路径填充供管理端格式化。 */
        Integer pricePrecision
) {
}
