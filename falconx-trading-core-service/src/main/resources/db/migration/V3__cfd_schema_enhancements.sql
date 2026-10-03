ALTER TABLE t_ledger
    MODIFY COLUMN biz_type TINYINT NOT NULL COMMENT '1=deposit_credit,2=deposit_reversal,3=margin_reserved,4=fee_charged,5=margin_confirmed,6=swap_charge,7=swap_income,8=realized_pnl,9=liquidation_loss';

ALTER TABLE t_position
    ADD COLUMN take_profit_price DECIMAL(24,8) NULL COMMENT '止盈触发价，NULL=未设置' AFTER liquidation_price,
    ADD COLUMN stop_loss_price DECIMAL(24,8) NULL COMMENT '止损触发价，NULL=未设置' AFTER take_profit_price;

CREATE TABLE IF NOT EXISTS t_risk_exposure (
    symbol              VARCHAR(32)     PRIMARY KEY COMMENT '交易品种',
    total_long_qty      DECIMAL(24,8)   NOT NULL DEFAULT 0 COMMENT '全平台多头总持仓量',
    total_short_qty     DECIMAL(24,8)   NOT NULL DEFAULT 0 COMMENT '全平台空头总持仓量',
    net_exposure        DECIMAL(24,8)   NOT NULL DEFAULT 0 COMMENT '净敞口 = 多头量 - 空头量',
    updated_at          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='品种净敞口实时汇总表';

ALTER TABLE t_liquidation_log
    ADD COLUMN platform_covered_loss DECIMAL(24,8) NOT NULL DEFAULT 0 COMMENT '平台兜底亏损金额' AFTER margin_released;
