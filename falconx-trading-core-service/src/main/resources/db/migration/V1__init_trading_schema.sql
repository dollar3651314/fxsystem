CREATE TABLE IF NOT EXISTS t_account (
    id              BIGINT          PRIMARY KEY COMMENT '主键 ID（雪花 ID）',
    user_id         BIGINT          NOT NULL UNIQUE COMMENT '用户 ID',
    currency        VARCHAR(16)     NOT NULL DEFAULT 'USDT' COMMENT '账户币种',
    balance         DECIMAL(24,8)   NOT NULL DEFAULT 0 COMMENT '总现金余额',
    frozen          DECIMAL(24,8)   NOT NULL DEFAULT 0 COMMENT '预留冻结金额',
    margin_used     DECIMAL(24,8)   NOT NULL DEFAULT 0 COMMENT '已占用保证金',
    version         INT             NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='交易账户表';

CREATE TABLE IF NOT EXISTS t_ledger (
    id                  BIGINT          PRIMARY KEY COMMENT '主键 ID（雪花 ID）',
    user_id             BIGINT          NOT NULL COMMENT '用户 ID',
    account_id          BIGINT          NOT NULL COMMENT '账户 ID',
    biz_type            TINYINT         NOT NULL COMMENT '1=deposit_credit,2=deposit_reversal,3=margin_reserved,4=fee_charged,5=margin_confirmed',
    idempotency_key     VARCHAR(64)     NOT NULL COMMENT '账务动作幂等键',
    reference_no        VARCHAR(128)    NULL COMMENT '业务参考号，例如 txHash 或 orderNo',
    amount              DECIMAL(24,8)   NOT NULL COMMENT '本次动作金额',
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

CREATE TABLE IF NOT EXISTS t_deposit (
    id                  BIGINT          PRIMARY KEY COMMENT '主键 ID（雪花 ID）',
    user_id             BIGINT          NOT NULL COMMENT '用户 ID',
    account_id          BIGINT          NOT NULL COMMENT '入账账户 ID',
    wallet_tx_id        BIGINT          NULL COMMENT '原始钱包交易 ID，当前阶段可为空',
    chain               VARCHAR(16)     NOT NULL COMMENT '链标识',
    token               VARCHAR(16)     NOT NULL COMMENT '代币符号',
    tx_hash             VARCHAR(128)    NOT NULL COMMENT '链上交易哈希',
    amount              DECIMAL(24,8)   NOT NULL COMMENT '入账金额',
    status              TINYINT         NOT NULL DEFAULT 1 COMMENT '1=credited,2=reversed',
    credited_at         DATETIME(3)     NOT NULL COMMENT '入账时间',
    reversed_at         DATETIME(3)     NULL COMMENT '回滚时间',
    created_at          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    UNIQUE INDEX uk_chain_tx (chain, tx_hash),
    INDEX idx_user_created (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='业务入金事实表';

CREATE TABLE IF NOT EXISTS t_order (
    id                  BIGINT          PRIMARY KEY COMMENT '主键 ID（雪花 ID）',
    order_no            VARCHAR(32)     NOT NULL UNIQUE COMMENT '订单号',
    user_id             BIGINT          NOT NULL COMMENT '用户 ID',
    symbol              VARCHAR(32)     NOT NULL COMMENT '交易品种',
    side                TINYINT         NOT NULL COMMENT '1=buy,2=sell',
    order_type          TINYINT         NOT NULL COMMENT '1=market',
    quantity            DECIMAL(24,8)   NOT NULL COMMENT '下单数量',
    requested_price     DECIMAL(24,8)   NULL COMMENT '请求时标记价',
    filled_price        DECIMAL(24,8)   NULL COMMENT '成交价',
    leverage            DECIMAL(24,8)   NOT NULL COMMENT '杠杆倍数',
    margin              DECIMAL(24,8)   NOT NULL COMMENT '占用保证金',
    fee                 DECIMAL(24,8)   NOT NULL DEFAULT 0 COMMENT '手续费',
    client_order_id     VARCHAR(64)     NOT NULL COMMENT '客户端幂等键',
    status              TINYINT         NOT NULL DEFAULT 0 COMMENT '0=pending,1=triggered,2=filled,3=cancelled,4=rejected',
    reject_reason       VARCHAR(256)    NULL COMMENT '拒单原因',
    created_at          DATETIME(3)     NOT NULL COMMENT '创建时间',
    updated_at          DATETIME(3)     NOT NULL COMMENT '更新时间',
    INDEX idx_user_status_created (user_id, status, created_at),
    INDEX idx_symbol_status_created (symbol, status, created_at),
    UNIQUE INDEX uk_user_client_order (user_id, client_order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单表';

CREATE TABLE IF NOT EXISTS t_position (
    id                  BIGINT          PRIMARY KEY COMMENT '主键 ID（雪花 ID）',
    opening_order_id    BIGINT          NOT NULL UNIQUE COMMENT '开仓订单 ID',
    user_id             BIGINT          NOT NULL COMMENT '用户 ID',
    symbol              VARCHAR(32)     NOT NULL COMMENT '交易品种',
    side                TINYINT         NOT NULL COMMENT '1=buy,2=sell',
    quantity            DECIMAL(24,8)   NOT NULL COMMENT '持仓数量',
    entry_price         DECIMAL(24,8)   NOT NULL COMMENT '开仓均价',
    leverage            DECIMAL(24,8)   NOT NULL COMMENT '杠杆倍数',
    margin              DECIMAL(24,8)   NOT NULL COMMENT '占用保证金',
    liquidation_price   DECIMAL(24,8)   NULL COMMENT '强平价',
    status              TINYINT         NOT NULL DEFAULT 1 COMMENT '1=open,2=closed,3=liquidated',
    opened_at           DATETIME(3)     NOT NULL COMMENT '开仓时间',
    updated_at          DATETIME(3)     NOT NULL COMMENT '更新时间',
    INDEX idx_user_status (user_id, status),
    INDEX idx_user_symbol_status (user_id, symbol, status),
    INDEX idx_symbol_status_liq (symbol, status, liquidation_price)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='持仓表';

CREATE TABLE IF NOT EXISTS t_trade (
    id                  BIGINT          PRIMARY KEY COMMENT '主键 ID（雪花 ID）',
    order_id            BIGINT          NOT NULL COMMENT '订单 ID',
    position_id         BIGINT          NOT NULL COMMENT '持仓 ID',
    user_id             BIGINT          NOT NULL COMMENT '用户 ID',
    symbol              VARCHAR(32)     NOT NULL COMMENT '交易品种',
    side                TINYINT         NOT NULL COMMENT '成交方向',
    quantity            DECIMAL(24,8)   NOT NULL COMMENT '成交数量',
    price               DECIMAL(24,8)   NOT NULL COMMENT '成交价格',
    fee                 DECIMAL(24,8)   NOT NULL DEFAULT 0 COMMENT '手续费',
    realized_pnl        DECIMAL(24,8)   NOT NULL DEFAULT 0 COMMENT '已实现盈亏',
    traded_at           DATETIME(3)     NOT NULL COMMENT '成交时间',
    INDEX idx_user_created (user_id, traded_at),
    INDEX idx_order_id (order_id),
    INDEX idx_position_id (position_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='成交表';

CREATE TABLE IF NOT EXISTS t_risk_config (
    id                      BIGINT          PRIMARY KEY COMMENT '主键 ID（雪花 ID）',
    symbol                  VARCHAR(32)     NOT NULL UNIQUE COMMENT '交易品种',
    max_position_per_user   DECIMAL(24,8)   NOT NULL COMMENT '单用户最大持仓',
    max_position_total      DECIMAL(24,8)   NOT NULL COMMENT '平台总持仓上限',
    maintenance_margin_rate DECIMAL(10,6)   NOT NULL COMMENT '维持保证金率',
    max_leverage            INT             NOT NULL COMMENT '最大杠杆',
    created_at              DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at              DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='风控参数表';

CREATE TABLE IF NOT EXISTS t_liquidation_log (
    id                  BIGINT          PRIMARY KEY COMMENT '主键 ID（雪花 ID）',
    user_id             BIGINT          NOT NULL COMMENT '用户 ID',
    position_id         BIGINT          NOT NULL COMMENT '持仓 ID',
    symbol              VARCHAR(32)     NOT NULL COMMENT '交易品种',
    side                TINYINT         NOT NULL COMMENT '持仓方向',
    quantity            DECIMAL(24,8)   NOT NULL COMMENT '强平数量',
    entry_price         DECIMAL(24,8)   NOT NULL COMMENT '开仓价',
    liquidation_price   DECIMAL(24,8)   NOT NULL COMMENT '强平触发价',
    mark_price          DECIMAL(24,8)   NOT NULL COMMENT '触发时标记价',
    price_ts            DATETIME(3)     NULL COMMENT '价格时间戳',
    price_source        VARCHAR(32)     NULL COMMENT '价格来源',
    loss                DECIMAL(24,8)   NOT NULL COMMENT '本次亏损',
    fee                 DECIMAL(24,8)   NOT NULL DEFAULT 0 COMMENT '强平手续费',
    margin_released     DECIMAL(24,8)   NOT NULL COMMENT '释放保证金',
    created_at          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    INDEX idx_user_id (user_id),
    INDEX idx_position_id (position_id),
    INDEX idx_symbol_created (symbol, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='强平日志表';

CREATE TABLE IF NOT EXISTS t_outbox (
    id              BIGINT          PRIMARY KEY COMMENT '主键 ID（雪花 ID）',
    event_id        VARCHAR(64)     NOT NULL UNIQUE COMMENT '事件唯一 ID',
    event_type      VARCHAR(128)    NOT NULL COMMENT '事件类型',
    topic           VARCHAR(128)    NOT NULL COMMENT '目标 Topic',
    partition_key   VARCHAR(128)    NULL COMMENT '分区键',
    payload         JSON            NOT NULL COMMENT '事件 payload',
    status          TINYINT         NOT NULL DEFAULT 0 COMMENT '0=pending,1=dispatching,2=sent,3=failed,4=dead',
    retry_count     INT             NOT NULL DEFAULT 0 COMMENT '已重试次数',
    next_retry_at   DATETIME(3)     NULL COMMENT '下次重试时间',
    last_error      VARCHAR(512)    NULL COMMENT '最近一次发送失败原因',
    created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    sent_at         DATETIME(3)     NULL COMMENT '发送成功时间',
    updated_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    INDEX idx_status_retry (status, next_retry_at, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='交易低频事件发件箱表';

CREATE TABLE IF NOT EXISTS t_inbox (
    id              BIGINT          PRIMARY KEY COMMENT '主键 ID（雪花 ID）',
    event_id        VARCHAR(64)     NOT NULL UNIQUE COMMENT '事件唯一 ID',
    event_type      VARCHAR(128)    NOT NULL COMMENT '事件类型',
    source          VARCHAR(128)    NOT NULL COMMENT '事件来源服务',
    payload         JSON            NOT NULL COMMENT '事件 payload',
    status          TINYINT         NOT NULL DEFAULT 0 COMMENT '0=processing,1=done,2=failed',
    last_error      VARCHAR(512)    NULL COMMENT '最近一次失败原因',
    consumed_at     DATETIME(3)     NULL COMMENT '消费完成时间',
    created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    INDEX idx_status_created (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='交易低频关键事件收件箱表';
