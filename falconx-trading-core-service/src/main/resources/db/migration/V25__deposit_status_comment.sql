-- STAGE-2-DEPOSIT 2026-05-19：V23/V24 漏了的 status 注释更新
--
-- V23 在 status 枚举里加了 REJECTED=3，但漏改 status 字段的 COMMENT
-- （仍是历史的 '1=credited,2=reversed'），IDE DDL 视图看起来缺少新状态码。
-- 单纯改 COMMENT，不动数据 / 不动结构。

ALTER TABLE t_deposit
    MODIFY COLUMN status TINYINT NOT NULL DEFAULT 1 COMMENT '1=CREDITED, 2=REVERSED, 3=REJECTED';
