package com.falconx.trading.service;

import java.time.OffsetDateTime;

/**
 * 隔夜利息批量结算服务。
 */
public interface TradingSwapSettlementService {

    /**
     * 扫描并结算当前已到期的 `Swap`。
     *
     * @param now 本轮调度时点
     * @return 实际写入账本的结算条数
     */
    int settleDuePositions(OffsetDateTime now);
}
