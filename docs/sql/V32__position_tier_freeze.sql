-- STAGE-14C1 Task 3：t_position 加 mm_rate_at_open / tier_no_at_open 冻结列。
--
-- 注意：Flyway migration 不得写 `USE <schema>;`，schema 由连接绑定决定（与 V1-V31 一致）。
--
-- 范围：开仓时把 tier 解析出的 mmRate 与档位号冻结到持仓行，后续 tier 调整不影响存量仓位强平价/MM。
--   - mm_rate_at_open：开仓时 tier mmRate 冻结（强平价/MM 计算用），AFTER entry_fx_rate（V29 已加）。
--   - tier_no_at_open：开仓时 tier 档位号冻结（审计用）。
--   - 老数据回填：用 properties 历史默认 mmRate 0.005 / tier_no 1（与历史强平价口径一致）。

ALTER TABLE t_position
    ADD COLUMN mm_rate_at_open DECIMAL(8,6) NULL COMMENT '开仓时 tier mmRate 冻结（强平价/MM 用）' AFTER entry_fx_rate,
    ADD COLUMN tier_no_at_open TINYINT      NULL COMMENT '开仓时 tier 档位冻结（审计）' AFTER mm_rate_at_open;

UPDATE t_position SET mm_rate_at_open = 0.005000, tier_no_at_open = 1 WHERE mm_rate_at_open IS NULL;

ALTER TABLE t_position
    MODIFY COLUMN mm_rate_at_open DECIMAL(8,6) NOT NULL DEFAULT 0.005000 COMMENT '开仓时 tier mmRate 冻结（强平价/MM 用）',
    MODIFY COLUMN tier_no_at_open TINYINT      NOT NULL DEFAULT 1        COMMENT '开仓时 tier 档位冻结（审计）';
