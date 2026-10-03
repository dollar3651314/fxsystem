package com.falconx.console.api;

import java.time.OffsetDateTime;

/**
 * STAGE-8-NOTIFICATION Phase 3：用户通知（管理端列表 / 详情）。
 *
 * <p>id / userId / relatedId 用 String 表示雪花 ID 防 JS 精度丢失。
 */
public record AdminNotificationItem(
        String id,
        String userId,
        String type,
        String templateCode,
        String level,
        String title,
        String body,
        String relatedKey,
        String relatedId,
        String payloadJson,
        String status,
        OffsetDateTime readAt,
        OffsetDateTime createdAt,
        /** 跨 schema enrich：用户对外短号（t_user.uid）。null = 未 enrich / 用户不存在。 */
        String userUid,
        /** 跨 schema enrich：用户邮箱。null = 未 enrich。 */
        String userEmail,
        /** 跨 schema enrich：用户姓名（firstName + lastName，profile 未填为 null）。 */
        String userFullName
) {
    /** Builder-style helper：基于已有 item 覆写 3 个用户信息字段，主字段不变。 */
    public AdminNotificationItem withUserInfo(String userUid, String userEmail, String userFullName) {
        return new AdminNotificationItem(id, userId, type, templateCode, level, title, body,
                relatedKey, relatedId, payloadJson, status, readAt, createdAt,
                userUid, userEmail, userFullName);
    }
}
