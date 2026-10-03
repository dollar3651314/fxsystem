-- STAGE-14D3a: t_risk_config 平台行承载 margin mode 冷静期（admin 运行时可配 60s-7d）
-- 沿用 C1 V31 既有平台行（symbol IS NULL）承载 stop_out_level / margin_call_level，本列同源。
-- 严禁 USE <schema>（14B root bug）；schema 由连接绑定。

ALTER TABLE t_risk_config
    ADD COLUMN cooling_period_seconds INT NOT NULL DEFAULT 300
    COMMENT 'margin mode 切换冷静期秒数（admin 可配 60-604800，默认 300=5min）' AFTER margin_call_level;
