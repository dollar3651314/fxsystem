ALTER TABLE t_ledger
    MODIFY COLUMN biz_type TINYINT NOT NULL COMMENT '1=deposit_credit,2=deposit_reversal,3=margin_reserved,4=fee_charged,5=margin_confirmed,6=swap_charge,7=swap_income,8=realized_pnl,9=liquidation_pnl,10=isolated_margin_supplement';
