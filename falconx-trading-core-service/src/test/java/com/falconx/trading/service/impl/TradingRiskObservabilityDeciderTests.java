package com.falconx.trading.service.impl;

import com.falconx.trading.calculator.RiskExposureCalculator;
import com.falconx.trading.entity.TradingHedgeLog;
import com.falconx.trading.entity.TradingHedgeLogStatus;
import com.falconx.trading.entity.TradingHedgeTriggerSource;
import com.falconx.trading.entity.TradingRiskExposure;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TradingRiskObservabilityDeciderTests {

    private final TradingRiskObservabilityDecider decider = new TradingRiskObservabilityDecider(new RiskExposureCalculator());

    @Test
    void shouldNotTriggerWhenThresholdIsMissingOrDisabled() {
        TradingRiskObservabilityDecision missingThreshold = decider.evaluate(
                exposure("BTCUSDT", "2.00000000", "0.00000000"),
                null,
                new BigDecimal("9995.00000000"),
                BigDecimal.ONE,
                null
        );
        TradingRiskObservabilityDecision disabledThreshold = decider.evaluate(
                exposure("BTCUSDT", "2.00000000", "0.00000000"),
                BigDecimal.ZERO,
                new BigDecimal("9995.00000000"),
                BigDecimal.ONE,
                null
        );

        Assertions.assertFalse(missingThreshold.shouldWriteHedgeLog());
        Assertions.assertFalse(missingThreshold.breached());
        Assertions.assertEquals(new BigDecimal("19990.00000000"), missingThreshold.netExposureUsd());
        Assertions.assertFalse(disabledThreshold.shouldWriteHedgeLog());
        Assertions.assertFalse(disabledThreshold.breached());
    }

    @Test
    void shouldCreateAlertWhenExposureFirstBreachesThreshold() {
        TradingRiskObservabilityDecision decision = decider.evaluate(
                exposure("BTCUSDT", "2.00000000", "0.00000000"),
                new BigDecimal("15000.00000000"),
                new BigDecimal("9995.00000000"),
                BigDecimal.ONE,
                null
        );

        Assertions.assertEquals(TradingHedgeLogStatus.ALERT_ONLY, decision.actionStatus());
        Assertions.assertEquals(new BigDecimal("19990.00000000"), decision.netExposureUsd());
        Assertions.assertTrue(decision.publishAlertEvent());
        Assertions.assertTrue(decision.breached());
    }

    @Test
    void shouldDeduplicateWhileExposureStaysAboveThresholdInSameDirection() {
        TradingRiskObservabilityDecision decision = decider.evaluate(
                exposure("BTCUSDT", "2.00000000", "0.00000000"),
                new BigDecimal("15000.00000000"),
                new BigDecimal("9995.00000000"),
                BigDecimal.ONE,
                latestLog(TradingHedgeLogStatus.ALERT_ONLY, "19990.00000000")
        );

        Assertions.assertFalse(decision.shouldWriteHedgeLog());
        Assertions.assertFalse(decision.publishAlertEvent());
        Assertions.assertEquals(new BigDecimal("19990.00000000"), decision.netExposureUsd());
        // 虽然不需要写日志，breached 仍为 true（风控动作应保持激活）
        Assertions.assertTrue(decision.breached());
    }

    @Test
    void shouldCreateRecoveredWhenExposureFallsBackWithinThreshold() {
        TradingRiskObservabilityDecision decision = decider.evaluate(
                exposure("BTCUSDT", "2.00000000", "0.00000000"),
                new BigDecimal("15000.00000000"),
                new BigDecimal("6995.00000000"),
                BigDecimal.ONE,
                latestLog(TradingHedgeLogStatus.ALERT_ONLY, "19990.00000000")
        );

        Assertions.assertEquals(TradingHedgeLogStatus.RECOVERED, decision.actionStatus());
        Assertions.assertEquals(new BigDecimal("13990.00000000"), decision.netExposureUsd());
        Assertions.assertFalse(decision.publishAlertEvent());
        Assertions.assertFalse(decision.breached());
    }

    @Test
    void shouldCreateSecondAlertWhenDirectionChangesAcrossThreshold() {
        TradingRiskObservabilityDecision decision = decider.evaluate(
                exposure("BTCUSDT", "0.00000000", "2.00000000"),
                new BigDecimal("15000.00000000"),
                new BigDecimal("9995.00000000"),
                BigDecimal.ONE,
                latestLog(TradingHedgeLogStatus.ALERT_ONLY, "19990.00000000")
        );

        Assertions.assertEquals(TradingHedgeLogStatus.ALERT_ONLY, decision.actionStatus());
        Assertions.assertEquals(new BigDecimal("-19990.00000000"), decision.netExposureUsd());
        Assertions.assertTrue(decision.publishAlertEvent());
        Assertions.assertTrue(decision.breached());
    }

    @Test
    void 多币种USD化_JPY计价按fx换算后不再误触发阈值() {
        // AUDJPY 100 手 × 114.6(JPY)：旧口径 11460 "USD" >> 阈值 5000 误触发；
        // 真 USD = 11460 × 0.00625 = 71.625 << 5000 不触发。
        TradingRiskObservabilityDecision decision = decider.evaluate(
                exposure("AUDJPY", "100.00000000", "0.00000000"),
                new BigDecimal("5000.00000000"),
                new BigDecimal("114.60000000"),
                new BigDecimal("0.00625000"),
                null
        );
        Assertions.assertEquals(new BigDecimal("71.62500000"), decision.netExposureUsd());
        Assertions.assertFalse(decision.breached(), "真 USD 口径不应触发阈值");
        Assertions.assertFalse(decision.shouldWriteHedgeLog());

        // 对照：fx 缺失降级 1（历史口径）会误触发——证明 fx 是判定的决定因素
        TradingRiskObservabilityDecision degraded = decider.evaluate(
                exposure("AUDJPY", "100.00000000", "0.00000000"),
                new BigDecimal("5000.00000000"),
                new BigDecimal("114.60000000"),
                null,
                null
        );
        Assertions.assertTrue(degraded.breached());
    }

    private TradingRiskExposure exposure(String symbol, String totalLongQty, String totalShortQty) {
        BigDecimal longQty = new BigDecimal(totalLongQty);
        BigDecimal shortQty = new BigDecimal(totalShortQty);
        return new TradingRiskExposure(
                symbol,
                longQty,
                shortQty,
                longQty.subtract(shortQty),
                BigDecimal.ZERO,
                OffsetDateTime.now()
        );
    }

    private TradingHedgeLog latestLog(TradingHedgeLogStatus actionStatus, String netExposureUsd) {
        return new TradingHedgeLog(
                1L,
                "BTCUSDT",
                1001L,
                TradingHedgeTriggerSource.OPEN_POSITION,
                actionStatus,
                new BigDecimal("2.00000000"),
                new BigDecimal(netExposureUsd),
                new BigDecimal("15000.00000000"),
                new BigDecimal("9995.00000000"),
                OffsetDateTime.now(),
                "risk-observability-unit-test",
                OffsetDateTime.now()
        );
    }
}
