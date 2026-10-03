-- 调整 t_symbol_quote_mapping.min_qty：
--   股票（HK_STOCK / JP_STOCK / US_STOCK / ETF）→ 100
--   其他（CRYPTO / FX / METAL / INDEX / ENERGY）→ 0.01
--
-- 背景：原 seed (V2) 给股票一律设 10000，其他设 100，与"股票最低 100 股 / 其他最低 0.01 手"
-- 的业务口径不一致。本迁移直接 update 现有 mapping；新 symbol 通过管理端 P14
-- 创建时仍可按 source 默认值或自定义覆盖。
--
-- min_notional 与 max_qty 不动（独立守门 + min_qty 减小不会触发 CHECK 失败）。
--
-- STAGE-2-SYMBOL-PARAMS-DOWNSHIFT (V11) 后 min_qty 真源在 mapping 表，t_symbol 已 DROP 该列。

UPDATE t_symbol_quote_mapping m
JOIN t_symbol s ON s.symbol = m.platform_symbol
SET m.min_qty = 100
WHERE s.market_code IN ('HK_STOCK', 'JP_STOCK', 'US_STOCK', 'ETF');

UPDATE t_symbol_quote_mapping m
JOIN t_symbol s ON s.symbol = m.platform_symbol
SET m.min_qty = 0.01
WHERE s.market_code IN ('CRYPTO', 'FX', 'METAL', 'INDEX', 'ENERGY');
