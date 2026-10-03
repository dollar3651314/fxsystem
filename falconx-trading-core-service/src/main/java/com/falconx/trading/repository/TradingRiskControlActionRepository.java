package com.falconx.trading.repository;

import com.falconx.trading.entity.TradingRiskControlActionType;
import java.util.List;
import java.util.Optional;

/**
 * BBook 风控执行动作仓储接口。
 *
 * <p>该仓储负责管理 {@code t_risk_control_action} 的生命周期：
 * 自动触发时通过 {@code activateIfAbsent} 写入激活记录，
 * 恢复时通过 {@code deactivate*} 停用对应记录。
 */
public interface TradingRiskControlActionRepository {

    /**
     * 幂等激活：仅当同品种+同动作类型+同触发来源下不存在激活记录时才插入。
     *
     * @param symbol        品种；{@code null} 表示全局
     * @param actionType    动作类型
     * @param triggerSource 触发来源（AUTO / AUTO_CONCENTRATION / MANUAL）
     * @param triggerReason 触发说明
     * @param hedgeLogId    关联的 t_hedge_log.id
     * @return {@code true} 表示成功插入了新记录
     */
    boolean activateIfAbsent(String symbol,
                             TradingRiskControlActionType actionType,
                             String triggerSource,
                             String triggerReason,
                             Long hedgeLogId);

    /**
     * 停用指定品种+动作类型+触发来源的所有激活记录。
     *
     * @param symbol        品种
     * @param actionType    动作类型
     * @param triggerSource 触发来源
     */
    void deactivate(String symbol, TradingRiskControlActionType actionType, String triggerSource);

    /**
     * 批量停用指定品种列表+动作类型+触发来源的所有激活记录（跨品种集中度恢复时使用）。
     */
    void deactivateBatch(List<String> symbols, TradingRiskControlActionType actionType, String triggerSource);

    /**
     * 查询指定品种下严重程度最高的激活动作类型。
     */
    Optional<TradingRiskControlActionType> findMostSevereActiveBySymbol(String symbol);

    /**
     * 检查是否存在激活的全局暂停动作。
     */
    boolean hasActiveGlobalPause();

    /**
     * STAGE-2-RISK-ADMIN R4：管理端分页查询。
     */
    java.util.List<com.falconx.trading.entity.TradingRiskControlAction> findAdminPaginated(
            String symbol, TradingRiskControlActionType actionType, String triggerSource,
            Boolean isActive, java.time.OffsetDateTime fromCreatedAt, java.time.OffsetDateTime toCreatedAt,
            int offset, int limit);

    /**
     * STAGE-2-RISK-ADMIN R4：管理端计数。
     */
    long countAdminFiltered(String symbol, TradingRiskControlActionType actionType,
                            String triggerSource, Boolean isActive,
                            java.time.OffsetDateTime fromCreatedAt, java.time.OffsetDateTime toCreatedAt);

    /**
     * STAGE-2-RISK-ADMIN R4：按 id 查询。
     */
    java.util.Optional<com.falconx.trading.entity.TradingRiskControlAction> findById(long id);

    /**
     * STAGE-2-RISK-ADMIN R4：按 id 停用（仅 MANUAL_ADMIN）。
     *
     * @return 受影响行数
     */
    int deactivateAdminById(long id);
}
