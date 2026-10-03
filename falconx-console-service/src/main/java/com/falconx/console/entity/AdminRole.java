package com.falconx.console.entity;

/**
 * 管理后台角色。
 *
 * <p>对应 {@code t_admin_role} 的最小领域视图（不含 description / is_system / 时间戳），
 * 用于登录与 me/permissions 等场景下读取当前管理员的角色 code 与显示名。
 *
 * @param id 角色主键
 * @param code 角色 code（如 {@code SUPER_ADMIN}、{@code FINANCE}）
 * @param name 角色显示名
 */
public record AdminRole(Long id, String code, String name) {
}
