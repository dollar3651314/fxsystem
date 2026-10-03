-- 2026-05-21: 回滚 CH_V2 中 kline 的 90 天 TTL 决策。
--
-- 背景：
--   CH_V2 曾把 kline TTL 365d→90d。user review 后指出 kline 不应清理 —— K 线是用户
--   图表主数据源 + 回测 + 合规审计，且数据高度聚合体积小（1 年 ~250 MB），
--   强行 TTL 牺牲功能换不到多少存储收益。
--
-- 2026-06-03 修正（配合 CH_V2 修正）：
--   · CH_V2 已不再给 kline 加 TTL，但存量环境 kline 可能处于「有 TTL」（老 V2 加过且
--     V3 未跑成）或「无 TTL」两种状态；ClickHouse REMOVE TTL 在无 TTL 时报错、又无
--     IF EXISTS 语法 → 先用 metadata-only 的 MODIFY TTL 把表置为确定「有 TTL」状态
--     （10 年远期值 + materialize_ttl_after_modify=0，零数据影响），再 REMOVE，
--     两步均幂等，保证每次 boot 重跑安全。

ALTER TABLE falconx_market_analytics.kline
    MODIFY TTL open_time + INTERVAL 3650 DAY
    SETTINGS materialize_ttl_after_modify = 0;

ALTER TABLE falconx_market_analytics.kline REMOVE TTL;
