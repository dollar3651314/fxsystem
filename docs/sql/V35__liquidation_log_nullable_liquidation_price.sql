-- STAGE-14D2 Task 4：CROSS 账户级强平的 t_liquidation_log.liquidation_price 放开 NULL。
--
-- 背景：CROSS 仓 master §3.2 不存单仓强平价（liquidation_price=NULL，账户级 MarginLevel ≤ stopOut 触发），
--   但 V1 初始化时 t_liquidation_log.liquidation_price 为 NOT NULL（仅面向 ISOLATED 单仓有强平价的场景）。
--   CROSS 强平落 t_liquidation_log 时 liquidation_price=NULL → 违反 NOT NULL 约束 → 强平整链失败
--   （同步路径 settlePositionExit 直插 / 异步路径 outbox 消费者写入 均受阻）。
--
-- 变更：liquidation_price 改为 NULL，COMMENT 标注 CROSS 账户级强平为 NULL。ISOLATED 仍如实写单仓强平价。
--   t_position.liquidation_price 早已是 NULL（V1 line 83），本变更使 t_liquidation_log 与之口径一致。
--
-- 兼容性：放开约束（NOT NULL → NULL）对存量 ISOLATED 数据无影响（已有非空值保留），不回填。
ALTER TABLE t_liquidation_log
    MODIFY COLUMN liquidation_price DECIMAL(24, 8) NULL COMMENT '强平触发价；CROSS 账户级强平为 NULL（无单仓强平价），ISOLATED 为单仓强平价';
