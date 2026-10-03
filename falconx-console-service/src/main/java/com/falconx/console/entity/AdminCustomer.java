package com.falconx.console.entity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * STAGE-2-CUSTOMER：客户领域视图（跨 schema JOIN 结果）。
 *
 * <p>{@code status} 用 String 表达 UserStatus 枚举名（如 {@code ACTIVE} / {@code FROZEN} / {@code BANNED} / {@code PENDING_DEPOSIT}），
 * 在 Repository 层从数据库 TINYINT 转换。
 */
public record AdminCustomer(
        long userId,
        String uid,
        String email,
        String status,
        boolean emailVerified,
        String groupCode,
        BigDecimal balance,
        BigDecimal frozen,
        BigDecimal marginUsed,
        OffsetDateTime activatedAt,
        OffsetDateTime lastLoginAt,
        String lastLoginIp,
        OffsetDateTime createdAt,
        /** STAGE-6-KYC：0=未认证 / 1=已通过简单 KYC */
        int kycLevel,
        /** 拼接 firstName + lastName，profile 未填时为 null（让前端显示「-」） */
        String fullName
) {

    /** 可用余额 = balance - frozen - marginUsed（按 trading-core TradingAccount.available()）。 */
    public BigDecimal available() {
        return balance.subtract(frozen).subtract(marginUsed);
    }
}
