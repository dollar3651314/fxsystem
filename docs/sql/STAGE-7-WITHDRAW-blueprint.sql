-- STAGE-7-WITHDRAW Phase 0 数据库蓝图
--
-- 本文件不是生产 Flyway 文件，仅作为 R2 契约冻结的 schema 蓝图。
-- Phase 1+ 实施时由 R4 拷贝并最终化为：
--   trading-core: falconx-trading-core-service/src/main/resources/db/migration/V21__withdraw_schema.sql
--   wallet-service: falconx-wallet-service/src/main/resources/db/migration/V5__withdraw_tx_schema.sql
--   wallet-service: falconx-wallet-service/src/main/resources/db/migration/V6__withdraw_whitelist.sql

-- ============================================================
-- trading-core: t_withdraw_order  (V21)
-- ============================================================
-- 用户出金主表；trading-core owner，状态机驱动方。

CREATE TABLE t_withdraw_order (
    id                          BIGINT          NOT NULL PRIMARY KEY,
    user_id                     BIGINT          NOT NULL,
    amount                      DECIMAL(28, 8)  NOT NULL                COMMENT '出金金额，与 t_account.balance 精度一致',
    currency                    VARCHAR(16)     NOT NULL DEFAULT 'USDT' COMMENT '一期固定 USDT',
    network                     VARCHAR(16)     NOT NULL                COMMENT 'ERC20 / TRC20',
    target_address              VARCHAR(128)    NOT NULL                COMMENT '链上目标地址',
    whitelist_id                BIGINT          NOT NULL                COMMENT '关联的白名单记录 ID（确权 + 审计）',

    status                      TINYINT         NOT NULL DEFAULT 0      COMMENT '0=COOLING 1=PENDING 2=APPROVED 3=APPROVED_DELAYED 4=PROCESSING 5=COMPLETED 6=FAILED 7=CANCELED 8=REJECTED',
    cooling_until               DATETIME(3)     NOT NULL                COMMENT '冷静期到期时间（提交时间 + 2h）',
    delayed_until               DATETIME(3)                             COMMENT '大额延迟期到期时间（审核通过时间 + 6h），APPROVED_DELAYED 时填',
    processing_started_at       DATETIME(3)                             COMMENT '切到 PROCESSING 的时刻，用于超时调度',

    reviewer_id                 BIGINT                                  COMMENT 'admin 审核者 ID',
    review_at                   DATETIME(3)                             COMMENT '审核完成时间',
    review_note                 VARCHAR(512)                            COMMENT '审核备注（approve 时可为空）',
    reject_reason               VARCHAR(512)                            COMMENT 'admin 拒绝原因',

    tx_hash                     VARCHAR(128)                            COMMENT '链上 tx hash（broadcast 后填）',
    confirmations               INT             NOT NULL DEFAULT 0      COMMENT '当前确认数（PROCESSING 时由 wallet 推送）',
    failure_code                VARCHAR(16)                             COMMENT '失败时填，对应 wallet 2xxxx 错误码',
    failure_reason              VARCHAR(512)                            COMMENT 'FAILED 时人可读原因',

    idempotency_key             VARCHAR(64)                             COMMENT '客户端 X-Idempotency-Key',
    daily_amount_usd_snapshot   DECIMAL(28, 8)                          COMMENT '当日已累计美元（提交时快照，用于审计单日上限）',

    created_at                  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at                  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),

    UNIQUE KEY uk_user_idempotency (user_id, idempotency_key),
    KEY idx_user_created (user_id, created_at DESC),
    KEY idx_status_cooling (status, cooling_until),
    KEY idx_status_delayed (status, delayed_until),
    KEY idx_status_processing (status, processing_started_at),
    KEY idx_admin_pending (status, created_at DESC)              COMMENT '管理端 PENDING 列表查询'
) COMMENT='出金主表，trading-core owner，状态机驱动';

