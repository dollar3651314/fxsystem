package com.falconx.identity.repository.mapper.record;

import java.time.LocalDateTime;

/**
 * Refresh Token 会话 MyBatis 记录对象。
 *
 * <p>该记录对象对应 `t_refresh_token_session` 的数据库结构，
 * 用于把一次性使用会话的持久化字段从领域模型中分离出来。
 *
 * @param jti Refresh Token 唯一 ID
 * @param userId 用户主键 ID
 * @param expiresAt 过期时间
 * @param used 是否已使用，0=未使用，1=已使用
 * @param issuedAt 签发时间
 * @param usedAt 标记已使用时间
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record RefreshTokenSessionRecord(
        String jti,
        Long userId,
        LocalDateTime expiresAt,
        Integer used,
        LocalDateTime issuedAt,
        LocalDateTime usedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
