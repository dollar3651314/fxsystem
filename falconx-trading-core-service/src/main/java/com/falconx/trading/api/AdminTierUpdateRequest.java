package com.falconx.trading.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * STAGE-14C2 Task 4：编辑杠杆/MM 档位 internal RPC 请求体（按主键更新可变字段）。
 *
 * <p>symbol/groupCode 不可改（落档键），仅改区间 / 杠杆 / mmRate；tierNo 随档位语义可改。
 * 校验口径同新建（区间合法、不重叠、CHECK）。
 *
 * @param tierNo 档位序号（≥1）
 * @param notionalLower 档位名义价值下界（含，≥0）
 * @param notionalUpper 档位名义价值上界（不含）；{@code null} 表示无上限
 * @param maxLeverage 最大杠杆倍数（≥1）
 * @param mmRate 维持保证金率
 */
public record AdminTierUpdateRequest(
        @NotNull @Min(1) Integer tierNo,
        @NotNull BigDecimal notionalLower,
        BigDecimal notionalUpper,
        @NotNull @Min(1) Integer maxLeverage,
        @NotNull BigDecimal mmRate
) {
}
