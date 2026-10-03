CREATE TABLE IF NOT EXISTS t_symbol (
    id                  BIGINT          PRIMARY KEY COMMENT '主键 ID（雪花 ID）',
    symbol              VARCHAR(32)     NOT NULL UNIQUE COMMENT '内部标准 symbol',
    category            TINYINT         NOT NULL COMMENT '1=crypto,2=forex,3=metal,4=index,5=energy',
    market_code         VARCHAR(32)     NOT NULL COMMENT '所属市场代码，如 CRYPTO、FX、METAL、US_INDEX、EU_INDEX、UK_INDEX、HK_INDEX、USD_INDEX、ENERGY',
    base_currency       VARCHAR(16)     NOT NULL COMMENT '基础币种',
    quote_currency      VARCHAR(16)     NOT NULL COMMENT '计价币种',
    price_precision     INT             NOT NULL COMMENT '价格精度',
    qty_precision       INT             NOT NULL COMMENT '数量精度',
    min_qty             DECIMAL(24,8)   NOT NULL COMMENT '最小下单数量',
    max_qty             DECIMAL(24,8)   NOT NULL COMMENT '最大下单数量',
    min_notional        DECIMAL(24,8)   NOT NULL COMMENT '最小名义价值',
    max_leverage        INT             NOT NULL DEFAULT 100 COMMENT '最大杠杆',
    taker_fee_rate      DECIMAL(10,6)   NOT NULL DEFAULT 0 COMMENT '手续费率',
    spread              DECIMAL(24,8)   NOT NULL DEFAULT 0 COMMENT '基础点差',
    status              TINYINT         NOT NULL DEFAULT 1 COMMENT '1=trading,2=suspended',
    created_at          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    INDEX idx_category_status (category, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='market 品种配置表';

CREATE TABLE IF NOT EXISTS t_trading_hours (
    id                  BIGINT          PRIMARY KEY COMMENT '主键 ID（雪花 ID）',
    symbol              VARCHAR(32)     NOT NULL COMMENT '交易品种',
    day_of_week         TINYINT         NOT NULL COMMENT '星期，1=MON,2=TUE,3=WED,4=THU,5=FRI,6=SAT,7=SUN',
    session_no          TINYINT         NOT NULL COMMENT '同一天内的第几段交易时段，从1开始',
    open_time           TIME            NOT NULL COMMENT '开盘时间',
    close_time          TIME            NOT NULL COMMENT '收盘时间',
    timezone            VARCHAR(32)     NOT NULL COMMENT '时区',
    enabled             TINYINT         NOT NULL DEFAULT 1 COMMENT '是否启用，1=启用，0=停用',
    effective_from      DATE            NOT NULL COMMENT '规则生效日期',
    effective_to        DATE            NULL COMMENT '规则失效日期，NULL=长期有效',
    created_at          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    UNIQUE KEY uk_symbol_day_session_effective (symbol, day_of_week, session_no, effective_from),
    KEY idx_symbol_effective (symbol, effective_from, effective_to)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='交易时间周规则表';

CREATE TABLE IF NOT EXISTS t_trading_hours_exception (
    id                  BIGINT          PRIMARY KEY COMMENT '主键 ID（雪花 ID）',
    symbol              VARCHAR(32)     NOT NULL COMMENT '交易品种',
    trade_date          DATE            NOT NULL COMMENT '特殊交易日',
    exception_type      TINYINT         NOT NULL COMMENT '1=FULL_CLOSE,2=SPECIAL_SESSION',
    session_no          TINYINT         NULL COMMENT '特殊时段序号，FULL_CLOSE 时可为空',
    open_time           TIME            NULL COMMENT '特殊开盘时间',
    close_time          TIME            NULL COMMENT '特殊收盘时间',
    timezone            VARCHAR(32)     NOT NULL COMMENT '时区',
    reason              VARCHAR(128)    NULL COMMENT '原因，如 maintenance、manual_override、special_event',
    created_at          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    UNIQUE KEY uk_symbol_trade_date_session (symbol, trade_date, session_no),
    KEY idx_symbol_trade_date (symbol, trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='交易时间例外规则表';

CREATE TABLE IF NOT EXISTS t_trading_holiday (
    id                  BIGINT          PRIMARY KEY COMMENT '主键 ID（雪花 ID）',
    market_code         VARCHAR(32)     NOT NULL COMMENT '市场代码，如 CRYPTO、FX、METAL、US_INDEX、EU_INDEX、UK_INDEX、HK_INDEX、USD_INDEX、ENERGY',
    holiday_date        DATE            NOT NULL COMMENT '节假日日期',
    holiday_type        TINYINT         NOT NULL COMMENT '1=FULL_CLOSE,2=EARLY_CLOSE,3=LATE_OPEN',
    open_time           TIME            NULL COMMENT '晚开盘时间，仅 LATE_OPEN 使用',
    close_time          TIME            NULL COMMENT '提前收盘时间，仅 EARLY_CLOSE 使用',
    timezone            VARCHAR(32)     NOT NULL COMMENT '时区',
    holiday_name        VARCHAR(128)    NOT NULL COMMENT '节假日名称',
    country_code        VARCHAR(16)     NULL COMMENT '国家/地区代码，如 US、UK',
    created_at          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    UNIQUE KEY uk_market_holiday (market_code, holiday_date),
    KEY idx_market_date (market_code, holiday_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='交易节假日配置表';

CREATE TABLE IF NOT EXISTS t_swap_rate (
    id              BIGINT          PRIMARY KEY COMMENT '主键 ID（雪花 ID）',
    symbol          VARCHAR(32)     NOT NULL COMMENT '交易品种',
    long_rate       DECIMAL(12,8)   NOT NULL COMMENT '多头隔夜费率',
    short_rate      DECIMAL(12,8)   NOT NULL COMMENT '空头隔夜费率',
    rollover_time   TIME            NOT NULL DEFAULT '22:00:00' COMMENT '每日结算时间（UTC）',
    effective_from  DATE            NOT NULL COMMENT '生效日期',
    created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    UNIQUE INDEX uk_symbol_effective (symbol, effective_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='隔夜利息费率表';

CREATE TABLE IF NOT EXISTS t_outbox (
    id              BIGINT          PRIMARY KEY COMMENT '主键 ID（雪花 ID）',
    event_id        VARCHAR(64)     NOT NULL UNIQUE COMMENT '事件唯一 ID',
    event_type      VARCHAR(128)    NOT NULL COMMENT '事件类型',
    topic           VARCHAR(128)    NOT NULL COMMENT '目标 Topic',
    partition_key   VARCHAR(128)    NULL COMMENT '分区键',
    payload         JSON            NOT NULL COMMENT '事件 payload',
    status          TINYINT         NOT NULL DEFAULT 0 COMMENT '0=pending,1=dispatching,2=sent,3=failed,4=dead',
    retry_count     INT             NOT NULL DEFAULT 0 COMMENT '重试次数',
    next_retry_at   DATETIME(3)     NULL COMMENT '下次重试时间',
    last_error      VARCHAR(512)    NULL COMMENT '最近一次发送失败原因',
    created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    sent_at         DATETIME(3)     NULL COMMENT '发送完成时间',
    updated_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    INDEX idx_status_retry (status, next_retry_at, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='market 低频事件发件箱表';
