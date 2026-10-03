-- 为 ENERGY / INDEX / HK_STOCK / JP_STOCK / US_STOCK / ETF 补充标准交易时段。
--
-- 背景：V6 只覆盖 FX/METAL/CRYPTO（category 1/2/3），其它 market_code 在 t_trading_hours
-- 没有任何记录，warmup snapshot.sessions=[] 导致 LP 报价被 MARKET_CLOSED skip。
--
-- 时段口径：
--   ENERGY / INDEX → CME Globex 电子盘 周日 18:00 - 周五 17:00 America/New_York
--                    （每日维护窗口 17:00-18:00；周五 17:00 收盘到周日 18:00 开盘）
--   HK_STOCK → HKEX 周一-周五 09:30-12:00 + 13:00-16:00 Asia/Hong_Kong
--   JP_STOCK → TSE 周一-周五 09:00-11:30 + 12:30-15:00 Asia/Tokyo
--   US_STOCK / ETF → NYSE / NASDAQ Regular Hours 周一-周五 09:30-16:00 America/New_York
--                    （不含 pre-market / after-hours，与平台开仓口径对齐）
--
-- id 编号约定：
--   700xxxxxxxxx ENERGY/INDEX
--   710xxxxxxxxx HK_STOCK
--   720xxxxxxxxx JP_STOCK
--   730xxxxxxxxx US_STOCK / ETF
--   每条 = base + s.id * 100 + day_of_week * 10 + session_no

-- ENERGY (category=5) + INDEX (category=4)
INSERT INTO t_trading_hours (
    id, symbol, day_of_week, session_no, open_time, close_time, timezone, enabled, effective_from
)
SELECT
    700000000000 + s.id * 100 + d.day_of_week * 10 + d.session_no,
    s.symbol, d.day_of_week, d.session_no, d.open_time, d.close_time,
    'America/New_York', 1, '2026-01-01'
FROM t_symbol s
JOIN (
    -- Sun 开盘段
    SELECT 7 AS day_of_week, 1 AS session_no, '18:00:00' AS open_time, '23:59:59' AS close_time
    -- Mon-Thu 上午段（接周日晚开盘）+ 晚开盘段
    UNION ALL SELECT 1, 1, '00:00:00', '17:00:00'
    UNION ALL SELECT 1, 2, '18:00:00', '23:59:59'
    UNION ALL SELECT 2, 1, '00:00:00', '17:00:00'
    UNION ALL SELECT 2, 2, '18:00:00', '23:59:59'
    UNION ALL SELECT 3, 1, '00:00:00', '17:00:00'
    UNION ALL SELECT 3, 2, '18:00:00', '23:59:59'
    UNION ALL SELECT 4, 1, '00:00:00', '17:00:00'
    UNION ALL SELECT 4, 2, '18:00:00', '23:59:59'
    -- Fri 收盘段
    UNION ALL SELECT 5, 1, '00:00:00', '17:00:00'
) d
WHERE s.status = 1 AND s.category IN (4, 5)
ON DUPLICATE KEY UPDATE
    open_time = VALUES(open_time),
    close_time = VALUES(close_time),
    timezone = VALUES(timezone),
    enabled = 1;

-- HK_STOCK
INSERT INTO t_trading_hours (
    id, symbol, day_of_week, session_no, open_time, close_time, timezone, enabled, effective_from
)
SELECT
    710000000000 + s.id * 100 + d.day_of_week * 10 + d.session_no,
    s.symbol, d.day_of_week, d.session_no, d.open_time, d.close_time,
    'Asia/Hong_Kong', 1, '2026-01-01'
FROM t_symbol s
JOIN (
    SELECT 1 AS day_of_week, 1 AS session_no, '09:30:00' AS open_time, '12:00:00' AS close_time
    UNION ALL SELECT 1, 2, '13:00:00', '16:00:00'
    UNION ALL SELECT 2, 1, '09:30:00', '12:00:00'
    UNION ALL SELECT 2, 2, '13:00:00', '16:00:00'
    UNION ALL SELECT 3, 1, '09:30:00', '12:00:00'
    UNION ALL SELECT 3, 2, '13:00:00', '16:00:00'
    UNION ALL SELECT 4, 1, '09:30:00', '12:00:00'
    UNION ALL SELECT 4, 2, '13:00:00', '16:00:00'
    UNION ALL SELECT 5, 1, '09:30:00', '12:00:00'
    UNION ALL SELECT 5, 2, '13:00:00', '16:00:00'
) d
WHERE s.status = 1 AND s.market_code = 'HK_STOCK'
ON DUPLICATE KEY UPDATE
    open_time = VALUES(open_time),
    close_time = VALUES(close_time),
    timezone = VALUES(timezone),
    enabled = 1;

-- JP_STOCK
INSERT INTO t_trading_hours (
    id, symbol, day_of_week, session_no, open_time, close_time, timezone, enabled, effective_from
)
SELECT
    720000000000 + s.id * 100 + d.day_of_week * 10 + d.session_no,
    s.symbol, d.day_of_week, d.session_no, d.open_time, d.close_time,
    'Asia/Tokyo', 1, '2026-01-01'
FROM t_symbol s
JOIN (
    SELECT 1 AS day_of_week, 1 AS session_no, '09:00:00' AS open_time, '11:30:00' AS close_time
    UNION ALL SELECT 1, 2, '12:30:00', '15:00:00'
    UNION ALL SELECT 2, 1, '09:00:00', '11:30:00'
    UNION ALL SELECT 2, 2, '12:30:00', '15:00:00'
    UNION ALL SELECT 3, 1, '09:00:00', '11:30:00'
    UNION ALL SELECT 3, 2, '12:30:00', '15:00:00'
    UNION ALL SELECT 4, 1, '09:00:00', '11:30:00'
    UNION ALL SELECT 4, 2, '12:30:00', '15:00:00'
    UNION ALL SELECT 5, 1, '09:00:00', '11:30:00'
    UNION ALL SELECT 5, 2, '12:30:00', '15:00:00'
) d
WHERE s.status = 1 AND s.market_code = 'JP_STOCK'
ON DUPLICATE KEY UPDATE
    open_time = VALUES(open_time),
    close_time = VALUES(close_time),
    timezone = VALUES(timezone),
    enabled = 1;

-- US_STOCK + ETF
INSERT INTO t_trading_hours (
    id, symbol, day_of_week, session_no, open_time, close_time, timezone, enabled, effective_from
)
SELECT
    730000000000 + s.id * 100 + d.day_of_week * 10 + d.session_no,
    s.symbol, d.day_of_week, d.session_no, d.open_time, d.close_time,
    'America/New_York', 1, '2026-01-01'
FROM t_symbol s
JOIN (
    SELECT 1 AS day_of_week, 1 AS session_no, '09:30:00' AS open_time, '16:00:00' AS close_time
    UNION ALL SELECT 2, 1, '09:30:00', '16:00:00'
    UNION ALL SELECT 3, 1, '09:30:00', '16:00:00'
    UNION ALL SELECT 4, 1, '09:30:00', '16:00:00'
    UNION ALL SELECT 5, 1, '09:30:00', '16:00:00'
) d
WHERE s.status = 1 AND (s.market_code = 'US_STOCK' OR s.category = 7)
ON DUPLICATE KEY UPDATE
    open_time = VALUES(open_time),
    close_time = VALUES(close_time),
    timezone = VALUES(timezone),
    enabled = 1;
