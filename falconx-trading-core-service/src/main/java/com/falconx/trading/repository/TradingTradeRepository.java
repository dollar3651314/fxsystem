package com.falconx.trading.repository;

import com.falconx.trading.entity.TradingTrade;
import com.falconx.trading.entity.TradingTradeType;
import java.util.List;
import java.util.Optional;

/**
 * 成交仓储接口。
 *
 * <p>当前阶段提供最小保存能力，以及对 OPEN/CLOSE 成交显式区分的正式查询接口。
 */
public interface TradingTradeRepository {

    /**
     * 保存成交。
     *
     * @param trade 成交对象
     * @return 持久化后的成交对象
     */
    TradingTrade save(TradingTrade trade);

    /**
     * 用调用方提供的 id 持久化 trade（不重新生成雪花 id）。
     *
     * <p>专为 Sprint 3 S6 异步消费 t_trade 写入设计：主事务 reserve id 后通过
     * outbox payload 传给消费者，消费者用此方法写入保证 in-memory trade 对象与
     * DB t_trade.id 完全一致。
     *
     * @param trade 必须含非 null {@code tradeId} 的 TradingTrade 对象
     * @return 入参对象（直接 return，不修改）
     * @throws IllegalArgumentException 当 trade.tradeId() == null
     */
    TradingTrade saveWithExplicitId(TradingTrade trade);

    /**
     * 按开仓订单 ID 查询 OPEN 成交。
     *
     * @param orderId 订单 ID
     * @return 成交可选结果
     */
    Optional<TradingTrade> findOpenTradeByOrderId(Long orderId);

    /**
     * 按持仓 ID 与成交类型查询成交。
     *
     * @param positionId 持仓 ID
     * @param tradeType 成交类型
     * @return 成交可选结果
     */
    Optional<TradingTrade> findByPositionIdAndTradeType(Long positionId, TradingTradeType tradeType);

    /**
     * 分页查询用户成交。
     *
     * @param userId 用户 ID
     * @param offset 偏移量
     * @param limit 本页条数
     * @return 成交列表
     */
    List<TradingTrade> findByUserIdPaginated(Long userId, int offset, int limit);

    /**
     * 统计用户成交总数。
     *
     * @param userId 用户 ID
     * @return 总条数
     */
    long countByUserId(Long userId);
}
