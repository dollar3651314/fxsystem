-- STAGE-14C tier 模板 T5-T10 维持保证金率修正（2026-06-03）
--
-- 问题：master §5.1 原稿 T5-T10（FX/贵金属/股指能源）每档 mm_rate = 1/max_leverage
--   （lev×mm = 0.99~1.0），满杠杆开仓时 MM 占满 IM、强平缓冲≈0：按对手价成交后
--   标记价（点差）立即低于强平价 → 瞬时强平。demo 实证：AUDCAD 300x（tier1
--   mm=0.33%）开仓 199ms 被强平，强平价距离 0.33 pip < 0.5 pip 点差。
--
-- 修正：对齐 T1-T4 既有设计准则 max_leverage × mm_rate ≤ 0.5（满杠杆时 MM = IM
--   的 50%，保留 ≥50% IM 强平缓冲）：lev×mm > 0.5 的行 mm_rate 减半（原值均为
--   1/lev 或 0.99/lev，减半后 0.495~0.5，DECIMAL(8,6) 精确无舍入）。
--   §4.2 CHECK（≤1.0）保持为 DB 硬下限不变。已开仓持仓的 mm_rate_at_open 为
--   开仓冻结值，不回溯。
UPDATE t_symbol_leverage_tier
   SET mm_rate = mm_rate / 2
 WHERE max_leverage * mm_rate > 0.5;
