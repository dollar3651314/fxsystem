package com.falconx.trading.repository.mapper;

import com.falconx.trading.repository.mapper.record.TradingPriceAlertRecord;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * STAGE-4-PRICE-ALERT：价格告警 Mapper。
 */
@Mapper
public interface TradingPriceAlertMapper {

    int insert(TradingPriceAlertRecord record);

    TradingPriceAlertRecord selectById(@Param("id") Long id);

    TradingPriceAlertRecord selectByIdForUpdate(@Param("id") Long id);

    /**
     * 触发引擎用：按 symbol 扫所有 ACTIVE + 距离上次触发 ≥ 5 分钟的告警。
     */
    List<TradingPriceAlertRecord> selectTriggerableBySymbol(@Param("symbol") String symbol,
                                                              @Param("now") LocalDateTime now);

    List<String> selectActiveSymbols();

    /**
     * 用户列表（按 status 过滤）。
     */
    List<TradingPriceAlertRecord> selectByUserId(@Param("userId") Long userId,
                                                   @Param("status") Integer status,
                                                   @Param("symbol") String symbol,
                                                   @Param("offset") int offset,
                                                   @Param("limit") int limit);

    long countByUserId(@Param("userId") Long userId,
                       @Param("status") Integer status,
                       @Param("symbol") String symbol);

    /**
     * 用户级 ACTIVE 数量（10 条上限校验）。
     */
    int countActiveByUserId(@Param("userId") Long userId);

    long countActiveBySymbol(@Param("symbol") String symbol);

    /**
     * 管理端列表。
     */
    List<TradingPriceAlertRecord> selectAdminPaginated(@Param("userId") Long userId,
                                                        @Param("symbol") String symbol,
                                                        @Param("status") Integer status,
                                                        @Param("offset") int offset,
                                                        @Param("limit") int limit);

    long countAdminFiltered(@Param("userId") Long userId,
                            @Param("symbol") String symbol,
                            @Param("status") Integer status);

    /**
     * 触发后更新：CAS 写入 trigger_count + 1 / last_triggered_at / last_triggered_price，
     * 达 3 次时同时切 status=EXHAUSTED。
     *
     * @return 影响行数；0 表示并发竞态或 CAS 不匹配
     */
    int incrementTriggerAtomic(@Param("id") Long id,
                                @Param("expectedTriggerCount") int expectedTriggerCount,
                                @Param("nextTriggerCount") int nextTriggerCount,
                                @Param("nextStatus") int nextStatus,
                                @Param("lastTriggeredAt") LocalDateTime lastTriggeredAt,
                                @Param("lastTriggeredPrice") BigDecimal lastTriggeredPrice,
                                @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * 撤销/管理员删除。
     */
    int markCancelledAtomic(@Param("id") Long id,
                             @Param("nextStatus") int nextStatus,
                             @Param("cancelSource") String cancelSource,
                             @Param("cancelledAt") LocalDateTime cancelledAt,
                             @Param("updatedAt") LocalDateTime updatedAt);
}
