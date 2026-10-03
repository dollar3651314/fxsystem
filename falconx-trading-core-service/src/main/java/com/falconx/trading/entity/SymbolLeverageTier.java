package com.falconx.trading.entity;

import java.math.BigDecimal;

/**
 * 杠杆/MM 双重分级档位（领域实体，对应表 {@code t_symbol_leverage_tier}）。
 *
 * <p>STAGE-14C1 引入。每个 (symbol, group_code) 维护若干档位，按账户币 notional
 * 落入 {@code [notionalLower, notionalUpper)} 区间命中对应档，给出该档允许的最大杠杆
 * 与维持保证金率（mmRate）。最高档 {@code notionalUpper} 为 {@code null} 表示无上限。
 *
 * <p>本实体为只读访问产物（读路径），写入（CRUD）由 console（C2 阶段）负责，
 * 因此创建/更新时间不进入领域读模型。
 *
 * @param id 主键 ID
 * @param symbol 品种代码
 * @param groupCode 客户组代码（默认 {@code default}）
 * @param tierNo 档位序号（从 1 开始，同 symbol+group 内唯一）
 * @param notionalLower 档位名义价值下界（含，账户币口径）
 * @param notionalUpper 档位名义价值上界（不含）；{@code null} 表示最高档无上限
 * @param maxLeverage 该档允许的最大杠杆倍数
 * @param mmRate 该档维持保证金率（DECIMAL(8,6)）
 * @param enabled 是否启用
 */
public record SymbolLeverageTier(
        Long id,
        String symbol,
        String groupCode,
        Integer tierNo,
        BigDecimal notionalLower,
        BigDecimal notionalUpper,
        Integer maxLeverage,
        BigDecimal mmRate,
        Boolean enabled
) {
}
