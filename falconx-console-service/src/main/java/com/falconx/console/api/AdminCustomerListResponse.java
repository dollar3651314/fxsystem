package com.falconx.console.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

/**
 * STAGE-2-CUSTOMER：客户列表响应。
 */
public record AdminCustomerListResponse(List<Item> items, long total, int page, int size) {

    public record Item(
            // 雪花 ID 远超 JS Number.MAX_SAFE_INTEGER (2^53-1)，必须以 String 序列化避免浏览器精度丢失（FX-071）
            @JsonSerialize(using = ToStringSerializer.class) long userId,
            String uid,
            String email,
            String status,
            boolean emailVerified,
            String groupCode,
            BigDecimal balanceUSD,
            OffsetDateTime lastLoginAt,
            OffsetDateTime createdAt,
            /** firstName + lastName 拼接结果，profile 未填时为 null */
            String fullName,
            /** STAGE-6-KYC：0=未认证 / 1=已通过简单 KYC */
            Integer kycLevel
    ) {
    }
}
