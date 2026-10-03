package com.falconx.trading.repository;

import com.falconx.trading.entity.TradingPendingOrderStatus;
import com.falconx.trading.entity.TradingPendingOrderTrigger;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * STAGE-3-PENDING-ORDER：挂单触发仓储。
 */
public interface TradingPendingOrderTriggerRepository {

    void insert(TradingPendingOrderTrigger order);

    Optional<TradingPendingOrderTrigger> findById(Long id);

    Optional<TradingPendingOrderTrigger> findByIdForUpdate(Long id);

    /**
     * 触发引擎用：扫该 symbol 全部 PENDING 挂单。
     */
    List<TradingPendingOrderTrigger> findPendingBySymbol(String symbol);

    /**
     * 启动预热高频 tick 触发索引用：查询仍有 PENDING 挂单的 symbol。
     */
    List<String> findPendingSymbols();

    long countPendingBySymbol(String symbol);

    /**
     * 客户端挂单列表（仅开仓挂单 LIMIT/STOP/STOP_LIMIT，过滤掉 SL_TP）。
     */
    List<TradingPendingOrderTrigger> findUserOpeningPending(Long userId, String symbol,
                                                            Integer statusCode, int offset, int limit);

    long countUserOpeningPending(Long userId, String symbol, Integer statusCode);

    /**
     * 持仓接口回显 SL/TP 用：按 parent_position_id 查 PENDING 状态的 SL_TP 挂单。
     */
    List<TradingPendingOrderTrigger> findSlTpByPositionId(Long positionId);

    /**
     * 管理端列表（含 / 不含 SL_TP 可选）。
     */
    List<TradingPendingOrderTrigger> findAdminPaginated(Long userId, String symbol, Integer statusCode,
                                                        boolean includeSlTp, int offset, int limit);

    long countAdminFiltered(Long userId, String symbol, Integer statusCode, boolean includeSlTp);

    /**
     * 状态原子切换（PENDING → TRIGGERED / CANCELLED / REJECTED）。
     * @return 影响行数；用于竞态检测
     */
    int markTriggered(Long id, Long triggeredOrderId);

    int markCancelled(Long id, String cancelReason);

    int markRejected(Long id, String cancelReason);

    /**
     * 修改挂单的触发价 / 限价 / 数量（仅 PENDING 状态允许）。
     */
    int updateTriggerPrices(Long id, BigDecimal triggerPrice, BigDecimal limitPrice, BigDecimal quantity);

    /**
     * UPSERT SL/TP：先撤旧 SL_TP 同 kind 的行，由调用方再 insert 新行。
     * @param positionId  持仓 ID
     * @param triggerKindCode 1=TP, 2=SL
     */
    int cancelSlTpByPositionId(Long positionId, int triggerKindCode, String cancelReason);

    /**
     * 持仓平仓时级联撤所有关联 SL_TP 挂单。
     */
    int cancelAllSlTpByPositionId(Long positionId, String cancelReason);
}
