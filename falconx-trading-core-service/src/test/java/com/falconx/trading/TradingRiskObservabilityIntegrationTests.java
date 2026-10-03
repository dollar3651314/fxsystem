package com.falconx.trading;

import com.falconx.domain.enums.ChainType;
import com.falconx.market.contract.event.MarketPriceTickEventPayload;
import com.falconx.trading.application.TradingDepositCreditApplicationService;
import com.falconx.trading.application.TradingOrderPlacementApplicationService;
import com.falconx.trading.command.CreditConfirmedDepositCommand;
import com.falconx.trading.command.PlaceMarketOrderCommand;
import com.falconx.trading.dto.PriceTickProcessingResult;
import com.falconx.trading.engine.OpenPositionSnapshotStore;
import com.falconx.trading.engine.QuoteDrivenEngine;
import com.falconx.trading.entity.TradingOrderSide;
import com.falconx.trading.dto.OrderPlacementResult;
import com.falconx.trading.repository.RedisTradingScheduleSnapshotRepository;
import com.falconx.trading.repository.mapper.test.TradingTestSupportMapper;
import com.falconx.trading.service.model.TradingScheduleSnapshot;
import com.falconx.trading.service.model.TradingSessionWindow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * FX-026 B-book 风险可观测性集成测试。
 *
 * <p>该测试验证：
 *
 * <ul>
 *   <li>开仓后会落库 `net_exposure_usd`</li>
 *   <li>行情变动时会实时重算美元敞口</li>
 *   <li>超阈值与恢复到阈值内都会留下 `t_hedge_log` 与日志</li>
 * </ul>
 */
@ExtendWith(OutputCaptureExtension.class)
@ActiveProfiles("stage5")
@SpringBootTest(
        classes = TradingCoreServiceApplication.class,
        properties = {
                "spring.datasource.url=jdbc:mysql://localhost:3306/falconx_trading_it?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                "spring.datasource.username=root",
                "spring.datasource.password=root",
                "spring.data.redis.host=localhost",
                "spring.data.redis.port=6380"
        }
)
class TradingRiskObservabilityIntegrationTests {

    @Autowired
    private TradingDepositCreditApplicationService tradingDepositCreditApplicationService;

    @Autowired
    private TradingOrderPlacementApplicationService tradingOrderPlacementApplicationService;

    @Autowired
    private QuoteDrivenEngine quoteDrivenEngine;

    @Autowired
    private TradingTestSupportMapper tradingTestSupportMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedisTradingScheduleSnapshotRepository tradingScheduleSnapshotRepository;

    @Autowired
    private OpenPositionSnapshotStore openPositionSnapshotStore;

    @BeforeEach
    void cleanTradingStores() {
        tradingTestSupportMapper.clearOwnerTables();
        openPositionSnapshotStore.replaceAll(List.of());
        stringRedisTemplate.delete("falconx:trading:quote:snapshot:BTCUSDT");
        stringRedisTemplate.delete("falconx:market:trading:schedule:BTCUSDT");
        tradingTestSupportMapper.updateRiskConfigHedgeThresholdUsd("BTCUSDT", new BigDecimal("100000.00000000"));
    }

