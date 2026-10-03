package com.falconx.trading.repository.mapper.record;

import java.math.BigDecimal;

/**
 * 杠杆/MM 分级档位 MyBatis 记录对象，对应 {@code t_symbol_leverage_tier} 的读列。
 *
 * <p>组件顺序必须与 {@code SymbolLeverageTierMapper.xml} 中 resultMap 的 constructor
 * {@code <arg>} 顺序严格一致（STAGE-14B 高危点）。
 *
 * @param id 主键 ID（列 id）
 * @param symbol 品种代码（列 symbol）
 * @param groupCode 客户组代码（列 group_code）
 * @param tierNo 档位序号（列 tier_no，TINYINT → Integer）
 * @param notionalLower 名义价值下界（列 notional_lower）
 * @param notionalUpper 名义价值上界，可空（列 notional_upper）
 * @param maxLeverage 最大杠杆（列 max_leverage）
 * @param mmRate 维持保证金率（列 mm_rate）
 * @param enabled 是否启用（列 enabled，TINYINT → Integer）
 */
public record SymbolLeverageTierRecord(
        Long id,
        String symbol,
        String groupCode,
        Integer tierNo,
        BigDecimal notionalLower,
        BigDecimal notionalUpper,
        Integer maxLeverage,
        BigDecimal mmRate,
        Integer enabled
) {
}
