-- =============================================================
-- V12: STAGE-14C2 杠杆/MM tier 配置权限点 + 角色关联 + 菜单 seed
--
-- 新增 2 个权限点：
--   tier:view  — 查看杠杆档位配置（symbol/group 多档位）
--   tier:edit  — 新建/编辑/软删杠杆档位配置（高危）
--
-- 说明：
--   1) 权限点本身也会由 AdminPermissionDictionaryInitializer 在 Task 8 controller
--      加上 @RequiresPermission("tier:view"/"tier:edit") 后启动自动扫描入库。
--      这里显式 seed 是为了不依赖运行时自动入库的时序（避免上线初期角色关联/菜单
--      引用了尚未入库的权限点）。module=code 中 ":" 前部分、action=":" 后部分，
--      与 Initializer.registerPermission 解析口径一致；UNIQUE(code) 索引保证两条
--      路径幂等共存（WHERE NOT EXISTS + 自动 INSERT IGNORE 不冲突）。
--   2) SUPER_ADMIN 角色自动放行所有 @RequiresPermission（PermissionGuardService 通配），
--      无需在 t_admin_role_permission 中显式绑定。其余运营/风控角色按 V11 同款"平滑补齐"
--      策略：把 tier:view/tier:edit 关联到已持有 risk-config 权限的角色（tier 配置与
--      risk-config 同属交易风控运营范畴），避免上线后非 SUPER_ADMIN 看得到入口却无法操作。
--   3) 菜单挂在"交易监控"父菜单（V9 9500005 'trading'）下，path /admin/trading/tier-configs，
--      绑定 tier:view（持有该权限的角色 + SUPER_ADMIN 可见）。
--
-- ID 区段：9600001-9600010（避开 9100/9200/9300/9400/9500 已占用段）。
-- 不写 USE：schema 由 Flyway 连接绑定（14B root bug 教训）。
-- =============================================================

-- 1. 权限点字典（显式 seed，与运行时自动入库幂等共存）
INSERT INTO t_admin_permission (id, code, module, action, description)
SELECT * FROM (
    SELECT 9600001 AS id, 'tier:view' AS code, 'tier' AS module, 'view' AS action, '查看杠杆/MM 档位配置（symbol/group 多档位）' AS description
    UNION ALL
    SELECT 9600002    , 'tier:edit', 'tier', 'edit', '新建/编辑/软删杠杆/MM 档位配置（高危：影响开仓杠杆与维持保证金）'
) AS new_perms
WHERE NOT EXISTS (
    SELECT 1 FROM t_admin_permission p WHERE p.code = new_perms.code
);

-- 2. 角色 ↔ 权限点关联：把 tier:view 补齐到已持有 risk-config:view 的角色
INSERT INTO t_admin_role_permission (role_id, permission_code)
SELECT DISTINCT rp.role_id, 'tier:view'
FROM t_admin_role_permission rp
WHERE rp.permission_code = 'risk-config:view'
  AND NOT EXISTS (
      SELECT 1
      FROM t_admin_role_permission existing
      WHERE existing.role_id = rp.role_id
        AND existing.permission_code = 'tier:view'
  );

-- 2b. 角色 ↔ 权限点关联：把 tier:edit 补齐到已持有 risk-config:update 的角色
INSERT INTO t_admin_role_permission (role_id, permission_code)
SELECT DISTINCT rp.role_id, 'tier:edit'
FROM t_admin_role_permission rp
WHERE rp.permission_code = 'risk-config:update'
  AND NOT EXISTS (
      SELECT 1
      FROM t_admin_role_permission existing
      WHERE existing.role_id = rp.role_id
        AND existing.permission_code = 'tier:edit'
  );

-- 3. 菜单：交易监控（9500005）下新增"杠杆档位配置"
INSERT INTO t_admin_menu (id, parent_id, code, name, icon, path, permission_code, sort_order, is_visible)
SELECT * FROM (
    SELECT 9600010 AS id,
           9500005 AS parent_id,
           'trading-tier-configs' AS code,
           '杠杆档位配置'          AS name,
           'SlidersOutlined'       AS icon,
           '/admin/trading/tier-configs' AS path,
           'tier:view'             AS permission_code,
           700                     AS sort_order,
           1                       AS is_visible
) AS new_menu
WHERE NOT EXISTS (
    SELECT 1 FROM t_admin_menu m WHERE m.code = new_menu.code
);
