-- =============================================================
-- V11: Symbol 参数下沉到 mapping
-- 任务卡: STAGE-2-SYMBOL-PARAMS-DOWNSHIFT
--
-- 把 t_symbol 上的 6 个交易字段（max_leverage / taker_fee_rate /
-- spread / min_qty / max_qty / min_notional）下沉到 t_symbol_quote_mapping，
-- 使同一 LP 源可派生多套不同交易条件的 platform symbol。
--
-- t_symbol 降级为纯 LP 源元数据表；t_symbol_quote_mapping 成为
-- 系统唯一 Symbol 配置真源。
-- =============================================================

-- 第 1 步: t_symbol_quote_mapping 加 8 个字段（DEFAULT 0 让存量行通过 NOT NULL）
ALTER TABLE t_symbol_quote_mapping
  ADD COLUMN max_leverage    INT            NOT NULL DEFAULT 100 COMMENT '杠杆上限，CHECK 1-500',
  ADD COLUMN taker_fee_rate  DECIMAL(10,6)  NOT NULL DEFAULT 0   COMMENT 'Taker 费率，CHECK 0-0.05',
  ADD COLUMN spread          DECIMAL(24,8)  NOT NULL DEFAULT 0   COMMENT '点差，CHECK >= 0',
  ADD COLUMN min_qty         DECIMAL(24,8)  NOT NULL DEFAULT 0   COMMENT '最小下单量',
  ADD COLUMN max_qty         DECIMAL(24,8)  NOT NULL DEFAULT 0   COMMENT '最大下单量，CHECK > min_qty',
  ADD COLUMN min_notional    DECIMAL(24,8)  NOT NULL DEFAULT 0   COMMENT '最小名义价值，CHECK >= 0',
  ADD COLUMN price_precision INT            NULL                 COMMENT 'NULL 继承 source.price_precision',
  ADD COLUMN qty_precision   INT            NULL                 COMMENT 'NULL 继承 source.qty_precision';

-- 第 2 步: backfill 从 source 拷贝交易参数到 mapping（INNER JOIN source_symbol -> t_symbol.symbol）
UPDATE t_symbol_quote_mapping m
INNER JOIN t_symbol s ON s.symbol = m.source_symbol
SET m.max_leverage   = s.max_leverage,
    m.taker_fee_rate = s.taker_fee_rate,
    m.spread         = s.spread,
    m.min_qty        = s.min_qty,
    m.max_qty        = s.max_qty,
    m.min_notional   = s.min_notional;

-- 第 3 步: 升级 mapping 时间戳精度到 datetime(3)，与 t_symbol 对齐
ALTER TABLE t_symbol_quote_mapping
  MODIFY COLUMN created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  MODIFY COLUMN updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3);

-- 第 4 步: 加 CHECK 约束兜底（如有 backfill 后脏数据违反，整 migration ABORT）
ALTER TABLE t_symbol_quote_mapping
  ADD CONSTRAINT chk_mapping_leverage         CHECK (max_leverage BETWEEN 1 AND 500),
  ADD CONSTRAINT chk_mapping_fee              CHECK (taker_fee_rate BETWEEN 0 AND 0.05),
  ADD CONSTRAINT chk_mapping_qty              CHECK (min_qty >= 0 AND max_qty > min_qty),
  ADD CONSTRAINT chk_mapping_spread           CHECK (spread >= 0),
  ADD CONSTRAINT chk_mapping_notional         CHECK (min_notional >= 0),
  ADD CONSTRAINT chk_mapping_price_precision  CHECK (price_precision IS NULL OR (price_precision BETWEEN 0 AND 10)),
  ADD CONSTRAINT chk_mapping_qty_precision    CHECK (qty_precision IS NULL OR (qty_precision BETWEEN 0 AND 10));

-- 第 5 步: t_symbol 删 6 个交易字段（已下沉到 mapping，主表只保留 LP 源元数据）
ALTER TABLE t_symbol
  DROP COLUMN max_leverage,
  DROP COLUMN taker_fee_rate,
  DROP COLUMN spread,
  DROP COLUMN min_qty,
  DROP COLUMN max_qty,
  DROP COLUMN min_notional;
