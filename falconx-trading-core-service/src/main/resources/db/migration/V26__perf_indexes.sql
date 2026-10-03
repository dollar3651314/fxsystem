-- 2026-05-20 性能优化：补缺失的复合索引
--
-- 背景：
--  · 资金流水筛选端点（ledger filter）按 (user_id, biz_type, created_at) 多列过滤，
--    现有索引仅 (user_id, created_at) + (biz_type, created_at)，组合命中退化为 INDEX SKIP SCAN
--  · Swap 持仓维度聚合按 reference_no LIKE 'swap:{positionId}:%' 过滤，reference_no 当前无索引
--  · platform metrics 端点 biggest winner/loser 走 t_position WHERE status IN (2,3) ORDER BY realized_pnl LIMIT 1，
--    现有 idx_user_status / idx_user_symbol_status / idx_symbol_status_liq 都不能消除 filesort
--  · platform metrics 今日已成交订单按 status=2 AND created_at >= CURRENT_DATE() 平台聚合（无 user_id），
--    现有 idx_user_status_created 含 user_id 前缀无法直接用
--
-- 全部 idx_perf_* 前缀方便后续清理；无业务字段变更、零数据迁移。

-- ────────────────────────────── t_ledger ──────────────────────────────
-- (user_id, biz_type, created_at) 覆盖：单用户按类型 + 时间窗筛选（ledger filter 主路径）
CREATE INDEX idx_perf_ledger_user_biz_time
    ON t_ledger (user_id, biz_type, created_at);

-- reference_no 前缀索引：Swap 持仓维度聚合 reference_no LIKE 'swap:%' 命中
-- 注：MySQL InnoDB 不支持函数 / 表达式索引，用普通 BTREE 前缀；只要 LIKE 是左前缀仍有效
CREATE INDEX idx_perf_ledger_reference_no
    ON t_ledger (reference_no(64));

-- ────────────────────────────── t_position ──────────────────────────────
-- (status, realized_pnl) 覆盖：platform metrics biggest winner/loser 端点
-- 按 WHERE status IN (2,3) ORDER BY realized_pnl DESC/ASC LIMIT 1 直接用索引
CREATE INDEX idx_perf_position_status_pnl
    ON t_position (status, realized_pnl);

-- ────────────────────────────── t_order ──────────────────────────────
-- (status, created_at) 覆盖：platform metrics 平台聚合（不带 user_id）今日成交统计
-- 与现有 idx_user_status_created 互补（后者带 user_id 前缀，单用户路径仍用现有索引）
CREATE INDEX idx_perf_order_status_created
    ON t_order (status, created_at);
