package com.falconx.console.repository.mapper.record;

/**
 * {@code t_admin_permission} 行记录。
 *
 * @param code 权限码
 * @param module 所属模块
 * @param action 动作
 * @param description 描述
 */
public record AdminPermissionRecord(String code, String module, String action, String description) {
}
