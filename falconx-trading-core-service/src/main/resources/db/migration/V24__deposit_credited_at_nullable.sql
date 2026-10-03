-- STAGE-2-DEPOSIT 2026-05-19：v23 后续修
--
-- V23 引入 REJECTED 状态，t_deposit.account_id 已改可空，但 credited_at 仍 NOT NULL
-- 导致 REJECTED 行 insert 失败：
--   Column 'credited_at' cannot be null
-- 用户转 ETH 测试触发：consumer 在 t_deposit insert 失败 → 异常向上抛 →
-- Kafka offset 不提交 → 同事件 500ms 一次反复重投，trading-core 无法消费。
--
-- REJECTED 行语义：未入账，credited_at 必然为 null。统一改成可空。

ALTER TABLE t_deposit
    MODIFY COLUMN credited_at DATETIME(3) NULL COMMENT '入账时间（REJECTED 时为 NULL）';
