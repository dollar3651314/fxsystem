package com.falconx.trading.repository.mapper;

import com.falconx.trading.repository.mapper.record.TradingPositionRecord;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 交易持仓 MyBatis Mapper。
 *
 * <p>该 Mapper 负责 `t_position` 的 SQL 声明。
 */
@Mapper
public interface TradingPositionMapper {

    /**
     * 插入持仓记录。
     *
     * @param record 持仓记录
     * @return 影响行数
     */
    int insertTradingPosition(TradingPositionRecord record);

    /**
     * 更新持仓记录。
     *
     * @param record 持仓记录
     * @return 影响行数
     */
    int updateTradingPosition(TradingPositionRecord record);

    /**
     * 按开仓订单查询持仓。
     *
     * @param openingOrderId 开仓订单 ID
     * @return 持仓记录
     */
    TradingPositionRecord selectByOpeningOrderId(@Param("openingOrderId") Long openingOrderId);

    /**
     * 按持仓 ID 和用户 ID 查询持仓并加锁。
     *
     * @param positionId 持仓 ID
     * @param userId 用户 ID
     * @return 持仓记录
     */
    TradingPositionRecord selectByIdAndUserIdForUpdate(@Param("positionId") Long positionId, @Param("userId") Long userId);

    /**
     * 按持仓 ID 查询持仓并加锁。
     *
     * @param positionId 持仓 ID
     * @return 持仓记录
     */
    TradingPositionRecord selectByIdForUpdate(@Param("positionId") Long positionId);

    /**
     * 按用户查询 OPEN 持仓。
     *
     * @param userId 用户 ID
     * @return OPEN 持仓记录
     */
    List<TradingPositionRecord> selectOpenByUserId(@Param("userId") Long userId);

    long countOpenByUserIdAndSymbol(@Param("userId") Long userId, @Param("symbol") String symbol);

    long countOpenBySymbol(@Param("symbol") String symbol);

    /**
     * 查询全部 OPEN 持仓。
     *
     * @return OPEN 持仓记录
     */
    List<TradingPositionRecord> selectAllOpenPositions();

    List<TradingPositionRecord> selectByUserIdPaginated(@Param("userId") Long userId,
                                                        @Param("statusCodes") List<Integer> statusCodes,
                                                        @Param("offset") int offset,
                                                        @Param("limit") int limit);

    long countByUserId(@Param("userId") Long userId,
                       @Param("statusCodes") List<Integer> statusCodes);

    /**
     * 查询某品种上持有 OPEN 持仓的去重用户 ID 列表。
     *
     * @param symbol 交易品种
     * @return OPEN 持仓用户 ID 列表
     */
    List<Long> selectDistinctUserIdsBySymbolOpen(@Param("symbol") String symbol);

    /**
     * STAGE-2-TRADING-MONITOR R4：管理端多条件分页查询持仓。
     *
     * <p>所有筛选参数均可为 null（表示不限），按 opened_at DESC, id DESC 排序。
     */
    List<TradingPositionRecord> selectAdminPaginated(@Param("userId") Long userId,
                                                     @Param("symbol") String symbol,
                                                     @Param("statusCode") Integer statusCode,
                                                     @Param("fromOpenedAt") LocalDateTime fromOpenedAt,
                                                     @Param("toOpenedAt") LocalDateTime toOpenedAt,
                                                     @Param("offset") int offset,
                                                     @Param("limit") int limit);

    /**
     * STAGE-2-TRADING-MONITOR R4：管理端多条件计数。
     */
    long countAdminFiltered(@Param("userId") Long userId,
                            @Param("symbol") String symbol,
                            @Param("statusCode") Integer statusCode,
                            @Param("fromOpenedAt") LocalDateTime fromOpenedAt,
                            @Param("toOpenedAt") LocalDateTime toOpenedAt);
}
