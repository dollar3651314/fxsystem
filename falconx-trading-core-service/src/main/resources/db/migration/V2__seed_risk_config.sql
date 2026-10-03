INSERT INTO t_risk_config (
    id, symbol, max_position_per_user, max_position_total, maintenance_margin_rate, max_leverage
) VALUES
(1, 'BTCUSDT', 10.00000000, 1000.00000000, 0.005000, 100),
(2, 'ETHUSDT', 100.00000000, 10000.00000000, 0.005000, 100),
(3, 'EURUSD', 1000000.00000000, 100000000.00000000, 0.002000, 500),
(4, 'XAUUSD', 100.00000000, 10000.00000000, 0.005000, 200)
ON DUPLICATE KEY UPDATE
    max_position_per_user = VALUES(max_position_per_user),
    max_position_total = VALUES(max_position_total),
    maintenance_margin_rate = VALUES(maintenance_margin_rate),
    max_leverage = VALUES(max_leverage);
