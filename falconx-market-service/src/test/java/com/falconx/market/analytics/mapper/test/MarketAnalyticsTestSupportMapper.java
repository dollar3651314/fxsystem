package com.falconx.market.analytics.mapper.test;

import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * market-service ClickHouse 测试专用 Mapper。
 *
 * <p>该 Mapper 只在测试源码中存在，用来完成 Stage 5 集成测试的清表和计数断言。
 */
@Mapper
public interface MarketAnalyticsTestSupportMapper {

    default void clearAnalyticsTables() {
        truncateQuoteTick();
        truncateKline();
    }

    int truncateQuoteTick();

    int truncateKline();

    Long countQuoteTickBySymbol(@Param("symbol") String symbol);

    Long countKlineBySymbolAndInterval(@Param("symbol") String symbol, @Param("interval") String interval);

    Map<String, Object> selectLatestQuoteTickBySymbol(@Param("symbol") String symbol);
}
