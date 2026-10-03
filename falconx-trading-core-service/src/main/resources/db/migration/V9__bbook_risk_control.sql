-- V9: BBook 风控执行动作

-- 1. 给 t_risk_config 添加 market_code 字段（跨品种集中度分类）
ALTER TABLE t_risk_config
    ADD COLUMN market_code VARCHAR(20) NULL COMMENT '品种分类（FX/CRYPTO/COMMODITY）' AFTER symbol;

UPDATE t_risk_config
SET market_code = CASE symbol
    WHEN 'BTCUSDT' THEN 'CRYPTO'
    WHEN 'ETHUSDT' THEN 'CRYPTO'
    WHEN 'EURUSD'  THEN 'FX'
    WHEN 'XAUUSD'  THEN 'COMMODITY'
    ELSE NULL
END;

-- 2. 跨品种集中度阈值表
CREATE TABLE IF NOT EXISTS t_risk_market_config (
    market_code                 VARCHAR(20)     NOT NULL    COMMENT '品种分类（FX/CRYPTO/COMMODITY）',
    concentration_threshold_usd DECIMAL(24,8)   NOT NULL    COMMENT '同分类净敞口合计阈值（USD）',
    is_enabled                  TINYINT(1)      NOT NULL DEFAULT 1 COMMENT '是否启用',
    created_at                  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at                  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (market_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='跨品种集中度阈值配置表';

INSERT INTO t_risk_market_config (market_code, concentration_threshold_usd) VALUES
    ('CRYPTO',    500000.00000000),
    ('FX',       5000000.00000000),
    ('COMMODITY', 1000000.00000000);

-- 3. BBook 风控执行动作表
CREATE TABLE IF NOT EXISTS t_risk_control_action (
    id              BIGINT          NOT NULL                COMMENT '主键（雪花 ID）',
    symbol          VARCHAR(32)     NULL                    COMMENT '品种；NULL = 全局动作',
    action_type     TINYINT         NOT NULL                COMMENT '1=reject_open,2=reduce_only,3=suspend_symbol,4=global_pause',
    is_active       TINYINT(1)      NOT NULL DEFAULT 1      COMMENT '是否生效',
    trigger_source  VARCHAR(50)     NOT NULL                COMMENT '触发来源：AUTO/AUTO_CONCENTRATION/MANUAL',
    trigger_reason  VARCHAR(200)    NULL                    COMMENT '触发原因说明',
    hedge_log_id    BIGINT          NULL                    COMMENT '关联 t_hedge_log.id（自动触发时填写）',
    created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    INDEX idx_symbol_active     (symbol, is_active),
    INDEX idx_active            (is_active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='BBook 风控执行动作表';
