package com.falconx.trading.websocket;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * STAGE-4-PRICE-ALERT：价格告警触发 WS payload。
 */
public record PriceAlertTriggeredPayload(
        String alertId,           // 雪花 ID 用 String 避免前端 JSON.parse 精度损失
        String symbol,
        String direction,         // ABOVE / BELOW
        BigDecimal targetPrice,
        BigDecimal triggeredPrice,
        int triggerCount,
        int remainingTriggers,    // 3 - triggerCount
        boolean exhausted,        // triggerCount == 3
        String note,
        OffsetDateTime triggeredAt
) {
}
