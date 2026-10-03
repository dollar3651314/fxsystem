-- =============================================================
-- V5: STAGE-2-DEPOSIT R9 权限点种入 t_admin_permission
--
-- 新增 1 个权限点：deposit:view（纯只读，无高危）
--
-- 注：使用 9300001 id。
-- =============================================================

INSERT INTO t_admin_permission (id, code, module, action, description)
SELECT 9300001, 'deposit:view', 'deposit', 'view', '查看入金记录'
WHERE NOT EXISTS (
    SELECT 1 FROM t_admin_permission p WHERE p.code = 'deposit:view'
);
