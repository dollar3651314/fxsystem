-- IVES-XAU100 / IVESUSD 自定义衍生品全套配置（2026-06-03，运营数据，REPLACE 幂等可重放）
-- 前置：t_symbol_quote_mapping 两行已由管理端 createSource 创建
--   （IVES-XAU100 = GODSA XAUUSD ×100，category 3 METAL；IVESUSD = GODSA BTCUSD ×10，category 1 CRYPTO）。
-- 本文件补齐：t_symbol 目录 / IVESUSD 7×24 交易时段 / default 组 markup 零值 /
--   trading 杠杆档位（衍生品继承标的模板：XAU100→T9 同 XAUUSD、IVESUSD→T1 同 BTCUSD，V38 值）/
--   mapping.max_leverage 对齐 tier1 上限（B 切片不变量）。
USE falconx_market;

REPLACE INTO t_symbol (id, lp_code, symbol, category, market_code, base_currency, quote_currency, price_precision, qty_precision, status) VALUES
(60000001, 'GODSA', 'IVES-XAU100', 3, 'METAL',  'XAU',  'USD', 2, 2, 1),
(60000002, 'GODSA', 'IVESUSD',     1, 'CRYPTO', 'IVES', 'USD', 2, 2, 1);

-- IVESUSD 7×24 交易时段（CRYPTO 同 BTCUSD 模式）；IVES-XAU100 时段已由管理端配置（5 段）不动
REPLACE INTO t_trading_hours (id, symbol, day_of_week, session_no, open_time, close_time, timezone, enabled, effective_from, effective_to) VALUES
(60000101,'IVESUSD',1,1,'00:00:00','23:59:59','UTC',1,'2026-01-01',NULL),
(60000102,'IVESUSD',2,1,'00:00:00','23:59:59','UTC',1,'2026-01-01',NULL),
(60000103,'IVESUSD',3,1,'00:00:00','23:59:59','UTC',1,'2026-01-01',NULL),
(60000104,'IVESUSD',4,1,'00:00:00','23:59:59','UTC',1,'2026-01-01',NULL),
(60000105,'IVESUSD',5,1,'00:00:00','23:59:59','UTC',1,'2026-01-01',NULL),
(60000106,'IVESUSD',6,1,'00:00:00','23:59:59','UTC',1,'2026-01-01',NULL),
(60000107,'IVESUSD',7,1,'00:00:00','23:59:59','UTC',1,'2026-01-01',NULL);

-- default 组 markup 零值行（与 V14 seed 口径一致）
REPLACE INTO t_symbol_group_markup (group_code, platform_symbol, bid_extra, ask_extra, enabled) VALUES
('default','IVES-XAU100',0,0,1),
('default','IVESUSD',0,0,1);

-- mapping 杠杆对齐 tier1 上限（XAU100→200 / IVESUSD→125；原 500 超 tier1 会致 UI 可选但 30070）
UPDATE t_symbol_quote_mapping SET max_leverage=200 WHERE platform_symbol='IVES-XAU100' AND max_leverage>200;
UPDATE t_symbol_quote_mapping SET max_leverage=125 WHERE platform_symbol='IVESUSD' AND max_leverage>125;

USE falconx_trading;

-- 杠杆/MM 档位（V38 后模板值，lev×mm≤0.5）：IVES-XAU100→T9 贵金属；IVESUSD→T1 顶级 crypto
REPLACE INTO t_symbol_leverage_tier (id, symbol, group_code, tier_no, notional_lower, notional_upper, max_leverage, mm_rate) VALUES
(30100001,'IVES-XAU100','default',1,0,50000,200,0.002500),
(30100002,'IVES-XAU100','default',2,50000,200000,100,0.005000),
(30100003,'IVES-XAU100','default',3,200000,1000000,50,0.010000),
(30100004,'IVES-XAU100','default',4,1000000,5000000,20,0.025000),
(30100005,'IVES-XAU100','default',5,5000000,NULL,10,0.050000),
(30100011,'IVESUSD','default',1,0,50000,125,0.004000),
(30100012,'IVESUSD','default',2,50000,250000,100,0.005000),
(30100013,'IVESUSD','default',3,250000,1000000,50,0.010000),
(30100014,'IVESUSD','default',4,1000000,5000000,20,0.025000),
(30100015,'IVESUSD','default',5,5000000,20000000,10,0.050000),
(30100016,'IVESUSD','default',6,20000000,NULL,5,0.100000);

-- 附：首次尝试遗留的幽灵 symbol "IVES"（仅 mapping、无 t_symbol/tier，曾向客户端发行情）
-- 已禁用（可逆）；其 redis 残键（spec/schedule/last-valid-price/swap-rate）已清理。
USE falconx_market;
UPDATE t_symbol_quote_mapping SET enabled=0, lp_subscribe_enabled=0 WHERE platform_symbol='IVES';
