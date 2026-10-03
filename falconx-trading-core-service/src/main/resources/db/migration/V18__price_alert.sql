-- V18: STAGE-4-PRICE-ALERT
-- 用户价格告警表。3 次触发上限 + 5 分钟节流。

CREATE TABLE IF NOT EXISTS t_price_alert (
    id                   BIGINT          NOT NULL    COMMENT '主键（雪花 ID）',
    user_id              BIGINT          NOT NULL    COMMENT '用户 ID',
    symbol               VARCHAR(32)     NOT NULL    COMMENT '交易品种（平台 symbol）',
    direction            TINYINT         NOT NULL    COMMENT '1=ABOVE（向上穿透），2=BELOW（向下穿透）',
    target_price         DECIMAL(24,8)   NOT NULL    COMMENT '触发价',
    status               TINYINT         NOT NULL    COMMENT '1=ACTIVE, 2=EXHAUSTED, 3=CANCELLED, 4=ADMIN_DELETED',
    note                 VARCHAR(200)    NULL        COMMENT '用户备注',
    base_price           DECIMAL(24,8)   NULL        COMMENT '创建时的 markPrice 快照',
    trigger_count        INT             NOT NULL DEFAULT 0  COMMENT '已触发次数 0..3',
    last_triggered_at    DATETIME(3)     NULL        COMMENT '最近一次触发时间（5min 节流判定）',
    last_triggered_price DECIMAL(24,8)   NULL        COMMENT '最近一次触发瞬间 markPrice',
    cancelled_at         DATETIME(3)     NULL        COMMENT '撤销时间',
    cancel_source        VARCHAR(50)     NULL        COMMENT 'USER / ADMIN:{adminUserId}',
    created_at           DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at           DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    INDEX idx_user_status        (user_id, status),
    INDEX idx_symbol_status      (symbol, status),
    INDEX idx_status_target      (status, target_price),
    INDEX idx_last_triggered_at  (last_triggered_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户价格告警表（3次/5min 节流）';
