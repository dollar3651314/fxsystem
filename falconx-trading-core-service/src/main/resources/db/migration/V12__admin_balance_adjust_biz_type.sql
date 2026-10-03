-- STAGE-2-CUSTOMER：t_ledger.biz_type 注释新增 11=admin_balance_adjust（管理员手动调整客户余额）。
-- 实际枚举值在 TradingLedgerBizType enum 中新增；本 migration 仅同步注释以保持文档与代码一致。

ALTER TABLE t_ledger
    MODIFY COLUMN biz_type TINYINT NOT NULL COMMENT '1=deposit_credit,2=deposit_reversal,3=margin_reserved,4=fee_charged,5=margin_confirmed,6=swap_charge,7=swap_income,8=realized_pnl,9=liquidation_pnl,10=isolated_margin_supplement,11=admin_balance_adjust';
