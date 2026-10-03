package com.falconx.console.entity;

import java.util.List;

/**
 * 管理后台菜单（树形结构）。
 *
 * <p>对应 {@code t_admin_menu} 表的领域视图。{@code parentId == null} 表示顶级菜单。
 *
 * @param id 主键
 * @param parentId 父菜单 ID（顶级菜单为 {@code null}）
 * @param code 菜单 code
 * @param name 显示名
 * @param icon 图标 code（可空）
 * @param path 前端路由路径（顶级菜单可空）
 * @param permissionCode 关联权限码（菜单按此过滤；可空表示无权限要求，但通常用于父菜单）
 * @param sortOrder 同级菜单排序（小→大）
 * @param visible 是否显示
 * @param children 子菜单（按 sortOrder 升序），叶子菜单为空列表
 */
public record AdminMenu(
        Long id,
        Long parentId,
        String code,
        String name,
        String icon,
        String path,
        String permissionCode,
        int sortOrder,
        boolean visible,
        List<AdminMenu> children
) {
}
