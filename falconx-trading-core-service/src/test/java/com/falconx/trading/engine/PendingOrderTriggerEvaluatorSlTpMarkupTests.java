package com.falconx.trading.engine;

import com.falconx.trading.entity.TradingMarginMode;
import com.falconx.trading.entity.TradingOrderSide;
import com.falconx.trading.entity.TradingPendingOrderStatus;
import com.falconx.trading.entity.TradingPendingOrderTrigger;
import com.falconx.trading.entity.TradingPendingOrderTriggerKind;
import com.falconx.trading.entity.TradingPendingOrderType;
import com.falconx.trading.entity.TradingQuoteSnapshot;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * STAGE-12-GROUP-MARKUP TC-GM-021 + TC-GM-UT-040~043：
 * PendingOrderTriggerEvaluator.evaluateSlTp 按 position 持仓方向应用挂单冻结的 markup。
 *
 * <p>SL_TP 业务语义：
 * <ul>
 *   <li>多仓 TAKE_PROFIT → effectiveMark ≥ trigger 触发（BUY 持仓 → 用 bidExtraAtCreate）</li>
 *   <li>多仓 STOP_LOSS  → effectiveMark ≤ trigger 触发</li>
 *   <li>空仓 TAKE_PROFIT → effectiveMark ≤ trigger 触发（SELL 持仓 → 用 askExtraAtCreate）</li>
 *   <li>空仓 STOP_LOSS  → effectiveMark ≥ trigger 触发</li>
 * </ul>
 */
class PendingOrderTriggerEvaluatorSlTpMarkupTests {

    private final PendingOrderTriggerEvaluator evaluator = new PendingOrderTriggerEvaluator();

    /**
     * TC-GM-UT-040: 多仓 TAKE_PROFIT 用 bidExtraAtCreate 调整 markPrice。
     * positionSide=BUY → effectiveMark = mark + bidExtraAtCreate；
     * 触发条件：effective ≥ trigger
     */
    @Test
    void TC_GM_UT_040_buy_position_take_profit_uses_bid_extra() {
        TradingPendingOrderTrigger slTp = slTpOrder(
                TradingOrderSide.SELL,  // SL_TP.side = 平仓方向（持仓相反），所以 SELL 对应多仓
                TradingPendingOrderTriggerKind.TAKE_PROFIT,
                new BigDecimal("11000.00"),       // trigger price
                new BigDecimal("0.5"),            // bidExtraAtCreate
                new BigDecimal("1.0"));           // askExtraAtCreate

        // base mark 10999.6，+ bidExtra 0.5 → effective = 11000.1 ≥ 11000 → 触发
        TradingQuoteSnapshot quote = quote(new BigDecimal("10999.6"));
        Assertions.assertTrue(evaluator.evaluate(slTp, quote),
                "BUY 持仓 TP 应用 bidExtra=0.5: 10999.6 + 0.5 = 11000.1 ≥ 11000 应触发");

        // base mark 10999.4 + 0.5 = 10999.9 < 11000 → 不触发
        Assertions.assertFalse(evaluator.evaluate(slTp, quote(new BigDecimal("10999.4"))),
                "10999.4 + 0.5 = 10999.9 < 11000 不应触发");
    }

    /**
     * TC-GM-UT-041: 多仓 STOP_LOSS 用 bidExtraAtCreate 调整。
     * effectiveMark ≤ trigger 触发
     */
    @Test
    void TC_GM_UT_041_buy_position_stop_loss_uses_bid_extra() {
        TradingPendingOrderTrigger slTp = slTpOrder(
                TradingOrderSide.SELL,
                TradingPendingOrderTriggerKind.STOP_LOSS,
                new BigDecimal("9000.00"),
                new BigDecimal("-0.5"),           // 负 bidExtra
                new BigDecimal("-1.0"));

        // base 9000.6, + (-0.5) = 9000.1 > 9000 → 不触发
        Assertions.assertFalse(evaluator.evaluate(slTp, quote(new BigDecimal("9000.6"))),
                "9000.6 + (-0.5) = 9000.1 > 9000 不应触发");

        // base 9000.4, + (-0.5) = 8999.9 < 9000 → 触发
        Assertions.assertTrue(evaluator.evaluate(slTp, quote(new BigDecimal("9000.4"))),
                "9000.4 + (-0.5) = 8999.9 < 9000 应触发");
    }

