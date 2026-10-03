package com.falconx.trading.entity;

/**
 * FX_PAUSED 期间各品种类目允许的操作开关（领域只读实体，对应表 {@code t_fx_pause_behavior}）。
 *
 * <p>STAGE-14C2 引入。GLOBAL_PAUSE / FX_PAUSED 活跃时，按品种 {@code category}（1-8：
 * 1crypto/2forex/3metal/4index/5energy/6stock/7etf/8other）查本表，决定该类目在停盘期间
 * 是否允许开仓 / 平仓 / 被动强平。V31 seed：forex(2)/metal(3) 默认 {@code allowOpen=false}、
 * {@code allowLiquidation=false}（仅允许平仓），其余类目默认全允许。
 *
 * <p>本实体为只读访问产物（读路径），写入（CRUD）由 console 负责，
 * 因此 {@code updated_at}/{@code updated_by_admin_id} 不进入领域读模型。
 *
 * @param category 品种类目编码（1-8，主键）
 * @param categoryName 类目名称（crypto/forex/metal/...）
 * @param allowOpen 停盘期间是否允许开仓（TINYINT 1/0 → boolean）
 * @param allowClose 停盘期间是否允许平仓（master §6.5 始终为 true）
 * @param allowLiquidation 停盘期间是否允许被动强平（TINYINT 1/0 → boolean）
 */
public record FxPauseBehavior(
        int category,
        String categoryName,
        boolean allowOpen,
        boolean allowClose,
        boolean allowLiquidation
) {
}
