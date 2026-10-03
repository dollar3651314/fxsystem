package com.falconx.console.api;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * GET /admin/me 响应。
 *
 * @param adminUserId 管理员主键
 * @param username 登录用户名
 * @param realName 真实姓名（可空）
 * @param roles 角色列表（含 code + name）
 * @param mustChangePassword 是否必须先修改密码
 * @param lastLoginAt 最后登录时间
 * @param lastLoginIp 最后登录 IP
 */
public record AdminMeResponse(
        long adminUserId,
        String username,
        String realName,
        List<RoleSummary> roles,
        boolean mustChangePassword,
        OffsetDateTime lastLoginAt,
        String lastLoginIp
) {

    /**
     * 角色摘要（code + name 对，用于前端 Avatar 下拉显示）。
     */
    public record RoleSummary(String code, String name) {
    }
}