    @Test
    void shouldWriteUsdExposureAndHedgeLogsWhenThresholdCrossesAndRecovers(CapturedOutput output) {
        long userId = 94001L;
        OffsetDateTime now = OffsetDateTime.now();

        tradingDepositCreditApplicationService.creditConfirmedDeposit(new CreditConfirmedDepositCommand(
                "evt-stage7-hedge-observe-001",
                99001L,
                userId,
                ChainType.ETH,
                "USDT",
                "0xstage7hedgeobserve001",
                new BigDecimal("20000.00000000"),
                now
        ));
        tradingTestSupportMapper.updateRiskConfigHedgeThresholdUsd("BTCUSDT", new BigDecimal("15000.00000000"));
        seedAlwaysOpenSchedule("BTCUSDT", "CRYPTO");
        publishQuote(
                "BTCUSDT",
                new BigDecimal("9990.00000000"),
                new BigDecimal("10000.00000000"),
                new BigDecimal("9995.00000000"),
                now
        );

        tradingOrderPlacementApplicationService.placeMarketOrder(new PlaceMarketOrderCommand(
                userId,
                "BTCUSDT",
                TradingOrderSide.BUY,
                new BigDecimal("2.00000000"),
                new BigDecimal("2"),
                null,
                new BigDecimal("20000.00000000"),
                new BigDecimal("1000.00000000"),
                "stage7-hedge-observe-order-001"
        ));

        Assertions.assertEquals("19980.00000000", tradingTestSupportMapper.selectRiskExposureNetUsdBySymbol("BTCUSDT"));
        Assertions.assertEquals(1, tradingTestSupportMapper.countHedgeLogsBySymbol("BTCUSDT"));
        Assertions.assertEquals(1, tradingTestSupportMapper.selectLatestHedgeLogActionStatusCodeBySymbol("BTCUSDT"));
        Assertions.assertEquals(1, tradingTestSupportMapper.selectLatestHedgeLogTriggerSourceCodeBySymbol("BTCUSDT"));
        Assertions.assertEquals("19980.00000000", tradingTestSupportMapper.selectLatestHedgeLogNetExposureUsdBySymbol("BTCUSDT"));
        Assertions.assertEquals("15000.00000000", tradingTestSupportMapper.selectLatestHedgeLogThresholdUsdBySymbol("BTCUSDT"));
        Assertions.assertEquals("9990.00000000", tradingTestSupportMapper.selectLatestHedgeLogMarkPriceBySymbol("BTCUSDT"));
        Assertions.assertEquals(1, tradingTestSupportMapper.countOpenPositionsByUserId(userId));
        Assertions.assertEquals(0, tradingTestSupportMapper.countOutboxByEventType("trading.position.closed"));

        PriceTickProcessingResult refreshResult = publishQuote(
                "BTCUSDT",
                new BigDecimal("6990.00000000"),
                new BigDecimal("7000.00000000"),
                new BigDecimal("6995.00000000"),
                OffsetDateTime.now()
        );

        Assertions.assertEquals(0, refreshResult.triggeredActions());
        Assertions.assertEquals("13980.00000000", tradingTestSupportMapper.selectRiskExposureNetUsdBySymbol("BTCUSDT"));
        Assertions.assertEquals(2, tradingTestSupportMapper.countHedgeLogsBySymbol("BTCUSDT"));
        Assertions.assertEquals(2, tradingTestSupportMapper.selectLatestHedgeLogActionStatusCodeBySymbol("BTCUSDT"));
        Assertions.assertEquals(6, tradingTestSupportMapper.selectLatestHedgeLogTriggerSourceCodeBySymbol("BTCUSDT"));
        Assertions.assertEquals("13980.00000000", tradingTestSupportMapper.selectLatestHedgeLogNetExposureUsdBySymbol("BTCUSDT"));
        Assertions.assertEquals("6990.00000000", tradingTestSupportMapper.selectLatestHedgeLogMarkPriceBySymbol("BTCUSDT"));
        Assertions.assertEquals(1, tradingTestSupportMapper.countOpenPositionsByUserId(userId));
        Assertions.assertEquals(0, tradingTestSupportMapper.countOutboxByEventType("trading.position.closed"));
        Assertions.assertTrue(output.toString().contains("trading.risk.hedge.alert"));
        Assertions.assertTrue(output.toString().contains("trading.risk.hedge.recovered"));
    }

    private PriceTickProcessingResult publishQuote(String symbol,
                                                   BigDecimal bid,
                                                   BigDecimal ask,
                                                   BigDecimal mark,
                                                   OffsetDateTime ts) {
        return quoteDrivenEngine.processTick(new MarketPriceTickEventPayload(
                symbol,
                bid,
                ask,
                mark,
                mark,
                ts,
                "stage7-risk-observability-it",
                false
        ));
    }

    @Test
    void shouldActivateRiskControlActionAndCreateAuditRecordWhenThresholdBreached() {
        long userId = 94002L;
        OffsetDateTime now = OffsetDateTime.now();

        tradingDepositCreditApplicationService.creditConfirmedDeposit(new CreditConfirmedDepositCommand(
                "evt-risk-control-audit-001",
                99002L,
                userId,
                ChainType.ETH,
                "USDT",
                "0xriskcontrolaudit001",
                new BigDecimal("20000.00000000"),
                now
        ));
        tradingTestSupportMapper.updateRiskConfigHedgeThresholdUsd("BTCUSDT", new BigDecimal("15000.00000000"));
        seedAlwaysOpenSchedule("BTCUSDT", "CRYPTO");
        publishQuote("BTCUSDT",
                new BigDecimal("9990.00000000"),
                new BigDecimal("10000.00000000"),
                new BigDecimal("9995.00000000"),
                now);

        // 开仓使敞口超阈值（2 * 9990 = 19980 > 15000）
        tradingOrderPlacementApplicationService.placeMarketOrder(new PlaceMarketOrderCommand(
                userId,
                "BTCUSDT",
                TradingOrderSide.BUY,
                new BigDecimal("2.00000000"),
                new BigDecimal("2"),
                null,
                new BigDecimal("20000.00000000"),
                new BigDecimal("1000.00000000"),
                "risk-control-audit-order-001"
        ));

        // 审计日志：t_risk_control_action 有 1 条激活记录
        Assertions.assertEquals(1, tradingTestSupportMapper.countActiveRiskControlActionsBySymbol("BTCUSDT"));
        Assertions.assertEquals(1, tradingTestSupportMapper.countRiskControlActionsBySymbol("BTCUSDT"));
        Assertions.assertTrue(tradingTestSupportMapper.countHedgeLogsBySymbol("BTCUSDT") >= 1);
    }

