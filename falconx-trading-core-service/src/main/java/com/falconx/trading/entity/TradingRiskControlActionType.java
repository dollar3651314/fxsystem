package com.falconx.trading.entity;

/**
 * BBook 风控执行动作类型。
 *
 * <p>严重程度从低到高：REJECT_OPEN < REDUCE_ONLY < SUSPEND_SYMBOL < GLOBAL_PAUSE。
 * 预交易检查取同品种下严重程度最高的激活动作执行。
 */
public enum TradingRiskControlActionType {
    /** 禁止该品种新开仓，已有仓位不受影响。 */
    REJECT_OPEN,
    /** 只允许减仓（平仓），禁止新开仓。 */
    REDUCE_ONLY,
    /** 品种全停，开仓和平仓均拒绝。 */
    SUSPEND_SYMBOL,
    /** 全局暂停，禁止所有品种的所有交易。 */
    GLOBAL_PAUSE
}
