package com.falconx.identity.entity;

import java.time.OffsetDateTime;

/**
 * Refresh Token 会话实体。
 *
 * <p>该对象用于表达 Refresh Token 的一次性使用状态，
 * 后续接入 Redis 或数据库时应沿用相同业务语义。
 *
 * @param jti Refresh Token 唯一 ID
 * @param userId 用户主键 ID
 * @param expiresAt 过期时间
 * @param used 是否已使用
 * @param issuedAt 签发时间
 */
public record RefreshTokenSession(
        String jti,
        Long userId,
        OffsetDateTime expiresAt,
        boolean used,
        OffsetDateTime issuedAt
) {
}
