package com.falconx.console.api;

import java.util.List;

/**
 * GET /admin/me/menus 响应（树形）。
 *
 * @param menus 已按权限过滤 + 隐藏空父节点 + sortOrder 升序的菜单树
 */
public record AdminMeMenusResponse(List<MenuNode> menus) {

    /**
     * 菜单节点（递归结构）。
     *
     * @param code 菜单 code
     * @param name 显示名
     * @param icon 图标 code
     * @param path 前端路由
     * @param permissionCode 关联权限码
     * @param children 子菜单（已按权限过滤；叶子菜单为空列表）
     */
    public record MenuNode(
            String code,
            String name,
            String icon,
            String path,
            String permissionCode,
            List<MenuNode> children
    ) {
    }
}
