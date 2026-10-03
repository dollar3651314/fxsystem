CREATE TABLE t_symbol_quote_mapping (
    platform_symbol    VARCHAR(32)     NOT NULL COMMENT '平台展示和交易symbol，对应t_symbol.symbol',
    source_provider    VARCHAR(32)     NOT NULL DEFAULT 'LP' COMMENT '报价源类型，当前固定LP',
    source_symbol      VARCHAR(32)     NOT NULL COMMENT '报价源原始symbol，例如XAUUSD',
    price_multiplier   DECIMAL(24,8)   NOT NULL DEFAULT 1.00000000 COMMENT '报价乘数，平台报价=源报价*乘数+加点',
    bid_adjustment     DECIMAL(24,8)   NOT NULL DEFAULT 0.00000000 COMMENT 'Bid绝对加点',
    ask_adjustment     DECIMAL(24,8)   NOT NULL DEFAULT 0.00000000 COMMENT 'Ask绝对加点',
    enabled            TINYINT         NOT NULL DEFAULT 1 COMMENT '是否启用，1=启用，0=停用',
    created_at         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (platform_symbol),
    INDEX idx_source_enabled (source_provider, source_symbol, enabled),
    INDEX idx_enabled_platform (enabled, platform_symbol)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='平台symbol到报价源symbol映射配置表';

CREATE TABLE t_symbol_group_visibility (
    group_code         VARCHAR(64)     NOT NULL COMMENT '用户组代码',
    symbol             VARCHAR(32)     NOT NULL COMMENT '平台symbol，对应t_symbol.symbol',
    visible            TINYINT         NOT NULL DEFAULT 1 COMMENT '是否可见，1=可见，0=隐藏',
    created_at         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (group_code, symbol),
    INDEX idx_symbol_visible (symbol, visible),
    INDEX idx_group_visible (group_code, visible)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户组symbol可见性配置表';

INSERT IGNORE INTO t_symbol_quote_mapping (
    platform_symbol,
    source_provider,
    source_symbol,
    price_multiplier,
    bid_adjustment,
    ask_adjustment,
    enabled
)
SELECT
    symbol,
    'LP',
    symbol,
    1.00000000,
    0.00000000,
    0.00000000,
    1
FROM t_symbol
WHERE status = 1;

INSERT IGNORE INTO t_symbol_group_visibility (
    group_code,
    symbol,
    visible
)
SELECT
    'default',
    symbol,
    1
FROM t_symbol
WHERE status = 1;
