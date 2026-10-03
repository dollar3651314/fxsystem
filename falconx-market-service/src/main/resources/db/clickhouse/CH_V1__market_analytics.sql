CREATE DATABASE IF NOT EXISTS falconx_market_analytics;

CREATE TABLE IF NOT EXISTS falconx_market_analytics.quote_tick
(
    symbol          LowCardinality(String) COMMENT '内部标准 symbol，如 BTCUSDT / EURUSD',
    source          LowCardinality(String) COMMENT '报价来源，例如 TM_QUOTE',
    bid_price       Decimal(24, 8) COMMENT '买一价',
    ask_price       Decimal(24, 8) COMMENT '卖一价',
    mid_price       Decimal(24, 8) COMMENT '中间价',
    mark_price      Decimal(24, 8) COMMENT '标记价',
    event_time      DateTime64(3, 'UTC') COMMENT '报价事件时间',
    ingest_time     DateTime64(3, 'UTC') DEFAULT now64(3) COMMENT '写入 ClickHouse 时间'
)
ENGINE = MergeTree
PARTITION BY toYYYYMM(event_time)
ORDER BY (symbol, event_time, source)
-- 2026-05-21: quote_tick 仅用于 market-service 重启时回填参考价缓存（见
-- MarketQuoteHistoryRepository javadoc），不做长期分析、不对用户展示。
-- 7 天足够覆盖单次部署窗口和事故复盘。比原 30 天减少 ~4× 存储 / 加快查询。
-- 长期分析需求请用 kline 表（按周期已聚合，体积小、长期保留）。
TTL event_time + INTERVAL 7 DAY
SETTINGS index_granularity = 8192;

CREATE TABLE IF NOT EXISTS falconx_market_analytics.kline
(
    symbol          LowCardinality(String) COMMENT '内部标准 symbol',
    interval_type   LowCardinality(String) COMMENT 'K 线周期，例如 1m / 5m / 1h / 1d',
    open_price      Decimal(24, 8) COMMENT '开盘价',
    high_price      Decimal(24, 8) COMMENT '最高价',
    low_price       Decimal(24, 8) COMMENT '最低价',
    close_price     Decimal(24, 8) COMMENT '收盘价',
    volume          Decimal(24, 8) DEFAULT 0 COMMENT '成交量或平台定义量',
    open_time       DateTime64(3, 'UTC') COMMENT '开盘时间',
    close_time      DateTime64(3, 'UTC') COMMENT '收盘时间',
    source          LowCardinality(String) DEFAULT 'market-service' COMMENT 'K 线生成来源',
    ingest_time     DateTime64(3, 'UTC') DEFAULT now64(3) COMMENT '写入 ClickHouse 时间'
)
ENGINE = ReplacingMergeTree(ingest_time)
PARTITION BY (interval_type, toYYYYMM(open_time))
ORDER BY (symbol, interval_type, open_time)
-- 2026-05-21: kline 是用户图表主数据源 + 回测 + 合规审计，不设 TTL（长期保留）。
-- 数据形态高度聚合：每个 (symbol, interval) 每个周期 1 行，1500 symbols × 3 intervals
-- 累积一年也只 ~250 MB。强行 TTL 弊大于利。
-- 如未来 1m 数据膨胀有压力，再针对 interval 分级（1m TTL 1 年，5m/15m+ 永久）。
SETTINGS index_granularity = 8192;
