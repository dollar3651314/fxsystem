-- STAGE-1B-USER-PROFILE：用户基础资料表（PII，与 t_user 1:1）
--
-- 设计决策（2026-05-09 用户拍板）：
--   1. 1:1 表结构（t_user 是认证表；t_user_profile 是基础资料表，PII 单独表方便审计/加密迁移）
--   2. 5 强制字段：first_name / last_name / birth_date / nationality（middle_name 可空）
--      注册时强填；其余字段在「个人资料」页主动补
--   3. nationality / residence_country 用 ISO 3166-1 alpha-3（CHN/USA/JPN ...）
--   4. profile_verified=0 时用户可改 5 强制字段；=1（KYC 通过）后只能重新 KYC（阶段 6）
--   5. 不存 age 字段（DOB 派生）；18 岁限制在注册路径校验，不在 schema 层

CREATE TABLE IF NOT EXISTS t_user_profile (
    user_id                BIGINT       PRIMARY KEY  COMMENT '关联 t_user.id (1:1)',

    -- 5 强制字段（注册时必填）
    first_name             VARCHAR(64)  NOT NULL                  COMMENT '名 (Given name)',
    middle_name            VARCHAR(64)  NULL                      COMMENT '中间名（可空）',
    last_name              VARCHAR(64)  NOT NULL                  COMMENT '姓 (Family name)',
    birth_date             DATE         NOT NULL                  COMMENT '出生日期；年龄从此派生不存',
    nationality            CHAR(3)      NOT NULL                  COMMENT 'ISO 3166-1 alpha-3，如 CHN/USA',

    -- 个人资料页主动补填
    gender                 TINYINT      NULL                      COMMENT '1=MALE, 2=FEMALE, 9=OTHER_OR_UNDISCLOSED',
    residence_country      CHAR(3)      NULL                      COMMENT '居住国 ISO 3166-1 alpha-3',
    residence_state        VARCHAR(64)  NULL                      COMMENT '省/州/自治区',
    residence_city         VARCHAR(64)  NULL                      COMMENT '城市',
    residence_address      VARCHAR(255) NULL                      COMMENT '详细街道地址',
    residence_postal_code  VARCHAR(32)  NULL                      COMMENT '邮编',
    phone_country_code     VARCHAR(8)   NULL                      COMMENT 'E.164 国家码（如 86 / 1 / 81，不带 +）',
    phone_number           VARCHAR(32)  NULL                      COMMENT '手机号（不含国家码）',

    -- 用户体验偏好
    language_preference    VARCHAR(16)  NOT NULL DEFAULT 'zh-CN'  COMMENT 'BCP 47 标签，UI/通知本地化',
    timezone               VARCHAR(64)  NOT NULL DEFAULT 'Asia/Shanghai' COMMENT 'IANA 时区',

    -- KYC 衔接占位（阶段 6 启用）
    profile_verified       TINYINT      NOT NULL DEFAULT 0        COMMENT '0=自填; 1=KYC通过后锁定 5 强制字段',

    created_at             DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at             DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),

    INDEX idx_nationality (nationality),
    INDEX idx_residence_country (residence_country),
    INDEX idx_profile_verified (profile_verified)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户基础资料 (PII)，与 t_user 1:1，FX-PROFILE 阶段 1B';
