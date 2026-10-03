-- V11: Symbol 交易时段与市场节假日管理权限迁移。
-- 启动扫描 @RequiresPermission 会自动把权限点写入 t_admin_permission；本迁移把已有
-- symbol 配置运营角色平滑补齐到新增写权限，避免上线后非 SUPER_ADMIN 看得到入口但无法保存。

INSERT INTO t_admin_role_permission (role_id, permission_code)
SELECT DISTINCT rp.role_id, 'symbol:trading-hours:update'
FROM t_admin_role_permission rp
WHERE rp.permission_code IN ('symbol:quote-mapping:update', 'symbol:swap-rate:update')
  AND NOT EXISTS (
      SELECT 1
      FROM t_admin_role_permission existing
      WHERE existing.role_id = rp.role_id
        AND existing.permission_code = 'symbol:trading-hours:update'
  );

INSERT INTO t_admin_role_permission (role_id, permission_code)
SELECT DISTINCT rp.role_id, 'symbol:holiday:update'
FROM t_admin_role_permission rp
WHERE rp.permission_code IN ('symbol:quote-mapping:update', 'symbol:swap-rate:update')
  AND NOT EXISTS (
      SELECT 1
      FROM t_admin_role_permission existing
      WHERE existing.role_id = rp.role_id
        AND existing.permission_code = 'symbol:holiday:update'
  );
