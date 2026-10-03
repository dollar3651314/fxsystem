package com.falconx.trading.repository.mapper;

import com.falconx.trading.repository.mapper.record.TradingOrderRecord;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 交易订单 MyBatis Mapper。
 *
 * <p>该 Mapper 负责 `t_order` 的 SQL 声明。
 */
@Mapper
public interface TradingOrderMapper {

    /**
     * 插入订单记录。
     *
     * @param record 订单记录
     * @return 影响行数
     */
    int insertTradingOrder(TradingOrderRecord record);

    /**
     * 更新订单记录。
     *
     * @param record 订单记录
     * @return 影响行数
     */
    int updateTradingOrder(TradingOrderRecord record);

    /**
     * 按用户和客户端订单号查询订单。
     *
     * @param userId 用户 ID
     * @param clientOrderId 客户端幂等键
     * @return 订单记录
     */
    TradingOrderRecord selectByUserIdAndClientOrderId(@Param("userId") Long userId,
                                                      @Param("clientOrderId") String clientOrderId);

    List<TradingOrderRecord> selectByUserIdPaginated(@Param("userId") Long userId,
                                                     @Param("offset") int offset,
                                                     @Param("limit") int limit);

    long countByUserId(@Param("userId") Long userId);

    /**
     * STAGE-2-TRADING-MONITOR R4：管理端多条件分页查询订单。
     *
     * <p>所有筛选参数均可为 null（表示不限），按 created_at DESC, id DESC 排序。
     */
    List<TradingOrderRecord> selectAdminPaginated(@Param("userId") Long userId,
                                                  @Param("symbol") String symbol,
                                                  @Param("statusCode") Integer statusCode,
                                                  @Param("fromCreatedAt") LocalDateTime fromCreatedAt,
                                                  @Param("toCreatedAt") LocalDateTime toCreatedAt,
                                                  @Param("offset") int offset,
                                                  @Param("limit") int limit);

    /**
     * STAGE-2-TRADING-MONITOR R4：管理端多条件计数。
     */
    long countAdminFiltered(@Param("userId") Long userId,
                            @Param("symbol") String symbol,
                            @Param("statusCode") Integer statusCode,
                            @Param("fromCreatedAt") LocalDateTime fromCreatedAt,
                            @Param("toCreatedAt") LocalDateTime toCreatedAt);
}
