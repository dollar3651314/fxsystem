package com.falconx.trading.entity;

import java.time.OffsetDateTime;

/**
 * 交易风控全局开关实体。
 *
 * <p>对应 `falconx_trading.t_trading_risk_switch`。当前白名单只有 `auto_liquidate.enabled`，
 * 由 QuoteDrivenEngine 在每 tick 读 Redis 缓存决定是否触发 LIQUIDATION close reason。
 *
 * <p>变更路径：管理端 console → POST /internal/v1/trading/console/risk-switches/* →
 *   DB UPSERT → afterCommit 刷 Redis。
 */
public record TradingRiskSwitch(
        String switchKey,
        boolean enabled,
        String reason,
        String updatedBy,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static final String KEY_AUTO_LIQUIDATE_ENABLED = "auto_liquidate.enabled";

    /**
     * STAGE-14D1 Task 3：CROSS margin mode 启用开关。
     *
     * <p>默认语义为 false（DB/Redis 未配置该 key 时读取返回 false）。D1 阶段 CROSS 强平尚未就位，
     * 用户切换到 CROSS 一律被 30088 拒绝；D2 强平就位后由 admin 开启该开关放行。
     */
    public static final String KEY_CROSS_MODE_ENABLED = "cross_mode.enabled";
}
