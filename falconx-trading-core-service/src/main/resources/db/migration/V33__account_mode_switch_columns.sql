-- STAGE-14D1 Task 1：t_account 加 margin mode 切换时间列（mode_changed_at / mode_cooling_until）。
--
-- 注意：Flyway migration 不得写 `USE <schema>;`，schema 由连接绑定决定（与 V1-V32 一致）。
--
-- 范围：用户级 margin mode 切换闸门 + 冷静期落库支撑。
--   - margin_mode 列 V11 已存在（TINYINT NOT NULL DEFAULT 2，1=cross/2=isolated），本次不重复添加。
--   - mode_changed_at：上次 margin mode 切换时间（审计 + 冷静期起算）。
--   - mode_cooling_until：margin mode 冷静期截止时间，NULL 表示当前不在冷静期。
--   - 历史账户行两列默认 NULL（未发生过切换），无需 backfill。

ALTER TABLE t_account
    ADD COLUMN mode_changed_at    DATETIME(3) NULL COMMENT 'margin mode 上次切换时间' AFTER margin_mode,
    ADD COLUMN mode_cooling_until DATETIME(3) NULL COMMENT 'margin mode 冷静期截止' AFTER mode_changed_at;
