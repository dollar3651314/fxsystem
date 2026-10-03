INSERT INTO t_trading_hours (
    id,
    symbol,
    day_of_week,
    session_no,
    open_time,
    close_time,
    timezone,
    enabled,
    effective_from,
    effective_to
)
SELECT
    600000000000 + s.id * 10 + d.day_of_week AS id,
    s.symbol,
    d.day_of_week,
    1 AS session_no,
    '00:00:00' AS open_time,
    '23:59:59' AS close_time,
    'UTC' AS timezone,
    1 AS enabled,
    '2026-01-01' AS effective_from,
    NULL AS effective_to
FROM t_symbol s
JOIN (
    SELECT 1 AS day_of_week
    UNION ALL SELECT 2
    UNION ALL SELECT 3
    UNION ALL SELECT 4
    UNION ALL SELECT 5
    UNION ALL SELECT 6
    UNION ALL SELECT 7
) d ON d.day_of_week <= CASE
    WHEN s.category = 1 THEN 7
    WHEN s.category IN (2, 3) THEN 5
    ELSE 0
END
WHERE s.status = 1
  AND s.category IN (1, 2, 3)
ON DUPLICATE KEY UPDATE
    open_time = '00:00:00',
    close_time = '23:59:59',
    timezone = 'UTC',
    enabled = 1,
    effective_to = NULL;
