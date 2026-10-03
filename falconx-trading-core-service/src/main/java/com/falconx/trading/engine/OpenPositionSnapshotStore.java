package com.falconx.trading.engine;

import com.falconx.trading.entity.TradingPosition;
import java.util.List;

/**
 * OPEN 持仓内存快照仓库。
 *
 * <p>该组件不是数据库 Repository，而是 `QuoteDrivenEngine` 的运行态快照依赖：
 *
 * <ul>
 *   <li>启动时从数据库装载全部 OPEN 持仓</li>
 *   <li>运行期按 symbol 维护内存索引</li>
 *   <li>实时触发、强平、TP/SL 等高频路径只读这里，不直接查 MySQL</li>
 * </ul>
 */
public interface OpenPositionSnapshotStore {

    /**
     * 用启动时全量 OPEN 持仓初始化内存索引。
     *
     * @param positions 全量 OPEN 持仓
     */
    void replaceAll(List<TradingPosition> positions);

    /**
     * 在持仓开仓、更新 TP/SL 或强平状态变化后更新对应快照。
     *
     * @param position 最新持仓状态
     */
    void upsert(TradingPosition position);

    /**
     * 按持仓 ID 从内存索引中移除终态持仓。
     *
     * @param symbol 品种
     * @param positionId 持仓 ID
     */
    void remove(String symbol, Long positionId);

    /**
     * 查询某个品种当前全部 OPEN 持仓。
     *
     * @param symbol 交易品种
     * @return OPEN 持仓列表
     */
    List<TradingPosition> listOpenBySymbol(String symbol);

    /**
     * 查询某个用户当前全部 OPEN 持仓。
     *
     * @param userId 用户 ID
     * @return OPEN 持仓列表
     */
    List<TradingPosition> listOpenByUserId(Long userId);
}
