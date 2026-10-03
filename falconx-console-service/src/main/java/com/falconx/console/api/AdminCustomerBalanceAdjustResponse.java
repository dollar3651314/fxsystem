package com.falconx.console.api;

import java.math.BigDecimal;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

/**
 * STAGE-2-CUSTOMER：调余额响应。
 */
public record AdminCustomerBalanceAdjustResponse(
        // 雪花 ID 必须 String 序列化（FX-071）
        @JsonSerialize(using = ToStringSerializer.class) long userId,
        BigDecimal deltaUSD,
        BigDecimal balanceBefore,
        BigDecimal balanceAfter,
        // 账本条目 ID 同为雪花 ID
        @JsonSerialize(using = ToStringSerializer.class) Long ledgerEntryId
) {
}
