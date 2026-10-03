-- FalconX v1 GODSA Database Blueprint
-- This file is a documentation-oriented SQL blueprint, not a runtime migration.
-- Final architecture:
-- 1. gateway has no business schema
-- 2. identity / market / trading-core / wallet each owns its own schema
-- 3. market owns symbol metadata and outbox in MySQL, quote/kline persistence in ClickHouse
-- 4. trading-core owns account, ledger, deposit credit, order, position, trade and liquidation facts
-- 5. wallet owns on-chain raw facts and listener state

CREATE DATABASE IF NOT EXISTS falconx_identity CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS falconx_market CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS falconx_trading CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS falconx_wallet CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

-- ==================== falconx_identity ====================
USE falconx_identity;

CREATE TABLE t_user (
    id              BIGINT          PRIMARY KEY COMMENT '主键ID（雪花ID）',
    uid             VARCHAR(16)     NOT NULL UNIQUE COMMENT '对外展示UID',
    email           VARCHAR(128)    NOT NULL UNIQUE COMMENT '登录邮箱',
    password_hash   VARCHAR(256)    NOT NULL COMMENT '密码哈希',
    nickname        VARCHAR(64)     COMMENT '用户昵称',
    status          TINYINT         NOT NULL DEFAULT 1 COMMENT '0=LEGACY_PENDING_DEPOSIT,1=ACTIVE,2=FROZEN,3=BANNED',
    group_code      VARCHAR(64)     NOT NULL DEFAULT 'default' COMMENT '用户所属产品组代码，用于下游控制可见symbol',
    email_verified  TINYINT         NOT NULL DEFAULT 0 COMMENT '0=not_verified,1=verified',
    register_ip     VARCHAR(64)     COMMENT '注册IP',
    last_login_at   DATETIME        COMMENT '最后登录时间',
    activated_at    DATETIME        COMMENT '账户可用时间；新注册用户创建时写入',
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_status_created (status, created_at),
    INDEX idx_group_status (group_code, status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户主表';

CREATE TABLE t_refresh_token_session (
    jti             VARCHAR(64)     PRIMARY KEY COMMENT 'Refresh Token 唯一ID',
    user_id         BIGINT          NOT NULL COMMENT '用户主键ID',
    expires_at      DATETIME(3)     NOT NULL COMMENT '过期时间',
    used            TINYINT         NOT NULL DEFAULT 0 COMMENT '0=未使用,1=已使用',
    issued_at       DATETIME(3)     NOT NULL COMMENT '签发时间',
    used_at         DATETIME(3)     COMMENT '标记已使用时间',
    created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    INDEX idx_user_created (user_id, created_at),
    INDEX idx_expires_used (expires_at, used)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='identity Refresh Token 一次性会话表';

CREATE TABLE t_inbox (
    id              BIGINT          PRIMARY KEY COMMENT '主键ID（雪花ID）',
    event_id        VARCHAR(64)     NOT NULL UNIQUE COMMENT '事件唯一ID，用于消费端去重',
    event_type      VARCHAR(128)    NOT NULL COMMENT '事件类型，例如 trading.deposit.credited',
    source          VARCHAR(128)    NOT NULL COMMENT '事件来源服务',
    payload         JSON            NOT NULL COMMENT '消费时落地的事件 payload',
    status          TINYINT         NOT NULL DEFAULT 0 COMMENT '0=processing,1=done,2=failed',
    last_error      VARCHAR(512)    COMMENT '最近一次消费失败原因',
    consumed_at     DATETIME        COMMENT '消费完成时间',
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_status_created (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='identity-service 消息收件箱表';

-- ==================== falconx_market ====================
USE falconx_market;

CREATE TABLE t_symbol (
    id                  BIGINT          PRIMARY KEY COMMENT '主键ID（雪花ID）',
    lp_code             VARCHAR(32)     NOT NULL DEFAULT 'GODSA' COMMENT 'LP代码，例如 GODSA；与symbol共同唯一标识上游源',
    symbol              VARCHAR(32)     NOT NULL COMMENT 'LP源symbol，如 BTCUSDT / EURUSD / AAPL.NAS',
    category            TINYINT         NOT NULL COMMENT '上游源品类，1=crypto,2=forex,3=metal,4=index,5=energy,6=stock,7=etf,8=other',
    market_code         VARCHAR(32)     NOT NULL COMMENT '上游源市场代码，如 CRYPTO、FX、METAL、INDEX、ENERGY、US_STOCK、HK_STOCK、JP_STOCK、ETF、OTHER',
    base_currency       VARCHAR(16)     NOT NULL COMMENT '基础币种',
    quote_currency      VARCHAR(16)     NOT NULL COMMENT '计价币种',
    price_precision     INT             NOT NULL COMMENT '价格精度',
    qty_precision       INT             NOT NULL COMMENT '数量精度',
    status              TINYINT         NOT NULL DEFAULT 1 COMMENT '1=trading,2=suspended',
    created_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_symbol_lp_symbol (lp_code, symbol),
    INDEX idx_category_status (category, status),
    INDEX idx_symbol_lp_status (lp_code, status, symbol),
    CONSTRAINT chk_symbol_lp_code_nonempty CHECK (lp_code <> '')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='交易品种配置表';

CREATE TABLE t_symbol_quote_mapping (
    platform_symbol    VARCHAR(32)     NOT NULL COMMENT '平台展示和交易symbol，创建后不可改名',
    source_provider    VARCHAR(32)     NOT NULL DEFAULT 'LP' COMMENT '报价源类型，当前固定LP',
    source_lp_code     VARCHAR(32)     NOT NULL DEFAULT 'GODSA' COMMENT '报价源LP代码，对应t_symbol.lp_code',
    source_symbol      VARCHAR(32)     NOT NULL COMMENT '报价源原始symbol，例如XAUUSD',
    category           TINYINT         NOT NULL COMMENT '系统级品类，1=crypto,2=forex,3=metal,4=index,5=energy,6=stock,7=etf,8=other',
    market_code        VARCHAR(32)     NOT NULL COMMENT '系统级市场代码，如 CRYPTO、FX、METAL、INDEX、ENERGY、US_STOCK、HK_STOCK、JP_STOCK、ETF、OTHER',
    price_multiplier   DECIMAL(24,8)   NOT NULL DEFAULT 1.00000000 COMMENT '报价乘数，平台报价=源报价*乘数+加点',
    bid_adjustment     DECIMAL(24,8)   NOT NULL DEFAULT 0.00000000 COMMENT 'Bid绝对加点',
    ask_adjustment     DECIMAL(24,8)   NOT NULL DEFAULT 0.00000000 COMMENT 'Ask绝对加点',
    max_leverage       INT             NOT NULL DEFAULT 100 COMMENT '系统级最大杠杆',
    taker_fee_rate     DECIMAL(10,6)   NOT NULL DEFAULT 0 COMMENT '系统级手续费率',
    spread             DECIMAL(24,8)   NOT NULL DEFAULT 0 COMMENT '系统级基础点差',
    min_qty            DECIMAL(24,8)   NOT NULL DEFAULT 0 COMMENT '系统级最小下单数量',
    max_qty            DECIMAL(24,8)   NOT NULL DEFAULT 0 COMMENT '系统级最大下单数量',
    min_notional       DECIMAL(24,8)   NOT NULL DEFAULT 0 COMMENT '系统级最小名义价值',
    price_precision    INT             NOT NULL COMMENT '系统级价格精度',
    qty_precision      INT             NOT NULL COMMENT '系统级数量精度',
    enabled            TINYINT         NOT NULL DEFAULT 1 COMMENT '是否启用，1=启用，0=停用',
    lp_subscribe_enabled TINYINT       NOT NULL DEFAULT 1 COMMENT '是否订阅LP实时报价，1=订阅，0=不订阅',
    created_at         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (platform_symbol),
    INDEX idx_source_enabled (source_provider, source_lp_code, source_symbol, enabled),
    INDEX idx_source_subscription_enabled (source_provider, source_lp_code, source_symbol, enabled, lp_subscribe_enabled),
    INDEX idx_enabled_platform (enabled, platform_symbol),
    CONSTRAINT chk_mapping_source_lp_code_nonempty CHECK (source_lp_code <> '')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='平台symbol到报价源symbol映射配置表';

CREATE TABLE t_symbol_group_visibility (
    group_code         VARCHAR(64)     NOT NULL COMMENT '用户组代码',
    symbol             VARCHAR(32)     NOT NULL COMMENT '平台symbol，对应t_symbol_quote_mapping.platform_symbol',
    visible            TINYINT         NOT NULL DEFAULT 1 COMMENT '是否可见，1=可见，0=隐藏',
    created_at         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (group_code, symbol),
    INDEX idx_symbol_visible (symbol, visible),
    INDEX idx_group_visible (group_code, visible)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户组symbol可见性配置表';

CREATE TABLE t_trading_hours (
    id                  BIGINT          PRIMARY KEY COMMENT '主键ID（雪花ID）',
    symbol              VARCHAR(32)     NOT NULL COMMENT '系统平台symbol，对应t_symbol_quote_mapping.platform_symbol',
    day_of_week         TINYINT         NOT NULL COMMENT '星期，1=MON,2=TUE,3=WED,4=THU,5=FRI,6=SAT,7=SUN',
    session_no          TINYINT         NOT NULL COMMENT '同一天内的第几段交易时段，从1开始',
    open_time           TIME            NOT NULL COMMENT '开盘时间',
    close_time          TIME            NOT NULL COMMENT '收盘时间',
    timezone            VARCHAR(32)     NOT NULL COMMENT '时区，如 UTC、Europe/London、America/New_York',
    enabled             TINYINT         NOT NULL DEFAULT 1 COMMENT '是否启用，1=启用，0=停用',
    effective_from      DATE            NOT NULL COMMENT '规则生效日期',
    effective_to        DATE            COMMENT '规则失效日期，NULL=长期有效',
    created_at          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    UNIQUE KEY uk_symbol_day_session_effective (symbol, day_of_week, session_no, effective_from),
    KEY idx_symbol_effective (symbol, effective_from, effective_to),
    CONSTRAINT fk_trading_hours_platform_symbol FOREIGN KEY (symbol) REFERENCES t_symbol_quote_mapping(platform_symbol)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='交易时间周规则表';

CREATE TABLE t_trading_hours_exception (
    id                  BIGINT          PRIMARY KEY COMMENT '主键ID（雪花ID）',
    symbol              VARCHAR(32)     NOT NULL COMMENT '系统平台symbol，对应t_symbol_quote_mapping.platform_symbol',
    trade_date          DATE            NOT NULL COMMENT '特殊交易日',
    exception_type      TINYINT         NOT NULL COMMENT '1=FULL_CLOSE,2=SPECIAL_SESSION',
    session_no          TINYINT         COMMENT '特殊时段序号，FULL_CLOSE 时可为空',
    open_time           TIME            COMMENT '特殊开盘时间',
    close_time          TIME            COMMENT '特殊收盘时间',
    timezone            VARCHAR(32)     NOT NULL COMMENT '时区',
    reason              VARCHAR(128)    COMMENT '原因，如 maintenance、manual_override、special_event',
    created_at          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    UNIQUE KEY uk_symbol_trade_date_session (symbol, trade_date, session_no),
    KEY idx_symbol_trade_date (symbol, trade_date),
    CONSTRAINT fk_trading_hours_exception_platform_symbol FOREIGN KEY (symbol) REFERENCES t_symbol_quote_mapping(platform_symbol)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='交易时间例外规则表';

CREATE TABLE t_trading_holiday (
    id                  BIGINT          PRIMARY KEY COMMENT '主键ID（雪花ID）',
    market_code         VARCHAR(32)     NOT NULL COMMENT '市场代码，如 CRYPTO、FX、METAL、US_INDEX、EU_INDEX、UK_INDEX、HK_INDEX、USD_INDEX、ENERGY',
    holiday_date        DATE            NOT NULL COMMENT '节假日日期',
    holiday_type        TINYINT         NOT NULL COMMENT '1=FULL_CLOSE,2=EARLY_CLOSE,3=LATE_OPEN',
    open_time           TIME            COMMENT '晚开盘时间，仅 LATE_OPEN 使用',
    close_time          TIME            COMMENT '提前收盘时间，仅 EARLY_CLOSE 使用',
    timezone            VARCHAR(32)     NOT NULL COMMENT '时区',
    holiday_name        VARCHAR(128)    NOT NULL COMMENT '节假日名称',
    country_code        VARCHAR(16)     COMMENT '国家/地区代码，如 US、UK',
    created_at          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    UNIQUE KEY uk_market_holiday (market_code, holiday_date),
    KEY idx_market_date (market_code, holiday_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='交易节假日配置表';

CREATE TABLE t_swap_rate (
    id              BIGINT          PRIMARY KEY COMMENT '主键ID（雪花ID）',
    symbol          VARCHAR(32)     NOT NULL COMMENT '系统平台symbol，对应t_symbol_quote_mapping.platform_symbol',
    long_rate       DECIMAL(12,8)   NOT NULL COMMENT '多头隔夜费率',
    short_rate      DECIMAL(12,8)   NOT NULL COMMENT '空头隔夜费率',
    rollover_time   TIME            NOT NULL DEFAULT '22:00:00' COMMENT '每日结算时间（UTC）',
    effective_from  DATE            NOT NULL COMMENT '生效日期',
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE INDEX uk_symbol_effective (symbol, effective_from),
    CONSTRAINT fk_swap_rate_platform_symbol FOREIGN KEY (symbol) REFERENCES t_symbol_quote_mapping(platform_symbol)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='隔夜利息费率表';

CREATE TABLE t_outbox (
    id              BIGINT          PRIMARY KEY COMMENT '主键ID（雪花ID）',
    event_id        VARCHAR(64)     NOT NULL UNIQUE COMMENT '事件唯一ID，用于发送端去重',
    event_type      VARCHAR(128)    NOT NULL COMMENT '事件类型，例如 market.kline.update',
    topic           VARCHAR(128)    NOT NULL COMMENT '目标 Kafka Topic',
    partition_key   VARCHAR(128)    COMMENT '分区键',
    payload         JSON            NOT NULL COMMENT '事件完整 payload',
    status          TINYINT         NOT NULL DEFAULT 0 COMMENT '0=pending,1=dispatching,2=sent,3=failed,4=dead',
    retry_count     INT             NOT NULL DEFAULT 0 COMMENT '已重试次数',
    next_retry_at   DATETIME        COMMENT '下次可重试时间',
    last_error      VARCHAR(512)    COMMENT '最近一次发送失败原因',
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    sent_at         DATETIME        COMMENT '发送成功时间',
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_status_retry (status, next_retry_at, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='market-service 消息发件箱表';

-- ==================== falconx_trading ====================
USE falconx_trading;

CREATE TABLE t_account (
    id              BIGINT          PRIMARY KEY COMMENT '主键ID（雪花ID）',
    user_id         BIGINT          NOT NULL UNIQUE COMMENT '用户ID',
    currency        VARCHAR(16)     NOT NULL DEFAULT 'USDT' COMMENT '账户币种',
    balance         DECIMAL(24,8)   NOT NULL DEFAULT 0 COMMENT '总现金余额',
    frozen          DECIMAL(24,8)   NOT NULL DEFAULT 0 COMMENT '预留冻结金额',
    margin_used     DECIMAL(24,8)   NOT NULL DEFAULT 0 COMMENT '已占用保证金',
    version         INT             NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='交易账户表';

CREATE TABLE t_ledger (
    id                  BIGINT          PRIMARY KEY COMMENT '主键ID（雪花ID）',
    user_id             BIGINT          NOT NULL COMMENT '用户ID',
    account_id          BIGINT          NOT NULL COMMENT '账户ID',
    biz_type            TINYINT         NOT NULL COMMENT '1=deposit_credit,2=deposit_reversal,3=margin_reserved,4=fee_charged,5=margin_confirmed,6=swap_charge,7=swap_income,8=realized_pnl,9=liquidation_pnl,10=isolated_margin_supplement',
    idempotency_key     VARCHAR(64)     NOT NULL COMMENT '资金动作幂等键',
    reference_no        VARCHAR(128)    COMMENT '业务参考号，例如 txHash 或 orderNo',
    amount              DECIMAL(24,8)   NOT NULL COMMENT '本次变动金额',
    balance_before      DECIMAL(24,8)   NOT NULL COMMENT '余额变动前快照',
    balance_after       DECIMAL(24,8)   NOT NULL COMMENT '余额变动后快照',
    frozen_before       DECIMAL(24,8)   NOT NULL COMMENT '冻结金额变动前快照',
    frozen_after        DECIMAL(24,8)   NOT NULL COMMENT '冻结金额变动后快照',
    margin_used_before  DECIMAL(24,8)   NOT NULL COMMENT '保证金占用变动前快照',
    margin_used_after   DECIMAL(24,8)   NOT NULL COMMENT '保证金占用变动后快照',
    created_at          DATETIME(3)     NOT NULL COMMENT '账本记录时间',
    INDEX idx_user_created (user_id, created_at),
    INDEX idx_biz_type_created (biz_type, created_at),
    UNIQUE INDEX uk_ledger_user_idempotency (user_id, idempotency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='账本流水表';

CREATE TABLE t_deposit (
    id                  BIGINT          PRIMARY KEY COMMENT '主键ID（雪花ID）',
    user_id             BIGINT          NOT NULL COMMENT '用户ID',
    wallet_tx_id        BIGINT          NOT NULL COMMENT '钱包服务原始交易ID，wallet -> trading 跨服务唯一业务主键',
    chain               VARCHAR(16)     NOT NULL COMMENT '链标识',
    token               VARCHAR(16)     NOT NULL COMMENT '代币符号',
    tx_hash             VARCHAR(128)    NOT NULL COMMENT '链上交易哈希',
    amount              DECIMAL(24,8)   NOT NULL COMMENT '入账金额',
    status              TINYINT         NOT NULL DEFAULT 1 COMMENT '1=credited,2=reversed',
    credited_at         DATETIME        NOT NULL COMMENT '入账时间',
    reversed_at         DATETIME        COMMENT '回滚时间',
    created_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE INDEX uk_wallet_tx_id (wallet_tx_id),
    INDEX idx_chain_tx_hash (chain, tx_hash),
    INDEX idx_user_created (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='业务入金事实表';

CREATE TABLE t_order (
    id              BIGINT          PRIMARY KEY COMMENT '主键ID（雪花ID）',
    order_no        VARCHAR(32)     NOT NULL UNIQUE COMMENT '订单号',
    user_id         BIGINT          NOT NULL COMMENT '用户ID',
    symbol          VARCHAR(32)     NOT NULL COMMENT '交易品种',
    side            TINYINT         NOT NULL COMMENT '1=buy/long,2=sell/short',
    order_type      TINYINT         NOT NULL COMMENT '1=market,2=limit,3=stop',
    leverage        INT             NOT NULL COMMENT '杠杆倍数',
    quantity        DECIMAL(24,8)   NOT NULL COMMENT '下单数量',
    mark_price      DECIMAL(24,8)   COMMENT '下单时标记价',
    price_ts        DATETIME        COMMENT '价格时间戳',
    price_source    VARCHAR(32)     COMMENT '价格来源',
    filled_price    DECIMAL(24,8)   COMMENT '成交均价',
    margin          DECIMAL(24,8)   NOT NULL COMMENT '占用保证金',
    fee             DECIMAL(24,8)   NOT NULL DEFAULT 0 COMMENT '手续费',
    status          TINYINT         NOT NULL DEFAULT 0 COMMENT '0=pending,1=triggered,2=filled,3=cancelled,4=rejected',
    client_order_id VARCHAR(64)     COMMENT '幂等键',
    reject_reason   VARCHAR(256)    COMMENT '拒单原因',
    filled_at       DATETIME        COMMENT '成交时间',
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_user_status_created (user_id, status, created_at),
    INDEX idx_symbol_status_created (symbol, status, created_at),
    UNIQUE INDEX uk_user_client_order (user_id, client_order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单表';

CREATE TABLE t_position (
    id                  BIGINT          PRIMARY KEY COMMENT '主键ID（雪花ID）',
    user_id             BIGINT          NOT NULL COMMENT '用户ID',
    symbol              VARCHAR(32)     NOT NULL COMMENT '交易品种',
    side                TINYINT         NOT NULL COMMENT '1=long,2=short',
    leverage            INT             NOT NULL COMMENT '杠杆倍数',
    quantity            DECIMAL(24,8)   NOT NULL COMMENT '持仓数量',
    entry_price         DECIMAL(24,8)   NOT NULL COMMENT '开仓均价',
    margin              DECIMAL(24,8)   NOT NULL COMMENT '占用保证金',
    take_profit_price   DECIMAL(24,8)   COMMENT '止盈触发价，NULL=未设置',
    stop_loss_price     DECIMAL(24,8)   COMMENT '止损触发价，NULL=未设置',
    realized_pnl        DECIMAL(24,8)   NOT NULL DEFAULT 0 COMMENT '已实现盈亏',
    liquidation_price   DECIMAL(24,8)   COMMENT '强平价格',
    status              TINYINT         NOT NULL DEFAULT 1 COMMENT '1=open,2=closed,3=liquidated',
    close_price         DECIMAL(24,8)   COMMENT '平仓价',
    close_reason        TINYINT         COMMENT '1=manual,2=tp,3=sl,4=liquidation',
    version             INT             NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    opened_at           DATETIME        NOT NULL COMMENT '开仓时间',
    closed_at           DATETIME        COMMENT '平仓时间',
    created_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_user_status (user_id, status),
    INDEX idx_user_symbol_status (user_id, symbol, status),
    INDEX idx_symbol_status_liq (symbol, status, liquidation_price)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='持仓表';

CREATE TABLE t_risk_exposure (
    symbol          VARCHAR(32)     PRIMARY KEY COMMENT '交易品种',
    total_long_qty  DECIMAL(24,8)   NOT NULL DEFAULT 0 COMMENT '全平台多头总持仓量',
    total_short_qty DECIMAL(24,8)   NOT NULL DEFAULT 0 COMMENT '全平台空头总持仓量',
    net_exposure    DECIMAL(24,8)   NOT NULL DEFAULT 0 COMMENT '净敞口 = 多头量 - 空头量',
    net_exposure_usd DECIMAL(24,8)  NOT NULL DEFAULT 0 COMMENT '净美元敞口 = net_exposure * mark_price',
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='品种净敞口实时汇总表';

CREATE TABLE t_trade (
    id              BIGINT          PRIMARY KEY COMMENT '主键ID（雪花ID）',
    user_id         BIGINT          NOT NULL COMMENT '用户ID',
    order_id        BIGINT          NOT NULL COMMENT '订单ID',
    position_id     BIGINT          NOT NULL COMMENT '持仓ID',
    symbol          VARCHAR(32)     NOT NULL COMMENT '交易品种',
    side            TINYINT         NOT NULL COMMENT '成交方向',
    price           DECIMAL(24,8)   NOT NULL COMMENT '成交价格',
    price_ts        DATETIME        COMMENT '价格时间戳',
    price_source    VARCHAR(32)     COMMENT '价格来源',
    quantity        DECIMAL(24,8)   NOT NULL COMMENT '成交数量',
    fee             DECIMAL(24,8)   NOT NULL DEFAULT 0 COMMENT '手续费',
    realized_pnl    DECIMAL(24,8)   COMMENT '已实现盈亏',
    trade_type      TINYINT         NOT NULL COMMENT '1=open,2=close',
    idempotency_key VARCHAR(64)     COMMENT '成交幂等键',
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_user_created (user_id, created_at),
    INDEX idx_order_id (order_id),
    INDEX idx_position_id (position_id),
    INDEX idx_symbol_created (symbol, created_at),
    UNIQUE INDEX uk_trade_user_idempotency (user_id, idempotency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='成交表';

CREATE TABLE t_risk_config (
    id                      BIGINT          PRIMARY KEY COMMENT '主键ID（雪花ID）',
    symbol                  VARCHAR(32)     NOT NULL UNIQUE COMMENT '交易品种',
    max_position_per_user   DECIMAL(24,8)   NOT NULL COMMENT '单用户最大持仓',
    max_position_total      DECIMAL(24,8)   NOT NULL COMMENT '平台总持仓上限',
    hedge_threshold_usd     DECIMAL(24,8)   NOT NULL DEFAULT 0 COMMENT '净美元敞口告警阈值，按绝对值比较',
    maintenance_margin_rate DECIMAL(10,6)   NOT NULL COMMENT '维持保证金率',
    max_leverage            INT             NOT NULL COMMENT '最大杠杆',
    created_at              DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at              DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='风控参数表';

CREATE TABLE t_hedge_log (
    id                  BIGINT          PRIMARY KEY COMMENT '主键ID（雪花ID）',
    symbol              VARCHAR(32)     NOT NULL COMMENT '交易品种',
    position_id         BIGINT          COMMENT '触发本次观测的持仓ID，纯行情刷新时为空',
    trigger_source      TINYINT         NOT NULL COMMENT '1=open_position,2=manual_close,3=take_profit,4=stop_loss,5=liquidation,6=price_tick',
    action_status       TINYINT         NOT NULL COMMENT '1=alert_only,2=recovered',
    net_exposure        DECIMAL(24,8)   NOT NULL COMMENT '当前净敞口数量',
    net_exposure_usd    DECIMAL(24,8)   NOT NULL COMMENT '当前净美元敞口',
    hedge_threshold_usd DECIMAL(24,8)   NOT NULL COMMENT '触发时的对冲阈值（美元）',
    mark_price          DECIMAL(24,8)   NOT NULL COMMENT '本次估值使用的标记价',
    price_ts            DATETIME        COMMENT '估值使用的行情时间',
    price_source        VARCHAR(32)     COMMENT '估值使用的行情来源',
    created_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_symbol_created (symbol, created_at),
    INDEX idx_symbol_status_created (symbol, action_status, created_at),
    INDEX idx_position_id (position_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='B-book 对冲观测日志表';

CREATE TABLE t_liquidation_log (
    id                BIGINT          PRIMARY KEY COMMENT '主键ID（雪花ID）',
    user_id           BIGINT          NOT NULL COMMENT '用户ID',
    position_id       BIGINT          NOT NULL COMMENT '持仓ID',
    symbol            VARCHAR(32)     NOT NULL COMMENT '交易品种',
    side              TINYINT         NOT NULL COMMENT '持仓方向',
    quantity          DECIMAL(24,8)   NOT NULL COMMENT '强平数量',
    entry_price       DECIMAL(24,8)   NOT NULL COMMENT '开仓价',
    liquidation_price DECIMAL(24,8)   NOT NULL COMMENT '强平触发价',
    mark_price        DECIMAL(24,8)   NOT NULL COMMENT '触发时标记价',
    price_ts          DATETIME        COMMENT '价格时间戳',
    price_source      VARCHAR(32)     COMMENT '价格来源',
    loss              DECIMAL(24,8)   NOT NULL COMMENT '本次亏损',
    fee               DECIMAL(24,8)   NOT NULL DEFAULT 0 COMMENT '强平手续费',
    margin_released   DECIMAL(24,8)   NOT NULL COMMENT '释放保证金',
    platform_covered_loss DECIMAL(24,8) NOT NULL DEFAULT 0 COMMENT '平台兜底亏损金额',
    created_at        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_user_id (user_id),
    INDEX idx_position_id (position_id),
    INDEX idx_symbol_created (symbol, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='强平日志表';

CREATE TABLE t_outbox (
    id              BIGINT          PRIMARY KEY COMMENT '主键ID（雪花ID）',
    event_id        VARCHAR(64)     NOT NULL UNIQUE COMMENT '事件唯一ID，用于发送端去重',
    event_type      VARCHAR(128)    NOT NULL COMMENT '事件类型，例如 trading.order.created',
    topic           VARCHAR(128)    NOT NULL COMMENT '目标 Kafka Topic',
    partition_key   VARCHAR(128)    COMMENT '分区键',
    payload         JSON            NOT NULL COMMENT '事件完整 payload',
    status          TINYINT         NOT NULL DEFAULT 0 COMMENT '0=pending,1=dispatching,2=sent,3=failed,4=dead',
    retry_count     INT             NOT NULL DEFAULT 0 COMMENT '已重试次数',
    next_retry_at   DATETIME        COMMENT '下次可重试时间',
    last_error      VARCHAR(512)    COMMENT '最近一次发送失败原因',
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    sent_at         DATETIME        COMMENT '发送成功时间',
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_status_retry (status, next_retry_at, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='trading-core-service 消息发件箱表';

CREATE TABLE t_inbox (
    id              BIGINT          PRIMARY KEY COMMENT '主键ID（雪花ID）',
    event_id        VARCHAR(64)     NOT NULL UNIQUE COMMENT '事件唯一ID，用于消费端去重',
    event_type      VARCHAR(128)    NOT NULL COMMENT '事件类型，例如 wallet.deposit.confirmed',
    source          VARCHAR(128)    NOT NULL COMMENT '事件来源服务',
    payload         JSON            NOT NULL COMMENT '消费时落地的事件 payload',
    status          TINYINT         NOT NULL DEFAULT 0 COMMENT '0=processing,1=done,2=failed',
    last_error      VARCHAR(512)    COMMENT '最近一次消费失败原因',
    consumed_at     DATETIME        COMMENT '消费完成时间',
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_status_created (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='trading-core-service 低频关键事件收件箱表';

-- ==================== falconx_wallet ====================
USE falconx_wallet;

CREATE TABLE t_wallet_address (
    id              BIGINT          PRIMARY KEY COMMENT '主键ID（雪花ID）',
    user_id         BIGINT          NOT NULL COMMENT '用户ID',
    chain           VARCHAR(16)     NOT NULL COMMENT '链标识（ETH/BSC/TRON/SOL）',
    token           VARCHAR(16)     NOT NULL DEFAULT 'USDT' COMMENT '入金代币符号',
    network         VARCHAR(16)     NOT NULL COMMENT '入金网络展示名，如 TRC20/ERC20',
    address         VARCHAR(128)    NOT NULL COMMENT '钱包地址',
    address_index   INT             NOT NULL COMMENT 'HD派生索引',
    derivation_path VARCHAR(128)    NOT NULL COMMENT 'HD 派生路径',
    status          TINYINT         NOT NULL DEFAULT 1 COMMENT '1=assigned,2=disabled',
    assigned_at     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '分配时间',
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE INDEX uk_user_chain (user_id, chain),
    UNIQUE INDEX uk_chain_address (chain, address)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='钱包地址表';

CREATE TABLE t_wallet_deposit_tx (
    id                  BIGINT          PRIMARY KEY COMMENT '主键ID（雪花ID）',
    user_id             BIGINT          COMMENT '归属用户ID',
    chain               VARCHAR(16)     NOT NULL COMMENT '链标识',
    token               VARCHAR(16)     NOT NULL COMMENT '代币符号',
    token_contract_address VARCHAR(128) COMMENT '代币合约地址，原生币为空',
    tx_hash             VARCHAR(128)    NOT NULL COMMENT '链上交易哈希',
    log_index           INT             NOT NULL DEFAULT 0 COMMENT '日志索引，原生币固定为 0',
    from_address        VARCHAR(128)    NOT NULL COMMENT '来源地址',
    to_address          VARCHAR(128)    NOT NULL COMMENT '目标地址',
    amount              DECIMAL(24,8)   NOT NULL COMMENT '原始链上金额',
    block_number        BIGINT          COMMENT '区块高度',
    confirmations       INT             NOT NULL DEFAULT 0 COMMENT '当前确认数',
    required_confirms   INT             NOT NULL COMMENT '所需确认数',
    status              TINYINT         NOT NULL DEFAULT 0 COMMENT '0=detected,1=confirming,2=confirmed,3=reversed,4=ignored',
    detected_at         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '检测时间',
    confirmed_at        DATETIME        COMMENT '确认时间',
    updated_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE INDEX uk_chain_tx_log_index (chain, tx_hash, log_index),
    INDEX idx_chain_status_updated (chain, status, updated_at),
    INDEX idx_to_address_detected (to_address, detected_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='原始链上入金交易表';

CREATE TABLE t_wallet_chain_cursor (
    id              BIGINT          PRIMARY KEY COMMENT '主键ID（雪花ID）',
    chain           VARCHAR(16)     NOT NULL UNIQUE COMMENT '链标识',
    cursor_type     VARCHAR(32)     NOT NULL COMMENT '游标类型，例如 block / slot / signature',
    cursor_value    VARCHAR(128)    NOT NULL COMMENT '当前游标值',
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='链监听游标表';

CREATE TABLE t_outbox (
    id              BIGINT          PRIMARY KEY COMMENT '主键ID（雪花ID）',
    event_id        VARCHAR(64)     NOT NULL UNIQUE COMMENT '事件唯一ID，用于发送端去重',
    event_type      VARCHAR(128)    NOT NULL COMMENT '事件类型，例如 wallet.deposit.confirmed',
    topic           VARCHAR(128)    NOT NULL COMMENT '目标 Kafka Topic',
    partition_key   VARCHAR(128)    COMMENT '分区键',
    payload         JSON            NOT NULL COMMENT '事件完整 payload',
    status          TINYINT         NOT NULL DEFAULT 0 COMMENT '0=pending,1=dispatching,2=sent,3=failed,4=dead',
    retry_count     INT             NOT NULL DEFAULT 0 COMMENT '已重试次数',
    next_retry_at   DATETIME        COMMENT '下次可重试时间',
    last_error      VARCHAR(512)    COMMENT '最近一次发送失败原因',
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    sent_at         DATETIME        COMMENT '发送成功时间',
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_status_retry (status, next_retry_at, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='wallet-service 消息发件箱表';
