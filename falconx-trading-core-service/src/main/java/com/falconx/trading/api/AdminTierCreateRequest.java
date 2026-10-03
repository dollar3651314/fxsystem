package com.falconx.trading.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * STAGE-14C2 Task 4：新建杠杆/MM 档位 internal RPC 请求体。
 *
 * <p>区间 {@code [notionalLower, notionalUpper)}（下界含、上界不含）；最高档 {@code notionalUpper}
 * 为 {@code null} 表示无上限。{@code maxLeverage × mmRate ≤ 1.0}、区间不与同 (symbol, groupCode)
 * 现有 enabled 档重叠、tierNo 唯一等业务校验在 {@code TradingTierAdminApplicationService}。
 *
 * @param symbol 品种代码
 * @param groupCode 客户组代码（默认 {@code default}）
 * @param tierNo 档位序号（≥1，同 symbol+group 内唯一）
 * @param notionalLower 档位名义价值下界（含，≥0）
 * @param notionalUpper 档位名义价值上界（不含）；{@code null} 表示无上限
 * @param maxLeverage 最大杠杆倍数（≥1）
 * @param mmRate 维持保证金率
 */
public record AdminTierCreateRequest(
        @NotBlank String symbol,
        @NotBlank String groupCode,
        @NotNull @Min(1) Integer tierNo,
        @NotNull BigDecimal notionalLower,
        BigDecimal notionalUpper,
        @NotNull @Min(1) Integer maxLeverage,
        @NotNull BigDecimal mmRate
) {
}
