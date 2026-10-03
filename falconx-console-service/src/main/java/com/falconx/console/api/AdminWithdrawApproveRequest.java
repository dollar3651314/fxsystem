package com.falconx.console.api;

import jakarta.validation.constraints.Size;

/**
 * STAGE-7-WITHDRAW Phase 4：approve 请求体。
 *
 * <p>contract: {@code reviewNote} 可选（管理端接口规范 §10.3）。downstream trading-core 字段名是 {@code note}，
 * console application service 负责映射。
 */
public record AdminWithdrawApproveRequest(@Size(max = 512) String reviewNote) {
}
