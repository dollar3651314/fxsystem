-- FalconX v1 ClickHouse Market Analytics Blueprint
-- This file is a documentation-oriented SQL blueprint, not a runtime migration.
-- Owner service: falconx-market-service
-- Purpose:
-- 1. Persist high-frequency quote ticks outside transactional schemas
-- 2. Persist finalized K-line rows in the analytics engine
-- 3. Support historical quote and candlestick queries without loading trading service transactional tables

CREATE DATABASE IF NOT EXISTS falconx_market_analytics;

USE falconx_market_analytics;

CREATE TABLE IF NOT EXISTS quote_tick
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
TTL event_time + INTERVAL 30 DAY
SETTINGS index_granularity = 8192
COMMENT '高频报价历史表，每个 tick 一条记录';

CREATE TABLE IF NOT EXISTS kline
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
TTL open_time + INTERVAL 365 DAY
SETTINGS index_granularity = 8192
COMMENT '已收盘 K 线历史表，仅收盘时写入最终行';
