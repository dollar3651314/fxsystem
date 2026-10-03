ALTER TABLE t_risk_exposure
    ADD COLUMN net_exposure_usd DECIMAL(24,8) NOT NULL DEFAULT 0 COMMENT '净美元敞口 = net_exposure * mark_price' AFTER net_exposure;

ALTER TABLE t_risk_config
    ADD COLUMN hedge_threshold_usd DECIMAL(24,8) NOT NULL DEFAULT 0 COMMENT '净美元敞口告警阈值，按绝对值比较' AFTER max_position_total;

UPDATE t_risk_config
SET hedge_threshold_usd = CASE symbol
    WHEN 'BTCUSDT' THEN 100000.00000000
    WHEN 'ETHUSDT' THEN 50000.00000000
    WHEN 'EURUSD' THEN 1000000.00000000
    WHEN 'XAUUSD' THEN 200000.00000000
    ELSE hedge_threshold_usd
END;

CREATE TABLE IF NOT EXISTS t_hedge_log (
    id                  BIGINT          PRIMARY KEY COMMENT '主键 ID（雪花 ID）',
    symbol              VARCHAR(32)     NOT NULL COMMENT '交易品种',
    position_id         BIGINT          NULL COMMENT '触发本次观测的持仓 ID，纯行情刷新时为空',
    trigger_source      TINYINT         NOT NULL COMMENT '1=open_position,2=manual_close,3=take_profit,4=stop_loss,5=liquidation,6=price_tick',
    action_status       TINYINT         NOT NULL COMMENT '1=alert_only,2=recovered',
    net_exposure        DECIMAL(24,8)   NOT NULL COMMENT '当前净敞口数量',
    net_exposure_usd    DECIMAL(24,8)   NOT NULL COMMENT '当前净美元敞口',
    hedge_threshold_usd DECIMAL(24,8)   NOT NULL COMMENT '触发时的对冲阈值（美元）',
    mark_price          DECIMAL(24,8)   NOT NULL COMMENT '本次估值使用的标记价',
    price_ts            DATETIME(3)     NULL COMMENT '估值使用的行情时间',
    price_source        VARCHAR(32)     NULL COMMENT '估值使用的行情来源',
    created_at          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    INDEX idx_symbol_created (symbol, created_at),
    INDEX idx_symbol_status_created (symbol, action_status, created_at),
    INDEX idx_position_id (position_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='B-book 对冲观测日志表';
