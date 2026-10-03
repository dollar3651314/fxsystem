-- =============================================================
-- V13: STAGE-14D3b 平台配置权限点 + 角色关联 + 菜单 seed
--
-- 新增 6 个权限点：
--   margin-mode-config:view  — 查看保证金模式冷静期配置
--   margin-mode-config:edit  — 编辑保证金模式冷静期配置（高危）
--   risk-threshold:view      — 查看 StopOut/MarginCall 阈值配置
--   risk-threshold:edit      — 编辑 StopOut/MarginCall 阈值配置（高危）
--   fx:pause-behavior:view   — 查看 FX_PAUSED 行为配置（按类目）
--   fx:pause-behavior:edit   — 编辑 FX_PAUSED 行为配置（按类目，高危）
--
-- 说明：
--   1) 权限点本身也会由 AdminPermissionDictionaryInitializer 在 D3b Task1 controller
--      加上 @RequiresPermission 后启动自动扫描入库。这里显式 seed 是为了不依赖运行时
--      自动入库的时序。module = code 中第一个 ":" 之前部分，action = ":" 之后全部，
--      与 Initializer.registerPermission 解析口径一致（indexOf(':') 取首个冒号）。
--      fx:pause-behavior:view → module=fx, action=pause-behavior:view。
--      UNIQUE(code) 索引保证两条路径幂等共存（WHERE NOT EXISTS + INSERT IGNORE 不冲突）。
--   2) SUPER_ADMIN 角色自动放行所有 @RequiresPermission（PermissionGuardService 通配），
--      无需在 t_admin_role_permission 中显式绑定。其余运营/风控角色按 V12 同款"平滑补齐"
--      策略：:view 关联到已持有 risk-config:view 的角色，:edit 关联到已持有
--      risk-config:update 的角色（与 tier 同属交易风控运营范畴）。
--   3) 菜单挂在"交易监控"父菜单（V9 9500005 'trading'）下，与 tier-configs 同父，
--      path 前缀 /admin/trading/，绑定对应 :view 权限码。
--
-- ID 区段：9700001-9700012（避开 9100-9600 已占用段）。
-- 不写 USE：schema 由 Flyway 连接绑定（14B root bug 教训）。
-- =============================================================

-- 1. 权限点字典（显式 seed，与运行时自动入库幂等共存）
INSERT INTO t_admin_permission (id, code, module, action, description)
SELECT * FROM (
    SELECT 9700001 AS id, 'margin-mode-config:view' AS code, 'margin-mode-config' AS module, 'view' AS action, '查看保证金模式冷静期配置' AS description
    UNION ALL
    SELECT 9700002, 'margin-mode-config:edit', 'margin-mode-config', 'edit', '编辑保证金模式冷静期配置（高危）'
    UNION ALL
    SELECT 9700003, 'risk-threshold:view', 'risk-threshold', 'view', '查看 StopOut/MarginCall 阈值配置'
    UNION ALL
    SELECT 9700004, 'risk-threshold:edit', 'risk-threshold', 'edit', '编辑 StopOut/MarginCall 阈值配置（高危）'
    UNION ALL
    SELECT 9700005, 'fx:pause-behavior:view', 'fx', 'pause-behavior:view', '查看 FX_PAUSED 行为配置（按类目）'
    UNION ALL
    SELECT 9700006, 'fx:pause-behavior:edit', 'fx', 'pause-behavior:edit', '编辑 FX_PAUSED 行为配置（按类目，高危）'
) AS new_perms
WHERE NOT EXISTS (
    SELECT 1 FROM t_admin_permission p WHERE p.code = new_perms.code
);

-- 2a. 角色关联：把 margin-mode-config:view 补齐到已持有 risk-config:view 的角色
INSERT INTO t_admin_role_permission (role_id, permission_code)
SELECT DISTINCT rp.role_id, 'margin-mode-config:view'
FROM t_admin_role_permission rp
WHERE rp.permission_code = 'risk-config:view'
  AND NOT EXISTS (
      SELECT 1
      FROM t_admin_role_permission existing
      WHERE existing.role_id = rp.role_id
        AND existing.permission_code = 'margin-mode-config:view'
  );

-- 2b. 角色关联：把 margin-mode-config:edit 补齐到已持有 risk-config:update 的角色
INSERT INTO t_admin_role_permission (role_id, permission_code)
SELECT DISTINCT rp.role_id, 'margin-mode-config:edit'
FROM t_admin_role_permission rp
WHERE rp.permission_code = 'risk-config:update'
  AND NOT EXISTS (
      SELECT 1
      FROM t_admin_role_permission existing
      WHERE existing.role_id = rp.role_id
        AND existing.permission_code = 'margin-mode-config:edit'
  );

