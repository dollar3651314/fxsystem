package com.falconx.trading.repository.mapper;

import com.falconx.trading.repository.mapper.record.TradingPendingOrderTriggerRecord;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * STAGE-3-PENDING-ORDER：挂单触发 Mapper。
 */
@Mapper
public interface TradingPendingOrderTriggerMapper {

    int insert(TradingPendingOrderTriggerRecord record);

    TradingPendingOrderTriggerRecord selectById(@Param("id") Long id);

    TradingPendingOrderTriggerRecord selectByIdForUpdate(@Param("id") Long id);

    /**
     * 按 symbol 查所有 PENDING 挂单，供 PendingOrderTriggerEvaluator 使用。
     */
    List<TradingPendingOrderTriggerRecord> selectPendingBySymbol(@Param("symbol") String symbol);

    List<String> selectPendingSymbols();

    long countPendingBySymbol(@Param("symbol") String symbol);

    /**
     * 客户端挂单列表（默认过滤掉 SL_TP，仅返回 LIMIT/STOP/STOP_LIMIT）。
     */
    List<TradingPendingOrderTriggerRecord> selectUserOpeningPending(@Param("userId") Long userId,
                                                                     @Param("symbol") String symbol,
                                                                     @Param("status") Integer status,
                                                                     @Param("offset") int offset,
                                                                     @Param("limit") int limit);

    long countUserOpeningPending(@Param("userId") Long userId,
                                 @Param("symbol") String symbol,
                                 @Param("status") Integer status);

    /**
     * 持仓回显 SL/TP：按 parent_position_id 查 PENDING 状态的 SL_TP 行。
     */
    List<TradingPendingOrderTriggerRecord> selectSlTpByPositionId(@Param("positionId") Long positionId);

    /**
     * 管理端列表（含/不含 SL_TP 可选）。
     */
    List<TradingPendingOrderTriggerRecord> selectAdminPaginated(@Param("userId") Long userId,
                                                                @Param("symbol") String symbol,
                                                                @Param("status") Integer status,
                                                                @Param("includeSlTp") boolean includeSlTp,
                                                                @Param("offset") int offset,
                                                                @Param("limit") int limit);

    long countAdminFiltered(@Param("userId") Long userId,
                            @Param("symbol") String symbol,
                            @Param("status") Integer status,
                            @Param("includeSlTp") boolean includeSlTp);

    int updateStatusAtomic(@Param("id") Long id,
                           @Param("fromStatus") Integer fromStatus,
                           @Param("toStatus") Integer toStatus,
                           @Param("triggeredOrderId") Long triggeredOrderId,
                           @Param("triggeredAt") LocalDateTime triggeredAt,
                           @Param("cancelledAt") LocalDateTime cancelledAt,
                           @Param("cancelReason") String cancelReason,
                           @Param("updatedAt") LocalDateTime updatedAt);

    int updateTriggerPrices(@Param("id") Long id,
                            @Param("triggerPrice") java.math.BigDecimal triggerPrice,
                            @Param("limitPrice") java.math.BigDecimal limitPrice,
                            @Param("quantity") java.math.BigDecimal quantity,
                            @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * 持仓 SL/TP UPSERT 辅助：先撤旧 SL_TP，再 insert 新行。
     * 这里只提供撤旧的批量操作。
     */
    int cancelSlTpByPositionId(@Param("positionId") Long positionId,
                                @Param("triggerKind") Integer triggerKind,
                                @Param("cancelReason") String cancelReason,
                                @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * 持仓平仓（手动/强平/SL_TP 自身触发）时级联撤掉所有关联 SL_TP 挂单。
     */
    int cancelAllSlTpByPositionId(@Param("positionId") Long positionId,
                                   @Param("cancelReason") String cancelReason,
                                   @Param("updatedAt") LocalDateTime updatedAt);
}
