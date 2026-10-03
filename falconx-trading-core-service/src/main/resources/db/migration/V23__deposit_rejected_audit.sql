-- STAGE-2-DEPOSIT 2026-05-19：入金拒收审计
--
-- 背景：trading-core 的 deposit consumer 在 token 不在白名单时直接 mark inbox
-- 跳过入账（只 WARN log），admin 没有可视化留痕，ops 排查只能 grep 日志。
--
-- 本次新增：
--   1. status=3 (REJECTED) 状态码；映射在 TradingMybatisSupport
--   2. rejection_reason VARCHAR(64) NULL：拒收原因（如 token_not_whitelisted）
--   3. rejected_at DATETIME(3) NULL：拒收时间
--   4. account_id 改为可空：REJECTED 行不归属任何账户
--
-- REJECTED 行只是审计留痕，不入账、不发 outbox、不影响余额。

ALTER TABLE t_deposit
    MODIFY COLUMN account_id BIGINT NULL COMMENT '入账账户 ID（REJECTED 时为 NULL）',
    ADD COLUMN rejection_reason VARCHAR(64) NULL COMMENT '拒收原因，仅 REJECTED 行使用' AFTER reversed_at,
    ADD COLUMN rejected_at DATETIME(3) NULL COMMENT '拒收时间，仅 REJECTED 行使用' AFTER rejection_reason;