-- 2c. 角色关联：把 risk-threshold:view 补齐到已持有 risk-config:view 的角色
INSERT INTO t_admin_role_permission (role_id, permission_code)
SELECT DISTINCT rp.role_id, 'risk-threshold:view'
FROM t_admin_role_permission rp
WHERE rp.permission_code = 'risk-config:view'
  AND NOT EXISTS (
      SELECT 1
      FROM t_admin_role_permission existing
      WHERE existing.role_id = rp.role_id
        AND existing.permission_code = 'risk-threshold:view'
  );

-- 2d. 角色关联：把 risk-threshold:edit 补齐到已持有 risk-config:update 的角色
INSERT INTO t_admin_role_permission (role_id, permission_code)
SELECT DISTINCT rp.role_id, 'risk-threshold:edit'
FROM t_admin_role_permission rp
WHERE rp.permission_code = 'risk-config:update'
  AND NOT EXISTS (
      SELECT 1
      FROM t_admin_role_permission existing
      WHERE existing.role_id = rp.role_id
        AND existing.permission_code = 'risk-threshold:edit'
  );

-- 2e. 角色关联：把 fx:pause-behavior:view 补齐到已持有 risk-config:view 的角色
INSERT INTO t_admin_role_permission (role_id, permission_code)
SELECT DISTINCT rp.role_id, 'fx:pause-behavior:view'
FROM t_admin_role_permission rp
WHERE rp.permission_code = 'risk-config:view'
  AND NOT EXISTS (
      SELECT 1
      FROM t_admin_role_permission existing
      WHERE existing.role_id = rp.role_id
        AND existing.permission_code = 'fx:pause-behavior:view'
  );

-- 2f. 角色关联：把 fx:pause-behavior:edit 补齐到已持有 risk-config:update 的角色
INSERT INTO t_admin_role_permission (role_id, permission_code)
SELECT DISTINCT rp.role_id, 'fx:pause-behavior:edit'
FROM t_admin_role_permission rp
WHERE rp.permission_code = 'risk-config:update'
  AND NOT EXISTS (
      SELECT 1
      FROM t_admin_role_permission existing
      WHERE existing.role_id = rp.role_id
        AND existing.permission_code = 'fx:pause-behavior:edit'
  );

-- 3. 菜单：交易监控（9500005）下新增 3 个平台配置页
INSERT INTO t_admin_menu (id, parent_id, code, name, icon, path, permission_code, sort_order, is_visible)
SELECT * FROM (
    SELECT 9700010 AS id,
           9500005 AS parent_id,
           'trading-margin-mode-config' AS code,
           '冷静期配置'                  AS name,
           'ControlOutlined'            AS icon,
           '/admin/trading/margin-mode-config' AS path,
           'margin-mode-config:view'    AS permission_code,
           800                          AS sort_order,
           1                            AS is_visible
) AS new_menu
WHERE NOT EXISTS (
    SELECT 1 FROM t_admin_menu m WHERE m.code = new_menu.code
);

INSERT INTO t_admin_menu (id, parent_id, code, name, icon, path, permission_code, sort_order, is_visible)
SELECT * FROM (
    SELECT 9700011 AS id,
           9500005 AS parent_id,
           'trading-risk-threshold'     AS code,
           'StopOut 阈值配置'            AS name,
           'SafetyOutlined'             AS icon,
           '/admin/trading/risk-thresholds' AS path,
           'risk-threshold:view'        AS permission_code,
           900                          AS sort_order,
           1                            AS is_visible
) AS new_menu
WHERE NOT EXISTS (
    SELECT 1 FROM t_admin_menu m WHERE m.code = new_menu.code
);

INSERT INTO t_admin_menu (id, parent_id, code, name, icon, path, permission_code, sort_order, is_visible)
SELECT * FROM (
    SELECT 9700012 AS id,
           9500005 AS parent_id,
           'trading-fx-pause-behavior'  AS code,
           'FX 暂停行为配置'              AS name,
           'SettingOutlined'            AS icon,
           '/admin/trading/fx-pause-behavior' AS path,
           'fx:pause-behavior:view'     AS permission_code,
           1000                         AS sort_order,
           1                            AS is_visible
) AS new_menu
WHERE NOT EXISTS (
    SELECT 1 FROM t_admin_menu m WHERE m.code = new_menu.code
);
