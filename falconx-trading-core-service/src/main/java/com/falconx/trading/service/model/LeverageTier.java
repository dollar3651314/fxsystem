package com.falconx.trading.service.model;

import java.math.BigDecimal;

/**
 * 杠杆/MM 档位解析结果（领域读模型）。
 *
 * <p>STAGE-14C1 引入。由 {@code LeverageTierResolver} 按账户币 notional 落入
 * {@code [notionalLower, notionalUpper)} 区间命中后返回，供开仓风控（Task 6）读取该档
 * 允许的最大杠杆与维持保证金率（mmRate）。
 *
 * <p>本 record 是 {@code SymbolLeverageTier} 实体的解析投影，仅暴露下游计算所需字段，
 * 不携带 id / group / enabled 等访问元数据。
 *
 * @param tierNo 档位序号（从 1 开始）
 * @param maxLeverage 该档允许的最大杠杆倍数
 * @param mmRate 该档维持保证金率（DECIMAL(8,6)）
 * @param notionalLower 档位名义价值下界（含，账户币口径）
 * @param notionalUpper 档位名义价值上界（不含）；{@code null} 表示最高档无上限
 */
public record LeverageTier(
        int tierNo,
        int maxLeverage,
        BigDecimal mmRate,
        BigDecimal notionalLower,
        BigDecimal notionalUpper
) {
}