    /**
     * TC-GM-UT-042: 空仓 TAKE_PROFIT 用 askExtraAtCreate 调整。
     * SL_TP.side = BUY → 持仓方向 SELL；effectiveMark = mark + askExtraAtCreate；
     * 触发：effective ≤ trigger
     */
    @Test
    void TC_GM_UT_042_sell_position_take_profit_uses_ask_extra() {
        TradingPendingOrderTrigger slTp = slTpOrder(
                TradingOrderSide.BUY,  // 平仓方向 BUY 对应空仓
                TradingPendingOrderTriggerKind.TAKE_PROFIT,
                new BigDecimal("9000.00"),
                new BigDecimal("0.5"),
                new BigDecimal("1.0"));

        // base 8999.5, + askExtra 1.0 = 9000.5 > 9000 → 不触发
        Assertions.assertFalse(evaluator.evaluate(slTp, quote(new BigDecimal("8999.5"))),
                "SELL TP 走 askExtra=1.0: 8999.5+1.0=9000.5 > 9000 不应触发");

        // base 8998.9 + 1.0 = 8999.9 < 9000 → 触发
        Assertions.assertTrue(evaluator.evaluate(slTp, quote(new BigDecimal("8998.9"))),
                "8998.9 + 1.0 = 8999.9 ≤ 9000 应触发");
    }

    /**
     * TC-GM-UT-043: 空仓 STOP_LOSS 用 askExtraAtCreate；effective ≥ trigger 触发。
     */
    @Test
    void TC_GM_UT_043_sell_position_stop_loss_uses_ask_extra() {
        TradingPendingOrderTrigger slTp = slTpOrder(
                TradingOrderSide.BUY,
                TradingPendingOrderTriggerKind.STOP_LOSS,
                new BigDecimal("11000.00"),
                new BigDecimal("0.5"),
                new BigDecimal("1.0"));

        // base 10999.5, + askExtra 1.0 = 11000.5 ≥ 11000 → 触发
        Assertions.assertTrue(evaluator.evaluate(slTp, quote(new BigDecimal("10999.5"))),
                "SELL SL 走 askExtra=1.0: 10999.5+1.0=11000.5 ≥ 11000 应触发");

        Assertions.assertFalse(evaluator.evaluate(slTp, quote(new BigDecimal("10998.9"))),
                "10998.9 + 1.0 = 10999.9 < 11000 不应触发");
    }

    /**
     * TC-GM-UT-044: 零 markup 时 SL_TP 评估等价基准 markPrice 比较（无 markup 自动回退）。
     */
    @Test
    void TC_GM_UT_044_zero_markup_falls_back_to_base_mark() {
        TradingPendingOrderTrigger slTp = slTpOrder(
                TradingOrderSide.SELL,  // 多仓 TP
                TradingPendingOrderTriggerKind.TAKE_PROFIT,
                new BigDecimal("11000.00"),
                BigDecimal.ZERO, BigDecimal.ZERO);

        // 0 markup: effective = base mark, 11000 ≥ 11000 → 触发
        Assertions.assertTrue(evaluator.evaluate(slTp, quote(new BigDecimal("11000.00"))));
        Assertions.assertFalse(evaluator.evaluate(slTp, quote(new BigDecimal("10999.99"))));
    }

    private TradingPendingOrderTrigger slTpOrder(TradingOrderSide closingSide,
                                                  TradingPendingOrderTriggerKind kind,
                                                  BigDecimal triggerPrice,
                                                  BigDecimal bidExtraAtCreate,
                                                  BigDecimal askExtraAtCreate) {
        return new TradingPendingOrderTrigger(
                1L, "po-1", 1L,
                "vip", bidExtraAtCreate, askExtraAtCreate,
                "BTCUSDT",
                TradingPendingOrderType.SL_TP,
                closingSide,
                new BigDecimal("1"),
                triggerPrice, null,
                new BigDecimal("10"), TradingMarginMode.ISOLATED,
                BigDecimal.ZERO, BigDecimal.ZERO,
                TradingPendingOrderStatus.PENDING,
                100L, kind, "client-1", null,
                null, null, null,
                OffsetDateTime.now(), OffsetDateTime.now()
        );
    }

    private TradingQuoteSnapshot quote(BigDecimal mark) {
        return new TradingQuoteSnapshot(
                "BTCUSDT",
                mark.subtract(new BigDecimal("0.5")),
                mark.add(new BigDecimal("0.5")),
                mark,
                OffsetDateTime.now(),
                "LP", false);
    }
}
