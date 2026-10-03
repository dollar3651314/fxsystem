-- STAGE-8-NOTIFICATION Phase 0：模板表 + t_notification 加 template_code 列。
--
-- 范围（用户决策 2026-05-15）：
--   - 反转 V19 sql "V2 一期不做模板插值" 决策，引入模板表统一管理通知文案
--   - 旧路径（producer 直接拼 title/body）保留 1 个版本兼容，新增 send(templateCode, params) API
--   - 邮件 / Telegram channel 接口预留（SPI interface），V2 一期不真实发送
--
-- 插值规约：`${variable}` 占位符 + Map<String,String> params + 简单 String.replace（无新依赖）
-- 模板编辑 owner：管理端 admin（通过 console 调 trading-core internal RPC）
-- 模板渲染 owner：trading-core（NotificationTemplateService 缓存 + 渲染）

CREATE TABLE t_notification_template (
    code            VARCHAR(64)  NOT NULL PRIMARY KEY,
    title_template  VARCHAR(255) NOT NULL,
    body_template   VARCHAR(2048) NOT NULL,
    level           TINYINT      NOT NULL DEFAULT 1 COMMENT '1=INFO,2=WARN,3=CRITICAL',
    channels        VARCHAR(128) NOT NULL DEFAULT 'IN_APP' COMMENT 'CSV: IN_APP,EMAIL,TELEGRAM',
    description     VARCHAR(512),
    enabled         TINYINT      NOT NULL DEFAULT 1 COMMENT '0=disabled (template skipped),1=enabled',
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),

    KEY idx_enabled (enabled)
);

-- t_notification 加 template_code 列（NULL 表示旧路径硬编码 title/body 兜底）
ALTER TABLE t_notification ADD COLUMN template_code VARCHAR(64) AFTER type;
ALTER TABLE t_notification ADD KEY idx_template_code (template_code);

-- Seed 10 个模板（覆盖现有 5 个调用点 8 种 type + 入金 + 风控）
-- 模板文案与 Phase 0 反转前 producer 硬编码 title/body 等价，迁移到 send() 时保持用户感知 0 变化
INSERT INTO t_notification_template (code, title_template, body_template, level, channels, description) VALUES
('PRICE_ALERT_TRIGGERED',   '价格告警：${symbol} ${direction} ${targetPrice}',
                            '${symbol} ${direction} ${targetPrice} → 现价 ${markPrice}${exhaustSuffix}${noteSuffix}',
                            1, 'IN_APP', '价格告警触发（用户设价 → 触发；direction=向上穿透/向下穿透）'),
('POSITION_LIQUIDATED',     '⚠️ ${symbol} ${side} 被强平',
                            '${symbol} ${side} 在 ${closePrice} 被强平，实现盈亏 ${pnl}',
                            3, 'IN_APP', '持仓强平（side=多/空）'),
('POSITION_TP_HIT',         '${symbol} ${side} 止盈触发',
                            '${symbol} ${side} 止盈触发，平仓价 ${closePrice}，实现盈亏 ${pnl}',
                            1, 'IN_APP', '止盈触发'),
('POSITION_SL_HIT',         '${symbol} ${side} 止损触发',
                            '${symbol} ${side} 止损触发，平仓价 ${closePrice}，实现盈亏 ${pnl}',
                            2, 'IN_APP', '止损触发'),
('KYC_APPROVED',            'KYC 已通过',
                            '您的 KYC 认证已通过审核，现在可以正常使用全部功能。',
                            1, 'IN_APP', 'KYC 审核通过'),
('KYC_REJECTED',            'KYC 未通过',
                            '您的 KYC 认证未通过：${rejectReason}',
                            2, 'IN_APP', 'KYC 审核拒绝'),
('WITHDRAW_COMPLETED',      '出金已完成',
                            '您的出金 ${amount} ${currency} 已链上确认。',
                            1, 'IN_APP', '出金链上确认'),
('WITHDRAW_FAILED',         '出金失败',
                            '您的出金 ${amount} ${currency} 处理失败：${failureReason}。已退回冻结资金。',
                            2, 'IN_APP', '出金失败（含 frozen 退冻）'),
('DEPOSIT_CREDITED',        '入金到账',
                            '${amount} ${currency} 已入账（${chain} ${txHash}）',
                            1, 'IN_APP', '入金到账'),
('RISK_ACTION_TRIGGERED',   '风控告警：${actionType}',
                            '${reason}',
                            2, 'IN_APP', '风控动作触发（GLOBAL_PAUSE / EXPOSURE_LIMIT 等）');

-- 注意：本 migration 不删除任何现有数据；旧 t_notification 行的 template_code = NULL，
-- 由 producer 在迁移到 send(templateCode, params) API 后逐渐填充。
-- 全部 producer 切到 send() API 后，template_code 可改为 NOT NULL（V23 或更晚）。
