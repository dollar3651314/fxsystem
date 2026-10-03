-- STAGE-2-TRADING-MONITOR R4：风控全局开关持久化表
-- 用法：管理员通过 console 切换 auto_liquidate.enabled 等开关
--      DB 持久化（重启可恢复） + Redis 缓存 falconx:trading:risk:<key> 供 worker 读取

CREATE TABLE IF NOT EXISTS t_trading_risk_switch (
    `switch_key`   varchar(64)   NOT NULL,
    `enabled`      tinyint(1)    NOT NULL,
    `reason`       varchar(256)  NULL,
    `updated_by`   varchar(64)   NULL,
    `created_at`   datetime(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at`   datetime(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`switch_key`),
    CONSTRAINT chk_risk_switch_enabled CHECK (`enabled` IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 种子默认值：auto_liquidate 默认启用
INSERT INTO t_trading_risk_switch (`switch_key`, `enabled`, `reason`, `updated_by`)
SELECT 'auto_liquidate.enabled', 1, 'V14 seed default', 'system'
WHERE NOT EXISTS (
    SELECT 1 FROM t_trading_risk_switch WHERE `switch_key` = 'auto_liquidate.enabled'
);
