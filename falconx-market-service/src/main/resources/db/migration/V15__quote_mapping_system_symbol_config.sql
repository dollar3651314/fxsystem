-- =============================================================
-- V15: t_symbol_quote_mapping 承接系统级 Symbol 配置
--
-- 目标：
-- 1. category / market_code 由 mapping 配置，t_symbol 仅保留 LP 源元数据。
-- 2. price_precision / qty_precision 在 mapping 上固定为系统级精度，不再 NULL 继承 source。
-- 3. platform_symbol 是 FalconX 系统 Symbol，创建后不可通过管理端改名；改名必须新建 mapping 并停用旧 mapping。
-- =============================================================

ALTER TABLE t_symbol_quote_mapping
  ADD COLUMN category TINYINT NULL COMMENT '系统级品类，1=CRYPTO,2=FX,3=METAL,4=INDEX,5=ENERGY,6=STOCK,7=ETF,8=OTHER' AFTER source_symbol,
  ADD COLUMN market_code VARCHAR(32) NULL COMMENT '系统级市场代码，如 CRYPTO、FX、METAL、INDEX、ENERGY、US_STOCK、HK_STOCK、JP_STOCK、ETF、OTHER' AFTER category;

UPDATE t_symbol_quote_mapping m
INNER JOIN t_symbol s ON s.symbol = m.source_symbol
SET m.category = s.category,
    m.market_code = s.market_code,
    m.price_precision = COALESCE(m.price_precision, s.price_precision),
    m.qty_precision = COALESCE(m.qty_precision, s.qty_precision);

ALTER TABLE t_symbol_quote_mapping
  MODIFY COLUMN category TINYINT NOT NULL COMMENT '系统级品类，1=CRYPTO,2=FX,3=METAL,4=INDEX,5=ENERGY,6=STOCK,7=ETF,8=OTHER',
  MODIFY COLUMN market_code VARCHAR(32) NOT NULL COMMENT '系统级市场代码，如 CRYPTO、FX、METAL、INDEX、ENERGY、US_STOCK、HK_STOCK、JP_STOCK、ETF、OTHER',
  MODIFY COLUMN price_precision INT NOT NULL COMMENT '系统级价格精度',
  MODIFY COLUMN qty_precision INT NOT NULL COMMENT '系统级数量精度';

ALTER TABLE t_symbol_quote_mapping
  ADD CONSTRAINT chk_mapping_category CHECK (category BETWEEN 1 AND 8),
  ADD INDEX idx_mapping_category_market_enabled (category, market_code, enabled);
