-- STAGE-6-KYC Phase 1：KYC 提交与文档存储。
--
-- Trigger 1：用户首次出金时 wallet/withdraw 服务调
-- GET /internal/v1/identity/users/{id}/kyc-status，发现 kyc_level=0
-- 强制要求先做 KYC。
-- Trigger 2：出金地址与历史入金地址不一致也走 KYC（阶段 7 出金链路时挂上）。
--
-- 当前 V2 一期不接 S3：证件图片 base64 落 t_kyc_document.data_base64
-- LONGTEXT 字段；表 schema 兼容后续切换为 s3_url（增加 NULL 列即可）。

ALTER TABLE t_user
    ADD COLUMN kyc_level TINYINT NOT NULL DEFAULT 0 COMMENT '0=未认证, 1=已通过简单 KYC' AFTER group_code;

CREATE TABLE t_kyc_submission (
    id              BIGINT       NOT NULL PRIMARY KEY,
    user_id         BIGINT       NOT NULL,
    level           TINYINT      NOT NULL DEFAULT 1     COMMENT '申请的 KYC 等级；一期仅 1',
    status          TINYINT      NOT NULL DEFAULT 0     COMMENT '0=PENDING, 1=APPROVED, 2=REJECTED',
    id_type         TINYINT      NOT NULL               COMMENT '1=身份证, 2=护照, 3=驾照',
    id_number       VARCHAR(64)  NOT NULL,
    submitted_at    DATETIME(3)  NOT NULL,
    reviewer_id     BIGINT,
    review_at       DATETIME(3),
    reject_reason   VARCHAR(512),
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    KEY idx_user_status (user_id, status, submitted_at DESC),
    KEY idx_status_submitted (status, submitted_at DESC)
);

CREATE TABLE t_kyc_document (
    id              BIGINT       NOT NULL PRIMARY KEY,
    submission_id   BIGINT       NOT NULL,
    doc_type        TINYINT      NOT NULL               COMMENT '1=证件正面, 2=证件反面, 3=手持证件自拍',
    data_base64     LONGTEXT     NOT NULL               COMMENT 'base64 图片内容；后续接 S3 时可改为 s3_url + 保留旧列做兼容',
    sha256          VARCHAR(64)  NOT NULL,
    mime_type       VARCHAR(32)  NOT NULL DEFAULT 'image/jpeg',
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_submission (submission_id, doc_type)
);
