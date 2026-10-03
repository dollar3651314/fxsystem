-- =============================================================
-- V2: RBAC 权限码迁移 symbol:update → symbol:source:update
-- 任务卡: STAGE-2-SYMBOL-PARAMS-DOWNSHIFT R9
--
-- 把所有持有 symbol:update 的角色自动补 symbol:source:update（idempotent）；
-- 旧权限码 symbol:update 标记为 deprecated（enabled=0）保留审计追溯，不删除。
-- =============================================================

-- 1. 把 symbol:update 的角色绑定补一份 symbol:source:update（idempotent，重复执行无副作用）
INSERT INTO t_admin_role_permission (role_id, permission_code)
SELECT DISTINCT rp1.role_id, 'symbol:source:update'
FROM t_admin_role_permission rp1
WHERE rp1.permission_code = 'symbol:update'
  AND NOT EXISTS (
      SELECT 1 FROM t_admin_role_permission rp2
      WHERE rp2.role_id = rp1.role_id AND rp2.permission_code = 'symbol:source:update'
  );

-- 2. 把旧权限码 symbol:update 在 description 上标 deprecated（t_admin_permission 无 enabled 列）。
-- 保留 t_admin_permission.code='symbol:update' 行用于审计追溯；启动时 HighRiskPermissionRegistry 不再注解扫描到该 code，
-- 但本行仍存在便于历史 t_admin_operation_log 的 join 不丢失。
UPDATE t_admin_permission
SET description = CONCAT(IFNULL(description, ''), ' [DEPRECATED@V2: replaced by symbol:source:update]')
WHERE code = 'symbol:update'
  AND description NOT LIKE '%[DEPRECATED@V2%';
