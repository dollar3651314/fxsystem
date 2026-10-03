package com.falconx.market.service;

import com.falconx.market.entity.StandardQuote;

/**
 * 行情质量保护服务。
 */
public interface MarketQuoteQualityGuardService {

    /**
     * 评估报价是否仍可作为有效最新价。
     *
     * @param quote 标准报价
     * @return 带行情质量状态的报价
     */
    StandardQuote evaluate(StandardQuote quote);
}
