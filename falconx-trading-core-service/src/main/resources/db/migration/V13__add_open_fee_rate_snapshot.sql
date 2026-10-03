-- =============================================================
-- V13: t_order / t_position 加 open_fee_rate 快照
-- 任务卡: STAGE-2-SYMBOL-PARAMS-DOWNSHIFT R4.2
--
-- 开仓时把当前的 mapping.taker_fee_rate 快照入 t_order.open_fee_rate
-- 与 t_position.open_fee_rate；后续平仓 / 强平 / Swap 结算用 position 快照值，
-- 不再回查 mapping，保证运营改 mapping 后历史持仓的费率口径不被反向影响。
-- =============================================================

ALTER TABLE t_order
    ADD COLUMN open_fee_rate DECIMAL(10,6) NOT NULL DEFAULT 0
        COMMENT '开仓时 mapping.taker_fee_rate 快照（STAGE-2-SYMBOL-PARAMS-DOWNSHIFT 引入）';

ALTER TABLE t_position
    ADD COLUMN open_fee_rate DECIMAL(10,6) NOT NULL DEFAULT 0
        COMMENT '开仓时 mapping.taker_fee_rate 快照（STAGE-2-SYMBOL-PARAMS-DOWNSHIFT 引入）';
