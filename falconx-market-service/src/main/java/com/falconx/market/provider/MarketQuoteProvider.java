package com.falconx.market.provider;

import java.util.List;
import java.util.function.Consumer;

/**
 * market-service 外部报价源 Provider。
 */
public interface MarketQuoteProvider {

    /**
     * 启动外部报价源连接。
     *
     * @param symbols owner 已启用的内部标准 symbol
     * @param quoteConsumer 原始报价消费函数
     */
    void start(List<String> symbols, Consumer<ExternalRawQuote> quoteConsumer);

    /**
     * 热刷新 provider 本地 symbol 白名单或订阅。
     *
     * @param symbols owner 已启用的内部标准 symbol
     */
    void refreshSymbols(List<String> symbols);
}
