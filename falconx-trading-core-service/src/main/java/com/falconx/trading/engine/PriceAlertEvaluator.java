package com.falconx.trading.engine;

import com.falconx.trading.entity.TradingPriceAlert;
import com.falconx.trading.entity.TradingPriceAlertDirection;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/**
 * STAGE-4-PRICE-ALERT：价格告警触发规则判定器。
 *
 * <ul>
 *   <li>ABOVE → mark ≥ targetPrice 触发</li>
 *   <li>BELOW → mark ≤ targetPrice 触发</li>
 * </ul>
 *
 * <p>5min 节流和 trigger_count 上限由 SQL 层 + CAS 保证，本判定器只看价格条件。
 */
@Component
public class PriceAlertEvaluator {

    public boolean evaluate(TradingPriceAlert alert, BigDecimal markPrice) {
        if (alert == null || markPrice == null) return false;
        BigDecimal target = alert.targetPrice();
        if (target == null) return false;
        return alert.direction() == TradingPriceAlertDirection.ABOVE
                ? markPrice.compareTo(target) >= 0
                : markPrice.compareTo(target) <= 0;
    }
}
