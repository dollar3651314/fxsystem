package com.falconx.console.api;

import jakarta.validation.constraints.NotNull;

/**
 * STAGE-14D3b Task2：更新冷静期请求（管理端）。
 *
 * <p>{@code reason} 仅本地审计，不进 trading RPC body（与 C2 tier 口径一致），reason 必填由
 * service 层 verifyReason 处理；此处 {@link NotNull} 仅拒非 reason 字段为 null（防 Map.of 审计快照 NPE→500，统一返 400）。
 * 越界校验（60-604800）由 trading-core Bean Validation 兜底，返回 99004 → 翻译 90950。
 */
public record UpdateCoolingPeriodRequest(@NotNull Integer coolingPeriodSeconds, String reason) {
}
