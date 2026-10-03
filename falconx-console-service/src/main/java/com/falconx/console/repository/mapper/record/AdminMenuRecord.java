package com.falconx.console.repository.mapper.record;

/**
 * {@code t_admin_menu} 行记录（不含 children，children 在 Java 层组装）。
 *
 * @param id 主键
 * @param parentId 父菜单 ID（顶级菜单为 null）
 * @param code 菜单 code
 * @param name 显示名
 * @param icon 图标 code
 * @param path 前端路由
 * @param permissionCode 关联权限码
 * @param sortOrder 排序
 * @param isVisible TINYINT，1=visible
 */
public record AdminMenuRecord(
        Long id,
        Long parentId,
        String code,
        String name,
        String icon,
        String path,
        String permissionCode,
        Integer sortOrder,
        Integer isVisible
) {
}
