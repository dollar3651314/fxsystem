-- STAGE-0-INFRA-EXT-01：t_account 新增 margin_mode 字段。
--
-- 按 2026-05-08 范围调整决策：
-- - 一期只做 ISOLATED 逐仓模式，CROSS 推迟到一期之外，但保留 TradingMarginMode 枚举与
--   t_account.margin_mode 字段作为扩展占位。
-- - 字段语义为"账户级默认保证金模式偏好"：下单请求若不传 marginMode，则 inherit 该字段；
--   显式传值则用请求值（一期仅允许 ISOLATED，显式传 CROSS 仍返回 MARGIN_MODE_NOT_SUPPORTED）。
-- - 创建持仓时把最终模式落库到 t_position.margin_mode，账户字段后续变更不影响已有持仓。
-- - 历史账户行自动获得默认值 2(ISOLATED)，无需 backfill。
ALTER TABLE t_account
    ADD COLUMN margin_mode TINYINT NOT NULL DEFAULT 2 COMMENT '账户默认保证金模式：1=cross,2=isolated（一期仅 isolated，CROSS 为预留扩展占位）'
    AFTER margin_used;
