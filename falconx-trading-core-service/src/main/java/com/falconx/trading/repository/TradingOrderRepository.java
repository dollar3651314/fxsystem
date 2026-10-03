package com.falconx.trading.repository;

import com.falconx.trading.entity.TradingOrder;
import java.util.List;
import java.util.Optional;

/**
 * 订单仓储接口。
 *
 * <p>该接口负责保存订单事实，并提供按 `(userId, clientOrderId)` 的幂等查询能力。
 */
public interface TradingOrderRepository {

    /**
     * 保存订单。
     *
     * @param order 订单对象
     * @return 持久化后的订单对象
     */
    TradingOrder save(TradingOrder order);

    /**
     * 按用户和客户端订单号查询。
     *
     * @param userId 用户 ID
     * @param clientOrderId 客户端订单号
     * @return 订单可选结果
     */
    Optional<TradingOrder> findByUserIdAndClientOrderId(Long userId, String clientOrderId);

    /**
     * 分页查询用户订单。
     *
     * @param userId 用户 ID
     * @param offset 偏移量
     * @param limit 本页条数
     * @return 订单列表
     */
    List<TradingOrder> findByUserIdPaginated(Long userId, int offset, int limit);

    /**
     * 统计用户订单总数。
     *
     * @param userId 用户 ID
     * @return 总条数
     */
    long countByUserId(Long userId);
}
