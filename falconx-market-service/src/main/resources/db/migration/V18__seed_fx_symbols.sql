-- V18__seed_fx_symbols.sql
-- STAGE-14A Task 2: seed 8 核心 FX symbol
-- ID 占位：2001-2008（V5 已占用 1-1870；真正执行时 ON DUPLICATE KEY UPDATE 将命中 V5 已有行）
-- 真 Flyway migrate 验证待 Task 10 集成测试或用户本地执行

USE falconx_market;

INSERT INTO t_symbol (
    id, lp_code, symbol, category, market_code,
    base_currency, quote_currency, price_precision, qty_precision, status
) VALUES
    (2001, 'GODSA', 'EURUSD', 2, 'FX', 'EUR', 'USD', 5, 2, 1),
    (2002, 'GODSA', 'AUDUSD', 2, 'FX', 'AUD', 'USD', 5, 2, 1),
    (2003, 'GODSA', 'USDJPY', 2, 'FX', 'USD', 'JPY', 3, 2, 1),
    (2004, 'GODSA', 'GBPUSD', 2, 'FX', 'GBP', 'USD', 5, 2, 1),
    (2005, 'GODSA', 'USDCAD', 2, 'FX', 'USD', 'CAD', 5, 2, 1),
    (2006, 'GODSA', 'USDCHF', 2, 'FX', 'USD', 'CHF', 5, 2, 1),
    (2007, 'GODSA', 'NZDUSD', 2, 'FX', 'NZD', 'USD', 5, 2, 1),
    (2008, 'GODSA', 'USDCNH', 2, 'FX', 'USD', 'CNH', 5, 2, 1)
ON DUPLICATE KEY UPDATE
    base_currency    = VALUES(base_currency),
    quote_currency   = VALUES(quote_currency),
    price_precision  = VALUES(price_precision),
    qty_precision    = VALUES(qty_precision),
    status           = VALUES(status),
    updated_at       = CURRENT_TIMESTAMP;

-- 注意: 8 个 FX symbol 自动按 t_trading_hours 默认规则（如未单独配置走兜底；
--       若需 24x5 配置，由 admin 通过 SYMBOL-ADMIN-SCHEDULE-HOLIDAY 接口设定）。
