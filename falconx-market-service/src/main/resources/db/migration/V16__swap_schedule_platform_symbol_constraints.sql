-- =============================================================
-- V16: Swap 与交易时段统一关联系统 platform_symbol
--
-- 目标：
-- 1. t_swap_rate.symbol 改为系统 Symbol 维度，对应 t_symbol_quote_mapping.platform_symbol。
-- 2. t_trading_hours / t_trading_hours_exception.symbol 改为系统 Symbol 维度。
-- 3. 删除历史上无法映射到 platform_symbol 的孤儿配置，避免后续继续按 LP 源 symbol 生效。
-- =============================================================

DELETE r
FROM t_swap_rate r
LEFT JOIN t_symbol_quote_mapping m ON m.platform_symbol = r.symbol
WHERE m.platform_symbol IS NULL;

DELETE h
FROM t_trading_hours h
LEFT JOIN t_symbol_quote_mapping m ON m.platform_symbol = h.symbol
WHERE m.platform_symbol IS NULL;

DELETE e
FROM t_trading_hours_exception e
LEFT JOIN t_symbol_quote_mapping m ON m.platform_symbol = e.symbol
WHERE m.platform_symbol IS NULL;

ALTER TABLE t_swap_rate
  MODIFY COLUMN symbol VARCHAR(32) NOT NULL COMMENT '系统平台 symbol，对应 t_symbol_quote_mapping.platform_symbol',
  ADD CONSTRAINT fk_swap_rate_platform_symbol
    FOREIGN KEY (symbol) REFERENCES t_symbol_quote_mapping(platform_symbol);

ALTER TABLE t_trading_hours
  MODIFY COLUMN symbol VARCHAR(32) NOT NULL COMMENT '系统平台 symbol，对应 t_symbol_quote_mapping.platform_symbol',
  ADD CONSTRAINT fk_trading_hours_platform_symbol
    FOREIGN KEY (symbol) REFERENCES t_symbol_quote_mapping(platform_symbol);

ALTER TABLE t_trading_hours_exception
  MODIFY COLUMN symbol VARCHAR(32) NOT NULL COMMENT '系统平台 symbol，对应 t_symbol_quote_mapping.platform_symbol',
  ADD CONSTRAINT fk_trading_hours_exception_platform_symbol
    FOREIGN KEY (symbol) REFERENCES t_symbol_quote_mapping(platform_symbol);
