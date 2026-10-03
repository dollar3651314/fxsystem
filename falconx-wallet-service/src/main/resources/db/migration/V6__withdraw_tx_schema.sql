-- STAGE-7-WITHDRAW Phase 3：wallet 出金链上交易记录。
-- 详见 docs/sql/STAGE-7-WITHDRAW-blueprint.sql 与 docs/domain/状态机规范.md §7A/B。
--
-- 链上状态：
--   0=SIGNING  尚未广播（KmsSigner 签名 + 未提交链上）
--   1=BROADCAST 已广播至 RPC，等待确认
--   2=CONFIRMED 链上确认达 min_confirmations
--   3=FAILED   广播失败 / 链上 revert / 超时 / nonce 冲突
--
-- 关键约束：
-- - uk_withdraw_order：一个出金单只能对应一条链上 tx；防止重复广播
-- - uk_network_nonce：同链同 fromAddress 同 nonce 唯一；防止 nonce 重用
-- - uk_tx_hash：tx_hash 全局唯一；可空（SIGNING 状态时未填）

CREATE TABLE IF NOT EXISTS t_withdraw_tx (
    id                  BIGINT          NOT NULL    COMMENT '主键（雪花 ID）',
    withdraw_order_id   BIGINT          NOT NULL    COMMENT '关联 trading-core t_withdraw_order.id',
    user_id             BIGINT          NOT NULL    COMMENT '审计用，冗余 user_id',
    network             VARCHAR(16)     NOT NULL    COMMENT 'ERC20 / TRC20',
    from_address        VARCHAR(128)    NOT NULL    COMMENT '平台热钱包出金地址',
    target_address      VARCHAR(128)    NOT NULL    COMMENT '链上目标地址（同 t_withdraw_order）',
    amount              DECIMAL(28, 8)  NOT NULL    COMMENT '链上转账金额',

    nonce               BIGINT          NOT NULL    COMMENT 'ETH/TRX nonce',
    gas_price           DECIMAL(28, 8)  NULL        COMMENT '广播时的 gas price / fee_limit',
    gas_used            DECIMAL(28, 8)  NULL        COMMENT '确认后回填实际 gas used',
    gas_fee_usd         DECIMAL(28, 8)  NULL        COMMENT '美元等值 gas 费',

    tx_hash             VARCHAR(128)    NULL        COMMENT '广播后填',
    block_number        BIGINT          NULL        COMMENT '确认时填',
    confirmations       INT             NOT NULL DEFAULT 0,

    status              TINYINT         NOT NULL DEFAULT 0      COMMENT '0=SIGNING 1=BROADCAST 2=CONFIRMED 3=FAILED',
    failure_code        VARCHAR(16)     NULL        COMMENT '失败时填，对应 wallet 2xxxx 错误码',
    failure_reason      VARCHAR(512)    NULL,

    broadcast_at        DATETIME(3)     NULL,
    confirmed_at        DATETIME(3)     NULL,
    failed_at           DATETIME(3)     NULL,
    created_at          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    UNIQUE KEY uk_withdraw_order (withdraw_order_id),
    UNIQUE KEY uk_network_nonce (network, from_address, nonce),
    UNIQUE KEY uk_tx_hash (tx_hash),
    KEY idx_status_broadcast (status, broadcast_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='出金链上交易记录，wallet-service owner';
