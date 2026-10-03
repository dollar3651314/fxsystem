package com.falconx.identity.entity;

import com.falconx.domain.enums.UserStatus;
import java.time.OffsetDateTime;

/**
 * identity-service 用户实体。
 *
 * <p>该对象对应 `falconx_identity.t_user` 的内存骨架表示，
 * 用于冻结用户注册、登录和状态限制的最小字段形态。
 *
 * @param id 用户主键 ID
 * @param uid 对外展示 UID
 * @param email 归一化邮箱
 * @param passwordHash bcrypt 密码哈希
 * @param status 当前用户状态
 * @param groupCode 用户所属产品组代码，用于控制可见 symbol 范围
 * @param emailVerified 邮箱是否已完成可信度验证；一期只建模事实，不参与登录或交易授权
 * @param activatedAt 账户可用时间；新注册用户创建时即写入
 * @param lastLoginAt 最近登录时间
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record IdentityUser(
        Long id,
        String uid,
        String email,
        String passwordHash,
        UserStatus status,
        String groupCode,
        boolean emailVerified,
        OffsetDateTime activatedAt,
        OffsetDateTime lastLoginAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
