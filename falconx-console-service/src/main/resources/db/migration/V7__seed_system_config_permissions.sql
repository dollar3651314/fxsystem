-- =============================================================
-- V7: STAGE-13 系统配置中心权限点
--
-- 新增 2 个权限点：
--   system-config:view  — 查看系统配置（含历史审计）
--   system-config:write — 修改 / 重置系统配置（高危）
--
-- 使用 9400001+ id 区段（9300001 已被 V5 deposit:view 占用，避让到 9400xxx）。
-- 注：SUPER_ADMIN 角色自动放行所有 @RequiresPermission，无需在 t_admin_role_permission 中显式绑定。
-- 其他角色（如 OPS / SRE）需在 admin UI 中手动绑定 system-config:* 权限。
-- =============================================================

INSERT INTO t_admin_permission (id, code, module, action, description)
SELECT * FROM (
    SELECT 9400001 AS id, 'system-config:view'  AS code, 'system-config' AS module, 'view'  AS action, '查看系统配置（限流/IP白名单/Token TTL/登录锁定/bcrypt等）' AS description
    UNION ALL
    SELECT 9400002    , 'system-config:write' , 'system-config', 'write' , '修改/重置系统配置（高危：影响全栈运行参数）'
) AS new_perms
WHERE NOT EXISTS (
    SELECT 1 FROM t_admin_permission p WHERE p.code = new_perms.code
);
