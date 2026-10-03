package com.falconx.console.api;

import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

/**
 * STAGE-2-CUSTOMER：冻结 / 解冻客户响应（共用）。
 */
public record AdminCustomerFreezeResponse(
        // 雪花 ID 必须 String 序列化（FX-071）
        @JsonSerialize(using = ToStringSerializer.class) long userId,
        String previousStatus,
        String newStatus
) {
}
