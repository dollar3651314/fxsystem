-- =============================================================
-- V3: STAGE-2-TRADING-MONITOR R9 权限点种入 t_admin_permission
--
-- 新增 5 个权限点：trading-monitor:order:view / position:view / exposure:view
--                  / manual-liquidate（高危）/ auto-liquidate:pause（高危）
--
-- 注：t_admin_permission.id 没有 AUTO_INCREMENT，业务层用 IdGenerator 生成；
-- migration seed 使用大常量 id 区段（9100001+），不与 snowflake id 冲突。
-- =============================================================

INSERT INTO t_admin_permission (id, code, module, action, description)
SELECT * FROM (
    SELECT 9100001 AS id, 'trading-monitor:order:view'         AS code, 'trading-monitor' AS module, 'order:view'           AS action, '查看订单列表' AS description
    UNION ALL
    SELECT 9100002    , 'trading-monitor:position:view'        , 'trading-monitor', 'position:view'      , '查看持仓列表'
    UNION ALL
    SELECT 9100003    , 'trading-monitor:exposure:view'        , 'trading-monitor', 'exposure:view'      , '查看净敞口 + 风控开关'
    UNION ALL
    SELECT 9100004    , 'trading-monitor:manual-liquidate'     , 'trading-monitor', 'manual-liquidate'   , '手动强平指定持仓（高危）'
    UNION ALL
    SELECT 9100005    , 'trading-monitor:auto-liquidate:pause' , 'trading-monitor', 'auto-liquidate:pause', '暂停/恢复自动强平（高危）'
) AS new_perms
WHERE NOT EXISTS (
    SELECT 1 FROM t_admin_permission p WHERE p.code = new_perms.code
);
