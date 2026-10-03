package com.falconx.console.api;

import jakarta.validation.constraints.Size;

/**
 * STAGE-7-WITHDRAW Phase 4：emergency-cancel 请求体。
 *
 * <p>contract: {@code reason} 必填（管理端接口规范 §10.5）；空字符串 / null 抛 90503
 * ADMIN_WITHDRAW_REJECT_REASON_REQUIRED（与 reject 共用错误码，按 spec 复用语义）。
 * downstream trading-core 字段名是 {@code note}，console application service 负责映射。
 *
 * <p>必填校验由 {@link com.falconx.console.withdraw.AdminWithdrawApplicationService#emergencyCancel}
 * 显式判 null / isBlank 抛 90503，不用 {@code @NotBlank}（理由同 reject 请求体注释）。
 */
public record AdminWithdrawEmergencyCancelRequest(@Size(max = 512) String reason) {
}
