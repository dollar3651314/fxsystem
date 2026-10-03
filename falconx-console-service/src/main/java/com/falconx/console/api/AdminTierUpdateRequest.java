package com.falconx.console.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * STAGE-14C2 Task 8：管理端编辑杠杆/MM 档位请求体（console 侧，按主键更新可变字段）。
 *
 * <p>symbol/groupCode 不可改（落档键），仅改区间 / 杠杆 / mmRate；tierNo 随档位语义可改。
 * 校验口径同新建（trading-core 执行）。{@code reason} 仅用于本地审计快照，不透传到 trading RPC。
 *
 * @param tierNo 档位序号（≥1）
 * @param notionalLower 档位名义价值下界（含，≥0）
 * @param notionalUpper 档位名义价值上界（不含）；{@code null} 表示无上限
 * @param maxLeverage 最大杠杆倍数（≥1）
 * @param mmRate 维持保证金率
 * @param reason 高危操作原因（审计用，≤200）
 */
public record AdminTierUpdateRequest(
        @NotNull @Min(1) Integer tierNo,
        @NotNull BigDecimal notionalLower,
        BigDecimal notionalUpper,
        @NotNull @Min(1) Integer maxLeverage,
        @NotNull BigDecimal mmRate,
        @NotBlank @Size(max = 200) String reason
) {
}
