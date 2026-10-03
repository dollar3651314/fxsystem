package com.falconx.trading.engine;

import com.falconx.trading.entity.TradingOrderSide;
import com.falconx.trading.entity.TradingPosition;
import com.falconx.trading.entity.TradingPositionCloseReason;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/**
 * 持仓触发规则判定器。
 *
 * <p>该组件只负责把“持仓快照 + 当前标记价”映射为触发结果，
 * 不参与数据库写入。强平优先级固定高于止损。
 */
@Component
public class PositionTriggerRuleEvaluator {

    /**
     * 判定当前价格是否触发 TP / SL / 强平。
     *
     * @param position OPEN 持仓
     * @param markPrice 当前标记价
     * @return 触发原因；若未触发则返回 `null`
     */
    public TradingPositionCloseReason evaluate(TradingPosition position, BigDecimal markPrice) {
        if (position == null || markPrice == null || position.isTerminal()) {
            return null;
        }
        if (position.side() == TradingOrderSide.BUY) {
            if (position.liquidationPrice() != null && markPrice.compareTo(position.liquidationPrice()) <= 0) {
                return TradingPositionCloseReason.LIQUIDATION;
            }
            if (position.stopLossPrice() != null && markPrice.compareTo(position.stopLossPrice()) <= 0) {
                return TradingPositionCloseReason.STOP_LOSS;
            }
            if (position.takeProfitPrice() != null && markPrice.compareTo(position.takeProfitPrice()) >= 0) {
                return TradingPositionCloseReason.TAKE_PROFIT;
            }
            return null;
        }

        if (position.liquidationPrice() != null && markPrice.compareTo(position.liquidationPrice()) >= 0) {
            return TradingPositionCloseReason.LIQUIDATION;
        }
        if (position.stopLossPrice() != null && markPrice.compareTo(position.stopLossPrice()) >= 0) {
            return TradingPositionCloseReason.STOP_LOSS;
        }
        if (position.takeProfitPrice() != null && markPrice.compareTo(position.takeProfitPrice()) <= 0) {
            return TradingPositionCloseReason.TAKE_PROFIT;
        }
        return null;
    }
}
