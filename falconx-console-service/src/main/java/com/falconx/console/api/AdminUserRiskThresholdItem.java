package com.falconx.console.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * BBOOK-RISK-CONTROL-01：用户级风控阈值列表项。
 */
public record AdminUserRiskThresholdItem(
        Long userId,
        BigDecimal netExposureThresholdUsd,
        BigDecimal profitableNetExposureThresholdUsd,
        boolean profitableUser,
        String updatedBy,
        String updatedReason,
        LocalDateTime updatedAt,
        /** 跨 schema enrich：用户对外短号（t_user.uid）。null = 未 enrich / 用户不存在。 */
        String userUid,
        /** 跨 schema enrich：用户邮箱。null = 未 enrich。 */
        String userEmail,
        /** 跨 schema enrich：用户姓名（firstName + lastName，profile 未填为 null）。 */
        String userFullName
) {
    /** Builder-style helper：基于已有 item 覆写 3 个用户信息字段，主字段不变。 */
    public AdminUserRiskThresholdItem withUserInfo(String userUid, String userEmail, String userFullName) {
        return new AdminUserRiskThresholdItem(userId, netExposureThresholdUsd,
                profitableNetExposureThresholdUsd, profitableUser, updatedBy, updatedReason, updatedAt,
                userUid, userEmail, userFullName);
    }
}
