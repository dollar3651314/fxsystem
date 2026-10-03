-- STAGE-14C1 Task 2：t_risk_config 加 StopOut/MarginCall 阈值列 + t_fx_pause_behavior 表 + 通知模板 seed。
--
-- 注意：Flyway migration 不得写 `USE <schema>;`，schema 由连接绑定决定（与 V1-V30 一致）。
--
-- 范围：
--   1) t_risk_config 加 stop_out_level / margin_call_level（账户级 MarginLevel 阈值，trading-core DB 读，config SDK 接入延后）
--   2) 新建 t_fx_pause_behavior（FX_PAUSED 各品种类目允许的操作开关，category 1-8）
--   3) seed 2 个通知模板（MARGIN_CALL_TRIGGERED / STOP_OUT_TRIGGERED），列结构对齐 V22 t_notification_template
--      （实际列：code/title_template/body_template/level/channels/description/enabled，无 type/title/body 列）

ALTER TABLE t_risk_config
    ADD COLUMN stop_out_level    DECIMAL(8,6) NOT NULL DEFAULT 0.30 COMMENT 'StopOut 强平阈值（MarginLevel）',
    ADD COLUMN margin_call_level DECIMAL(8,6) NOT NULL DEFAULT 1.00 COMMENT 'MarginCall 告警阈值';

CREATE TABLE t_fx_pause_behavior (
    category            TINYINT      PRIMARY KEY,
    category_name       VARCHAR(32)  NOT NULL,
    allow_open          TINYINT      NOT NULL DEFAULT 1,
    allow_close         TINYINT      NOT NULL DEFAULT 1,
    allow_liquidation   TINYINT      NOT NULL DEFAULT 1,
    updated_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by_admin_id BIGINT       NULL
) ENGINE=InnoDB COMMENT='FX_PAUSED 各品种类目允许操作开关';

-- master §3.4.2 seed 为 1-5；实际数据含 stock=6/etf=7（Task 1 已确认 6/7 可交易），other=8 兜底。
-- forex/metal 在 FX_PAUSED 期间默认禁止开仓与被动强平（仅允许平仓），其余类目默认全允许。
INSERT INTO t_fx_pause_behavior (category, category_name, allow_open, allow_close, allow_liquidation) VALUES
  (1, 'crypto', 1, 1, 1),
  (2, 'forex',  0, 1, 0),
  (3, 'metal',  0, 1, 0),
  (4, 'index',  1, 1, 1),
  (5, 'energy', 1, 1, 1),
  (6, 'stock',  1, 1, 1),
  (7, 'etf',    1, 1, 1),
  (8, 'other',  1, 1, 1);

-- 通知模板 seed：列结构对齐 V22 t_notification_template（code PK / title_template / body_template / level / channels / description / enabled）。
-- 占位符 ${var} + Map<String,String> params + String.replace（与 V22 插值规约一致）。
INSERT INTO t_notification_template (code, title_template, body_template, level, channels, description, enabled) VALUES
  ('MARGIN_CALL_TRIGGERED', '保证金预警',
                            '您的账户保证金率 ${marginLevel}% 已低于预警线，请及时补充保证金或减仓',
                            2, 'IN_APP', 'MarginLevel 跌破 MarginCall 告警线（不强平）', 1),
  ('STOP_OUT_TRIGGERED',    '强制平仓',
                            '您的账户保证金率 ${marginLevel}% 触发强平线，已强平仓位 ${symbol}',
                            3, 'IN_APP', 'MarginLevel 触发 StopOut 强平线', 1);
