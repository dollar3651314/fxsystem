-- STAGE-5-WALLET-PROVISION Phase 2：地址预分配失败死信队列。
--
-- consumer 处理 falconx.identity.user.registered 时若因 xpub 缺失等配置错误
-- 导致 WALLET_ADDRESS_ALLOCATION_FAILED，会被吞掉（避免无限重试）并落本表，
-- 运营在 console 上看到列表，配齐 xpub 后点"重试"触发补分配。
--
-- 重复消费同 eventId 时按 event_id 去重，仅 attempt_count++ / last_error_*
-- 更新；resolved 后允许相同 eventId 再次进入新 PENDING 行（理论上不会重发）。

CREATE TABLE t_wallet_address_provision_dlq (
    id                  BIGINT       NOT NULL PRIMARY KEY,
    event_id            VARCHAR(64)  NOT NULL UNIQUE,
    user_id             BIGINT       NOT NULL,
    uid                 VARCHAR(32),
    email               VARCHAR(255),
    attempt_count       INT          NOT NULL DEFAULT 1,
    status              TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING,1=RESOLVED',
    last_error_code     VARCHAR(32),
    last_error_message  VARCHAR(512),
    last_attempt_at     DATETIME(3)  NOT NULL,
    resolved_at         DATETIME(3),
    created_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_status_created (status, created_at DESC),
    KEY idx_user (user_id)
);
