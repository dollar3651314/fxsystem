package com.falconx.console.api;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * STAGE-12-GROUP-MARKUP: 编辑用户组加点配置请求（PUT）。
 */
public record AdminSymbolGroupMarkupUpdateRequest(
        @NotNull @DecimalMin(value = "-1000000", inclusive = false)
        @DecimalMax(value = "1000000", inclusive = false) BigDecimal bidExtra,
        @NotNull @DecimalMin(value = "-1000000", inclusive = false)
        @DecimalMax(value = "1000000", inclusive = false) BigDecimal askExtra,
        @NotNull Boolean enabled,
        @NotBlank @Size(min = 10, max = 500) String reason
) {
}
