package com.falconx.trading.calculator;

import com.falconx.trading.entity.TradingOrderSide;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;

/**
 * 强平价计算器。
 *
 * <p>当前阶段只冻结最小近似公式，用于支撑下单链路测试和持仓审计字段生成。
 * 更复杂的逐仓、全仓、多仓位净额计算会在后续风险阶段继续扩展。
 */
@Component
public class LiquidationPriceCalculator {

    /**
     * 计算强平价。
     *
     * @param side 开仓方向
     * @param entryPrice 开仓价
     * @param leverage 杠杆倍数
     * @param maintenanceMarginRate 维持保证金率
     * @return 近似强平价
     */
    public BigDecimal calculate(TradingOrderSide side,
                                BigDecimal entryPrice,
                                BigDecimal leverage,
                                BigDecimal maintenanceMarginRate) {
        BigDecimal leverageFactor = BigDecimal.ONE.divide(leverage, 8, RoundingMode.DOWN);
        if (side == TradingOrderSide.BUY) {
            return entryPrice.multiply(BigDecimal.ONE.subtract(leverageFactor).add(maintenanceMarginRate))
                    .setScale(8, RoundingMode.DOWN);
        }
        return entryPrice.multiply(BigDecimal.ONE.add(leverageFactor).subtract(maintenanceMarginRate))
                .setScale(8, RoundingMode.DOWN);
    }

    /**
     * 计算逐仓保证金变化后的通用强平价。
     *
     * <p>当 `margin = entryPrice * quantity / leverage` 时，该公式与旧的杠杆近似公式保持一致。
     */
    public BigDecimal calculate(TradingOrderSide side,
                                BigDecimal entryPrice,
                                BigDecimal quantity,
                                BigDecimal margin,
                                BigDecimal maintenanceMarginRate) {
        BigDecimal marginPerUnit = margin.divide(quantity, 8, RoundingMode.DOWN);
        BigDecimal maintenanceComponent = entryPrice.multiply(maintenanceMarginRate).setScale(8, RoundingMode.DOWN);
        if (side == TradingOrderSide.BUY) {
            return entryPrice.subtract(marginPerUnit)
                    .add(maintenanceComponent)
                    .setScale(8, RoundingMode.DOWN);
        }
        return entryPrice.add(marginPerUnit)
                .subtract(maintenanceComponent)
                .setScale(8, RoundingMode.DOWN);
    }
}
