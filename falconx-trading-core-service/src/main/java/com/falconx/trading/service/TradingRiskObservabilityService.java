package com.falconx.trading.service;

import com.falconx.trading.entity.TradingOrderSide;
import com.falconx.trading.entity.TradingPositionCloseReason;
import com.falconx.trading.entity.TradingQuoteSnapshot;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * B-book 风险可观测性服务。
 *
 * <p>该服务统一负责 FX-026 的 3 件事：
 *
 * <ul>
 *   <li>更新 `t_risk_exposure.net_exposure_usd`</li>
 *   <li>按 `t_risk_config.hedge_threshold_usd` 判断是否超阈值</li>
 *   <li>把超阈值/恢复事实写入 `t_hedge_log`，输出告警日志，并在超阈值时发布服务内 Spring Event stub</li>
 * </ul>
 */
public interface TradingRiskObservabilityService {

    /**
     * 在开仓链路中应用数量敞口变化并更新观测结果。
     */
    void applyOpenPosition(String symbol,
                           TradingOrderSide side,
                           BigDecimal quantity,
                           TradingQuoteSnapshot quote,
                           OffsetDateTime occurredAt,
                           Long positionId);

    /**
     * 在平仓 / 强平链路中应用数量敞口变化并更新观测结果。
     */
    void applyClosePosition(String symbol,
                            TradingOrderSide side,
                            BigDecimal quantity,
                            TradingQuoteSnapshot quote,
                            OffsetDateTime occurredAt,
                            TradingPositionCloseReason closeReason,
                            Long positionId);

    /**
     * 在行情刷新时仅更新美元口径敞口与阈值状态。
     */
    void refreshExposureFromQuote(TradingQuoteSnapshot quote, OffsetDateTime occurredAt);
}
