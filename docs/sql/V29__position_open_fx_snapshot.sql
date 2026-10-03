-- 注意：Flyway migration 不得写 `USE <schema>;`，schema 由连接绑定决定（与 V1-V27 一致）。

ALTER TABLE t_position
    ADD COLUMN entry_fx_rate DECIMAL(24,8) NULL COMMENT '开仓时 FX rate (quote→account)，仅审计用'
        AFTER entry_price;

UPDATE t_position SET entry_fx_rate = 1.00000000 WHERE entry_fx_rate IS NULL;

ALTER TABLE t_position MODIFY COLUMN entry_fx_rate DECIMAL(24,8) NOT NULL DEFAULT 1.00000000;
