-- 2026-05-21: 缩短 ClickHouse 分析表 TTL。
--
-- 背景：
--   · quote_tick 原 TTL=30 天；实际只用于 MarketQuoteHistoryRepository.findRecentBySymbol()，
--     市场服务冷启动时回填参考价缓存。完全不需要 30 天历史。
--   · （历史）本文件原还把 kline TTL 365d→90d，后被 CH_V3 整体回滚（kline 不应清理）。
--
-- 2026-06-03 修正（demo P0：market 启动僵尸根因）：
--   · 本机制每次 boot 重跑全部 CH_V*.sql。ALTER ... MODIFY TTL 默认
--     materialize_ttl_after_modify=1，并非 metadata-only——会对全表发起 TTL 物化 mutation，
--     kline 表长大后 mutation 撞 ClickHouse 内存上限（1.8G）→ ScriptStatementFailed →
--     Application run failed → Spring context 关闭但 JVM 因非守护线程存活 → 容器假活
--     （running/RestartCount=0 但不监听端口），gateway 熔断 504。
--   · 修正：① 删除 kline 的 MODIFY TTL（反正被 CH_V3 回滚，此前每次 boot 白白
--     add+materialize+remove 一轮）；② quote_tick 的 MODIFY TTL 显式
--     materialize_ttl_after_modify=0（真 metadata-only，过期数据由后台 merge 渐进清理）。

ALTER TABLE falconx_market_analytics.quote_tick
    MODIFY TTL event_time + INTERVAL 7 DAY
    SETTINGS materialize_ttl_after_modify = 0;
