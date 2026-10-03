CREATE TABLE IF NOT EXISTS t_user (
    id              BIGINT          PRIMARY KEY COMMENT '主键 ID（雪花 ID）',
    uid             VARCHAR(16)     NOT NULL UNIQUE COMMENT '对外展示 UID',
    email           VARCHAR(128)    NOT NULL UNIQUE COMMENT '登录邮箱',
    password_hash   VARCHAR(256)    NOT NULL COMMENT '密码哈希',
    nickname        VARCHAR(64)     NULL COMMENT '用户昵称，当前阶段留空',
    status          TINYINT         NOT NULL DEFAULT 0 COMMENT '0=PENDING_DEPOSIT,1=ACTIVE,2=FROZEN,3=BANNED',
    register_ip     VARCHAR(64)     NULL COMMENT '注册 IP，当前阶段未采集',
    last_login_at   DATETIME(3)     NULL COMMENT '最后登录时间',
    activated_at    DATETIME(3)     NULL COMMENT '激活时间',
    created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    INDEX idx_status_created (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='identity 用户主表';

CREATE TABLE IF NOT EXISTS t_refresh_token_session (
    jti             VARCHAR(64)     PRIMARY KEY COMMENT 'Refresh Token 唯一 ID',
    user_id         BIGINT          NOT NULL COMMENT '用户主键 ID',
    expires_at      DATETIME(3)     NOT NULL COMMENT '过期时间',
    used            TINYINT         NOT NULL DEFAULT 0 COMMENT '0=未使用,1=已使用',
    issued_at       DATETIME(3)     NOT NULL COMMENT '签发时间',
    used_at         DATETIME(3)     NULL COMMENT '标记已使用时间',
    created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    INDEX idx_user_created (user_id, created_at),
    INDEX idx_expires_used (expires_at, used)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='identity Refresh Token 一次性会话表';

CREATE TABLE IF NOT EXISTS t_inbox (
    id              BIGINT          PRIMARY KEY COMMENT '主键 ID（雪花 ID）',
    event_id        VARCHAR(64)     NOT NULL UNIQUE COMMENT '事件唯一 ID',
    event_type      VARCHAR(128)    NOT NULL COMMENT '事件类型',
    source          VARCHAR(128)    NOT NULL COMMENT '事件来源服务',
    payload         JSON            NOT NULL COMMENT '事件 payload',
    status          TINYINT         NOT NULL DEFAULT 0 COMMENT '0=processing,1=done,2=failed',
    last_error      VARCHAR(512)    NULL COMMENT '最近一次失败原因',
    consumed_at     DATETIME(3)     NULL COMMENT '消费完成时间',
    created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    INDEX idx_status_created (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='identity 关键事件收件箱表';
