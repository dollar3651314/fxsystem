package com.falconx.console.api;

/**
 * STAGE-14D3b Task3：FX_PAUSED 行为视图（按商品类目 1-8）。
 *
 * <p>透传自 trading-core {@code GET /internal/v1/trading/console/fx-pause-behavior} 的每行：
 * {@code category} 商品类目（1-8）、{@code categoryName} 类目名（可空）、
 * {@code allowOpen/allowClose/allowLiquidation} 在 FX_PAUSED 期间是否允许开仓/平仓/强平。
 */
public record FxPauseBehaviorView(
        int category,
        String categoryName,
        boolean allowOpen,
        boolean allowClose,
        boolean allowLiquidation) {
}
