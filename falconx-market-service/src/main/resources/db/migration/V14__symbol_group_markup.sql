-- =============================================================
-- V14: 用户组对每个平台 symbol 的额外加点配置（双向）
-- 任务卡：STAGE-12-GROUP-MARKUP
--
-- 与 V7 平行的第二张组级关系表：
--   - V7  t_symbol_group_visibility (group_code, symbol) → 该组能看哪些 symbol
--   - V14 t_symbol_group_markup     (group_code, platform_symbol) → 该组在每个 symbol 上的加点
--
-- bid_extra / ask_extra 允许负值（用于 VIP 让利）；CHECK 兜底防止极端值。
-- 配套设计稿：docs/design/STAGE-12-GROUP-MARKUP-design.md
-- =============================================================

CREATE TABLE t_symbol_group_markup (
    group_code      VARCHAR(64)    NOT NULL COMMENT '用户组代码，对应 t_user.group_code',
    platform_symbol VARCHAR(32)    NOT NULL COMMENT '平台symbol，对应 t_symbol_quote_mapping.platform_symbol',
    bid_extra       DECIMAL(24,8)  NOT NULL DEFAULT 0 COMMENT 'Bid 组级额外加点（可负）',
    ask_extra       DECIMAL(24,8)  NOT NULL DEFAULT 0 COMMENT 'Ask 组级额外加点（可负）',
    enabled         TINYINT        NOT NULL DEFAULT 1 COMMENT '1=启用，0=停用（停用等价于 0 加点）',
    created_at      DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (group_code, platform_symbol),
    INDEX idx_group_enabled (group_code, enabled),
    INDEX idx_platform (platform_symbol),
    INDEX idx_updated_at (updated_at),
    CONSTRAINT chk_group_markup_range CHECK (
        bid_extra > -1000000 AND bid_extra < 1000000 AND
        ask_extra > -1000000 AND ask_extra < 1000000
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='用户组在平台基准价之上的额外加点配置';

-- 默认组兜底：为 default 组、所有当前启用的 platform symbol 写一行 0 加点
-- 保证应用层 lookup 必有命中，无需对 cache miss 做额外分支
INSERT IGNORE INTO t_symbol_group_markup (group_code, platform_symbol, bid_extra, ask_extra, enabled)
SELECT 'default', platform_symbol, 0, 0, 1
FROM t_symbol_quote_mapping
WHERE enabled = 1;