    @Test
    void shouldDeactivateRiskControlActionAndResumeTradeAfterRecovery() {
        long userId = 94003L;
        OffsetDateTime now = OffsetDateTime.now();

        tradingDepositCreditApplicationService.creditConfirmedDeposit(new CreditConfirmedDepositCommand(
                "evt-risk-control-recovery-001",
                99003L,
                userId,
                ChainType.ETH,
                "USDT",
                "0xriskcontrolrecovery001",
                new BigDecimal("40000.00000000"),
                now
        ));
        tradingTestSupportMapper.updateRiskConfigHedgeThresholdUsd("BTCUSDT", new BigDecimal("15000.00000000"));
        seedAlwaysOpenSchedule("BTCUSDT", "CRYPTO");
        publishQuote("BTCUSDT",
                new BigDecimal("9990.00000000"),
                new BigDecimal("10000.00000000"),
                new BigDecimal("9995.00000000"),
                now);

        // 第一笔开仓 → 敞口 19980 > 15000 → 激活 REJECT_OPEN
        tradingOrderPlacementApplicationService.placeMarketOrder(new PlaceMarketOrderCommand(
                userId,
                "BTCUSDT",
                TradingOrderSide.BUY,
                new BigDecimal("2.00000000"),
                new BigDecimal("2"),
                null,
                new BigDecimal("40000.00000000"),
                new BigDecimal("1000.00000000"),
                "risk-control-recovery-order-001"
        ));

        Assertions.assertEquals(1, tradingTestSupportMapper.countActiveRiskControlActionsBySymbol("BTCUSDT"));

        // 第二笔开仓应被 REJECT_OPEN 拒绝
        OrderPlacementResult rejectedResult = tradingOrderPlacementApplicationService.placeMarketOrder(
                new PlaceMarketOrderCommand(
                        userId,
                        "BTCUSDT",
                        TradingOrderSide.BUY,
                        new BigDecimal("1.00000000"),
                        new BigDecimal("2"),
                        null,
                        new BigDecimal("40000.00000000"),
                        new BigDecimal("500.00000000"),
                        "risk-control-recovery-order-002"
                )
        );
        Assertions.assertEquals("BBOOK_RISK_OPEN_REJECTED", rejectedResult.rejectionReason());

        // 行情下跌使敞口降至阈值以下 → REJECT_OPEN 停用
        publishQuote("BTCUSDT",
                new BigDecimal("6990.00000000"),
                new BigDecimal("7000.00000000"),
                new BigDecimal("6995.00000000"),
                OffsetDateTime.now());

        // 恢复后：激活记录归零
        Assertions.assertEquals(0, tradingTestSupportMapper.countActiveRiskControlActionsBySymbol("BTCUSDT"));
        // 审计记录仍保留（is_active=0 的历史行）
        Assertions.assertEquals(1, tradingTestSupportMapper.countRiskControlActionsBySymbol("BTCUSDT"));

        // 恢复后开第三笔仓应成功（敞口 2*6990=13980 < 15000）
        OrderPlacementResult recoveredResult = tradingOrderPlacementApplicationService.placeMarketOrder(
                new PlaceMarketOrderCommand(
                        userId,
                        "BTCUSDT",
                        TradingOrderSide.BUY,
                        new BigDecimal("1.00000000"),
                        new BigDecimal("2"),
                        null,
                        new BigDecimal("40000.00000000"),
                        new BigDecimal("500.00000000"),
                        "risk-control-recovery-order-003"
                )
        );
        Assertions.assertNull(recoveredResult.rejectionReason());
    }

    private void seedAlwaysOpenSchedule(String symbol, String marketCode) {
        tradingScheduleSnapshotRepository.saveForTest(new TradingScheduleSnapshot(
                symbol,
                marketCode,
                List.of(
                        new TradingSessionWindow(1, 1, LocalTime.of(0, 0), LocalTime.of(23, 59, 59), "UTC", true, LocalDate.of(2026, 1, 1), null),
                        new TradingSessionWindow(2, 1, LocalTime.of(0, 0), LocalTime.of(23, 59, 59), "UTC", true, LocalDate.of(2026, 1, 1), null),
                        new TradingSessionWindow(3, 1, LocalTime.of(0, 0), LocalTime.of(23, 59, 59), "UTC", true, LocalDate.of(2026, 1, 1), null),
                        new TradingSessionWindow(4, 1, LocalTime.of(0, 0), LocalTime.of(23, 59, 59), "UTC", true, LocalDate.of(2026, 1, 1), null),
                        new TradingSessionWindow(5, 1, LocalTime.of(0, 0), LocalTime.of(23, 59, 59), "UTC", true, LocalDate.of(2026, 1, 1), null),
                        new TradingSessionWindow(6, 1, LocalTime.of(0, 0), LocalTime.of(23, 59, 59), "UTC", true, LocalDate.of(2026, 1, 1), null),
                        new TradingSessionWindow(7, 1, LocalTime.of(0, 0), LocalTime.of(23, 59, 59), "UTC", true, LocalDate.of(2026, 1, 1), null)
                ),
                List.of(),
                List.of(),
                OffsetDateTime.now()
        ));
    }
}
