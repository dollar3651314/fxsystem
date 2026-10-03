ALTER TABLE t_user
    ADD COLUMN group_code VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '用户所属产品组代码，用于下游控制可见symbol'
        AFTER status,
    ADD INDEX idx_group_status (group_code, status, created_at);
