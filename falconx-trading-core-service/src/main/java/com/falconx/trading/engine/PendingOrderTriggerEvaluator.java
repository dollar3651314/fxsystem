package com.falconx.trading.engine;

import com.falconx.trading.entity.TradingOrderSide;
import com.falconx.trading.entity.TradingPendingOrderTrigger;
import com.falconx.trading.entity.TradingPendingOrderTriggerKind;
import com.falconx.trading.entity.TradingPendingOrderType;
import com.falconx.trading.entity.TradingQuoteSnapshot;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/**
 * STAGE-3-PENDING-ORDER：挂单触发规则判定器。
 *
 * <p>开仓挂单（LIMIT / STOP / STOP_LIMIT）：
 * <ul>
 *   <li>LIMIT BUY  → ask ≤ trigger_price 触发（以更优的价格买入）</li>
 *   <li>LIMIT SELL → bid ≥ trigger_price 触发（以更优的价格卖出）</li>
 *   <li>STOP  BUY  → ask ≥ trigger_price 触发（突破止损价追多）</li>
 *   <li>STOP  SELL → bid ≤ trigger_price 触发（跌破止损价追空）</li>
 *   <li>STOP_LIMIT 同 STOP，触发后转 LIMIT 单</li>
 * </ul>
 *
 * <p>平仓挂单（SL_TP）按持仓方向 + 类型对 mark 价比较：
 * <ul>
 *   <li>多仓 TAKE_PROFIT  → mark ≥ trigger_price 触发</li>
 *   <li>多仓 STOP_LOSS    → mark ≤ trigger_price 触发</li>
 *   <li>空仓 TAKE_PROFIT  → mark ≤ trigger_price 触发</li>
 *   <li>空仓 STOP_LOSS    → mark ≥ trigger_price 触发</li>
 * </ul>
 */
@Component
public class PendingOrderTriggerEvaluator {

    public boolean evaluate(TradingPendingOrderTrigger order, TradingQuoteSnapshot quote) {
        if (order == null || quote == null) return false;
        BigDecimal triggerPrice = order.triggerPrice();
        if (triggerPrice == null) return false;

        if (order.orderType() == TradingPendingOrderType.SL_TP) {
            return evaluateSlTp(order, quote.mark());
        }
        return evaluateOpening(order, quote);
    }

    private boolean evaluateOpening(TradingPendingOrderTrigger order, TradingQuoteSnapshot quote) {
        BigDecimal trigger = order.triggerPrice();
        BigDecimal ask = quote.ask();
        BigDecimal bid = quote.bid();
        if (ask == null || bid == null) return false;

        // STAGE-12-GROUP-MARKUP: 触发判定用挂单冻结的组级 markup，确保挂单创建时 LP 价
        // 与触发时 LP 价之间的判定一致；管理端改加点不影响存量挂单的触发条件。
        BigDecimal bidExtra = order.bidExtraAtCreate() == null ? BigDecimal.ZERO : order.bidExtraAtCreate();
        BigDecimal askExtra = order.askExtraAtCreate() == null ? BigDecimal.ZERO : order.askExtraAtCreate();
        BigDecimal effectiveAsk = askExtra.signum() == 0 ? ask : ask.add(askExtra);
        BigDecimal effectiveBid = bidExtra.signum() == 0 ? bid : bid.add(bidExtra);

        switch (order.orderType()) {
            case LIMIT -> {
                return order.side() == TradingOrderSide.BUY
                        ? effectiveAsk.compareTo(trigger) <= 0
                        : effectiveBid.compareTo(trigger) >= 0;
            }
            case STOP, STOP_LIMIT -> {
                return order.side() == TradingOrderSide.BUY
                        ? effectiveAsk.compareTo(trigger) >= 0
                        : effectiveBid.compareTo(trigger) <= 0;
            }
            default -> { return false; }
        }
    }

    private boolean evaluateSlTp(TradingPendingOrderTrigger order, BigDecimal markPrice) {
        if (markPrice == null) return false;
        BigDecimal trigger = order.triggerPrice();
        TradingPendingOrderTriggerKind kind = order.triggerKind();
        TradingOrderSide positionSide = order.side() == TradingOrderSide.BUY
                ? TradingOrderSide.SELL  // SL_TP.side 已经存反向（平仓方向）；持仓方向相反
                : TradingOrderSide.BUY;
        // 但 evaluator 关心的是"原持仓方向"对 trigger 的关系；SL_TP 行 side 字段表示"触发后的平仓方向"，
        // 故持仓方向 = side 取反。
        // STAGE-12-GROUP-MARKUP: SL/TP 触发用挂单冻结的 markup 调整 markPrice（与持仓平仓口径对齐）
        BigDecimal extra = positionSide == TradingOrderSide.BUY
                ? (order.bidExtraAtCreate() == null ? BigDecimal.ZERO : order.bidExtraAtCreate())
                : (order.askExtraAtCreate() == null ? BigDecimal.ZERO : order.askExtraAtCreate());
        BigDecimal effectiveMark = extra.signum() == 0 ? markPrice : markPrice.add(extra);
        if (kind == TradingPendingOrderTriggerKind.TAKE_PROFIT) {
            return positionSide == TradingOrderSide.BUY
                    ? effectiveMark.compareTo(trigger) >= 0
                    : effectiveMark.compareTo(trigger) <= 0;
        } else {
            return positionSide == TradingOrderSide.BUY
                    ? effectiveMark.compareTo(trigger) <= 0
                    : effectiveMark.compareTo(trigger) >= 0;
        }
    }
}
