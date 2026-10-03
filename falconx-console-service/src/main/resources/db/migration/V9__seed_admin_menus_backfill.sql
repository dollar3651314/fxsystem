-- =============================================================
-- V9: STAGE-13 把 AdminLayout 硬编码的 25 个菜单 backfill 到 t_admin_menu
--
-- 历史问题：falconx-console-frontend/src/features/layout/AdminLayout.tsx 用 JSX 硬编码菜单，
-- 不读 t_admin_menu 表 → 菜单管理页只显示 1 条记录（V8 加的 system-config）。
-- 本 V9 把现有所有菜单 INSERT 到表，让"菜单管理"页能完整显示。后续 AdminLayout 会改
-- 为调 GET /admin/me/menus API 读 DB，废弃 JSX 硬编码。
--
-- ID 区段 9500001-9500030（避开 9100/9200/9300/9400 已有段）。
-- 每条 INSERT 用 WHERE NOT EXISTS 防止 code 重复（包括 V8 已加的 system-config）。
-- permission_code 全部留 NULL 表示无权限要求（SUPER_ADMIN 看所有，其他角色按未来扩展过滤）。
-- =============================================================

-- 顶级菜单（parent_id = NULL）
INSERT INTO t_admin_menu (id, parent_id, code, name, icon, path, permission_code, sort_order, is_visible)
SELECT * FROM (
    SELECT 9500001 AS id, NULL AS parent_id, 'dashboard' AS code, '仪表盘' AS name, 'DashboardOutlined' AS icon, '/admin' AS path, NULL AS permission_code, 100 AS sort_order, 1 AS is_visible
    UNION ALL SELECT 9500002, NULL, 'customers', '客户管理', 'TeamOutlined', '/admin/customers', NULL, 200, 1
    UNION ALL SELECT 9500003, NULL, 'deposits', '入金记录', 'DownloadOutlined', '/admin/deposits', NULL, 300, 1
    UNION ALL SELECT 9500004, NULL, 'symbols', '行情品种', 'AreaChartOutlined', NULL, NULL, 400, 1
    UNION ALL SELECT 9500005, NULL, 'trading', '交易监控', 'LineChartOutlined', NULL, NULL, 500, 1
    UNION ALL SELECT 9500006, NULL, 'risk-admin', '风控管理', 'SafetyOutlined', NULL, NULL, 600, 1
    UNION ALL SELECT 9500007, NULL, 'rbac', 'RBAC 自管', 'SafetyCertificateOutlined', NULL, NULL, 700, 1
    UNION ALL SELECT 9500008, NULL, 'audit-logs', '审计日志', 'AuditOutlined', '/admin/audit-logs', NULL, 800, 1
    UNION ALL SELECT 9500009, NULL, 'reconciliation-deposits', '入金对账', 'ReconciliationOutlined', '/admin/reconciliation/deposits', NULL, 850, 1
    -- 注：system-config（9300010 / 9400010 二选一历史）由 V8 seed，此处 NOT EXISTS 跳过；保持 sort_order 9000 排末尾
) AS new_menu
WHERE NOT EXISTS (SELECT 1 FROM t_admin_menu m WHERE m.code = new_menu.code);

-- 行情品种子菜单（parent=9500004）
INSERT INTO t_admin_menu (id, parent_id, code, name, icon, path, permission_code, sort_order, is_visible)
SELECT * FROM (
    SELECT 9500011 AS id, 9500004 AS parent_id, 'symbols-list' AS code, '品种管理' AS name, NULL AS icon, '/admin/symbols' AS path, NULL AS permission_code, 100 AS sort_order, 1 AS is_visible
    UNION ALL SELECT 9500012, 9500004, 'symbols-group-markup', '用户组加点', NULL, '/admin/symbols/group-markup', NULL, 200, 1
) AS new_menu
WHERE NOT EXISTS (SELECT 1 FROM t_admin_menu m WHERE m.code = new_menu.code);

