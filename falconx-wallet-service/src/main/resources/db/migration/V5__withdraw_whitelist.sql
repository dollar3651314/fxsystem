-- STAGE-7-WITHDRAW Phase 1：wallet 出金地址白名单表。
-- 详见 docs/sql/STAGE-7-WITHDRAW-blueprint.sql 与 docs/api/REST接口规范.md §9.2.6。
--
-- 24h 冷静期：地址添加后 status=PENDING(0)；24h 后由调度器或惰性检查切到 ACTIVE(1)；
-- DELETE 操作把 status=ACTIVE/PENDING 改为 REMOVED(2)（保留历史；同地址可再次添加）。
--
-- 唯一约束：(user_id, network, address, status) 保证同用户同链同地址同 status 唯一；
-- REMOVED 后允许再次插入 PENDING/ACTIVE。

CREATE TABLE IF NOT EXISTS t_withdraw_whitelist (
    id              BIGINT          NOT NULL    COMMENT '主键（雪花 ID）',
    user_id         BIGINT          NOT NULL,
    network         VARCHAR(16)     NOT NULL    COMMENT 'ERC20 / TRC20',
    address         VARCHAR(128)    NOT NULL    COMMENT '链上地址（格式校验后入库）',
    label           VARCHAR(64)     NULL        COMMENT '用户自定义备注',
    status          TINYINT         NOT NULL DEFAULT 0  COMMENT '0=PENDING（24h 冷静期未过）1=ACTIVE 2=REMOVED',
    activated_at    DATETIME(3)     NULL        COMMENT '冷静期通过时间（status=1 后填）',
    removed_at      DATETIME(3)     NULL,
    created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    UNIQUE KEY uk_user_network_address_status (user_id, network, address, status),
    KEY idx_user_status (user_id, status, created_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='出金地址白名单，wallet-service owner';
