package com.falconx.trading.repository.mapper;

import com.falconx.trading.repository.mapper.record.TradingTradeRecord;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 交易成交 MyBatis Mapper。
 *
 * <p>该 Mapper 负责 `t_trade` 的 SQL 声明。
 */
@Mapper
public interface TradingTradeMapper {

    /**
     * 插入成交记录。
     *
     * @param record 成交记录
     * @return 影响行数
     */
    int insertTradingTrade(TradingTradeRecord record);

    TradingTradeRecord selectByOrderIdAndTradeType(@Param("orderId") Long orderId,
                                                   @Param("tradeTypeCode") Integer tradeTypeCode);

    TradingTradeRecord selectByPositionIdAndTradeType(@Param("positionId") Long positionId,
                                                      @Param("tradeTypeCode") Integer tradeTypeCode);

    List<TradingTradeRecord> selectByUserIdPaginated(@Param("userId") Long userId,
                                                     @Param("offset") int offset,
                                                     @Param("limit") int limit);

    long countByUserId(@Param("userId") Long userId);
}
