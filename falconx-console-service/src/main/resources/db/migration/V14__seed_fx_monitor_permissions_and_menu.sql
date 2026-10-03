-- =============================================================
-- V14: STAGE-14E2 Task3 FX 实时汇率监控权限点 + 角色关联 + 菜单 seed
--
-- 新增 1 个权限点：
--   fx:view  — 查看 FX 实时汇率监控（只读，非高危，不入 HighRiskPermissionRegistry）
--
-- 说明：
--   1) 权限点本身也会由 AdminPermissionDictionaryInitializer 在 AdminMarketFxController
--      加上 @RequiresPermission("fx:view") 后启动自动扫描入库。这里显式 seed 是为了不依赖
--      运行时自动入库的时序。module = code 中第一个 ":" 之前部分、action = ":" 之后部分，
--      与 Initializer.registerPermission 解析口径一致；fx:view → module=fx, action=view。
--      注意：fx module 已由 V13 fx:pause-behavior:* 引入，此处 fx:view 为不同 code，
--      UNIQUE(code) 保证幂等共存（WHERE NOT EXISTS + 自动 INSERT IGNORE 不冲突）。
--   2) SUPER_ADMIN 角色自动放行所有 @RequiresPermission（PermissionGuardService 通配），
--      无需在 t_admin_role_permission 中显式绑定。其余运营/风控角色按 V12/V13 同款"平滑补齐"
--      策略：fx:view 关联到已持有 risk-config:view 的角色（FX 监控与 risk-config 同属
--      交易风控运营范畴）。fx:view 只读，不关联 :edit。
--   3) 菜单挂在"交易监控"父菜单（V9 9500005 'trading'，与 market/trading 监控页同父）下，
--      path /admin/market/fx-rates（controller @RequestMapping("/admin/market")），
--      绑定 fx:view（持有该权限的角色 + SUPER_ADMIN 可见）。icon 用 ICON_MAP 已有键
--      LineChartOutlined。
--
-- ID 区段：9800001（权限）+ 9800010（菜单），避开 9100-9700 已占用段。
-- 不写 USE：schema 由 Flyway 连接绑定（14B root bug 教训）。
-- =============================================================

-- 1. 权限点字典（显式 seed，与运行时自动入库幂等共存）
INSERT INTO t_admin_permission (id, code, module, action, description)
SELECT * FROM (
    SELECT 9800001 AS id, 'fx:view' AS code, 'fx' AS module, 'view' AS action, '查看 FX 实时汇率监控（只读）' AS description
) AS new_perms
WHERE NOT EXISTS (
    SELECT 1 FROM t_admin_permission p WHERE p.code = new_perms.code
);

-- 2. 角色关联：把 fx:view 补齐到已持有 risk-config:view 的角色
INSERT INTO t_admin_role_permission (role_id, permission_code)
SELECT DISTINCT rp.role_id, 'fx:view'
FROM t_admin_role_permission rp
WHERE rp.permission_code = 'risk-config:view'
  AND NOT EXISTS (
      SELECT 1
      FROM t_admin_role_permission existing
      WHERE existing.role_id = rp.role_id
        AND existing.permission_code = 'fx:view'
  );

-- 3. 菜单：交易监控（9500005）下新增"FX 汇率监控"
INSERT INTO t_admin_menu (id, parent_id, code, name, icon, path, permission_code, sort_order, is_visible)
SELECT * FROM (
    SELECT 9800010 AS id,
           9500005 AS parent_id,
           'market-fx-rates'     AS code,
           'FX 汇率监控'          AS name,
           'LineChartOutlined'   AS icon,
           '/admin/market/fx-rates' AS path,
           'fx:view'             AS permission_code,
           1100                  AS sort_order,
           1                     AS is_visible
) AS new_menu
WHERE NOT EXISTS (
    SELECT 1 FROM t_admin_menu m WHERE m.code = new_menu.code
);
