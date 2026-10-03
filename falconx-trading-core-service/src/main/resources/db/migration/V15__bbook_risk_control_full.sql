-- V15: BBOOK-RISK-CONTROL-01
--   1) 方向集中度阈值（t_risk_config 加 2 列）
--   2) 用户级风控阈值表
--   3) 平台总净敞口阈值（symbol IS NULL 行）

-- 1. 方向集中度
ALTER TABLE t_risk_config
    ADD COLUMN direction_imbalance_ratio_threshold DECIMAL(8,4) NULL
        COMMENT '多/空 USD 失衡比阈值（0-1）；任一侧占比≥阈值触发' AFTER hedge_threshold_usd,
    ADD COLUMN direction_imbalance_min_total_usd DECIMAL(24,8) NULL
        COMMENT '触发的最小总敞口（USD），小于此值不触发避免小盘误伤' AFTER direction_imbalance_ratio_threshold;

-- 2. 用户级风控阈值
CREATE TABLE IF NOT EXISTS t_user_risk_threshold (
    user_id                                BIGINT          NOT NULL    COMMENT 'identity.t_user.id',
    net_exposure_threshold_usd             DECIMAL(24,8)   NULL        COMMENT '普通用户单用户净敞口阈值（USD）',
    profitable_net_exposure_threshold_usd  DECIMAL(24,8)   NULL        COMMENT '盈利用户单用户敞口阈值（USD）',
    is_profitable_user                     TINYINT(1)      NOT NULL DEFAULT 0 COMMENT '管理员手动标记的盈利用户',
    updated_by                             VARCHAR(64)     NULL        COMMENT '最近修改的 admin user id',
    updated_reason                         VARCHAR(200)    NULL        COMMENT '修改原因（审计）',
    created_at                             DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at                             DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户级 BBook 风控阈值表';

-- 3. 平台总净敞口：symbol IS NULL 行
-- 放宽列约束（symbol UNIQUE 改为可以 NULL；其他业务列在全局行允许 NULL）
ALTER TABLE t_risk_config DROP INDEX symbol;
ALTER TABLE t_risk_config MODIFY COLUMN symbol VARCHAR(32) NULL COMMENT '品种；NULL=平台全局行';
ALTER TABLE t_risk_config MODIFY COLUMN max_position_per_user DECIMAL(24,8) NULL;
ALTER TABLE t_risk_config MODIFY COLUMN max_position_total DECIMAL(24,8) NULL;
ALTER TABLE t_risk_config MODIFY COLUMN maintenance_margin_rate DECIMAL(10,6) NULL;
ALTER TABLE t_risk_config MODIFY COLUMN max_leverage INT NULL;

-- 唯一索引：symbol 仍唯一（NULL 不参与唯一约束在 MySQL InnoDB 行为下允许多个 NULL，但本表平台行只允许 1 个，靠应用层 + id=0 约定保障）
CREATE UNIQUE INDEX uk_risk_config_symbol ON t_risk_config (symbol);

-- 插入平台全局行（id=0 保留）；默认阈值 10M USD
INSERT IGNORE INTO t_risk_config (id, symbol, hedge_threshold_usd, created_at, updated_at)
    VALUES (0, NULL, 10000000.00000000, NOW(3), NOW(3));
