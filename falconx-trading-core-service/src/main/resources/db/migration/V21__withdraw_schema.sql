-- STAGE-7-WITHDRAW Phase 1：trading-core 出金主表。
-- 详见 docs/sql/STAGE-7-WITHDRAW-blueprint.sql 与 docs/domain/状态机规范.md §7A。
--
-- 状态机：COOLING(0) → PENDING(1) → APPROVED(2)/APPROVED_DELAYED(3) → PROCESSING(4)
--                                                                        ↓
--                                  COMPLETED(5) / FAILED(6) / CANCELED(7) / REJECTED(8)
--
-- 余额时点：
--   提交（→COOLING）：t_account.frozen += amount；ledger biz_type=12 withdraw_freeze
--   用户取消（COOLING→CANCELED）：frozen -=；biz_type=13 withdraw_refund_cancel
--   admin 拒绝（PENDING→REJECTED）：frozen -=；biz_type=14 withdraw_refund_reject
--   admin 紧急取消（APPROVED_DELAYED→CANCELED）：frozen -=；biz_type=15 withdraw_refund_emergency
--   链上完成（PROCESSING→COMPLETED）：frozen -= + balance -=；biz_type=16 withdraw_settle
--   链上失败（PROCESSING/APPROVED→FAILED）：frozen -=；biz_type=17 withdraw_refund_chain_failed

CREATE TABLE IF NOT EXISTS t_withdraw_order (
    id                          BIGINT          NOT NULL    COMMENT '主键（雪花 ID）',
    user_id                     BIGINT          NOT NULL    COMMENT '用户 ID',
    amount                      DECIMAL(28, 8)  NOT NULL    COMMENT '出金金额，与 t_account.balance 精度一致',
    currency                    VARCHAR(16)     NOT NULL DEFAULT 'USDT' COMMENT '一期固定 USDT',
    network                     VARCHAR(16)     NOT NULL    COMMENT 'ERC20 / TRC20',
    target_address              VARCHAR(128)    NOT NULL    COMMENT '链上目标地址',
    whitelist_id                BIGINT          NOT NULL    COMMENT '关联 wallet t_withdraw_whitelist.id',

    status                      TINYINT         NOT NULL DEFAULT 0      COMMENT '0=COOLING 1=PENDING 2=APPROVED 3=APPROVED_DELAYED 4=PROCESSING 5=COMPLETED 6=FAILED 7=CANCELED 8=REJECTED',
    cooling_until               DATETIME(3)     NOT NULL    COMMENT '冷静期到期时间（提交时间 + 2h）',
    delayed_until               DATETIME(3)     NULL        COMMENT '大额延迟期到期时间（审核通过 + 6h），APPROVED_DELAYED 时填',
    processing_started_at       DATETIME(3)     NULL        COMMENT '切到 PROCESSING 的时刻，用于超时调度',

    reviewer_id                 BIGINT          NULL        COMMENT 'admin 审核者 ID',
    review_at                   DATETIME(3)     NULL        COMMENT '审核完成时间',
    review_note                 VARCHAR(512)    NULL        COMMENT '审核备注',
    reject_reason               VARCHAR(512)    NULL        COMMENT 'admin 拒绝原因',

    tx_hash                     VARCHAR(128)    NULL        COMMENT '链上 tx hash（broadcast 后填）',
    confirmations               INT             NOT NULL DEFAULT 0  COMMENT '当前确认数（PROCESSING 时由 wallet 推送）',
    failure_code                VARCHAR(16)     NULL        COMMENT '失败时填，对应 wallet 2xxxx 错误码',
    failure_reason              VARCHAR(512)    NULL        COMMENT 'FAILED 时人可读原因',

    idempotency_key             VARCHAR(64)     NOT NULL    COMMENT '客户端 X-Idempotency-Key（必传）',
    daily_amount_usd_snapshot   DECIMAL(28, 8)  NULL        COMMENT '当日已累计美元（提交时快照，用于审计单日上限）',

    created_at                  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at                  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    UNIQUE KEY uk_user_idempotency (user_id, idempotency_key),
    KEY idx_user_created (user_id, created_at DESC),
    KEY idx_status_cooling (status, cooling_until),
    KEY idx_status_delayed (status, delayed_until),
    KEY idx_status_processing (status, processing_started_at),
    KEY idx_admin_pending (status, created_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='出金主表，trading-core owner，状态机驱动';
