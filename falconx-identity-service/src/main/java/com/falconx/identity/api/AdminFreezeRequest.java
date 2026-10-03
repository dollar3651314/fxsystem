package com.falconx.identity.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * STAGE-2-CUSTOMER：管理员冻结/解冻客户请求体。
 *
 * @param reason 操作原因，长度 ≥ 10（与 console 端一致）
 */
public record AdminFreezeRequest(@NotBlank @Size(min = 10) String reason) {
}
