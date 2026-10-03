package com.falconx.console.repository.mapper.record;

import java.time.LocalDateTime;

/**
 * {@code t_admin_user} 行记录。
 *
 * <p>字段与 {@code V1__init_console_schema.sql} 中 {@code t_admin_user} DDL 一一对应。
 *
 * @param id 雪花 ID 主键
 * @param username 登录用户名
 * @param passwordHash BCrypt 加密密码
 * @param realName 真实姓名（可空）
 * @param status TINYINT，1=ACTIVE，2=DISABLED
 * @param mustChangePassword TINYINT，1=必须改密
 * @param lastLoginAt 最后登录时间
 * @param lastLoginIp 最后登录 IP
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record AdminUserRecord(
        Long id,
        String username,
        String passwordHash,
        String realName,
        Integer status,
        Integer mustChangePassword,
        LocalDateTime lastLoginAt,
        String lastLoginIp,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
