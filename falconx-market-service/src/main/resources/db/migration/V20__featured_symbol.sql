-- =============================================================
-- V20: 客户端顶栏跑马灯「热门产品」有序列表（管理端可配置）
-- 任务卡：FEATURED-TICKER
--
-- 单一全局有序列表（无 group 维度）：管理员维护展示哪些 symbol、顺序、启停。
-- 客户端公开接口按 sort_order 升序取 enabled=1 的 symbol 渲染跑马灯。
-- 空表时客户端回退前端默认偏好（不强制 seed），保证升级后跑马灯不空白。
-- owner：market-service（与 symbol 同库，便于校验 symbol 存在）。
-- =============================================================

CREATE TABLE t_featured_symbol (
    platform_symbol VARCHAR(32)  NOT NULL COMMENT '平台 symbol，对应 t_symbol_quote_mapping.platform_symbol',
    sort_order      INT          NOT NULL DEFAULT 0 COMMENT '展示顺序（小→大）',
    enabled         TINYINT      NOT NULL DEFAULT 1 COMMENT '1=展示，0=停用',
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (platform_symbol),
    INDEX idx_enabled_sort (enabled, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='顶栏跑马灯热门产品有序列表（管理端可配置）';
