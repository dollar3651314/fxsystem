-- STAGE-9-RISK-OPS-COMPLETE：跨品种相关性组初始化。
--
-- 已有 t_risk_market_config (FX/CRYPTO/COMMODITY 大类阈值) 控制单 market_code
-- 内全部品种合计净敞口。相关性组提供更细粒度：同方向高度联动的 symbol
-- 集合（例：EUR_GROUP = EURUSD/EURGBP/EURJPY/EURAUD 几乎同涨同跌），按
-- weight 加权后超阈值即触发风控动作。
--
-- 当前 risk evaluator 还未读相关性组（迁移触发器属阶段 9 后续工作），先把
-- 数据落地，避免上线时再做 schema 迁移。

CREATE TABLE IF NOT EXISTS t_symbol_correlation_group (
    group_code      VARCHAR(32)     NOT NULL                COMMENT '组编码：EUR_GROUP/METAL_GROUP 等',
    group_name      VARCHAR(64)     NOT NULL                COMMENT '组中文名',
    threshold_usd   DECIMAL(24,8)   NOT NULL                COMMENT '组合净敞口阈值（USD）',
    enabled         TINYINT(1)      NOT NULL DEFAULT 1      COMMENT '是否启用',
    created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (group_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='跨品种相关性组配置';

CREATE TABLE IF NOT EXISTS t_symbol_correlation_member (
    group_code      VARCHAR(32)     NOT NULL,
    symbol          VARCHAR(32)     NOT NULL,
    weight          DECIMAL(6,4)    NOT NULL DEFAULT 1.0000 COMMENT '权重（取绝对值相加做敞口聚合）',
    created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (group_code, symbol),
    INDEX idx_symbol (symbol)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='相关性组成员';

INSERT INTO t_symbol_correlation_group (group_code, group_name, threshold_usd) VALUES
    ('EUR_GROUP',          '欧元组合',         3000000.00000000),
    ('JPY_GROUP',          '日元组合',         3000000.00000000),
    ('METAL_GROUP',        '贵金属组合',        1000000.00000000),
    ('CRYPTO_MAJOR_GROUP', '主流加密货币组合',  500000.00000000)
ON DUPLICATE KEY UPDATE threshold_usd = VALUES(threshold_usd);

-- EUR 组：欧元基础货币对，高度联动
INSERT INTO t_symbol_correlation_member (group_code, symbol, weight) VALUES
    ('EUR_GROUP', 'EURUSD', 1.0000),
    ('EUR_GROUP', 'EURGBP', 1.0000),
    ('EUR_GROUP', 'EURJPY', 1.0000),
    ('EUR_GROUP', 'EURAUD', 1.0000),
    ('EUR_GROUP', 'EURCHF', 1.0000),
    ('EUR_GROUP', 'EURCAD', 1.0000),
    ('EUR_GROUP', 'EURNZD', 1.0000)
ON DUPLICATE KEY UPDATE weight = VALUES(weight);

-- JPY 组：日元报价货币对
INSERT INTO t_symbol_correlation_member (group_code, symbol, weight) VALUES
    ('JPY_GROUP', 'USDJPY', 1.0000),
    ('JPY_GROUP', 'EURJPY', 1.0000),
    ('JPY_GROUP', 'GBPJPY', 1.0000),
    ('JPY_GROUP', 'AUDJPY', 1.0000),
    ('JPY_GROUP', 'CHFJPY', 1.0000),
    ('JPY_GROUP', 'CADJPY', 1.0000),
    ('JPY_GROUP', 'NZDJPY', 1.0000)
ON DUPLICATE KEY UPDATE weight = VALUES(weight);

-- 贵金属组
INSERT INTO t_symbol_correlation_member (group_code, symbol, weight) VALUES
    ('METAL_GROUP', 'XAUUSD', 1.0000),
    ('METAL_GROUP', 'XAGUSD', 1.0000),
    ('METAL_GROUP', 'XPTUSD', 1.0000),
    ('METAL_GROUP', 'XPDUSD', 1.0000),
    ('METAL_GROUP', 'GAUUSD', 1.0000),
    ('METAL_GROUP', 'IAU.NYS', 0.5000)
ON DUPLICATE KEY UPDATE weight = VALUES(weight);

-- 主流加密
INSERT INTO t_symbol_correlation_member (group_code, symbol, weight) VALUES
    ('CRYPTO_MAJOR_GROUP', 'BTCUSD', 1.0000),
    ('CRYPTO_MAJOR_GROUP', 'BTCUSDT', 1.0000),
    ('CRYPTO_MAJOR_GROUP', 'ETHUSD', 1.0000),
    ('CRYPTO_MAJOR_GROUP', 'ETHUSDT', 1.0000),
    ('CRYPTO_MAJOR_GROUP', 'SOLUSD', 0.8000),
    ('CRYPTO_MAJOR_GROUP', 'XRPUSD', 0.6000)
ON DUPLICATE KEY UPDATE weight = VALUES(weight);
