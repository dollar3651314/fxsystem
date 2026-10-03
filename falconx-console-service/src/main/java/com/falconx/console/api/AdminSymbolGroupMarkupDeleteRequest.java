package com.falconx.console.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * STAGE-12-GROUP-MARKUP: 删除用户组加点配置（高风险）。
 *
 * <p>统一通过 body 带 reason；DELETE 端点也强制 body 携带审计原因（≥ 10 字符）。
 */
public record AdminSymbolGroupMarkupDeleteRequest(
        @NotBlank @Size(min = 10, max = 500) String reason
) {
}
