-- =============================================================
-- V28: t_ledger 加 original_amount / original_currency / fx_rate_at_settlement
-- 任务卡：STAGE-14B Task 1
--
-- 三列承担原币种历史留痕：
--   original_amount       — 原币金额（quote currency），例如 USD pnl 结算时的 USD 金额
--   original_currency     — 原币种代码，例如 USD / EUR / JPY
--   fx_rate_at_settlement — 结算时 FX rate (original → account currency)
--
-- 流程：
--   1. ADD COLUMN 先以 NULL 落地，兼容存量行
--   2. UPDATE 回填老数据（假设历史全部为账户币 USDT，fx=1）
--   3. MODIFY COLUMN 改为 NOT NULL 约束
--
-- 真 Flyway migrate 验证留 Task 11 IT.
-- 注意：Flyway migration 不得写 `USE <schema>;`，schema 由连接绑定决定（与 V1-V27 一致）。
-- =============================================================

ALTER TABLE t_ledger
    ADD COLUMN original_amount         DECIMAL(24,8) NULL COMMENT '原币金额（quote currency）' AFTER amount,
    ADD COLUMN original_currency       VARCHAR(16)   NULL COMMENT '原币种代码' AFTER original_amount,
    ADD COLUMN fx_rate_at_settlement   DECIMAL(24,8) NULL COMMENT '结算时 FX rate (original→account)' AFTER original_currency;

-- 老数据回填: 假设历史 ledger 全部为账户币 (USDT) + fx=1
UPDATE t_ledger l
    LEFT JOIN t_account a ON a.id = l.account_id
SET l.original_amount           = l.amount,
    l.original_currency         = COALESCE(a.currency, 'USDT'),
    l.fx_rate_at_settlement     = 1.00000000
WHERE l.original_amount IS NULL;

ALTER TABLE t_ledger
    MODIFY COLUMN original_amount         DECIMAL(24,8) NOT NULL COMMENT '原币金额（quote currency）',
    MODIFY COLUMN original_currency       VARCHAR(16)   NOT NULL COMMENT '原币种代码',
    MODIFY COLUMN fx_rate_at_settlement   DECIMAL(24,8) NOT NULL COMMENT '结算时 FX rate (original→account)';
