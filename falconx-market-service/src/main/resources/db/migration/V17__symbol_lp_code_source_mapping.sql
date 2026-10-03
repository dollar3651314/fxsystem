-- =============================================================
-- V17: LP code 进入源 symbol 与报价映射
--
-- 目标：
-- 1. t_symbol 增加 lp_code，标识该源 symbol 来自哪个 LP。
-- 2. t_symbol 唯一键从 symbol 单列升级为 (lp_code, symbol)，允许不同 LP 存在同名 symbol。
-- 3. t_symbol_quote_mapping 增加 source_lp_code，mapping 通过 (source_lp_code, source_symbol)
--    精确选择上游源。
-- =============================================================

ALTER TABLE t_symbol
  ADD COLUMN lp_code VARCHAR(32) NOT NULL DEFAULT 'GODSA' COMMENT 'LP 代码，例如 GODSA；与 symbol 共同唯一标识上游源' AFTER id;

ALTER TABLE t_symbol_quote_mapping
  ADD COLUMN source_lp_code VARCHAR(32) NOT NULL DEFAULT 'GODSA' COMMENT '源 LP 代码，对应 t_symbol.lp_code' AFTER source_provider;

ALTER TABLE t_symbol
  DROP INDEX symbol,
  ADD UNIQUE KEY uk_symbol_lp_symbol (lp_code, symbol),
  ADD INDEX idx_symbol_lp_status (lp_code, status, symbol),
  ADD CONSTRAINT chk_symbol_lp_code_nonempty CHECK (lp_code <> '');

ALTER TABLE t_symbol_quote_mapping
  DROP INDEX idx_source_enabled,
  DROP INDEX idx_source_subscription_enabled,
  ADD INDEX idx_source_enabled (source_provider, source_lp_code, source_symbol, enabled),
  ADD INDEX idx_source_subscription_enabled (source_provider, source_lp_code, source_symbol, enabled, lp_subscribe_enabled),
  ADD CONSTRAINT chk_mapping_source_lp_code_nonempty CHECK (source_lp_code <> '');
