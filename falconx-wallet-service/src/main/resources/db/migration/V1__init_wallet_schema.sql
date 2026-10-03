CREATE TABLE IF NOT EXISTS t_wallet_address (
    id              BIGINT          PRIMARY KEY COMMENT '主键 ID（雪花 ID）',
    user_id         BIGINT          NOT NULL COMMENT '用户 ID',
    chain           VARCHAR(16)     NOT NULL COMMENT '链标识',
    address         VARCHAR(128)    NOT NULL COMMENT '钱包地址',
    address_index   INT             NOT NULL COMMENT 'HD 派生索引',
    status          TINYINT         NOT NULL DEFAULT 1 COMMENT '1=assigned,2=disabled',
    assigned_at     DATETIME(3)     NOT NULL COMMENT '分配时间',
    created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    UNIQUE INDEX uk_user_chain (user_id, chain),
    UNIQUE INDEX uk_chain_address (chain, address)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='钱包地址表';

CREATE TABLE IF NOT EXISTS t_wallet_deposit_tx (
    id                  BIGINT          PRIMARY KEY COMMENT '主键 ID（雪花 ID）',
    user_id             BIGINT          NULL COMMENT '归属用户 ID',
    chain               VARCHAR(16)     NOT NULL COMMENT '链标识',
    token               VARCHAR(16)     NOT NULL COMMENT '代币符号',
    tx_hash             VARCHAR(128)    NOT NULL COMMENT '链上交易哈希',
    from_address        VARCHAR(128)    NOT NULL COMMENT '来源地址',
    to_address          VARCHAR(128)    NOT NULL COMMENT '目标地址',
    amount              DECIMAL(24,8)   NOT NULL COMMENT '原始链上金额',
    block_number        BIGINT          NULL COMMENT '区块高度',
    confirmations       INT             NOT NULL DEFAULT 0 COMMENT '当前确认数',
    required_confirms   INT             NOT NULL COMMENT '所需确认数',
    status              TINYINT         NOT NULL DEFAULT 0 COMMENT '0=detected,1=confirming,2=confirmed,3=reversed,4=ignored',
    detected_at         DATETIME(3)     NOT NULL COMMENT '检测时间',
    confirmed_at        DATETIME(3)     NULL COMMENT '确认时间',
    updated_at          DATETIME(3)     NOT NULL COMMENT '最后更新时间',
    UNIQUE INDEX uk_chain_tx (chain, tx_hash),
    INDEX idx_chain_status_updated (chain, status, updated_at),
    INDEX idx_to_address_detected (to_address, detected_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='原始链上入金交易表';

CREATE TABLE IF NOT EXISTS t_wallet_chain_cursor (
    id              BIGINT          PRIMARY KEY COMMENT '主键 ID（雪花 ID）',
    chain           VARCHAR(16)     NOT NULL UNIQUE COMMENT '链标识',
    cursor_type     VARCHAR(32)     NOT NULL COMMENT '游标类型',
    cursor_value    VARCHAR(128)    NOT NULL COMMENT '当前游标值',
    updated_at      DATETIME(3)     NOT NULL COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='链监听游标表';

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
    sent_at         DATETIME(3)     NULL COMMENT '发送成功时间',
    updated_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    INDEX idx_status_retry (status, next_retry_at, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='wallet 低频事件发件箱表';
