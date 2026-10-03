package com.falconx.trading.repository.mapper.record;

/**
 * FX_PAUSED 类目行为开关 MyBatis 记录对象，对应 {@code t_fx_pause_behavior} 的读列。
 *
 * <p>组件顺序必须与 {@code FxPauseBehaviorMapper.xml} 中 resultMap 的 constructor
 * {@code <arg>} 顺序严格一致（STAGE-14B 高危点）。TINYINT 列 {@code allow_*} 映射为
 * {@link Integer}（1/0），1/0 → boolean 的转换在 Repository {@code toDomain} 完成。
 *
 * @param category 类目编码（列 category，TINYINT → Integer）
 * @param categoryName 类目名称（列 category_name）
 * @param allowOpen 是否允许开仓（列 allow_open，TINYINT → Integer）
 * @param allowClose 是否允许平仓（列 allow_close，TINYINT → Integer）
 * @param allowLiquidation 是否允许被动强平（列 allow_liquidation，TINYINT → Integer）
 */
public record FxPauseBehaviorRecord(
        Integer category,
        String categoryName,
        Integer allowOpen,
        Integer allowClose,
        Integer allowLiquidation
) {
}
