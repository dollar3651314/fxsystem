package com.falconx.console.api;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * STAGE-12-GROUP-MARKUP: 新建用户组加点配置请求。
 *
 * <p>bid/ask Extra 允许正负（运营加点 / 减点），范围 (-1000000, 1000000)。
 * 高风险操作必须带 reason（≥ 10 字符）记入审计日志。
 */
public record AdminSymbolGroupMarkupCreateRequest(
        @NotBlank @Size(max = 64) String groupCode,
        @NotBlank @Size(max = 32) String platformSymbol,
        @NotNull @DecimalMin(value = "-1000000", inclusive = false)
        @DecimalMax(value = "1000000", inclusive = false) BigDecimal bidExtra,
        @NotNull @DecimalMin(value = "-1000000", inclusive = false)
        @DecimalMax(value = "1000000", inclusive = false) BigDecimal askExtra,
        @NotNull Boolean enabled,
        @NotBlank @Size(min = 10, max = 500) String reason
) {
}
