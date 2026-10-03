ALTER TABLE t_user
    ADD COLUMN email_verified TINYINT NOT NULL DEFAULT 0 COMMENT '0=not_verified,1=verified' AFTER status;