-- 交易监控子菜单（parent=9500005）
INSERT INTO t_admin_menu (id, parent_id, code, name, icon, path, permission_code, sort_order, is_visible)
SELECT * FROM (
    SELECT 9500013 AS id, 9500005 AS parent_id, 'trading-orders' AS code, '订单监控' AS name, 'StockOutlined' AS icon, '/admin/trading/orders' AS path, NULL AS permission_code, 100 AS sort_order, 1 AS is_visible
    UNION ALL SELECT 9500014, 9500005, 'trading-positions', '持仓监控', 'FundOutlined', '/admin/trading/positions', NULL, 200, 1
    UNION ALL SELECT 9500015, 9500005, 'trading-pending-orders', '挂单监控', 'StockOutlined', '/admin/trading/pending-orders', NULL, 300, 1
    UNION ALL SELECT 9500016, 9500005, 'trading-price-alerts', '价格告警监控', 'AlertOutlined', '/admin/trading/price-alerts', NULL, 400, 1
    UNION ALL SELECT 9500017, 9500005, 'trading-exposures', '净敞口看板', 'ThunderboltOutlined', '/admin/trading/exposures', NULL, 500, 1
    UNION ALL SELECT 9500018, 9500005, 'trading-risk-switches', '风控开关', 'ControlOutlined', '/admin/trading/risk-switches', NULL, 600, 1
) AS new_menu
WHERE NOT EXISTS (SELECT 1 FROM t_admin_menu m WHERE m.code = new_menu.code);

-- 风控管理子菜单（parent=9500006）
INSERT INTO t_admin_menu (id, parent_id, code, name, icon, path, permission_code, sort_order, is_visible)
SELECT * FROM (
    SELECT 9500019 AS id, 9500006 AS parent_id, 'risk-actions' AS code, '风控动作' AS name, 'AlertOutlined' AS icon, '/admin/risk/actions' AS path, NULL AS permission_code, 100 AS sort_order, 1 AS is_visible
    UNION ALL SELECT 9500020, 9500006, 'risk-configs', '风控配置', 'DatabaseOutlined', '/admin/risk/configs', NULL, 200, 1
    UNION ALL SELECT 9500021, 9500006, 'risk-market-configs', '市场集中度', 'GlobalOutlined', '/admin/risk/market-configs', NULL, 300, 1
    UNION ALL SELECT 9500022, 9500006, 'risk-platform', '平台总敞口', 'GlobalOutlined', '/admin/risk/platform', NULL, 400, 1
    UNION ALL SELECT 9500023, 9500006, 'risk-user-thresholds', '用户级阈值', 'SafetyOutlined', '/admin/risk/user-thresholds', NULL, 500, 1
) AS new_menu
WHERE NOT EXISTS (SELECT 1 FROM t_admin_menu m WHERE m.code = new_menu.code);

-- RBAC 自管子菜单（parent=9500007）
INSERT INTO t_admin_menu (id, parent_id, code, name, icon, path, permission_code, sort_order, is_visible)
SELECT * FROM (
    SELECT 9500024 AS id, 9500007 AS parent_id, 'users' AS code, '管理员' AS name, 'SolutionOutlined' AS icon, '/admin/users' AS path, NULL AS permission_code, 100 AS sort_order, 1 AS is_visible
    UNION ALL SELECT 9500025, 9500007, 'roles', '角色', 'ApartmentOutlined', '/admin/roles', NULL, 200, 1
    UNION ALL SELECT 9500026, 9500007, 'menus', '菜单', 'MenuOutlined', '/admin/menus', NULL, 300, 1
    UNION ALL SELECT 9500027, 9500007, 'permissions', '权限点', 'KeyOutlined', '/admin/permissions', NULL, 400, 1
) AS new_menu
WHERE NOT EXISTS (SELECT 1 FROM t_admin_menu m WHERE m.code = new_menu.code);
