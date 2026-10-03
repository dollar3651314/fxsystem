package com.falconx.console.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * STAGE-11-OBS-RECON §14.2：手动标记对账项已 resolved 请求体。
 *
 * <p>{@code reason} 最少 10 字符（与 §14.4 错误码 90923 一致）。
 *
 * <p>{@code resolutionType} 枚举：MANUAL_CREDIT / IGNORE_NON_BUSINESS / WALLET_FALSE_POSITIVE。
 */
public record AdminReconciliationMarkResolvedRequest(
        @NotBlank @Size(min = 10, max = 512) String reason,
        @NotBlank @Pattern(regexp = "^(MANUAL_CREDIT|IGNORE_NON_BUSINESS|WALLET_FALSE_POSITIVE)$")
        String resolutionType
) {
}
