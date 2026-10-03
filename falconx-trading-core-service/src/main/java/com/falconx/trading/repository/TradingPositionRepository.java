package com.falconx.trading.repository;

import com.falconx.trading.entity.TradingPosition;
import com.falconx.trading.entity.TradingPositionStatus;
import java.util.List;
import java.util.Optional;

/**
 * 持仓仓储接口。
 *
 * <p>当前阶段只提供按开仓订单查询持仓的最小能力，
 * 便于在幂等下单场景中快速找到已经创建的持仓结果。
 */
public interface TradingPositionRepository {

    /**
     * 保存持仓。
     *
     * @param position 持仓对象
     * @return 持久化后的持仓对象
     */
    TradingPosition save(TradingPosition position);

    /**
     * 按开仓订单查询持仓。
     *
     * @param openingOrderId 开仓订单 ID
     * @return 持仓可选结果
     */
    Optional<TradingPosition> findByOpeningOrderId(Long openingOrderId);

    /**
     * 按持仓 ID 和用户 ID 查询持仓并加锁。
     *
     * @param positionId 持仓 ID
     * @param userId 用户 ID
     * @return 被锁定的持仓
     */
    Optional<TradingPosition> findByIdAndUserIdForUpdate(Long positionId, Long userId);

    /**
     * 按持仓 ID 查询持仓并加锁。
     *
     * <p>该方法用于 TP/SL / 强平等系统内部触发路径，不依赖 HTTP owner 上下文。
     *
     * @param positionId 持仓 ID
     * @return 被锁定的持仓
     */
    Optional<TradingPosition> findByIdForUpdate(Long positionId);

    /**
     * 查询某个用户当前全部 OPEN 持仓。
     *
     * @param userId 用户 ID
     * @return OPEN 持仓列表
     */
    List<TradingPosition> findOpenByUserId(Long userId);

    /**
     * 统计某用户在指定品种上的 OPEN 持仓数。
     *
     * @param userId 用户 ID
     * @param symbol 交易品种
     * @return OPEN 持仓数
     */
    long countOpenByUserIdAndSymbol(Long userId, String symbol);

    /**
     * 统计指定品种上的全平台 OPEN 持仓数。
     *
     * @param symbol 交易品种
     * @return OPEN 持仓数
     */
    long countOpenBySymbol(String symbol);

    /**
     * 查询系统当前全部 OPEN 持仓。
     *
     * <p>该方法主要用于交易核心启动时构建按 symbol 分组的内存快照，
     * 让 `QuoteDrivenEngine` 在运行期不直接扫描 MySQL。
     *
     * @return 全部 OPEN 持仓
     */
    List<TradingPosition> findAllOpenPositions();

    /**
     * 分页查询用户持仓，可按状态过滤。
     *
     * @param userId 用户 ID
     * @param statusFilter 状态过滤，null 或空表示不过滤；用于客户端区分活跃 / 历史持仓
     * @param offset 偏移量
     * @param limit 本页条数
     * @return 持仓列表
     */
    List<TradingPosition> findByUserIdPaginated(Long userId,
                                                List<TradingPositionStatus> statusFilter,
                                                int offset,
                                                int limit);

    /**
     * 统计用户持仓总数（按状态过滤）。
     *
     * @param userId 用户 ID
     * @param statusFilter 状态过滤，null 或空表示不过滤
     * @return 总条数
     */
    long countByUserId(Long userId, List<TradingPositionStatus> statusFilter);

    /**
     * 查询某品种上持有 OPEN 持仓的去重用户 ID 列表。
     *
     * <p>用于风险告警时向受影响用户推送 WebSocket 通知。
     *
     * @param symbol 交易品种
     * @return OPEN 持仓用户 ID 列表
     */
    List<Long> findDistinctUserIdsBySymbolOpen(String symbol);
}
