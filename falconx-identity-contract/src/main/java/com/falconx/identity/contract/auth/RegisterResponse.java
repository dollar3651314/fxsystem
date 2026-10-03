package com.falconx.identity.contract.auth;

/**
 * 注册响应契约。
 *
 * <p>该对象用于向调用方返回最小用户注册结果，
 * 包括用户主键、对外 UID、归一化邮箱、当前状态和邮箱可信度事实。
 *
 * @param userId 用户主键 ID
 * @param uid 对外展示 UID
 * @param email 归一化后的邮箱
 * @param status 当前用户状态
 * @param emailVerified 邮箱是否已完成可信度验证
 */
public record RegisterResponse(
        Long userId,
        String uid,
        String email,
        String status,
        boolean emailVerified
) {
}
