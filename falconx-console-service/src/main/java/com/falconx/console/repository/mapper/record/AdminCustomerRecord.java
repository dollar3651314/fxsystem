package com.falconx.console.repository.mapper.record;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * STAGE-2-CUSTOMER：客户列表 / 详情 跨 schema JOIN 结果。
 *
 * <p>来源：{@code falconx_identity.t_user u LEFT JOIN falconx_trading.t_account a ON a.user_id = u.id AND a.currency = 'USDT'}
 *
 * <p>console DB user 必须配置 identity + trading schema 的 SELECT 权限（[`管理端架构`](docs/architecture/管理端架构.md) §1.3）。
 */
public record AdminCustomerRecord(
        Long userId,
        String uid,
        String email,
        Integer status,
        Integer emailVerified,
        String groupCode,
        BigDecimal balance,
        BigDecimal frozen,
        BigDecimal marginUsed,
        LocalDateTime activatedAt,
        LocalDateTime lastLoginAt,
        String lastLoginIp,
        LocalDateTime createdAt,
        /** STAGE-6-KYC：t_user.kyc_level，0=未认证 / 1=已通过简单 KYC */
        Integer kycLevel,
        /** t_user_profile.first_name，LEFT JOIN 后可能为 null（注册早于 STAGE-1B-USER-PROFILE 的历史数据） */
        String firstName,
        /** t_user_profile.last_name，同上可能为 null */
        String lastName
) {
}
