package com.falconx.identity.repository.mapper.record;

import java.time.LocalDateTime;

/**
 * identity 用户 MyBatis 记录对象。
 *
 * <p>该记录对象与 `t_user` 字段一一对应，
 * 用于 Repository 与 XML Mapper 之间传递持久化数据。
 *
 * @param id 用户主键
 * @param uid 对外展示 UID
 * @param email 登录邮箱
 * @param passwordHash 密码哈希
 * @param statusCode 用户状态码
 * @param groupCode 用户所属产品组代码
 * @param emailVerified 邮箱可信度验证状态码
 * @param activatedAt 激活时间
 * @param lastLoginAt 最后登录时间
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record IdentityUserRecord(
        Long id,
        String uid,
        String email,
        String passwordHash,
        Integer statusCode,
        String groupCode,
        Integer emailVerified,
        LocalDateTime activatedAt,
        LocalDateTime lastLoginAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
