package com.falconx.console.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

/**
 * STAGE-2-CUSTOMER：客户详情响应（STAGE-1B-USER-PROFILE 扩展 profile 字段）。
 */
public record AdminCustomerDetailResponse(
        // 雪花 ID 必须 String 序列化（FX-071）
        @JsonSerialize(using = ToStringSerializer.class) long userId,
        String uid,
        String email,
        String status,
        boolean emailVerified,
        String groupCode,
        OffsetDateTime activatedAt,
        OffsetDateTime lastLoginAt,
        String lastLoginIp,
        Balance balance,
        OffsetDateTime createdAt,
        /** STAGE-6-KYC：0=未认证 / 1=已通过简单 KYC（来源 t_user.kyc_level） */
        int kycLevel,
        /** STAGE-1B-USER-PROFILE：基础资料；profile 缺失时为 null（理论上注册流程已写入，仅历史数据可能缺）。 */
        Profile profile
) {

    public record Balance(BigDecimal totalUSD, BigDecimal availableUSD, BigDecimal marginUsedUSD) {
    }

    public record Profile(
            String firstName,
            String middleName,
            String lastName,
            LocalDate birthDate,
            String nationality,
            Integer gender,
            String residenceCountry,
            String residenceState,
            String residenceCity,
            String residenceAddress,
            String residencePostalCode,
            String phoneCountryCode,
            String phoneNumber,
            String languagePreference,
            String timezone,
            boolean profileVerified
    ) {
    }
}
