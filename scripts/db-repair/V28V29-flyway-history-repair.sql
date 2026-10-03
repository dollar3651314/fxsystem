-- =============================================================================
-- FalconX trading-core V28/V29 Flyway 历史漂移 repair 脚本
--
-- 适用范围：仅用于被 STAGE-14B root bug 期间 `USE falconx_trading;` 污染过的
--           falconx_trading 库（含远程演示库）。
--           判定标志：t_ledger 三列（original_amount/original_currency/
--           fx_rate_at_settlement）+ t_position.entry_fx_rate 列已物理存在且已回填，
--           但 flyway_schema_history 没有 V28/V29 行。
--
-- 背景：root bug 期间 V28/V29 误写 USE 语句把 DDL 打到了 falconx_trading 库，
--       列被物理创建但迁移记录落到了当时连接的库（IT 库），导致本库出现
--       「列在、history 无 V28/V29 行」的漂移。删除 USE（commit 1031a9ce）后，
--       本库下次 flyway migrate 会因列已存在撞 Duplicate column，trading-core 无法启动。
--       本脚本向 flyway_schema_history 补写 V28/V29 的 success=1 行（不重跑 DDL），
--       使 flyway 视 V28/V29 为已应用，从 V30 继续顺序 migrate。
--
-- 执行方式：连接到目标库后执行（schema 由连接决定，本脚本不写 USE）：
--     mysql -h<host> -u<user> -p <目标库名> < V28V29-flyway-history-repair.sql
--
-- 安全性：守卫式 + 幂等 —— 仅当「列物理存在 且 history 无对应行」时才插入；
--         干净新库 / 已修复库上运行为安全 no-op（插入 0 行）。
--
-- checksum 说明：下方 checksum 为当前 flyway-core（Spring Boot 4 BOM）对 V28/V29
--               迁移文件计算的 CRC32 值，已由 TradingFlywayV28V29RepairRehearsalIntegrationTests
--               用相同 flyway 版本复演校验。若日后 flyway 大版本升级，执行前须以
--               flyway validate / 该 IT 复核 checksum 是否仍一致。
--
-- 🔴 禁止在未跑 preflight 核对前对生产/演示库直接 flyway migrate。
-- 详见 docs/operations/Flyway-V28V29-schema修复-runbook.md。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- A 段 · preflight 核对（只读，输出供 DBA 人工确认目标库是否处于待修复漂移态）
-- -----------------------------------------------------------------------------

-- A1 当前 flyway 基线（期望待修复库：max_rank=25 / max_version=27）
SELECT 'A1_flyway_baseline' AS check_point,
       COALESCE(MAX(installed_rank), 0) AS max_installed_rank,
       MAX(CAST(version AS UNSIGNED))   AS max_version
FROM flyway_schema_history;

-- A2 是否已存在 V28/V29 行（期望待修复库：0 行）
SELECT 'A2_existing_v28_v29_rows' AS check_point,
       installed_rank, version, checksum, success
FROM flyway_schema_history
WHERE version IN ('28', '29')
ORDER BY installed_rank;

-- A3 V28/V29 列是否物理存在（期望待修复库：ledger_cols=3 且 position_col=1）
SELECT 'A3_physical_columns' AS check_point,
       (SELECT COUNT(*) FROM information_schema.columns
          WHERE table_schema = DATABASE() AND table_name = 't_ledger'
            AND column_name IN ('original_amount', 'original_currency', 'fx_rate_at_settlement')) AS ledger_cols,
       (SELECT COUNT(*) FROM information_schema.columns
          WHERE table_schema = DATABASE() AND table_name = 't_position'
            AND column_name = 'entry_fx_rate') AS position_col;

-- A4 回填 NULL 残留（期望：均为 0；若非 0 说明列存在但回填不完整，须先人工核对再修复）
SELECT 'A4_backfill_null_residue' AS check_point,
       (SELECT COUNT(*) FROM t_ledger
          WHERE original_amount IS NULL OR original_currency IS NULL OR fx_rate_at_settlement IS NULL) AS ledger_null_residue,
       (SELECT COUNT(*) FROM t_position WHERE entry_fx_rate IS NULL) AS position_null_residue;

-- -----------------------------------------------------------------------------
-- B 段 · 守卫式补行（仅当列物理存在 且 history 无对应行时插入）
-- -----------------------------------------------------------------------------

SET @base_rank   := (SELECT COALESCE(MAX(installed_rank), 0) FROM flyway_schema_history);
SET @v28_exists  := (SELECT COUNT(*) FROM flyway_schema_history WHERE version = '28');
SET @v29_exists  := (SELECT COUNT(*) FROM flyway_schema_history WHERE version = '29');
SET @ledger_cols := (SELECT COUNT(*) FROM information_schema.columns
                       WHERE table_schema = DATABASE() AND table_name = 't_ledger'
                         AND column_name IN ('original_amount', 'original_currency', 'fx_rate_at_settlement'));
SET @position_col := (SELECT COUNT(*) FROM information_schema.columns
                        WHERE table_schema = DATABASE() AND table_name = 't_position'
                          AND column_name = 'entry_fx_rate');

-- 补 V28 行（仅当 t_ledger 三列齐备 且 尚无 V28 行）
INSERT INTO flyway_schema_history
    (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success)
SELECT @base_rank + 1, '28', 'ledger currency columns', 'SQL',
       'V28__ledger_currency_columns.sql', -448423770, '14b-repair', NOW(), 0, 1
FROM dual
WHERE @ledger_cols = 3 AND @v28_exists = 0;

-- 补 V29 行（仅当 t_position.entry_fx_rate 存在 且 尚无 V29 行）
INSERT INTO flyway_schema_history
    (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success)
SELECT @base_rank + 2, '29', 'position open fx snapshot', 'SQL',
       'V29__position_open_fx_snapshot.sql', -809397028, '14b-repair', NOW(), 0, 1
FROM dual
WHERE @position_col = 1 AND @v29_exists = 0;

-- -----------------------------------------------------------------------------
-- C 段 · post-check（核对 V28/V29 行已就位且 checksum 正确）
-- -----------------------------------------------------------------------------

-- 期望：V28 checksum=-448423770 / V29 checksum=-809397028 / success=1
SELECT 'C_post_check' AS check_point,
       installed_rank, version, description, script, checksum, success
FROM flyway_schema_history
WHERE version IN ('28', '29')
ORDER BY installed_rank;
