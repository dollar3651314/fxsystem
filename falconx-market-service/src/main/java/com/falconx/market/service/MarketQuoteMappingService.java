package com.falconx.market.service;

import com.falconx.market.provider.ExternalRawQuote;
import java.util.List;

/**
 * 报价源映射服务。
 */
public interface MarketQuoteMappingService {

    /**
     * 从 owner DB 刷新平台 symbol 与源 symbol 的映射快照。
     */
    void refreshMappings();

    /**
     * 返回当前应订阅的源 symbol 列表。
     *
     * @return 源 symbol 列表
     */
    List<String> sourceSymbols();

    /**
     * 将一条源报价转换为平台报价列表。
     *
     * @param sourceQuote 源报价
     * @return 平台报价列表
     */
    List<ExternalRawQuote> mapToPlatformQuotes(ExternalRawQuote sourceQuote);
}
