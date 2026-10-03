package com.falconx.console.api;

import jakarta.validation.constraints.Size;

/**
 * STAGE-7-WITHDRAW Phase 4：reject 请求体。
 *
 * <p>contract: {@code reason} 必填（管理端接口规范 §10.4）；空字符串 / null 抛 90503
 * ADMIN_WITHDRAW_REJECT_REASON_REQUIRED。downstream trading-core 字段名是 {@code note}，
 * console application service 负责映射。
 *
 * <p>必填校验由 {@link com.falconx.console.withdraw.AdminWithdrawApplicationService#reject}
 * 显式判 null / isBlank 抛 {@code AdminBusinessException(ADMIN_WITHDRAW_REJECT_REASON_REQUIRED)}，
 * 不用 {@code @NotBlank} —— 后者会被 Bean Validation 拦到 {@code MethodArgumentNotValidException}
 * 抛通用 99004，丢失 90503 细分错误码（Phase 4 commit 3 §4.1 优化）。
 * {@code @Size(max=512)} 保留作为长度护栏。
 */
public record AdminWithdrawRejectRequest(@Size(max = 512) String reason) {
}
