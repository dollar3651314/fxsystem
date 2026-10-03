-- V16: STAGE-3-PENDING-ORDER
--   1) 新表 t_pending_order_trigger 统一承载 LIMIT/STOP/STOP_LIMIT/SL_TP
--   2) t_risk_config 加挂单距离阈值
--   3) migration t_position SL/TP 字段到挂单表（id=-1 占位，trading-core 启动 backfill）

-- 1. 挂单触发表
CREATE TABLE IF NOT EXISTS t_pending_order_trigger (
    id                  BIGINT          NOT NULL                COMMENT '主键（雪花 ID）',
    order_no            VARCHAR(32)     NOT NULL                COMMENT '订单号；对外可见，复用 t_order.order_no 命名空间',
    user_id             BIGINT          NOT NULL                COMMENT '下单用户',
    symbol              VARCHAR(32)     NOT NULL                COMMENT '交易品种',
    order_type          TINYINT         NOT NULL                COMMENT '2=LIMIT, 3=STOP, 4=STOP_LIMIT, 5=SL_TP',
    side                TINYINT         NOT NULL                COMMENT '1=BUY, 2=SELL',
    quantity            DECIMAL(24,8)   NOT NULL                COMMENT '挂单数量',
    trigger_price       DECIMAL(24,8)   NOT NULL                COMMENT '触发价',
    limit_price         DECIMAL(24,8)   NULL                    COMMENT 'STOP_LIMIT 触发后挂 LIMIT 单的限价；其余 NULL',
    leverage            DECIMAL(8,2)    NOT NULL                COMMENT '杠杆',
    margin_mode         TINYINT         NOT NULL                COMMENT '保证金模式快照：1=CROSS, 2=ISOLATED',
    frozen_margin       DECIMAL(24,8)   NOT NULL DEFAULT 0      COMMENT '冻结保证金（SL_TP=0）',
    frozen_fee          DECIMAL(24,8)   NOT NULL DEFAULT 0      COMMENT '冻结预估手续费（SL_TP=0）',
    status              TINYINT         NOT NULL                COMMENT '1=PENDING, 2=TRIGGERED, 3=CANCELLED, 4=EXPIRED, 5=REJECTED',
    parent_position_id  BIGINT          NULL                    COMMENT '关联持仓 ID（SL_TP 类型有值）',
    trigger_kind        TINYINT         NULL                    COMMENT 'SL_TP 类型细分：1=TAKE_PROFIT, 2=STOP_LOSS',
    client_order_id     VARCHAR(64)     NULL                    COMMENT '幂等键',
    triggered_order_id  BIGINT          NULL                    COMMENT '触发后落地的 t_order.id',
    triggered_at        DATETIME(3)     NULL                    COMMENT '触发时间',
    cancelled_at        DATETIME(3)     NULL                    COMMENT '撤单时间',
    cancel_reason       VARCHAR(200)    NULL                    COMMENT '撤单原因',
    created_at          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    INDEX idx_user_status      (user_id, status),
    INDEX idx_symbol_status    (symbol, status),
    INDEX idx_position_id      (parent_position_id),
    INDEX idx_status_trigger   (status, trigger_price)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='挂单触发表（LIMIT/STOP/STOP_LIMIT/SL_TP 统一）';

-- 2. 挂单距离阈值
ALTER TABLE t_risk_config
    ADD COLUMN pending_order_min_distance_ratio DECIMAL(8,6) NULL
        COMMENT '挂单价与 markPrice 最小距离比例（0-1）；NULL=不限' AFTER direction_imbalance_min_total_usd;
UPDATE t_risk_config SET pending_order_min_distance_ratio = 0.003000 WHERE symbol IS NOT NULL;

-- 3. SL/TP migration：V16 只建表 + 加阈值列；t_position SL/TP 数据的迁移由
--    trading-core 启动时 PendingOrderMigrationBackfill 用雪花 ID 一次性写入，
--    避免 SQL migration 内多行预占主键冲突 + 雪花 ID 无法在 SQL 内生成的问题。
--    幂等：backfill 前检查 t_pending_order_trigger 是否已含对应 parent_position_id 的 SL_TP 行，已存在则跳过。
-- 注：t_position.take_profit_price / stop_loss_price 字段保留到 V17 阶段 3 稳定后再删
