-- =============================================================
-- V8: STAGE-13 系统配置中心菜单 seed
-- 在管理后台侧边栏添加"系统配置"入口，前端路由 /admin/system-config。
-- 仅持有 system-config:view 权限的角色可见（SUPER_ADMIN 自动放行）。
-- 使用 9400010+ id 区段（与 V7 权限点 9400001 区段衔接）。
-- =============================================================

INSERT INTO t_admin_menu (id, parent_id, code, name, icon, path, permission_code, sort_order, is_visible)
SELECT * FROM (
    SELECT 9400010 AS id,
           NULL    AS parent_id,
           'system-config'   AS code,
           '系统配置'        AS name,
           'settings'         AS icon,
           '/admin/system-config' AS path,
           'system-config:view'  AS permission_code,
           9000   AS sort_order,
           1      AS is_visible
) AS new_menu
WHERE NOT EXISTS (
    SELECT 1 FROM t_admin_menu m WHERE m.code = new_menu.code
);
