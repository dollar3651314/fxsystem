package com.falconx.identity.repository;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.falconx.domain.enums.UserStatus;
import com.falconx.identity.entity.IdentityUser;
import com.falconx.identity.entity.RefreshTokenSession;
import com.falconx.identity.repository.mapper.record.IdentityUserRecord;
import com.falconx.identity.repository.mapper.record.RefreshTokenSessionRecord;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * identity MyBatis 持久化映射支持工具。
 *
 * <p>该工具统一负责 identity owner 仓储中的三类转换：
 *
 * <ul>
 *   <li>领域实体与 MyBatis 记录对象之间的转换</li>
 *   <li>用户状态码与枚举之间的转换</li>
 *   <li>JSON payload 与时间类型的统一序列化规则</li>
 * </ul>
 *
 * <p>这样可以避免每个 MyBatis Repository 都各自重复维护一套映射逻辑。
 */
final class IdentityMybatisSupport {

    private IdentityMybatisSupport() {
    }

    /**
     * 把领域用户实体转换为 MyBatis 记录对象。
     *
     * @param user identity 领域用户
     * @return 可直接交给 Mapper 的记录对象
     */
    static IdentityUserRecord toRecord(IdentityUser user) {
        return new IdentityUserRecord(
                user.id(),
                user.uid(),
                user.email(),
                user.passwordHash(),
                toUserStatusCode(user.status()),
                normalizeGroupCode(user.groupCode()),
                user.emailVerified() ? 1 : 0,
                toLocalDateTime(user.activatedAt()),
                toLocalDateTime(user.lastLoginAt()),
                toLocalDateTime(user.createdAt()),
                toLocalDateTime(user.updatedAt())
        );
    }

    /**
     * 把 MyBatis 查询结果转换回 identity 领域用户实体。
     *
     * @param record MyBatis 查询记录
     * @return identity 领域用户；若记录为空则返回空
     */
    static IdentityUser toDomain(IdentityUserRecord record) {
        if (record == null) {
            return null;
        }
        return new IdentityUser(
                record.id(),
                record.uid(),
                record.email(),
                record.passwordHash(),
                toUserStatus(record.statusCode()),
                normalizeGroupCode(record.groupCode()),
                record.emailVerified() != null && record.emailVerified() == 1,
                toOffsetDateTime(record.activatedAt()),
                toOffsetDateTime(record.lastLoginAt()),
                toOffsetDateTime(record.createdAt()),
                toOffsetDateTime(record.updatedAt())
        );
    }

    /**
     * 把 Refresh Token 领域对象转换为 MyBatis 记录对象。
     *
     * @param session Refresh Token 会话
     * @param usedAt 标记已使用时间；未使用时可为空
     * @return 可直接用于 Mapper 写入的记录对象
     */
    static RefreshTokenSessionRecord toRecord(RefreshTokenSession session, OffsetDateTime usedAt) {
        return new RefreshTokenSessionRecord(
                session.jti(),
                session.userId(),
                toLocalDateTime(session.expiresAt()),
                session.used() ? 1 : 0,
                toLocalDateTime(session.issuedAt()),
                toLocalDateTime(usedAt),
                toLocalDateTime(session.issuedAt()),
                toLocalDateTime(OffsetDateTime.now())
        );
    }

    /**
     * 把 MyBatis Refresh Token 记录对象转换为领域对象。
     *
     * @param record Mapper 查询记录
     * @return 领域会话对象；若记录为空则返回空
     */
    static RefreshTokenSession toDomain(RefreshTokenSessionRecord record) {
        if (record == null) {
            return null;
        }
        return new RefreshTokenSession(
                record.jti(),
                record.userId(),
                toOffsetDateTime(record.expiresAt()),
                record.used() == 1,
                toOffsetDateTime(record.issuedAt())
        );
    }

    /**
     * 序列化 identity inbox payload。
     *
     * @param payload 原始事件 payload
     * @param objectMapper 统一 JSON 序列化器
     * @return JSON 字符串
     */
    static String toJson(Object payload, ObjectMapper objectMapper) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to serialize identity inbox payload", exception);
        }
    }

    /**
     * 把领域时间转换为数据库记录使用的无时区时间。
     *
     * @param value 领域时间
     * @return 本地日期时间
     */
    static LocalDateTime toLocalDateTime(OffsetDateTime value) {
        return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    /**
     * 把数据库返回的无时区时间恢复为统一 UTC 偏移时间。
     *
     * @param value 数据库时间
     * @return UTC 偏移时间
     */
    static OffsetDateTime toOffsetDateTime(LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    /**
     * 把用户状态枚举转换为数据库状态码。
     *
     * @param status 用户状态
     * @return 数据库存储状态码
     */
    static int toUserStatusCode(UserStatus status) {
        return switch (status) {
            case PENDING_DEPOSIT -> 0;
            case ACTIVE -> 1;
            case FROZEN -> 2;
            case BANNED -> 3;
        };
    }

    /**
     * 把数据库状态码恢复为领域状态枚举。
     *
     * @param statusCode 状态码
     * @return 用户状态
     */
    static UserStatus toUserStatus(int statusCode) {
        return switch (statusCode) {
            case 0 -> UserStatus.PENDING_DEPOSIT;
            case 1 -> UserStatus.ACTIVE;
            case 2 -> UserStatus.FROZEN;
            case 3 -> UserStatus.BANNED;
            default -> throw new IllegalStateException("Unsupported user status code: " + statusCode);
        };
    }

    static String normalizeGroupCode(String groupCode) {
        return groupCode == null || groupCode.isBlank() ? "default" : groupCode.trim();
    }
}
