package com.falconx.console.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * STAGE-14C2 Task 8：管理端新建杠杆/MM 档位请求体（console 侧）。
 *
 * <p>区间 {@code [notionalLower, notionalUpper)}（下界含、上界不含）；最高档 {@code notionalUpper}
 * 为 {@code null} 表示无上限。区间不重叠 / {@code maxLeverage × mmRate ≤ 1.0} 等业务校验在
 * trading-core 执行（90930-90932）。{@code reason} 为高危操作原因，仅用于本地审计快照，
 * 不透传到 trading RPC（trading 请求体不含该字段）。
 *
 * @param symbol 品种代码
 * @param groupCode 客户组代码（默认 {@code default}）
 * @param tierNo 档位序号（≥1）
 * @param notionalLower 档位名义价值下界（含，≥0）
 * @param notionalUpper 档位名义价值上界（不含）；{@code null} 表示无上限
 * @param maxLeverage 最大杠杆倍数（≥1）
 * @param mmRate 维持保证金率
 * @param reason 高危操作原因（审计用，≤200）
 */
public record AdminTierCreateRequest(
        @NotBlank String symbol,
        @NotBlank String groupCode,
        @NotNull @Min(1) Integer tierNo,
        @NotNull BigDecimal notionalLower,
        BigDecimal notionalUpper,
        @NotNull @Min(1) Integer maxLeverage,
        @NotNull BigDecimal mmRate,
        @NotBlank @Size(max = 200) String reason
) {
}
