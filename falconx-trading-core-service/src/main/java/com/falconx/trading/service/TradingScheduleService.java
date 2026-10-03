package com.falconx.trading.service;

import java.time.OffsetDateTime;

/**
 * 交易时间校验服务。
 *
 * <p>该服务负责按方案 B 规则判断某个 `symbol` 在某一时刻是否允许交易。
 */
public interface TradingScheduleService {

    /**
     * 判断当前时刻是否允许开仓。
     *
     * @param symbol 品种代码
     * @param now 当前时间
     * @return `true` 表示允许开仓
     */
    boolean isOpenAllowed(String symbol, OffsetDateTime now);

    /**
     * 判断当前时刻是否允许手动平仓。
     *
     * <p>当前冻结口径下，休盘期间不允许产生新的成交事实；
     * 手动平仓必须和开仓、TP/SL、强平使用同一交易时间保护。
     *
     * @param symbol 品种代码
     * @param now 当前时间
     * @return `true` 表示允许继续执行平仓链路
     */
    boolean isCloseAllowed(String symbol, OffsetDateTime now);

    /**
     * 兼容既有调用方的旧方法。
     */
    default boolean isTradable(String symbol, OffsetDateTime now) {
        return isOpenAllowed(symbol, now);
    }
}
