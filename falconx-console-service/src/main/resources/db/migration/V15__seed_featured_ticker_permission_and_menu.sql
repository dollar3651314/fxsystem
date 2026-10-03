-- =============================================================
-- V15: FEATURED-TICKER 跑马灯热门产品配置权限点 + 角色关联 + 菜单 seed
--
-- 新增 1 个权限点：
--   symbol:featured:update — 配置跑马灯热门产品（全量替换，高风险写）
--   读用既有 symbol:view（菜单 permission_code NULL，与 group-markup 同款全可见，
--   写按钮由前端 RequiresPermission(symbol:featured:update) 控制）。
--
-- 说明（与 V14 同口径）：
--   1) 权限点也会由 AdminPermissionDictionaryInitializer 扫描 AdminSymbolsController
--      @RequiresPermission("symbol:featured:update") 自动入库；此处显式 seed 防时序依赖。
--      解析口径：module = 第一个 ":" 之前 = symbol，action = 其后 = featured:update。
--   2) SUPER_ADMIN 自动放行所有 @RequiresPermission，无需显式绑定。其余角色平滑补齐：
--      symbol:featured:update 关联到已持有 symbol:group-markup:update 的角色（同 symbol 运营范畴）。
--   3) 菜单挂"行情品种"父菜单（V9 9500004 'symbols'）下，path /admin/symbols/featured，
--      permission_code NULL（与 symbols-group-markup 一致：登录管理员可见，写权限按钮级控制）。
--
-- ID 区段：9800020（权限）+ 9800021（菜单），避开已占用段。
-- 不写 USE：schema 由 Flyway 连接绑定（14B root bug 教训）。
-- =============================================================

-- 1. 权限点字典（显式 seed，与运行时自动入库幂等共存）
INSERT INTO t_admin_permission (id, code, module, action, description)
SELECT * FROM (
    SELECT 9800020 AS id, 'symbol:featured:update' AS code, 'symbol' AS module,
           'featured:update' AS action, '配置跑马灯热门产品（全量替换）' AS description
) AS new_perms
WHERE NOT EXISTS (
    SELECT 1 FROM t_admin_permission p WHERE p.code = new_perms.code
);

-- 2. 角色关联：补齐到已持有 symbol:group-markup:update 的角色
INSERT INTO t_admin_role_permission (role_id, permission_code)
SELECT DISTINCT rp.role_id, 'symbol:featured:update'
FROM t_admin_role_permission rp
WHERE rp.permission_code = 'symbol:group-markup:update'
  AND NOT EXISTS (
      SELECT 1
      FROM t_admin_role_permission existing
      WHERE existing.role_id = rp.role_id
        AND existing.permission_code = 'symbol:featured:update'
  );

-- 3. 菜单：行情品种（9500004）下新增"跑马灯热门产品"
INSERT INTO t_admin_menu (id, parent_id, code, name, icon, path, permission_code, sort_order, is_visible)
SELECT * FROM (
    SELECT 9800021 AS id,
           9500004 AS parent_id,
           'symbols-featured'        AS code,
           '跑马灯热门产品'           AS name,
           NULL                      AS icon,
           '/admin/symbols/featured' AS path,
           NULL                      AS permission_code,
           250                       AS sort_order,
           1                         AS is_visible
) AS new_menu
WHERE NOT EXISTS (
    SELECT 1 FROM t_admin_menu m WHERE m.code = new_menu.code
);
