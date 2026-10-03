UPDATE t_user
SET status = 1,
    activated_at = COALESCE(activated_at, created_at),
    updated_at = CURRENT_TIMESTAMP(3)
WHERE status = 0;

ALTER TABLE t_user
    MODIFY status TINYINT NOT NULL DEFAULT 1 COMMENT '0=LEGACY_PENDING_DEPOSIT,1=ACTIVE,2=FROZEN,3=BANNED';
