ALTER TABLE t_symbol_quote_mapping
    ADD COLUMN lp_subscribe_enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否订阅LP实时报价，1=订阅，0=不订阅'
        AFTER enabled,
    ADD INDEX idx_source_subscription_enabled (source_provider, source_symbol, enabled, lp_subscribe_enabled);
