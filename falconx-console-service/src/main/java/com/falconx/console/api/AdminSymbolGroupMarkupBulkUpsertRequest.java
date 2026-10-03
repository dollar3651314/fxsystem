package com.falconx.console.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/**
 * STAGE-12-GROUP-MARKUP: 批量 upsert 用户组加点（运营批量调整某组的多 symbol 加点）。
 *
 * <p>市场服务 application service 上限 500 条，超出抛 90641；console 前置校验长度。
 */
public record AdminSymbolGroupMarkupBulkUpsertRequest(
        @NotNull @NotEmpty @Size(max = 500) List<@Valid Item> items,
        @NotBlank @Size(min = 10, max = 500) String reason
) {
    public record Item(
            @NotBlank @Size(max = 32) String platformSymbol,
            @NotNull @DecimalMin(value = "-1000000", inclusive = false)
            @DecimalMax(value = "1000000", inclusive = false) BigDecimal bidExtra,
            @NotNull @DecimalMin(value = "-1000000", inclusive = false)
            @DecimalMax(value = "1000000", inclusive = false) BigDecimal askExtra,
            @NotNull Boolean enabled
    ) {
    }
}