-- t_ledger biz_type 扩展（trading-core V21 同迁移补充）
-- ALTER TABLE t_ledger ... 已通过 V12 admin_balance_adjust_biz_type 扩展；本阶段新增的
-- 13-18 biz_type 由 Java 枚举层维护，不需要 schema 改：
--   13=withdraw_freeze, 14=withdraw_refund_cancel, 15=withdraw_refund_reject,
--   16=withdraw_refund_emergency, 17=withdraw_settle, 18=withdraw_refund_chain_failed
-- ⚠️ Phase 1 修订（2026-05-14）：原蓝图写到 12-17，但 12 已被 PENDING_ORDER_RELEASED
--    占用，R4 实施时整体下移到 13-18。docs/domain/状态机规范.md §7A 与
--    docs/api/REST接口规范.md §9.2 已同步。

-- ============================================================
-- wallet-service: t_withdraw_tx  (V5)
-- ============================================================
-- 链上交易记录；wallet-service owner，跟踪签名 / 广播 / 确认。

CREATE TABLE t_withdraw_tx (
    id                  BIGINT          NOT NULL PRIMARY KEY,
    withdraw_order_id   BIGINT          NOT NULL                COMMENT '关联 trading-core t_withdraw_order.id',
    user_id             BIGINT          NOT NULL                COMMENT '审计用，冗余 user_id',
    network             VARCHAR(16)     NOT NULL                COMMENT 'ERC20 / TRC20',
    from_address        VARCHAR(128)    NOT NULL                COMMENT '平台热钱包出金地址',
    target_address      VARCHAR(128)    NOT NULL                COMMENT '链上目标地址（同 t_withdraw_order）',
    amount              DECIMAL(28, 8)  NOT NULL                COMMENT '链上转账金额',

    nonce               BIGINT          NOT NULL                COMMENT 'ETH/TRX nonce',
    gas_price           DECIMAL(28, 8)                          COMMENT '广播时的 gas price / fee_limit',
    gas_used            DECIMAL(28, 8)                          COMMENT '确认后回填实际 gas used',
    gas_fee_usd         DECIMAL(28, 8)                          COMMENT '美元等值 gas 费',

    tx_hash             VARCHAR(128)                            COMMENT '广播后填',
    block_number        BIGINT                                  COMMENT '确认时填',
    confirmations       INT             NOT NULL DEFAULT 0,

    status              TINYINT         NOT NULL DEFAULT 0      COMMENT '0=SIGNING 1=BROADCAST 2=CONFIRMED 3=FAILED',
    failure_code        VARCHAR(16)                             COMMENT '失败时填',
    failure_reason      VARCHAR(512),

    broadcast_at        DATETIME(3),
    confirmed_at        DATETIME(3),
    failed_at           DATETIME(3),
    created_at          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),

    UNIQUE KEY uk_withdraw_order (withdraw_order_id),
    UNIQUE KEY uk_network_nonce (network, from_address, nonce)  COMMENT '同链同地址 nonce 唯一',
    UNIQUE KEY uk_tx_hash (tx_hash)                              COMMENT 'tx_hash 全局唯一（可空多行允许）',
    KEY idx_status_broadcast (status, broadcast_at)
) COMMENT='出金链上交易记录，wallet-service owner';

-- ============================================================
-- wallet-service: t_withdraw_whitelist  (V6)
-- ============================================================
-- 出金地址白名单；wallet-service owner。

CREATE TABLE t_withdraw_whitelist (
    id              BIGINT          NOT NULL PRIMARY KEY,
    user_id         BIGINT          NOT NULL,
    network         VARCHAR(16)     NOT NULL                COMMENT 'ERC20 / TRC20',
    address         VARCHAR(128)    NOT NULL                COMMENT '链上地址（已校验格式）',
    label           VARCHAR(64)                             COMMENT '用户自定义备注',
    status          TINYINT         NOT NULL DEFAULT 0      COMMENT '0=PENDING 24h 冷静期未过 / 1=ACTIVE / 2=REMOVED',
    activated_at    DATETIME(3)                             COMMENT '冷静期通过时间（status=1 后填）',
    removed_at      DATETIME(3),
    created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),

    UNIQUE KEY uk_user_network_address (user_id, network, address, status)  COMMENT '同用户同链同地址唯一活跃；REMOVED 后允许重新添加',
    KEY idx_user_status (user_id, status, created_at DESC)
) COMMENT='出金地址白名单，wallet-service owner';
