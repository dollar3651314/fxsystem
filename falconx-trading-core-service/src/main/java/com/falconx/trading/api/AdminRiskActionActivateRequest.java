package com.falconx.trading.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 管理端激活风控动作请求体。
 *
 * <p>当 actionType=GLOBAL_PAUSE 时 symbol 必须为 null；
 * 其他 actionType 时 symbol 必填。校验在 application service 中完成。
 */
public record AdminRiskActionActivateRequest(
        String symbol,
        @NotNull String actionType,
        @NotBlank @Size(max = 200) String reason
) {
}
