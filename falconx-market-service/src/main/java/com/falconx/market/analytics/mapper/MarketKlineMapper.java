package com.falconx.market.analytics.mapper;

import com.falconx.market.analytics.mapper.record.MarketKlineRecord;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * ClickHouse K 线 MyBatis Mapper。
 *
 * <p>该 Mapper 负责 `falconx_market_analytics.kline` 的 SQL 声明。
 */
@Mapper
public interface MarketKlineMapper {

    int insertKline(MarketKlineRecord record);

    /**
     * 批量插入已收盘 K 线（#2，2026-06-08）。由 writer 攒批后调用，替代逐行 insertKline 落库，
     * 消除 part 爆炸。单行 insertKline 保留供测试与回退使用。
     */
    int insertKlines(List<MarketKlineRecord> records);

    List<MarketKlineRecord> selectRecentKlines(@Param("symbol") String symbol,
                                               @Param("interval") String interval,
                                               @Param("limit") int limit);
}
