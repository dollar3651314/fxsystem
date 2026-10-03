package com.falconx.trading.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * STAGE-14D1 Task 3：margin mode 切换请求体（POST /api/v1/me/margin-mode）。
 *
 * @param targetMode 目标 margin mode 字符串（ISOLATED / CROSS），大小写不敏感
 */
public record MarginModeSwitchRequest(
        @NotBlank String targetMode
) {
}
