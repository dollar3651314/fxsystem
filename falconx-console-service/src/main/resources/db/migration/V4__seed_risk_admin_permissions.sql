-- =============================================================
-- V4: STAGE-2-RISK-ADMIN R9 权限点种入 t_admin_permission
--
-- 新增 5 个权限点：risk-action:view / risk-action:activate（高危）
--                  / risk-config:view / risk-config:update（高危）
--                  / risk-market-config:update（高危）
--
-- 注：使用 9200001+ id 区段。
-- =============================================================

INSERT INTO t_admin_permission (id, code, module, action, description)
SELECT * FROM (
    SELECT 9200001 AS id, 'risk-action:view'           AS code, 'risk-admin' AS module, 'action:view'          AS action, '查看风控动作列表' AS description
    UNION ALL
    SELECT 9200002    , 'risk-action:activate'       , 'risk-admin', 'action:activate'      , '激活/停用风控动作（高危）'
    UNION ALL
    SELECT 9200003    , 'risk-config:view'           , 'risk-admin', 'config:view'          , '查看 risk_config / risk_market_config'
    UNION ALL
    SELECT 9200004    , 'risk-config:update'         , 'risk-admin', 'config:update'        , '新建/编辑/删除 risk_config（高危）'
    UNION ALL
    SELECT 9200005    , 'risk-market-config:update'  , 'risk-admin', 'market-config:update' , '编辑 risk_market_config（高危）'
) AS new_perms
WHERE NOT EXISTS (
    SELECT 1 FROM t_admin_permission p WHERE p.code = new_perms.code
);
