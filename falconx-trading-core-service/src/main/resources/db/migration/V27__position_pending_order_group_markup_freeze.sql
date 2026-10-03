-- =============================================================
-- V27: position / pending_order_trigger 冻结开仓时的组加点
-- 任务卡：STAGE-12-GROUP-MARKUP
--
-- 把开仓 / 挂单时刻的 (groupCode, bid_extra, ask_extra) 冗余冻结到
-- t_position / t_pending_order_trigger，保证后续 PnL / 强平 / 触发口径稳定，
-- 不受用户后续换组、运营改加点影响。
--
-- 现存数据兼容：DEFAULT 0（基准价），不破坏现有持仓 / 挂单行为。
-- 应用层在新建 position / pending_order_trigger 时必须显式写入冻结值。
-- =============================================================

ALTER TABLE t_position
    ADD COLUMN group_code_at_open VARCHAR(64)    NOT NULL DEFAULT 'default'
        COMMENT '开仓时用户所属组（冗余冻结，用于稳定 PnL / 强平口径）' AFTER user_id,
    ADD COLUMN bid_extra_at_open  DECIMAL(24,8)  NOT NULL DEFAULT 0
        COMMENT '开仓时该组的 bid_extra（冗余冻结）' AFTER group_code_at_open,
    ADD COLUMN ask_extra_at_open  DECIMAL(24,8)  NOT NULL DEFAULT 0
        COMMENT '开仓时该组的 ask_extra（冗余冻结）' AFTER bid_extra_at_open;

ALTER TABLE t_pending_order_trigger
    ADD COLUMN group_code_at_create VARCHAR(64)   NOT NULL DEFAULT 'default'
        COMMENT '挂单创建时用户所属组（冗余冻结）' AFTER user_id,
    ADD COLUMN bid_extra_at_create  DECIMAL(24,8) NOT NULL DEFAULT 0
        COMMENT '挂单创建时该组的 bid_extra（冗余冻结）' AFTER group_code_at_create,
    ADD COLUMN ask_extra_at_create  DECIMAL(24,8) NOT NULL DEFAULT 0
        COMMENT '挂单创建时该组的 ask_extra（冗余冻结）' AFTER bid_extra_at_create;
