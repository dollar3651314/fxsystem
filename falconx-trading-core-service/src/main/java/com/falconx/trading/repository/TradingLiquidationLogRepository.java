package com.falconx.trading.repository;

import com.falconx.trading.entity.TradingLiquidationLog;
import java.util.List;

/**
 * 强平日志仓储接口。
 *
 * <p>该仓储负责把 `t_liquidation_log` 作为 owner 审计事实正式落库。
 */
public interface TradingLiquidationLogRepository {

    /**
     * 保存一条强平日志。
     *
     * @param liquidationLog 强平日志
     * @return 持久化后的日志
     */
    TradingLiquidationLog save(TradingLiquidationLog liquidationLog);

    /**
     * 分页查询用户强平记录。
     *
     * @param userId 用户 ID
     * @param offset 偏移量
     * @param limit 本页条数
     * @return 强平记录列表
     */
    List<TradingLiquidationLog> findByUserIdPaginated(Long userId, int offset, int limit);

    /**
     * 统计用户强平记录总数。
     *
     * @param userId 用户 ID
     * @return 总条数
     */
    long countByUserId(Long userId);
}
