package com.falconx.market.service;

import java.time.OffsetDateTime;

/**
 * market-service 本地交易时段保护服务。
 */
public interface MarketTradingScheduleGuardService {

    /**
     * 判断当前 symbol 是否处于可接收实时报价的交易时段。
     *
     * @param symbol 品种
     * @param now 当前时间
     * @return true 表示未判定休盘，可处理报价
     */
    boolean isQuoteProcessingAllowed(String symbol, OffsetDateTime now);
}
