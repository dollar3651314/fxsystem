package com.falconx.console.api;

import java.time.OffsetDateTime;

public record AdminKycItem(
        String submissionId,
        String userId,
        int level,
        String status,
        String idType,
        String idNumber,
        OffsetDateTime submittedAt,
        String reviewerId,
        OffsetDateTime reviewAt,
        String rejectReason,
        /** 跨 schema enrich：用户对外短号（t_user.uid）。null = 未 enrich / 用户不存在。 */
        String userUid,
        /** 跨 schema enrich：用户邮箱。null = 未 enrich。 */
        String userEmail,
        /** 跨 schema enrich：用户姓名（firstName + lastName，profile 未填为 null）。 */
        String userFullName
) {
    /** Builder-style helper：基于已有 item 覆写 3 个用户信息字段，主字段不变。 */
    public AdminKycItem withUserInfo(String userUid, String userEmail, String userFullName) {
        return new AdminKycItem(submissionId, userId, level, status, idType, idNumber, submittedAt,
                reviewerId, reviewAt, rejectReason, userUid, userEmail, userFullName);
    }
}
