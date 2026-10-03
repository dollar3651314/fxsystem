ALTER TABLE t_position
    ADD COLUMN close_price DECIMAL(24,8) NULL COMMENT '平仓价（仅终态持仓有值）' AFTER stop_loss_price,
    ADD COLUMN close_reason TINYINT NULL COMMENT '1=manual,2=tp,3=sl,4=liquidation' AFTER close_price,
    ADD COLUMN realized_pnl DECIMAL(24,8) NULL COMMENT '已实现盈亏（仅终态持仓有值）' AFTER close_reason,
    ADD COLUMN closed_at DATETIME(3) NULL COMMENT '平仓/强平完成时间' AFTER realized_pnl,
    ADD COLUMN margin_mode TINYINT NOT NULL DEFAULT 2 COMMENT '1=cross,2=isolated' AFTER margin,
    MODIFY COLUMN status TINYINT NOT NULL DEFAULT 1 COMMENT '1=open,2=closed,3=liquidated';

ALTER TABLE t_trade
    ADD COLUMN trade_type TINYINT NOT NULL DEFAULT 1 COMMENT '1=open,2=close,3=liquidation' AFTER side;
