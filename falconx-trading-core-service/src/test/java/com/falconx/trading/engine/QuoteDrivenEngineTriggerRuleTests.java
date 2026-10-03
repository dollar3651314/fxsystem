package com.falconx.trading.engine;

import com.falconx.trading.entity.TradingMarginMode;
import com.falconx.trading.entity.TradingOrderSide;
import com.falconx.trading.entity.TradingPosition;
import com.falconx.trading.entity.TradingPositionCloseReason;
import com.falconx.trading.entity.TradingPositionStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * QuoteDrivenEngine 触发规则测试。
 *
 * <p>该测试锁定多空 TP/SL 方向以及“强平优先于止损”的规则。
 */
class QuoteDrivenEngineTriggerRuleTests {

    private final PositionTriggerRuleEvaluator evaluator = new PositionTriggerRuleEvaluator();

    @Test
    void shouldTriggerTakeProfitForLongPosition() {
        TradingPosition position = openPosition(
                TradingOrderSide.BUY,
                new BigDecimal("10000.00000000"),
                new BigDecimal("9050.00000000"),
                new BigDecimal("10100.00000000"),
                new BigDecimal("9800.00000000")
        );

        TradingPositionCloseReason closeReason = evaluator.evaluate(position, new BigDecimal("10100.00000000"));

        Assertions.assertEquals(TradingPositionCloseReason.TAKE_PROFIT, closeReason);
    }

    @Test
    void shouldTriggerStopLossForLongPosition() {
        TradingPosition position = openPosition(
                TradingOrderSide.BUY,
                new BigDecimal("10000.00000000"),
                new BigDecimal("9050.00000000"),
                new BigDecimal("10100.00000000"),
                new BigDecimal("9800.00000000")
        );

        TradingPositionCloseReason closeReason = evaluator.evaluate(position, new BigDecimal("9799.00000000"));

        Assertions.assertEquals(TradingPositionCloseReason.STOP_LOSS, closeReason);
    }

    @Test
    void shouldApplyShortDirectionRules() {
        TradingPosition position = openPosition(
                TradingOrderSide.SELL,
                new BigDecimal("9990.00000000"),
                new BigDecimal("10939.05000000"),
                new BigDecimal("9800.00000000"),
                new BigDecimal("10100.00000000")
        );

        Assertions.assertEquals(
                TradingPositionCloseReason.TAKE_PROFIT,
                evaluator.evaluate(position, new BigDecimal("9799.00000000"))
        );
        Assertions.assertEquals(
                TradingPositionCloseReason.STOP_LOSS,
                evaluator.evaluate(position, new BigDecimal("10100.00000000"))
        );
    }

    @Test
    void shouldPrioritizeLiquidationOverStopLossAtSamePrice() {
        TradingPosition position = openPosition(
                TradingOrderSide.BUY,
                new BigDecimal("10000.00000000"),
                new BigDecimal("9800.00000000"),
                new BigDecimal("10300.00000000"),
                new BigDecimal("9800.00000000")
        );

        TradingPositionCloseReason closeReason = evaluator.evaluate(position, new BigDecimal("9800.00000000"));

        Assertions.assertEquals(TradingPositionCloseReason.LIQUIDATION, closeReason);
    }

    private TradingPosition openPosition(TradingOrderSide side,
                                         BigDecimal entryPrice,
                                         BigDecimal liquidationPrice,
                                         BigDecimal takeProfitPrice,
                                         BigDecimal stopLossPrice) {
        OffsetDateTime now = OffsetDateTime.parse("2026-04-19T00:00:00Z");
        return new TradingPosition(
                1L,
                2L,
                3L,
                "BTCUSDT",
                side,
                new BigDecimal("1.00000000"),
                entryPrice,
                BigDecimal.ONE,
                new BigDecimal("0.005000"),
                1,
                new BigDecimal("10"),
                new BigDecimal("1000.00000000"),
                TradingMarginMode.ISOLATED,
                liquidationPrice,
                takeProfitPrice,
                stopLossPrice,
                null,
                null,
                null,
                TradingPositionStatus.OPEN,
                BigDecimal.ZERO,
                "default",
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                now,
                null,
                now
        );
    }
}
