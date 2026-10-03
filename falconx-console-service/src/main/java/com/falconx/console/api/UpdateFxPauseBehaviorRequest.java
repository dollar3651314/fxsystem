package com.falconx.console.api;

import jakarta.validation.constraints.NotNull;

/**
 * STAGE-14D3b Task3：更新某商品类目 FX_PAUSED 行为请求（管理端）。
 *
 * <p>{@code reason} 仅本地审计，不进 trading RPC body（与 C2 tier / Task2 口径一致），reason 必填由
 * service 层 verifyReason 处理；此处 {@link NotNull} 仅拒非 reason 字段为 null（防 Map.of 审计快照 NPE→500，统一返 400）。
 * category 1-8 / 字段越界校验由 trading-core Bean Validation 兜底，返回 99004 → 翻译 90951。
 */
public record UpdateFxPauseBehaviorRequest(
        @NotNull Boolean allowOpen,
        @NotNull Boolean allowClose,
        @NotNull Boolean allowLiquidation,
        String reason) {
}
